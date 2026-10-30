package com.github.izerui.imux.bridge.channel.weixin

import com.github.izerui.imux.bridge.channel.DownloadResult
import com.github.izerui.imux.bridge.channel.HttpFileDownload
import com.github.izerui.imux.bridge.channel.download
import com.github.izerui.imux.bridge.core.AttachmentKind
import com.github.izerui.imux.bridge.core.FetchResult
import com.github.izerui.imux.bridge.core.FetchedFile
import com.github.izerui.imux.bridge.core.InboundAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.cancellation.CancellationException

private const val CDN_BASE_URL = "https://novac2c.cdn.weixin.qq.com/c2c"

/** 密文比明文最多多一个 16 字节的 PKCS7 填充块。 */
private const val AES_BLOCK = 16

/** 下载地址：服务端给了 full_url 就用，否则按 encrypt_query_param 拼 CDN 地址（官方 cdn-url.ts）。 */
fun weixinDownloadUrl(ref: WeixinMediaRef): String? =
    ref.fullUrl ?: ref.encryptQueryParam?.let { "$CDN_BASE_URL/download?encrypted_query_param=${URLEncoder.encode(it, StandardCharsets.UTF_8)}" }

/** 微信消息里的一个附件：[fetch] 时才下载（不带认证头），有密钥则 AES-128-ECB 解密为明文（官方 aes-ecb.ts）。 */
class WeixinAttachment(
    private val ref: WeixinMediaRef,
    private val download: HttpFileDownload,
) : InboundAttachment {
    override val kind: AttachmentKind
        get() = ref.kind

    override suspend fun fetch(maxBytes: Long): FetchResult {
        val url = weixinDownloadUrl(ref) ?: return FetchResult.Failed("no url")
        val key = ref.aesKeyHex
        val limit = if (key == null) maxBytes else maxBytes + AES_BLOCK
        return when (val result = download.download(url, limit)) {
            DownloadResult.TooLarge -> FetchResult.TooLarge
            is DownloadResult.Failed -> FetchResult.Failed(result.reason)
            is DownloadResult.Ok -> if (key == null) FetchResult.Ok(FetchedFile(result.path, ref.fileName)) else decrypt(result.path, key, maxBytes)
        }
    }

    private suspend fun decrypt(
        path: Path,
        keyHex: String,
        maxBytes: Long,
    ): FetchResult =
        withContext(Dispatchers.IO) {
            try {
                val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(hexBytes(keyHex), "AES"))
                // 边读边解密写到旁边的文件，不把整个附件读进内存
                val plain = Files.createTempFile(path.parent, "weixin", ".plain")
                val fits =
                    try {
                        CipherInputStream(Files.newInputStream(path), cipher).use { input ->
                            Files.newOutputStream(plain).use { output -> copyAtMost(input, output, maxBytes) }
                        }
                    } catch (e: Exception) {
                        Files.deleteIfExists(plain)
                        throw e
                    }
                if (!fits) {
                    Files.deleteIfExists(plain)
                    Files.deleteIfExists(path)
                    return@withContext FetchResult.TooLarge
                }
                Files.move(plain, path, StandardCopyOption.REPLACE_EXISTING)
                FetchResult.Ok(FetchedFile(path, ref.fileName))
            } catch (e: CancellationException) {
                runCatching { Files.deleteIfExists(path) }
                throw e
            } catch (e: Exception) {
                runCatching { Files.deleteIfExists(path) }
                FetchResult.Failed("decrypt ${e.javaClass.simpleName}")
            }
        }

    /** 拷贝到超过 [maxBytes] 为止；没超过返回 true。 */
    private fun copyAtMost(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long,
    ): Boolean {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return true
            total += read
            if (total > maxBytes) return false
            output.write(buffer, 0, read)
        }
    }

    private fun hexBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
