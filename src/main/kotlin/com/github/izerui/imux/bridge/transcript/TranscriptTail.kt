package com.github.izerui.imux.bridge.transcript

import com.github.izerui.imux.bridge.core.SessionEvent
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

data class TailPoll(
    val events: List<SessionEvent>,
    /** 记录文件被替换或截短；已从新末尾重新开始，调用方应提示同步出现缺口。 */
    val restarted: Boolean,
)

/**
 * 只读跟随一份会话记录的新增内容。
 *
 * 只按完整行解码：半行留在缓冲里等换行，所以跨两次写入的多字节字符不会被拆坏。
 * 自己维护读位置，不与导航索引、轮次监控共享任何状态——它们"读一次就推进"，
 * 桥接若去调用它们，会抢走原有的完成通知。
 *
 * [skipBefore] 不为空时，顶层 `timestamp` 早于它的行不解码。fork、交给 daemon 生成的新会话文件
 * 会把原会话的全部历史连同原时间戳复制进来，从头读时靠它跳过这些旧行；没有时间戳的行照常解码。
 */
class TranscriptTail(
    private val path: Path,
    private val decoder: TranscriptDecoder,
    private val maxLineBytes: Int = DEFAULT_MAX_LINE_BYTES,
    private val skipBefore: Instant? = null,
) {
    private var position = 0L
    private var fileKey: Any? = null
    private val pending = ByteArrayOutputStream()
    private var discarding = false

    /** 以当前文件末尾为边界，之前的历史不转发。文件尚不存在时边界为 0。 */
    fun startAtEnd() {
        val attrs = attributes()
        position = attrs?.size() ?: 0L
        fileKey = attrs?.fileKey()
        pending.reset()
        discarding = false
    }

    /** 发现文件被替换或截短、从新末尾重新开始的次数。读取位置在两次重启之间才可比较。 */
    @Volatile
    var restarts: Int = 0
        private set

    /** 已经读过的字节位置（含留在缓冲里的半行）。 */
    val readPosition: Long get() = position

    /** 文件当前大小；读不到时为 null。 */
    fun size(): Long? = attributes()?.size()

    /**
     * 读出新增的完整行。给了 [upTo] 时只读到这个字节位置为止，其后的内容留给下一次：
     * IDE 完成信号据此只结算信号发出那一刻之前写下的内容。
     */
    fun poll(upTo: Long? = null): TailPoll {
        val attrs = attributes() ?: return TailPoll(emptyList(), restarted = false)
        val key = attrs.fileKey()
        val replaced = (fileKey != null && key != null && key != fileKey) || attrs.size() < position
        if (replaced) {
            restarts++
            startAtEnd()
            return TailPoll(emptyList(), restarted = true)
        }
        if (fileKey == null) fileKey = key
        val limit = minOf(attrs.size(), upTo ?: Long.MAX_VALUE)
        if (position >= limit) return TailPoll(emptyList(), restarted = false)

        val events = mutableListOf<SessionEvent>()
        try {
            FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                channel.position(position)
                val buffer = ByteBuffer.allocate(READ_BUFFER_BYTES)
                while (position < limit) {
                    buffer.clear()
                    if (limit - position < buffer.capacity()) buffer.limit((limit - position).toInt())
                    val read = channel.read(buffer)
                    if (read <= 0) break
                    position += read
                    for (i in 0 until read) consume(buffer.get(i), events)
                }
            }
        } catch (_: IOException) {
            // 文件在 attributes() 与 open/read 之间消失或变为不可读（TOCTOU）——
            // 返回已解码的事件，position 只含实际读取的字节，下次 poll 会重新检测。
        }
        return TailPoll(events, restarted = false)
    }

    private fun consume(
        byte: Byte,
        events: MutableList<SessionEvent>,
    ) {
        if (byte == NEWLINE) {
            if (!discarding && pending.size() > 0) {
                val line = pending.toString(StandardCharsets.UTF_8)
                if (!copiedHistory(line)) events += decoder.decode(line)
            }
            pending.reset()
            discarding = false
            return
        }
        if (discarding) return
        if (pending.size() >= maxLineBytes) {
            pending.reset()
            discarding = true
            return
        }
        pending.write(byte.toInt())
    }

    private fun copiedHistory(line: String): Boolean {
        val limit = skipBefore ?: return false
        val time =
            runCatching {
                JsonParser.parseString(line).asJsonObject.get("timestamp")?.asString?.let(Instant::parse)
            }.getOrNull() ?: return false
        return time.isBefore(limit)
    }

    private fun attributes(): BasicFileAttributes? =
        runCatching { Files.readAttributes(path, BasicFileAttributes::class.java) }.getOrNull()

    private companion object {
        const val DEFAULT_MAX_LINE_BYTES = 8 * 1024 * 1024
        const val READ_BUFFER_BYTES = 64 * 1024
        const val NEWLINE: Byte = 10
    }
}
