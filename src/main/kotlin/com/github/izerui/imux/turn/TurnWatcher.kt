package com.github.izerui.imux.turn

import com.github.izerui.imux.model.AgentType
import com.intellij.openapi.diagnostic.logger
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 监控若干会话文件的轮次状态，产出「刚完成、需要提醒」的会话。
 *
 * 增量读取：每个会话记住已读到的字节偏移，每轮只读新追加的部分。
 * claude 的单行可达数 MB，全量重读不可接受。
 *
 * 开始监控时偏移直接设到文件末尾——只关心「开始监控之后」的跃迁，
 * 历史内容不参与判定。这也是插件启动后历史会话不会被误标的原因。
 */
class TurnWatcher(private val clock: () -> Instant = { Instant.now() }) {

    private class Entry(
        val agentType: AgentType,
        val file: Path,
        var offset: Long,
        /** 后台轮询线程写，EDT 渲染时读，故必须 volatile。 */
        @Volatile var state: TurnState,
        /** 本轮开始执行的时刻；不在执行中时为 null。 */
        @Volatile var workingSince: Instant? = null,
    )

    /** 最近一次完成的耗时，供提醒展示。 */
    private val durations = ConcurrentHashMap<String, Duration>()

    /**
     * 并发容器：[watch] / [unwatch] 由 EDT 调用（用户点击），而 [poll] 与
     * [workingIds] 由后台轮询线程调用。普通 HashMap 会在两者撞上时抛
     * ConcurrentModificationException。
     */
    private val entries = ConcurrentHashMap<String, Entry>()

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
        durations.remove(sessionId)
    }

    /** 该会话最近一轮跑了多久；未观察到完整的一轮时为 null。 */
    fun lastDuration(sessionId: String): Duration? = durations[sessionId]

    /**
     * 指定 agent 中当前处于「执行中」的会话 id。
     *
     * 状态由 [poll] 推进，本方法只读。因此调用顺序有意义：要拿最新的，先 poll 再读。
     */
    fun workingIds(agentType: AgentType): Set<String> = entries
        .filterValues { it.agentType == agentType && it.state == TurnState.WORKING }
        .keys
        .toSet()

    /** 返回本轮刚完成、需要提醒的 sessionId。 */
    fun poll(): List<String> {
        val completed = mutableListOf<String>()
        for ((sessionId, entry) in entries) {
            if (advance(sessionId, entry) == TurnEvent.COMPLETED) completed += sessionId
        }
        return completed
    }

    private fun advance(sessionId: String, entry: Entry): TurnEvent = runCatching {
        val size = currentSize(entry.file)

        // 文件被截断或重建：重置到末尾、回到空闲，不产出事件
        if (size < entry.offset) {
            entry.offset = size
            entry.state = TurnState.IDLE
            entry.workingSince = null
            return@runCatching TurnEvent.NONE
        }
        if (size == entry.offset) return@runCatching TurnEvent.NONE

        val appended = readRange(entry.file, entry.offset, size)
        entry.offset = size

        val result = TurnSignalParser.parse(entry.agentType, entry.state, appended.lines())
        val observedAt = clock()
        var startedInThisBatch = false

        for (transition in result.transitions) {
            when {
                transition.to == TurnState.WORKING -> {
                    entry.workingSince = observedAt
                    startedInThisBatch = true
                }

                transition.from == TurnState.WORKING && transition.to == TurnState.IDLE -> {
                    if (transition.event == TurnEvent.COMPLETED) {
                        val startedAt = entry.workingSince
                        if (startedAt != null && !startedInThisBatch) {
                            durations[sessionId] = Duration.between(startedAt, observedAt)
                        } else {
                            // 本批次内开始又完成，真实起点未知，不能沿用上一轮耗时。
                            durations.remove(sessionId)
                        }
                    }
                    entry.workingSince = null
                    startedInThisBatch = false
                }
            }
        }

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
