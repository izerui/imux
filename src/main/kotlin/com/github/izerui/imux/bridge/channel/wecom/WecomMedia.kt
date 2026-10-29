package com.github.izerui.imux.bridge.channel.wecom

import com.github.izerui.imux.bridge.channel.DownloadResult
import com.github.izerui.imux.bridge.channel.HttpFileDownload
import com.github.izerui.imux.bridge.channel.download
import com.github.izerui.imux.bridge.core.AttachmentKind
import com.github.izerui.imux.bridge.core.FetchResult
import com.github.izerui.imux.bridge.core.FetchedFile
import com.github.izerui.imux.bridge.core.InboundAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.cancellation.CancellationException

/** 企业微信媒体解密，与官方 SDK decryptFile 一致。 */
object WecomMedia {
    private const val BLOCK = 32

    /**
     * AES-256-CBC：密钥为 aeskey 的 base64 解码（允许缺少结尾 `=`），IV 取密钥前 16 字节。
     * 填充是 PKCS#7 到 32 字节倍数，JDK 的 PKCS5Padding 只认 16 字节块，所以用 NoPadding 手工去除。
     * 流式处理：始终扣住最后 32 字节，读完后再校验并去掉填充。
     */
    fun decryptFile(
        source: Path,
        target: Path,
        aesKeyBase64: String,
    ) {
        val key = Base64.getDecoder().decode(aesKeyBase64.trim())
        require(key.size == 32) { "aeskey 不是 32 字节" }
        val size = Files.size(source)
        require(size > 0 && size % 16 == 0L) { "密文长度不合法" }
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key, 0, 16))
        Files.newInputStream(source).use { input ->
            Files.newOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                var held = ByteArray(0)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    val chunk = cipher.update(buffer, 0, read) ?: continue
                    val all = held + chunk
                    val keep = minOf(BLOCK, all.size)
                    output.write(all, 0, all.size - keep)
                    held = all.copyOfRange(all.size - keep, all.size)
                }
                val tail = held + (cipher.doFinal() ?: ByteArray(0))
                require(tail.isNotEmpty()) { "填充不合法" }
                val pad = tail.last().toInt() and 0xFF
                require(pad in 1..BLOCK && pad <= tail.size) { "填充不合法" }
                for (index in tail.size - pad until tail.size) require((tail[index].toInt() and 0xFF) == pad) { "填充不合法" }
                output.write(tail, 0, tail.size - pad)
            }
        }
    }
}

/** 企业微信消息里的一个附件：只在 [fetch] 时才下载与解密。 */
class WecomAttachment(
    override val kind: AttachmentKind,
    private val url: String,
    private val aesKey: String,
    private val download: HttpFileDownload,
) : InboundAttachment {
    override suspend fun fetch(maxBytes: Long): FetchResult =
        when (val downloaded = download.download(url, maxBytes + PADDING_ALLOWANCE)) {
            DownloadResult.TooLarge -> FetchResult.TooLarge
            is DownloadResult.Failed -> FetchResult.Failed(downloaded.reason)
            is DownloadResult.Ok -> decrypt(downloaded, maxBytes)
        }

    private suspend fun decrypt(
        downloaded: DownloadResult.Ok,
        maxBytes: Long,
    ): FetchResult {
        var plain: Path? = null
        var handedOver = false
        try {
            val result =
                withContext(Dispatchers.IO) {
                    try {
                        val target = Files.createTempFile(downloaded.path.parent, "imux-", ".bin").also { plain = it }
                        WecomMedia.decryptFile(downloaded.path, target, aesKey)
                        if (Files.size(target) > maxBytes) FetchResult.TooLarge else FetchResult.Ok(FetchedFile(target, downloaded.fileName))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        FetchResult.Failed("decrypt: ${e.javaClass.simpleName}")
                    }
                }
            handedOver = result is FetchResult.Ok
            return result
        } finally {
            // 任何退出（含在 withContext 边界被取消）都删掉密文；明文只有交给调用方时才保留
            runCatching { Files.deleteIfExists(downloaded.path) }
            if (!handedOver) plain?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private companion object {
        /** 密文最多比明文多一个 32 字节的填充块。 */
        const val PADDING_ALLOWANCE = 32L
    }
}
