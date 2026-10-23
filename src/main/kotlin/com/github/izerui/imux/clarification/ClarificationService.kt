package com.github.izerui.imux.clarification

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.agent.AgentMcpConfig
import com.github.izerui.imux.agent.OneShotAgentTimeoutException
import com.github.izerui.imux.agent.UnexitedProcesses
import com.github.izerui.imux.agent.agentConversation
import com.github.izerui.imux.agent.conversationInvocation
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.monitor.SessionMonitor
import com.github.izerui.imux.overview.overviewProgressText
import com.github.izerui.imux.peer.latestConversationOf
import com.github.izerui.imux.peer.trySendPeerFeedback
import com.github.izerui.imux.settings.PluginLanguage
import com.github.izerui.imux.terminal.TerminalHost
import com.github.izerui.imux.terminal.resolveShell
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.jetbrains.plugins.terminal.settings.TerminalLocalOptions
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * 按会话保存需求澄清流程，负责开启与澄清 Agent 的对话、取消、会话 key 迁移、结束清理与发送。
 *
 * 一次澄清 = 一段对话：点「开始澄清」时开始，关窗、「交给 Agent」发送成功、会话标签页关闭、项目关闭时结束
 * （终止进程、删除 Codex 的会话文件、清空澄清状态）。不保留、不恢复。
 * 标签页关闭按标签页身份（tabId）清理，与轨迹相同：`/clear`、`/new` 迁移后旧 key 已不在。
 */
