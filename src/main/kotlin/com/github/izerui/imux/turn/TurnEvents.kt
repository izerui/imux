package com.github.izerui.imux.turn

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import java.util.EventListener
import kotlin.coroutines.cancellation.CancellationException

/**
 * 会话轮次事件的项目级广播。只广播，不改变 [TurnNotifier] 与监控状态机的任何行为。
 *
 * 放在 turn 包而不是消费方的包里：发布方（SessionMonitor）因此不必知道谁在听，
 * 依赖方向始终是"新功能依赖现有代码"，而不是反过来。
 */
interface TurnEventsListener : EventListener {
    fun turnCompleted(sessionId: String)

    fun turnWaiting(
        sessionId: String,
        reason: String?,
    )

    companion object {
        @JvmField
        @Topic.ProjectLevel
        val TOPIC: Topic<TurnEventsListener> =
            Topic(TurnEventsListener::class.java, Topic.BroadcastDirection.NONE)
    }
}

/** 发布一条轮次事件。监听方的任何异常都只记日志，绝不影响调用方原有的通知流程。 */
fun publishTurnEvent(
    project: Project,
    event: (TurnEventsListener) -> Unit,
) {
    if (project.isDisposed) return
    deliverTurnEvent({ project.messageBus.syncPublisher(TurnEventsListener.TOPIC) }, event) {
        LOG.warn("轮次事件监听方异常，已忽略", it)
    }
}

internal fun deliverTurnEvent(
    publisher: () -> TurnEventsListener,
    event: (TurnEventsListener) -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    try {
        event(publisher())
    } catch (e: Exception) {
        if (e is CancellationException || e is ControlFlowException) throw e
        onFailure(e)
    }
}

private val LOG = logger<TurnEventsListener>()
