package com.github.liuyuhua.imux.turn

import com.github.liuyuhua.imux.model.AgentType
import com.intellij.openapi.diagnostic.logger
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path

/**
 * 监控若干会话文件的轮次状态，产出「刚完成、需要提醒」的会话。
 *
 * 增量读取：每个会话记住已读到的字节偏移，每轮只读新追加的部分。
 * claude 的单行可达数 MB，全量重读不可接受。
 *
 * 开始监控时偏移直接设到文件末尾——只关心「开始监控之后」的跃迁，
 * 历史内容不参与判定。这也是插件启动后历史会话不会被误标的原因。
 */
class TurnWatcher {

    private class Entry(
        val agentType: AgentType,
        val file: Path,
        var offset: Long,
        var state: TurnState,
    )

    private val entries = LinkedHashMap<String, Entry>()

    fun watch(sessionId: String, agentType: AgentType, file: Path) {
        if (entries.containsKey(sessionId)) return
        entries[sessionId] = Entry(
            agentType = agentType,
            file = file,
            offset = currentSize(file),
            state = TurnState.IDLE,
        )
    }

    fun unwatch(sessionId: String) {
        entries.remove(sessionId)
    }

    /** 返回本轮刚完成、需要提醒的 sessionId。 */
    fun poll(): List<String> {
        val completed = mutableListOf<String>()
        for ((sessionId, entry) in entries) {
            if (advance(entry) == TurnEvent.COMPLETED) completed += sessionId
        }
        return completed
    }

    private fun advance(entry: Entry): TurnEvent = runCatching {
        val size = currentSize(entry.file)

        // 文件被截断或重建：重置到末尾、回到空闲，不产出事件
        if (size < entry.offset) {
            entry.offset = size
            entry.state = TurnState.IDLE
            return@runCatching TurnEvent.NONE
        }
        if (size == entry.offset) return@runCatching TurnEvent.NONE

        val appended = readRange(entry.file, entry.offset, size)
        entry.offset = size

        val result = TurnSignalParser.parse(entry.agentType, entry.state, appended.lines())
        entry.state = result.state
        result.event
    }.getOrElse {
        LOG.warn("轮次监控读取失败：${entry.file}", it)
        TurnEvent.NONE
    }

    private fun currentSize(file: Path): Long =
        if (Files.isRegularFile(file)) Files.size(file) else 0L

    private fun readRange(file: Path, from: Long, to: Long): String =
        Files.newByteChannel(file).use { channel ->
            channel.position(from)
            val buffer = ByteBuffer.allocate((to - from).toInt())
            while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
            String(buffer.array(), 0, buffer.position(), Charsets.UTF_8)
        }

    private companion object {
        val LOG = logger<TurnWatcher>()
    }
}
