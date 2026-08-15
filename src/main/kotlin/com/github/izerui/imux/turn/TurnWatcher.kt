package com.github.izerui.imux.turn

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.session.JsonLineScanner
import com.github.izerui.imux.session.PiStopReason
import com.github.izerui.imux.session.scanTail
import com.intellij.openapi.diagnostic.logger
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 监控会话文件的轮次状态，产出「刚完成、需要提醒」的会话。
 *
 * **只服务没有运行态文件的 agent（codex、pi）**：claude 有 CLI 亲自写的运行态文件
 * （`~/.claude/sessions/<pid>.json`），
 * 那是一手状态，由 [RuntimeStatusTracker] 判定完成即可。两边都监控会让同一轮完成被
 * 报两次——实测两个信号相隔约 0.1 秒，而轮询 1 秒一拍，约 10% 的概率跨拍，此时
 * [com.github.izerui.imux.monitor.SessionMonitor] 的 `distinct()` 只能同拍去重，
 * 兜不住，于是弹出两个通知气泡（[TurnNotifier] 覆盖 active 时也不 expire 旧的）。
 *
 * codex 与 pi 没有等价的运行态文件，只能从会话文件的轮次信号推断
 * （codex 是 `task_started` / `task_complete`，pi 是 assistant 消息的 `stopReason`），
 * 因此这里保留。[TurnSignalParser] 的 claude 分支不再被生产链路调用，但作为纯函数保留：
 * 它是按 8535 条真实记录调出来的，将来 claude 若不再写运行态文件可以直接启用。
 *
 * 增量读取：每个会话记住已读到的字节偏移，每轮只读新追加的部分。
 * 会话文件的单行可达数 MB，全量重读不可接受。
 *
 * 开始监控时偏移直接设到文件末尾——只关心「开始监控之后」的跃迁，
 * 历史内容不参与**提醒**判定。这也是插件启动后历史会话不会被误标的原因。
 *
 * 但初始**状态**要回溯尾部推断出来，见 [initialStateOf]：一个已经在跑的会话
 * 必须一打开就是执行中，否则标记要等到下一轮才亮。
 */
