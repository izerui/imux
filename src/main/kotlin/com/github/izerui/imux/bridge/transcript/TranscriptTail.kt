package com.github.izerui.imux.bridge.transcript

import com.github.izerui.imux.bridge.core.SessionEvent
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

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
 */
class TranscriptTail(
    private val path: Path,
    private val decoder: TranscriptDecoder,
    private val maxLineBytes: Int = DEFAULT_MAX_LINE_BYTES,
) {
    private var position = 0L
    private var fileKey: Any? = null
    private val pending = ByteArrayOutputStream()
    private var discarding = false

    var skippedOversizeLines: Int = 0
        private set

    /** 以当前文件末尾为边界，之前的历史不转发。文件尚不存在时边界为 0。 */
    fun startAtEnd() {
        val attrs = attributes()
        position = attrs?.size() ?: 0L
        fileKey = attrs?.fileKey()
        pending.reset()
        discarding = false
    }

    fun poll(): TailPoll {
        val attrs = attributes() ?: return TailPoll(emptyList(), restarted = false)
        val key = attrs.fileKey()
        val replaced = (fileKey != null && key != null && key != fileKey) || attrs.size() < position
        if (replaced) {
            startAtEnd()
            return TailPoll(emptyList(), restarted = true)
        }
        if (fileKey == null) fileKey = key
        if (attrs.size() == position) return TailPoll(emptyList(), restarted = false)

        val events = mutableListOf<SessionEvent>()
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            channel.position(position)
            val buffer = ByteBuffer.allocate(READ_BUFFER_BYTES)
            while (true) {
                buffer.clear()
                val read = channel.read(buffer)
                if (read <= 0) break
                position += read
                for (i in 0 until read) consume(buffer.get(i), events)
            }
        }
        return TailPoll(events, restarted = false)
    }

    private fun consume(
        byte: Byte,
        events: MutableList<SessionEvent>,
    ) {
        if (byte == NEWLINE) {
            if (!discarding && pending.size() > 0) {
                events += decoder.decode(pending.toString(StandardCharsets.UTF_8))
            }
            pending.reset()
            discarding = false
            return
        }
        if (discarding) return
        if (pending.size() >= maxLineBytes) {
            pending.reset()
            discarding = true
            skippedOversizeLines++
            return
        }
        pending.write(byte.toInt())
    }

    private fun attributes(): BasicFileAttributes? =
        runCatching { Files.readAttributes(path, BasicFileAttributes::class.java) }.getOrNull()

    private companion object {
        const val DEFAULT_MAX_LINE_BYTES = 8 * 1024 * 1024
        const val READ_BUFFER_BYTES = 64 * 1024
        const val NEWLINE: Byte = 10
    }
}