@Service(Service.Level.PROJECT)
class ClarificationService(
    private val project: Project,
    private val scope: CoroutineScope,
) : Disposable {
    private val flows = ConcurrentHashMap<String, ClarificationFlow>()
    private val owners = ConcurrentHashMap<String, String>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val dialogs = ConcurrentHashMap<String, ClarificationDialog>()
    private val unexited = UnexitedProcesses()

    init {
        TerminalHost.getInstance(project).addSessionKeyMigratedListener(this) { from, to -> migrate(from, to) }
    }

    internal fun flowOf(sessionKey: String): ClarificationFlow? = flows[sessionKey]

    internal fun currentKeyOf(flow: ClarificationFlow): String? = flows.entries.firstOrNull { it.value === flow }?.key

    /** 打开或聚焦该会话的窗口；第一次打开时创建流程。 */
    internal fun open(sessionKey: String, tabId: String, agentType: AgentType, title: String) {
        dialogs[sessionKey]?.let {
            it.toFront()
            return
        }
        owners[sessionKey] = tabId
        val flow = flows.getOrPut(sessionKey) { newFlow(agentType) }
        val dialog = ClarificationDialog(project, this, flow, title)
        dialogs[sessionKey] = dialog
        dialog.show()
    }

    private fun newFlow(agentType: AgentType): ClarificationFlow {
        val chinese = ImuxBundle.currentLanguage() in setOf(PluginLanguage.SIMPLIFIED_CHINESE, PluginLanguage.TRADITIONAL_CHINESE)
        return ClarificationFlow(
            chinese,
            loadGuidance(chinese),
            openConversation = { openConversation(agentType) },
            // 结束对话要等进程退出（最多几秒）：交给后台线程，关窗、取消不卡 EDT；项目关闭时服务作用域已取消，也照样执行
            closeConversation = { conversation -> ApplicationManager.getApplication().executeOnPooledThread { conversation.close() } },
            classifyFailure = { e ->
                if (e is OneShotAgentTimeoutException) FailureReason.TIMEOUT to e.timeoutSeconds.toString()
                else FailureReason.AGENT_ERROR to (e.message ?: e.javaClass.simpleName)
            },
        )
    }

    /** 会话记录文件是否存在。查文件系统，放在 IO 线程上做，结果交给 [onResult]（仍在 IO 线程）。 */
    internal fun checkBackground(sessionKey: String, onResult: (Boolean) -> Unit) {
        scope.launch(Dispatchers.IO) {
            onResult(SessionMonitor.getInstance(project).model.sessionOf(sessionKey)?.let { Files.isRegularFile(it.filePath) } == true)
        }
    }

    internal fun start(sessionKey: String, requirement: String, includeBackground: Boolean) {
        val flow = flows[sessionKey] ?: return
        val session = if (includeBackground) SessionMonitor.getInstance(project).model.sessionOf(sessionKey) else null
        val runId = flow.begin(requirement, null)
        launch(sessionKey, flow) {
            flow.attachBackground(runId, session?.let { s -> latestConversationOf(s, emptyList(), MAX_BACKGROUND_CHARS) })
            flow.run(runId)
        }
    }

    /** 提交这一批回答：一次记下，在同一段对话里只发起一轮。 */
    internal fun submit(sessionKey: String, answers: Map<String, Answer>) {
        val flow = flows[sessionKey] ?: return
        val runId = flow.submitBatch(answers) ?: return
        launch(sessionKey, flow) { flow.run(runId) }
    }

    internal fun fallback(sessionKey: String) { flows[sessionKey]?.fallbackResult() }

    internal fun continueClarifying(sessionKey: String) {
        val flow = flows[sessionKey] ?: return
        val runId = flow.continueClarifying() ?: return
        launch(sessionKey, flow) { flow.run(runId) }
    }

    internal fun retry(sessionKey: String) {
        val flow = flows[sessionKey] ?: return
        val runId = flow.retry() ?: return
        launch(sessionKey, flow) { flow.run(runId) }
    }

    internal fun cancel(sessionKey: String) {
        // 先让流程作废这一轮并结束对话，再中断任务：中断后 send 若抛出被包装的异常，也只会被当作过期结果忽略
        flows[sessionKey]?.cancelled()
        jobs.remove(sessionKey)?.cancel()
    }

    /**
     * 在 IO 线程跑一段阻塞的流程调用。Agent 的异常、进度、结果都由流程按调度时拿到的 runId 自己核对，
     * 这里不再处理失败：取消、重试之后才真正启动的旧任务，带着旧 runId，进去就会被忽略。
     */
    private fun launch(sessionKey: String, flow: ClarificationFlow, body: () -> Unit) {
        register(sessionKey, flow) { scope.launch(Dispatchers.IO) { runInterruptible { body() } } }
    }

    /**
     * 取消该流程的旧任务并登记新任务。key 取流程当前所在的 key（[sessionKey] 只作后备），
     * 与 [migrate] 同锁：迁移不会插在取消旧任务与登记新任务之间，把新任务留在旧 key 下。
     */
    private fun register(sessionKey: String, flow: ClarificationFlow, start: () -> Job) {
        synchronized(jobs) {
            val key = currentKeyOf(flow) ?: sessionKey
            jobs.remove(key)?.cancel()
            jobs[key] = start()
        }
    }

    /** 开一段对话（还没启动进程，第一次发消息时才启动）。在 IO 线程调用：读 IDEA MCP 设置、可能写 Pi 的扩展脚本。 */
    private fun openConversation(agentType: AgentType): ClarificationConversation {
        val projectPath = project.basePath ?: "."
        val shell =
            resolveShell(
                System.getenv("SHELL"),
                isWindows = SystemInfo.isWindows,
                configuredShell = ApplicationManager.getApplication()?.let { TerminalLocalOptions.getInstance().shellPath },
            )
        val mcp = AgentMcpConfig.configured(project, projectPath, agentType)
        unexited.retry()
        val conversation =
            agentConversation(
                agentType,
                { resumeId -> conversationInvocation(shell, agentType, projectPath, mcp, resumeId) },
                Path.of(projectPath),
                AGENT_TIMEOUT_SECONDS,
            )
        return object : ClarificationConversation {
            override fun send(message: String, onProgress: (String) -> Unit): String =
                conversation.send(message) { event -> overviewProgressText(event)?.let(onProgress) }

            override fun close() = unexited.addAll(conversation.close())
        }
    }

    /**
     * 与结对编程对齐的发送时机：主会话正在执行、或 Claude 停在确认框上时等这一轮结束，其余立即发。
     * 判定读的是 [SessionMonitor] 已有的运行集合与运行态，与结对编程触发审查用的是同一组信号；
     * 在循环里轮询而不是挂在「轮次完成」事件上，确认框被 Esc 取消、直接回到空闲时也能发出去。
     * 括号粘贴未开启时持续重试（与 `PeerCoordinator.injectFeedback` 相同）；可随时取消；
     * 标签页已关闭则通知并提供复制。
     */
    internal fun send(sessionKey: String, text: String) {
        val flow = flows[sessionKey] ?: return
        flow.sending(waitingForTurn = isMainSessionBusy(sessionKey))
        register(sessionKey, flow) {
            scope.launch(Dispatchers.EDT) {
                while (isActive) {
                    val key = currentKeyOf(flow) ?: return@launch
                    val view = TerminalHost.getInstance(project).terminalViewOf(key)
                    if (view == null) {
                        flow.sendCancelled()
                        notifyUndelivered(text)
                        return@launch
                    }
                    val busy = isMainSessionBusy(key)
                    flow.sending(waitingForTurn = busy)
                    if (!busy && trySendPeerFeedback(view.createSendTextBuilder(), text)) {
                        discard(key)
                        return@launch
                    }
                    delay(if (busy) BUSY_POLL_MILLIS else SEND_RETRY_MILLIS)
                }
            }
        }
    }

    internal fun cancelSend(sessionKey: String) {
        jobs.remove(sessionKey)?.cancel()
        flows[sessionKey]?.sendCancelled()
    }

    private fun isMainSessionBusy(sessionKey: String): Boolean {
        val monitor = SessionMonitor.getInstance(project)
        val waiting = monitor.runtime.values.filter { it.isWaiting }.map { it.sessionId }.toSet()
        return mainSessionBusy(monitor.runningIds, waiting, sessionKey)
    }

    private fun notifyUndelivered(text: String) {
        val notification =
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(ImuxBundle.message("clarify.notify.title"), ImuxBundle.message("clarify.notify.closed"), NotificationType.WARNING)
        notification.addAction(
            NotificationAction.createSimpleExpiring(ImuxBundle.message("clarify.notify.copy")) {
                CopyPasteManager.getInstance().setContents(StringSelection(text))
            },
        )
        notification.notify(project)
    }

    /** 窗口关闭即结束澄清：残留的对话以后也用不上（是否确认由窗口在关闭前问过）。 */
    internal fun dialogClosed(sessionKey: String) {
        // 先摘掉窗口：它正在关闭，discard 不能再去关它一次
        dialogs.remove(sessionKey)
        discard(sessionKey)
    }

    /** 结束该会话的澄清：先作废在途一轮并结束对话，再中断任务、清空状态、关窗。可重复调用。 */
    private fun discard(sessionKey: String) {
        flows.remove(sessionKey)?.end()
        jobs.remove(sessionKey)?.cancel()
        owners.remove(sessionKey)
        dialogs.remove(sessionKey)?.close(com.intellij.openapi.ui.DialogWrapper.OK_EXIT_CODE)
    }

    /**
     * 会话标签页真正关闭：结束对话、丢弃状态。结果页或发送中的需求还没送进终端，
     * 丢弃前先通知并提供复制，否则这段文字就没了。
     */
    internal fun releaseOwner(tabId: String) {
        owners.filterValues { it == tabId }.keys.forEach { key ->
            val flow = flows[key]
            val undelivered = flow?.let { undeliveredRequirement(it.state) }
            discard(key)
            undelivered?.let(::notifyUndelivered)
        }
    }

    private fun migrate(from: String, to: String) {
        if (from == to) return
        synchronized(jobs) {
            flows.remove(from)?.let { flows[to] = it }
            owners.remove(from)?.let { owners[to] = it }
            jobs.remove(from)?.let { jobs[to] = it }
        }
        dialogs.remove(from)?.let {
            dialogs[to] = it
            it.sessionKey = to
        }
    }

    override fun dispose() {
        flows.values.forEach { it.end() }
        jobs.values.forEach { it.cancel() }
        // 项目关闭时窗口不会随之关闭，必须由服务收起
        dialogs.values.toList().forEach { it.close(com.intellij.openapi.ui.DialogWrapper.CANCEL_EXIT_CODE) }
        dialogs.clear()
    }

    companion object {
        fun getInstance(project: Project): ClarificationService = project.service()

        internal const val AGENT_TIMEOUT_SECONDS = 600L
        private const val SEND_RETRY_MILLIS = 100L
        private const val BUSY_POLL_MILLIS = 500L
        private const val NOTIFICATION_GROUP = "imux.turnCompleted"
    }
}

/** 结果页或发送中的需求尚未送达终端，关闭标签页时需要交给用户；其余状态没有可交付的文字。 */
internal fun undeliveredRequirement(state: ClarificationState): String? =
    when (state) {
        is ClarificationState.Result -> state.requirement
        is ClarificationState.Sending -> state.requirement
        else -> null
    }

/** 主会话正在执行或停在确认框上（只有 Claude 有这个信号）。纯函数，便于单测。 */
internal fun mainSessionBusy(running: Set<String>, waiting: Set<String>, sessionKey: String): Boolean =
    sessionKey in running || sessionKey in waiting
