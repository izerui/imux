package com.github.izerui.imux.clarification

import java.util.concurrent.CopyOnWriteArrayList

internal sealed interface ClarificationState {
    data object Input : ClarificationState

    data class Investigating(val progress: String?, val startedAt: Long) : ClarificationState

    data class Asking(val question: Question) : ClarificationState

    data class Result(val requirement: String) : ClarificationState

    /** 正在发送：[waitingForTurn] 为 true 时在等主会话这一轮结束，否则在等终端可以接收。 */
    data class Sending(val requirement: String, val waitingForTurn: Boolean) : ClarificationState

    data class Failed(val reason: FailureReason, val detail: String, val raw: String?) : ClarificationState
}

internal enum class FailureReason { CANCELLED, TIMEOUT, AGENT_ERROR, PROTOCOL }

internal enum class PendingWork { ROUND, RESULT }

/**
 * 一个会话的澄清流程。状态读写都在 [lock] 内；[advance] 与 [generateResult] 阻塞地调用 Agent，
 * 只在 IO 线程调用，调用期间用户仍可跳过、先问这个、添加或重新打开方向。
 *
 * [generation] 在每次开始调查、取消时递增：Agent 返回时若它已变，说明这一轮已被取消或被新一轮取代，结果丢弃。
 */
internal class ClarificationFlow(
    private val isChinese: Boolean,
    private val guidance: String,
    private val runAgent: (String) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val changedDuringRun = mutableSetOf<String>()
    private var phase = Phase.FIRST
    private var pending = PendingWork.ROUND
    private var generation = 0L

    @Volatile var ledger: Ledger = Ledger("", null)
        private set

    @Volatile var state: ClarificationState = ClarificationState.Input
        private set

    fun addListener(listener: () -> Unit) { listeners += listener }

    fun removeListener(listener: () -> Unit) { listeners -= listener }

    private fun changed() = listeners.forEach { it() }

    private fun investigate(work: PendingWork): Long {
        generation++
        pending = work
        state = ClarificationState.Investigating(null, clock())
        return generation
    }

    fun begin(requirement: String, background: String?) {
        synchronized(lock) {
            ledger = Ledger(requirement.trim(), background?.take(MAX_BACKGROUND_CHARS)?.ifBlank { null })
            phase = Phase.FIRST
            investigate(PendingWork.ROUND)
        }
        changed()
    }

    /** 背景快照在 IO 线程读取，读完后、调用 Agent 之前补进台账。 */
    fun attachBackground(background: String?) {
        synchronized(lock) { ledger = ledger.copy(background = background?.take(MAX_BACKGROUND_CHARS)?.ifBlank { null }) }
    }

    fun answer(answer: Answer, correction: String?) {
        synchronized(lock) {
            val asking = state as? ClarificationState.Asking ?: return
            ledger = ledger.recordAnswer(asking.question, answer, correction)
            phase = if (correction.isNullOrBlank()) Phase.ROUND else Phase.CORRECTED
            investigate(PendingWork.ROUND)
        }
        changed()
    }

    /** 返回 true 表示跳过的正是当前问题，调用方需要再跑一轮。 */
    fun skip(id: String): Boolean {
        val current = synchronized(lock) { (state as? ClarificationState.Asking)?.question?.takeIf { it.directionId == id } }
        if (current != null) {
            answer(Answer.Skipped, null)
            return true
        }
        userEdit(id) { it.skip(id) }
        return false
    }

    fun pin(id: String) = userEdit(id) { it.pin(id) }

    fun reopen(id: String) = userEdit(id) { it.reopen(id) }

    fun addDirection(title: String) {
        if (title.isBlank()) return
        synchronized(lock) {
            val (updated, id) = ledger.addUserDirection(title)
            ledger = updated
            changedDuringRun += id
        }
        changed()
    }

    private fun userEdit(id: String, edit: (Ledger) -> Ledger) {
        synchronized(lock) {
            ledger = edit(ledger)
            changedDuringRun += id
        }
        changed()
    }

    /** 一轮调查：调用 Agent、合并；协议错误或问题因用户操作失效时重新调用一次。 */
    fun advance() {
        var feedback = emptyList<String>()
        var retried = false
        while (true) {
            val (prompt, runId) =
                synchronized(lock) {
                    // 取消可能抢在这里之前：此时已不在调查中，不能再开一轮
                    if (state !is ClarificationState.Investigating) return
                    changedDuringRun.clear()
                    clarificationPrompt(phase, ledger, isChinese, guidance, feedback) to generation
                }
            val raw = runAgent(prompt)
            val again: List<String>? =
                synchronized(lock) {
                    if (runId != generation) return
                    val outcome =
                        try {
                            ledger.merge(parseRoundResponse(raw), changedDuringRun.toSet())
                        } catch (e: ProtocolException) {
                            MergeOutcome(ledger, null, e.errors, invalidatedByUser = false)
                        }
                    when {
                        outcome.errors.isEmpty() -> {
                            ledger = outcome.ledger
                            phase = Phase.ROUND
                            state =
                                when (val next = outcome.next!!) {
                                    is MergedNext.Ask -> ClarificationState.Asking(next.question)
                                    is MergedNext.Done -> ClarificationState.Result(next.requirement)
                                }
                            null
                        }
                        retried -> {
                            state = ClarificationState.Failed(FailureReason.PROTOCOL, outcome.errors.joinToString("\n"), raw)
                            null
                        }
                        outcome.invalidatedByUser -> emptyList()
                        else -> outcome.errors
                    }
                }
            if (again == null) {
                changed()
                return
            }
            feedback = again
            retried = true
        }
    }

    fun beginResult() {
        synchronized(lock) { investigate(PendingWork.RESULT) }
        changed()
    }

    fun generateResult() {
        var feedback = emptyList<String>()
        repeat(2) { attempt ->
            val (prompt, runId) =
                synchronized(lock) {
                    if (state !is ClarificationState.Investigating) return
                    clarificationPrompt(Phase.RESULT, ledger, isChinese, guidance, feedback) to generation
                }
            val raw = runAgent(prompt)
            synchronized(lock) {
                if (runId != generation) return
                try {
                    state = ClarificationState.Result(parseRequirementResponse(raw))
                } catch (e: ProtocolException) {
                    if (attempt == 1) state = ClarificationState.Failed(FailureReason.PROTOCOL, e.errors.joinToString("\n"), raw)
                    feedback = e.errors
                }
            }
            if (state !is ClarificationState.Investigating) {
                changed()
                return
            }
        }
    }

    fun fallbackResult() {
        synchronized(lock) {
            generation++
            state = ClarificationState.Result(composeFallback(ledger))
        }
        changed()
    }

    fun continueClarifying() {
        synchronized(lock) {
            if (state !is ClarificationState.Result) return
            phase = Phase.ROUND
            investigate(PendingWork.ROUND)
        }
        changed()
    }

    /** 从失败状态重试失败的那一步；不在失败状态返回 null。 */
    fun retry(): PendingWork? {
        val work =
            synchronized(lock) {
                if (state !is ClarificationState.Failed) return null
                pending.also { investigate(it) }
            }
        changed()
        return work
    }

    fun progress(text: String) {
        synchronized(lock) {
            val current = state as? ClarificationState.Investigating ?: return
            state = current.copy(progress = text)
        }
        changed()
    }

    fun cancelled() = fail(FailureReason.CANCELLED, "", null, force = true)

    fun fail(reason: FailureReason, detail: String, raw: String?) = fail(reason, detail, raw, force = false)

    private fun fail(reason: FailureReason, detail: String, raw: String?, force: Boolean) {
        synchronized(lock) {
            if (!force && state !is ClarificationState.Investigating) return
            generation++
            state = ClarificationState.Failed(reason, detail, raw)
        }
        changed()
    }

    fun editResult(text: String) {
        synchronized(lock) { if (state is ClarificationState.Result) state = ClarificationState.Result(text) }
    }

    /** 从结果页进入发送，或在发送中切换"等这一轮结束 / 等终端接收"。状态不变时不通知。 */
    fun sending(waitingForTurn: Boolean) {
        val changedNow =
            synchronized(lock) {
                val requirement =
                    when (val s = state) {
                        is ClarificationState.Result -> s.requirement
                        is ClarificationState.Sending -> if (s.waitingForTurn == waitingForTurn) return else s.requirement
                        else -> return
                    }
                state = ClarificationState.Sending(requirement, waitingForTurn)
                true
            }
        if (changedNow) changed()
    }

    /** 取消发送或发送失败：回到结果页，文本保留。 */
    fun sendCancelled() {
        synchronized(lock) { (state as? ClarificationState.Sending)?.let { state = ClarificationState.Result(it.requirement) } }
        changed()
    }
}
