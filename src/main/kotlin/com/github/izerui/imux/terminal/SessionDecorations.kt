package com.github.izerui.imux.terminal

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import java.util.EventListener
import kotlin.coroutines.cancellation.CancellationException

/**
 * 其它功能给终端会话加的装饰：标签与会话树上的标记、终端右上角浮动图标组里的一个按钮。
 *
 * 现有代码只认这个接口，不认识具体功能；实现通过扩展点
 * `com.github.izerui.imux.sessionDecorator` 注册。实现必须很快返回，
 * 因为标签图标和会话树会频繁询问。
 */
interface SessionDecorator {
    fun isPhoneConnected(
        project: Project,
        tabId: String,
    ): Boolean

    /**
     * 放进终端右上角浮动图标组的按钮；不需要时返回 null。
     * 按钮的样子由 [SessionTool.state] 按当前状态算出；装饰变化时编辑器会重新询问。
     * 需要用户注意的事（断线、重连中、刚连上）通过 [notices] 发到终端顶部。随 [parent] 一起释放。
     */
    fun createTool(
        project: Project,
        file: AgentTerminalVirtualFile,
        parent: Disposable,
        notices: NoticeSink,
    ): SessionTool?
}

/** 装饰变化。只在 EDT 上发布，订阅方可直接操作 Swing。 */
fun interface SessionDecorationsListener : EventListener {
    fun decorationsChanged()

    companion object {
        @JvmField
        @Topic.AppLevel
        val TOPIC: Topic<SessionDecorationsListener> =
            Topic(SessionDecorationsListener::class.java, Topic.BroadcastDirection.NONE)
    }
}

/** 现有代码访问装饰的唯一入口。任何实现出错都不影响标签、会话树和终端。 */
object SessionDecorations {
    private val EP_NAME = ExtensionPointName<SessionDecorator>("com.github.izerui.imux.sessionDecorator")

    fun phoneConnected(
        project: Project,
        tabId: String,
    ): Boolean = collectSafely(EP_NAME.extensionList, false) { it.isPhoneConnected(project, tabId) }.any { it }

    fun tools(
        project: Project,
        file: AgentTerminalVirtualFile,
        parent: Disposable,
        notices: NoticeSink,
    ): List<SessionTool> = collectSafely(EP_NAME.extensionList, null) { it.createTool(project, file, parent, notices) }.filterNotNull()

    /** 由装饰实现在 EDT 上调用。 */
    fun changed() {
        val application = ApplicationManager.getApplication()
        if (application.isDisposed) return
        application.messageBus.syncPublisher(SessionDecorationsListener.TOPIC).decorationsChanged()
    }
}

internal fun <R> collectSafely(
    decorators: List<SessionDecorator>,
    fallback: R,
    call: (SessionDecorator) -> R,
): List<R> =
    decorators.map { decorator ->
        try {
            call(decorator)
        } catch (e: Throwable) {
            if (e is CancellationException || e is ControlFlowException || e is VirtualMachineError) throw e
            LOG.warn("会话装饰 ${decorator.javaClass.name} 出错，已忽略", e)
            fallback
        }
    }

private val LOG = logger<SessionDecorator>()
