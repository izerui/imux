package com.github.izerui.imux.clarification

import com.github.izerui.imux.model.AgentType
import java.util.concurrent.CopyOnWriteArrayList

internal sealed interface ClarificationState {
    data object Input : ClarificationState

    /** [run] 是这次调查的 runId：同一次调查只是进度变了，界面就地更新而不重建。 */
    data class Investigating(val progress: String?, val startedAt: Long, val run: Long = 0) : ClarificationState

    /**
     * 这一批 1～4 个问题。回答先留在窗口里（草稿），可以来回修改；
     * 用户提交（[ClarificationFlow.submitBatch]）时一次记下，才把回答发给 Agent。
     */
    data class Asking(val batch: List<Question>) : ClarificationState

    data class Result(val requirement: String) : ClarificationState

    /** 正在发送：[waitingForTurn] 为 true 时在等主会话这一轮结束，否则在等终端可以接收。 */
    data class Sending(val requirement: String, val waitingForTurn: Boolean) : ClarificationState

    data class Failed(val reason: FailureReason, val detail: String, val raw: String?) : ClarificationState
}

internal enum class FailureReason { CANCELLED, TIMEOUT, AGENT_ERROR, PROTOCOL }

/**
 * 与澄清 Agent 的一段持续对话（流程这一侧看到的样子）：[send] 发一条消息并阻塞到这一轮结束，返回回答；
 * [close] 结束对话（终止进程、清理会话文件）。实现由服务按当前会话的智能体提供。
 */
internal interface ClarificationConversation {
    fun send(message: String, onProgress: (String) -> Unit): String

    fun close()
}

/**
 * 一个会话的澄清流程：整个澄清只用一段对话。开始时发开场（指南、规则、需求、格式），
 * 之后每次只发这一批的回答，Agent 带着已有的上下文接着做；回复不合格式时在同一段对话里把错误发回一次。
 *
 * 什么时候问够了由 Agent 判断（返回 done）；用户不想继续时直接关窗。
 * 对话中断（取消、失败）时结束它；之后重试或继续都新开一段对话，以原需求与已确认的问答（[record]）作为开场接着做。
 *
 * 一个流程只对应一次澄清：[end] 之后不再复用，服务会丢掉它，下次打开窗口新建一个。
 *
 * 状态读写都在 [lock] 内；[run] 阻塞地等 Agent，只在 IO 线程调用。[generation] 在每次开始调查、取消、结束时递增：
 * Agent 返回时若它已变，说明这一轮已被取消或被新一轮取代，结果丢弃。
 */
