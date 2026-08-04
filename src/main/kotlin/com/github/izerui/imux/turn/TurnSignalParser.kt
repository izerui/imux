package com.github.izerui.imux.turn

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.session.JsonLineScanner

enum class TurnState { WORKING, IDLE }

enum class TurnEvent {
    /** 跃迁到空闲，应当提醒 */
    COMPLETED,

    /** 跃迁到空闲，但不提醒（用户自己中断的） */
    ABORTED,

    /** 无跃迁 */
    NONE,
}

data class TurnParseResult(val state: TurnState, val event: TurnEvent)

/**
 * 把会话文件新追加的行解析成轮次状态跃迁。
 *
 * 纯函数，无 IO、无平台依赖——本功能的全部判定规则集中于此，可完整单测。
 * 这是与上一个失败的运行状态标记最实质的区别：那个依赖终端运行时状态，无法离线验证。
 */
object TurnSignalParser {

    fun parse(agentType: AgentType, previous: TurnState, lines: List<String>): TurnParseResult {
        var state = previous
        var event = TurnEvent.NONE

        for (line in lines) {
            val signal = when (agentType) {
                AgentType.CLAUDE -> claudeSignal(line)
                AgentType.CODEX -> codexSignal(line)
            } ?: continue

            // 只有「进行中 -> 空闲」才算一次跃迁
            if (state == TurnState.WORKING && signal.state == TurnState.IDLE) {
                event = signal.eventOnIdle
            }
            state = signal.state
        }

        return TurnParseResult(state, event)
    }

    private class Signal(val state: TurnState, val eventOnIdle: TurnEvent)

    /**
     * Claude 用黑名单判定：只有 tool_use 算进行中，其余一律算完成。
     *
     * 实测 8535 条 assistant 记录中 stop_reason 有四种取值：
     * tool_use / end_turn / stop_sequence / null。若只把 end_turn 当完成，
     * 以 stop_sequence 收尾的会话（71 个中有 7 个）永远不会提醒。
     */
    private fun claudeSignal(line: String): Signal? = when {
        line.contains(STOP_REASON_KEY) -> {
            val reason = runCatching { JsonLineScanner.stringValue(line, "stop_reason") }.getOrNull()
            if (reason == TOOL_USE) {
                Signal(TurnState.WORKING, TurnEvent.NONE)
            } else {
                Signal(TurnState.IDLE, TurnEvent.COMPLETED)
            }
        }

        // 工具结果回填，说明这一轮还在跑
        line.contains(USER_RECORD) -> Signal(TurnState.WORKING, TurnEvent.NONE)

        else -> null
    }

    private fun codexSignal(line: String): Signal? = when {
        line.contains(TASK_STARTED) -> Signal(TurnState.WORKING, TurnEvent.NONE)
        line.contains(TASK_COMPLETE) -> Signal(TurnState.IDLE, TurnEvent.COMPLETED)
        line.contains(TURN_ABORTED) -> Signal(TurnState.IDLE, TurnEvent.ABORTED)
        else -> null
    }

    private const val STOP_REASON_KEY = "\"stop_reason\""
    private const val TOOL_USE = "tool_use"
    private const val USER_RECORD = "\"type\":\"user\""

    private const val TASK_STARTED = "\"task_started\""
    private const val TASK_COMPLETE = "\"task_complete\""
    private const val TURN_ABORTED = "\"turn_aborted\""
}
