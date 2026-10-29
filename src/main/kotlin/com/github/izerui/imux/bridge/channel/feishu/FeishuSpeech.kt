package com.github.izerui.imux.bridge.channel.feishu

import com.github.izerui.imux.bridge.channel.HttpFileDownload
import com.github.izerui.imux.bridge.channel.OggOpus
import com.github.izerui.imux.bridge.core.FetchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Random
import kotlin.coroutines.cancellation.CancellationException

/**
 * 飞书语音转文字：下载语音 → 解 Ogg Opus 成 16k pcm → 整段一次交给飞书语音识别，不切段。
 * 接口文档写“适合 60 秒以内”，更长的语音也照样提交，由平台决定能否识别；只有超过 [maxSamples]（10 分钟，内存保护）才不识别。
 * 任何一步失败（没权限、额度、解码、识别为空）都返回 null，调用方退回“作为音频附件转交”，消息照常送达。
 */
class FeishuSpeech(
    private val api: FeishuApi,
    private val download: HttpFileDownload,
    private val random: Random = SecureRandom(),
    /** 只防异常大文件占满内存（10 分钟约 19 MB pcm、26 MB 请求体），正常语音碰不到。 */
    private val maxSamples: Int = 10 * 60 * OggOpus.SAMPLE_RATE,
) {
    suspend fun transcribe(
        messageId: String,
        ref: FeishuMediaRef,
    ): String? {
        val fetched = FeishuAttachment(messageId, ref, api, download).fetch(MAX_AUDIO_BYTES)
        val file = (fetched as? FetchResult.Ok)?.file ?: return null
        val pcm =
            try {
                withContext(Dispatchers.IO) { OggOpus.decode(Files.readAllBytes(file.path), maxSamples) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 解码失败，或文件异常大
                return null
            } finally {
                runCatching { Files.deleteIfExists(file.path) }
            }
        if (pcm.isEmpty()) return null
        return when (val result = api.recognize(littleEndian(pcm), fileId())) {
            is FeishuResult.Ok -> result.value.trim().ifEmpty { null }
            is FeishuResult.Error -> null
            is FeishuResult.Network -> null
        }
    }

    private fun littleEndian(pcm: ShortArray): ByteArray {
        val bytes = ByteArray(pcm.size * 2)
        for (index in pcm.indices) {
            val value = pcm[index].toInt()
            bytes[index * 2] = value.toByte()
            bytes[index * 2 + 1] = (value shr 8).toByte()
        }
        return bytes
    }

    private fun fileId(): String = String(CharArray(16) { FILE_ID_CHARS[random.nextInt(FILE_ID_CHARS.length)] })

    private companion object {
        const val FILE_ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

        /** 只防异常大文件；十分钟的 Opus 语音也远小于此。 */
        const val MAX_AUDIO_BYTES = 20L * 1024 * 1024
    }
}