class TurnWatcher(
    private val clock: () -> Instant = { Instant.now() },
) {
    private class Entry(
        val agentType: AgentType,
        val file: Path,
        var offset: Long,
        /** 后台轮询线程写，EDT 渲染时读，故必须 volatile。 */
        @Volatile var state: TurnState,
        /** 本轮开始执行的时刻；不在执行中时为 null。 */
        @Volatile var workingSince: Instant? = null,
        var lastPiMessageId: String? = null,
    )

    /** 最近一次完成的耗时，供提醒展示。 */
    private val durations = ConcurrentHashMap<String, Duration>()

    /**
     * 并发容器：[watch] / [unwatch] 由 EDT 调用（用户点击），而 [poll] 与
     * [workingIds] 由后台轮询线程调用。普通 HashMap 会在两者撞上时抛
     * ConcurrentModificationException。
     */
    private val entries = ConcurrentHashMap<String, Entry>()

    /** pi 最终失败的明确终态；等文件里的 error / length 先把状态推进到 WORKING 后再收口。 */
    private val piSettled = ConcurrentHashMap<String, PiSettlement>()

    /**
     * 纳入监控。**自带运行态文件的 agent 一律拒绝**——它们由运行态文件判定，见本类 KDoc。
     */
    fun watch(
        sessionId: String,
        agentType: AgentType,
        file: Path,
        inferInitialState: Boolean = true,
    ) {
        if (agentType.hasRuntimeStatusFile) return
        if (entries.containsKey(sessionId)) return
        val initial =
            if (inferInitialState) {
                initialStateOf(agentType, file)
            } else {
                InitialState(TurnState.IDLE, null)
            }
        entries[sessionId] =
            Entry(
                agentType = agentType,
                file = file,
                offset = currentSize(file),
                // workingSince 保持 null：起点在观察窗口之外，报一个从此刻算起的耗时是错的
                state = initial.state,
                lastPiMessageId = initial.lastPiMessageId,
            )
    }

    /**
     * 开始监控时该会话「此刻」处于什么状态——回溯文件尾部推断，而不是一律当作空闲。
     *
     * 偏移设在文件末尾意味着此前那条 `task_started` 再也读不到。若初始状态硬编码为
     * 空闲，点开一个正在跑的会话就不会亮标记；新建会话更是必然不亮，因为绑定发生在
     * CLI 已经开跑之后，第一轮的开始信号早就写进去了。
     *
     * 只推断状态，不产出事件：历史内容仍然不该触发任何提醒。
     *
     * 窗口里一个信号都没有时返回 null，交由 [scanTail] 把窗口翻倍重来；
     * 到了文件头仍找不到，说明这个文件里就没有轮次信号，按空闲处理。
     */
    private fun initialStateOf(
        agentType: AgentType,
        file: Path,
    ): InitialState =
        scanTail(file) { lines ->
            val result = TurnSignalParser.parse(agentType, TurnState.IDLE, lines)
            if (result.transitions.isEmpty()) {
                null
            } else {
                InitialState(result.state, lastPiMessageId(lines))
            }
        } ?: InitialState(TurnState.IDLE, null)

    fun unwatch(sessionId: String) {
        entries.remove(sessionId)
        durations.remove(sessionId)
        piSettled.remove(sessionId)
    }

    /**
     * pi 已确认自动重试、压缩重试和后续消息全部结束。
     *
     * 正常 stop 仍由会话文件直接判定；这里只接管会被自动恢复的 error / length，
     * 因而不会与文件信号重复提醒。
     */
    fun reportPiSettled(
        sessionId: String,
        stopReason: PiStopReason,
        messageId: String,
    ) {
        if (stopReason == PiStopReason.ERROR || stopReason == PiStopReason.LENGTH) {
            piSettled[sessionId] = PiSettlement(stopReason, messageId)
        }
    }

    /** 该会话最近一轮跑了多久；未观察到完整的一轮时为 null。 */
    fun lastDuration(sessionId: String): Duration? = durations[sessionId]

    /**
     * 指定 agent 中当前处于「执行中」的会话 id。
     *
     * 状态由 [poll] 推进，本方法只读。因此调用顺序有意义：要拿最新的，先 poll 再读。
     */
    fun workingIds(agentType: AgentType): Set<String> =
        entries
            .filterValues { it.agentType == agentType && it.state == TurnState.WORKING }
            .keys
            .toSet()

    /**
     * 所有被监控的会话中当前处于「执行中」的 id，不分 agent。
     *
     * 本类监控的本就只有「没有运行态文件、只能从会话文件推断」的那些 agent，
     * 全取即可。逐个 agent 点名的写法每加一个 agent 就得改一次调用点，漏了就静默不亮。
     */
    fun workingIds(): Set<String> =
        entries
            .filterValues { it.state == TurnState.WORKING }
            .keys
            .toSet()

    /** 返回本轮刚完成、需要提醒的 sessionId。 */
    fun poll(): List<String> {
        val completed = mutableListOf<String>()
        for ((sessionId, entry) in entries) {
            val fileEvent = advance(sessionId, entry)
            val settledEvent = applyPiSettled(sessionId, entry)
            if (fileEvent == TurnEvent.COMPLETED || settledEvent == TurnEvent.COMPLETED) {
                completed += sessionId
            }
        }
        return completed
    }

    private fun applyPiSettled(
        sessionId: String,
        entry: Entry,
    ): TurnEvent {
        if (entry.agentType != AgentType.PI || entry.state != TurnState.WORKING) return TurnEvent.NONE
        val settled = piSettled[sessionId] ?: return TurnEvent.NONE
        if (entry.lastPiMessageId != settled.messageId) return TurnEvent.NONE
        piSettled.remove(sessionId, settled)

        entry.state = TurnState.IDLE
        entry.workingSince?.let { durations[sessionId] = Duration.between(it, clock()) }
            ?: durations.remove(sessionId)
        entry.workingSince = null
        return TurnEvent.COMPLETED
    }

    private fun advance(
        sessionId: String,
        entry: Entry,
    ): TurnEvent =
        runCatching {
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

            val lines = appended.lines()
            val result = TurnSignalParser.parse(entry.agentType, entry.state, lines)
            if (entry.agentType == AgentType.PI) {
                lastPiMessageId(lines)?.let { entry.lastPiMessageId = it }
            }
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

    private fun currentSize(file: Path): Long = if (Files.isRegularFile(file)) Files.size(file) else 0L

    private fun readRange(
        file: Path,
        from: Long,
        to: Long,
    ): String =
        Files.newByteChannel(file).use { channel ->
            channel.position(from)
            val buffer = ByteBuffer.allocate((to - from).toInt())
            while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
            String(buffer.array(), 0, buffer.position(), Charsets.UTF_8)
        }

    private fun lastPiMessageId(lines: List<String>): String? =
        lines
            .asReversed()
            .firstNotNullOfOrNull { line ->
                if (JsonLineScanner.topLevelStringValue(line, "type") != PI_MESSAGE) return@firstNotNullOfOrNull null
                val role = JsonLineScanner.objectStringValue(line, "message", "role")
                if (role != PI_USER && role != PI_ASSISTANT) return@firstNotNullOfOrNull null
                JsonLineScanner.topLevelStringValue(line, "id")
            }

    private data class InitialState(
        val state: TurnState,
        val lastPiMessageId: String?,
    )

    private data class PiSettlement(
        val reason: PiStopReason,
        val messageId: String,
    )

    private companion object {
        val LOG = logger<TurnWatcher>()
        const val PI_MESSAGE = "message"
        const val PI_USER = "user"
        const val PI_ASSISTANT = "assistant"
    }
}