internal class ClarificationFlow(
    private val isChinese: Boolean,
    private val guidance: String,
    private val openConversation: (AgentType) -> ClarificationConversation,
    /** 结束一段对话；服务把它交给后台线程，调用方（常在 EDT 上）不等进程退出。 */
    private val closeConversation: (ClarificationConversation) -> Unit = ClarificationConversation::close,
    private val classifyFailure: (Throwable) -> Pair<FailureReason, String> = { FailureReason.AGENT_ERROR to (it.message ?: it.javaClass.simpleName) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var generation = 0L
    private var conversation: ClarificationConversation? = null

    /** 下一轮要在现有对话里发的消息；对话为空（还没开始或已中断）时改发开场。 */
    private var outgoing: String? = null

    /** 重开对话时也保留用户最后一次继续讨论的方案。 */
    private var continuedPlan: String? = null
    private var continuedPlanHistorySize = 0

    /** 用户在输入页选择的智能体类型；在 [begin] 时设置，打开对话时使用。 */
    private var agentType: AgentType? = null

    @Volatile var record: ClarificationRecord = ClarificationRecord("")
        private set

    @Volatile var state: ClarificationState = ClarificationState.Input
        private set

    /**
     * 当前这一批还没提交的回答，由窗口（EDT）读写；只是界面状态，不进记录、不通知监听者。
     */
    @Volatile var drafts: BatchDrafts? = null

    /** 开始后用户选的智能体类型；还没开始时为 null。 */
    fun chosenAgentType(): AgentType? = agentType

    fun hasRetainedPlan(): Boolean = synchronized(lock) { continuedPlan != null }

    /** 关闭主会话前取得待交付文本；读取快照，不改变当前状态或发起调查。 */
    fun undeliveredPlan(): String? =
        synchronized(lock) { undeliveredRequirement(state) ?: retainedPlanLocked() }

    private fun retainedPlanLocked(): String? {
        val plan = continuedPlan ?: return null
        // 修订前的回答已由用户在方案中取舍；只补入继续聊之后新提交的回答。
        val laterAnswers = record.history.drop(continuedPlanHistorySize)
        return if (laterAnswers.isEmpty()) plan else composeFallback(ClarificationRecord(plan, laterAnswers))
    }

    fun addListener(listener: () -> Unit) { listeners += listener }

    fun removeListener(listener: () -> Unit) { listeners -= listener }

    private fun changed() = listeners.forEach { it() }

    private fun investigate(message: String?): Long {
        generation++
        outgoing = message
        state = ClarificationState.Investigating(null, clock(), generation)
        return generation
    }

    /** 摘下当前对话；调用方出锁后交给 [closeConversation]。 */
    private fun detachLocked(): ClarificationConversation? {
        val current = conversation
        conversation = null
        outgoing = null
        return current
    }

    private fun stale(runId: Long) = runId != generation || state !is ClarificationState.Investigating

    fun begin(requirement: String, agentType: AgentType): Long {
        val runId: Long
        val old: ClarificationConversation?
        synchronized(lock) {
            old = detachLocked()
            this.agentType = agentType
            record = ClarificationRecord(requirement.trim())
            continuedPlan = null
            continuedPlanHistorySize = 0
            runId = investigate(null)
        }
        old?.let(closeConversation)
        changed()
        return runId
    }

    /**
     * 提交这一批：[answers] 按题目 ID 给出回答，没给出的题按不回答处理；按这一批的顺序记下，
     * 然后发起一轮调查（在同一段对话里只发这一批的回答），返回它的 runId。不在提问页时返回 null。
     */
    fun submitBatch(answers: Map<String, Answer>): Long? {
        val runId: Long
        synchronized(lock) {
            val asking = state as? ClarificationState.Asking ?: return null
            val batch = asking.batch.map { answerRecord(it, answers[it.id] ?: Answer.Skipped) }
            record = record.copy(history = record.history + batch)
            // 草稿随提交作废：之后出现一模一样的问题时，不能把这次的回答当成新的草稿带回来
            drafts = null
            runId = investigate(conversation?.let { answersMessage(batch, isChinese) })
        }
        changed()
        return runId
    }

    /**
     * 一轮：发消息（没有对话时先开一段并发开场），等回复。回复不合协议时在同一段对话里把错误发回重答一次，
     * 仍不合格进入失败；Agent 出错或超时也进入失败。失败时结束这段对话。
     */
    fun run(runId: Long) {
        var corrected = false
        while (true) {
            var conv: ClarificationConversation?
            var message: String?
            synchronized(lock) {
                if (stale(runId)) return
                conv = conversation
                message = outgoing
            }
            if (conv == null || message == null) {
                // 开对话可能要读设置、写扩展脚本：不在锁内做
                val created =
                    try {
                        openConversation(agentType ?: error("agentType must be set before run"))
                    } catch (e: Throwable) {
                        rethrowIfCancellation(e)
                        fail(runId, null, e)
                        return
                    }
                var replaced: ClarificationConversation? = null
                val opening: String? =
                    synchronized(lock) {
                        if (stale(runId)) {
                            null
                        } else {
                            replaced = detachLocked()
                            conversation = created
                            openingMessage(record, isChinese, guidance, continuedPlan, continuedPlanHistorySize)
                        }
                    }
                replaced?.let(closeConversation)
                if (opening == null) {
                    closeConversation(created)
                    return
                }
                conv = created
                message = opening
            }
            val current = conv
            val raw =
                try {
                    current.send(message) { text -> progress(runId, text) }
                } catch (e: Throwable) {
                    rethrowIfCancellation(e)
                    fail(runId, current, e)
                    return
                }
            var dropped: ClarificationConversation? = null
            val again: Boolean =
                synchronized(lock) {
                    if (stale(runId)) return
                    val errors = accept(raw)
                    when {
                        errors.isEmpty() -> false
                        corrected -> {
                            state = ClarificationState.Failed(FailureReason.PROTOCOL, errors.joinToString("\n"), raw)
                            dropped = detachLocked()
                            false
                        }
                        else -> {
                            outgoing = correctionMessage(errors, isChinese)
                            true
                        }
                    }
                }
            if (!again) {
                dropped?.let(closeConversation)
                changed()
                return
            }
            corrected = true
        }
    }

    /** 在锁内：解析并接受回复，返回协议错误（空表示已进入提问页或结果页）。 */
    private fun accept(raw: String): List<String> {
        val reply =
            try {
                parseReply(raw)
            } catch (e: ProtocolException) {
                return e.errors
            }
        outgoing = null
        state =
            when (reply) {
                is AgentReply.Ask -> ClarificationState.Asking(reply.questions)
                is AgentReply.Done -> ClarificationState.Result(reply.requirement)
            }
        return emptyList()
    }

    private fun fail(runId: Long, failed: ClarificationConversation?, error: Throwable) {
        val dropped: ClarificationConversation?
        synchronized(lock) {
            if (stale(runId)) return
            val (reason, detail) = classifyFailure(error)
            state = ClarificationState.Failed(reason, detail, null)
            dropped = if (conversation === failed || failed == null) detachLocked() else null
        }
        dropped?.let(closeConversation)
        changed()
    }

    fun fallbackResult() {
        synchronized(lock) {
            if (state !is ClarificationState.Failed) return
            generation++
            state = ClarificationState.Result(retainedPlanLocked() ?: composeFallback(record))
        }
        changed()
    }

    /** 看过结果后继续：对话还在就在其中接着问，否则（兜底结果）新开一段。 */
    fun continueClarifying(): Long? {
        val runId: Long
        synchronized(lock) {
            val result = state as? ClarificationState.Result ?: return null
            continuedPlan = result.requirement
            continuedPlanHistorySize = record.history.size
            runId = investigate(conversation?.let { continueMessage(isChinese, result.requirement) })
        }
        changed()
        return runId
    }

    /** 从失败状态重试：对话已在失败时结束，新开一段并以已确认的问答为开场。不在失败状态返回 null。 */
    fun retry(): Long? {
        val runId: Long
        synchronized(lock) {
            if (state !is ClarificationState.Failed) return null
            runId = investigate(null)
        }
        changed()
        return runId
    }

    fun progress(runId: Long, text: String) {
        synchronized(lock) {
            if (runId != generation) return
            val current = state as? ClarificationState.Investigating ?: return
            state = current.copy(progress = text)
        }
        changed()
    }

    /** 只在调查进行中生效：刚到的问题或结果不会被一次晚到的取消点击盖掉。结束这段对话，已确认的问答保留。 */
    fun cancelled() {
        val dropped: ClarificationConversation?
        synchronized(lock) {
            if (state !is ClarificationState.Investigating) return
            generation++
            state = ClarificationState.Failed(FailureReason.CANCELLED, "", null)
            dropped = detachLocked()
        }
        dropped?.let(closeConversation)
        changed()
    }

    /** 澄清结束（关窗、发送成功、标签页或项目关闭）：作废在途一轮并结束对话。之后这个流程不再使用。 */
    fun end() {
        val dropped: ClarificationConversation?
        synchronized(lock) {
            generation++
            dropped = detachLocked()
        }
        dropped?.let(closeConversation)
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

    companion object {
        private fun rethrowIfCancellation(e: Throwable) {
            // 平台的取消信号；262 里它恰好继承 CancellationException，这里显式写出，不依赖这一继承关系
            if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
            // kotlin.coroutines.cancellation.CancellationException 是它的别名，无需另判
            if (e is java.util.concurrent.CancellationException) throw e
            if (e is InterruptedException) throw e
        }
    }
}
