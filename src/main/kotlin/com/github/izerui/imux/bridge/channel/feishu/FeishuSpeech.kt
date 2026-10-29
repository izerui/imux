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
 * 飞书语音转文字：下载语音 → 解 Ogg Opus 成 16k pcm → 按段调用飞书语音识别 → 按顺序拼接。
 * 识别接口适合 60 秒以内，所以默认按 50 秒切段。任何一步失败（没权限、额度、解码、识别为空）都返回 null，
 * 调用方退回"作为音频附件转交"，消息照常送达。
 */
class FeishuSpeech(
    private val api: FeishuApi,
    private val download: HttpFileDownload,
    private val random: Random = SecureRandom(),
    private val chunkSamples: Int = 50 * OggOpus.SAMPLE_RATE,
) {
    suspend fun transcribe(
        messageId: String,
        ref: FeishuMediaRef,
    ): String? {
        val fetched = FeishuAttachment(messageId, ref, api, download).fetch(MAX_AUDIO_BYTES)
        val file = (fetched as? FetchResult.Ok)?.file ?: return null
        val pcm =
            try {
                withContext(Dispatchers.IO) { OggOpus.decode(Files.readAllBytes(file.path), MAX_SAMPLES) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return null
            } finally {
                runCatching { Files.deleteIfExists(file.path) }
            }
        if (pcm.isEmpty()) return null
        val texts = mutableListOf<String>()
        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(pcm.size, offset + chunkSamples)
            // 任何一段识别为空都整体放弃：只交出前半段文字会丢掉后半段输入，还拿不到原音频
            when (val result = api.recognize(littleEndian(pcm, offset, end), fileId())) {
                is FeishuResult.Ok -> texts += result.value.trim().ifEmpty { return null }
                is FeishuResult.Error -> return null
                is FeishuResult.Network -> return null
            }
            offset = end
        }
        return texts.joinToString("")
    }

    private fun littleEndian(
        pcm: ShortArray,
        from: Int,
        to: Int,
    ): ByteArray {
        val bytes = ByteArray((to - from) * 2)
        for (index in from until to) {
            val value = pcm[index].toInt()
            val at = (index - from) * 2
            bytes[at] = value.toByte()
            bytes[at + 1] = (value shr 8).toByte()
        }
        return bytes
    }

    private fun fileId(): String = String(CharArray(16) { FILE_ID_CHARS[random.nextInt(FILE_ID_CHARS.length)] })

    private companion object {
        const val FILE_ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

        /** 语音消息不会很大；20 MB 足够容纳十分钟以上的 Opus。 */
        const val MAX_AUDIO_BYTES = 20L * 1024 * 1024

        /** 最长转写 10 分钟，再长就退回附件，避免一条语音占用过多识别调用。 */
        const val MAX_SAMPLES = 10 * 60 * OggOpus.SAMPLE_RATE
    }
}
