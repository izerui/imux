package com.github.izerui.imux.bridge

import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.terminal.TabChange
import com.github.izerui.imux.bridge.terminal.SessionStateObserver
import com.github.izerui.imux.bridge.terminal.tabChangeFor
import com.github.izerui.imux.bridge.transcript.SessionFile
import com.github.izerui.imux.bridge.transcript.TailRegistry
import com.github.izerui.imux.monitor.SessionMonitor
import com.github.izerui.imux.peer.PeerReviewEvent
import com.github.izerui.imux.terminal.TerminalHost
import com.github.izerui.imux.turn.TurnEventsListener
import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.terminal.frontend.view.TerminalViewSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * 一个项目里绑定终端的观察者：订阅标签变化、会话迁移与轮次事件，旁路读取会话记录。
 *
 * 只订阅、只读。不调用 TurnWatcher、TurnNotifier 或未读标记；不初始化懒启动的恢复标签。
 */
@Service(Service.Level.PROJECT)
class TerminalBridgeService(
    private val project: Project,
    parentScope: CoroutineScope,
) : Disposable {
    private val scope = isolatedScope(parentScope, LOG, "项目桥接任务")
    private val projectPath = project.basePath ?: System.getProperty("user.home")
    private val hub get() = BridgeHub.getInstance()
    private val tails =
        TailRegistry(
            bindingForTab = { hub.bindings.bindingForTab(it) },
            sessionFile = { id ->
                SessionMonitor.getInstance(project).model.sessionOf(id)?.let { SessionFile(it.filePath, it.createdAt) }
            },
            switchGeneration = { 0L },
        )

    private val poller = TabPoller(tails, hub.router) { !project.isDisposed }

    /** 结对审查提示按发生顺序逐条处理。 */
    private val peerEvents = Dispatchers.IO.limitedParallelism(1)
    private val seenTabs = ConcurrentHashMap.newKeySet<String>()
    private val pendingReleases = ConcurrentHashMap.newKeySet<RobotId>()
    // 终端状态变化（启动、CLI 退出）回到 EDT 重新对账：刷新界面，CLI 退出时解绑
    private val sessionStates = SessionStateObserver(scope) {
        ApplicationManager.getApplication()
            .invokeLater({ if (!projectClosing && !project.isDisposed) onSessionsChanged() }, ModalityState.any())
    }
    private val closeNotifier = BridgeCloseNotifier(
        send = { notice -> hub.router.onSuspended(notice.binding, notice.title, notice.label) },
        onFailure = { LOG.warn("关闭时发送手机断开提示失败", it) },
        onTimeout = { LOG.warn("关闭时发送手机断开提示超时，继续关闭") },
    )

    @Volatile
    private var projectClosing = false

    // 确定关闭后会停止项目任务，对外也随即判定不可用。
    internal fun isClosing(): Boolean = !scope.isActive

    // 启动活动（后台线程）与绑定动作（EDT）都会调用 start()，必须原子地只启动一次
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val host = TerminalHost.getInstance(project)
        seenTabs += host.openTabsByTabId().keys
        host.addSessionsChangedListener(this) { onSessionsChanged() }
        host.addSessionKeyMigratedListener(this) { _, to -> onMigrated(to) }
        project.messageBus.connect(this).subscribe(
            ProjectCloseListener.TOPIC,
            bridgeProjectCloseListener(project, ::notifyClosing),
        )
        // 确定要退出时记下（appClosing 之后还可能被取消，不用它）：之后关掉的标签都是退出顺带关的，绑定要留到重启后接回
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            AppLifecycleListener.TOPIC,
            object : AppLifecycleListener {
                override fun appWillBeClosed(isRestart: Boolean) {
                    notifyClosing()
                }
            },
        )
        project.messageBus.connect(this).subscribe(
            TurnEventsListener.TOPIC,
            object : TurnEventsListener {
                override fun turnCompleted(sessionId: String) {
                    val tabId = tabIdOf(sessionId) ?: return
                    // 信号到达这一刻就记下读取边界，结算只认这之前的内容；不另发“本轮完成”通知
                    val mark = tails.mark(tabId)
                    scope.launch(Dispatchers.IO) { poller.completed(tabId, mark) }
                }

                override fun turnWaiting(
                    sessionId: String,
                    reason: String?,
                ) {
                    val tabId = tabIdOf(sessionId) ?: return
                    scope.launch(Dispatchers.IO) { poller.waiting(tabId, reason) }
                }

                override fun turnResumed(sessionId: String) {
                    val tabId = tabIdOf(sessionId) ?: return
                    scope.launch(Dispatchers.IO) { poller.resumed(tabId) }
                }
            },
        )
        SessionMonitor.getInstance(project).peerCoordinator.addReviewListener(this) { event -> onPeerReview(event) }
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (!project.isDisposed) {
                    for (binding in bindingsInProject()) {
                        val tabId = binding.target.tabId
                        // 上一次还在发送的标签这轮跳过，等它发完再读
                        if (poller.isBusy(tabId)) continue
                        launch {
                            try {
                                poller.poll(tabId)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                LOG.warn("轮询标签 $tabId 失败", e)
                            }
                        }
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
        // 恢复的绑定可能指向 "restored:<sessionId>" 占位标签；如果会话标签已经打开，
        // 需要立即对账一次以触发 Reattach，否则直到下次标签变化才会挂接。
        ApplicationManager.getApplication()
            .invokeLater({ if (!project.isDisposed) onSessionsChanged() }, ModalityState.any())
    }

    /**
     * 开着结对时，把审查的开始与结局推给绑定这个标签的手机，远程用户看得到回复之后是不是还有审查在进行。
     * 在 EDT 调用。持续结对的审查开始、到上限在主会话轮次完成信号之前发出，所以走 [TabPoller.afterTurn]，排在本轮回复之后。
     */
    private fun onPeerReview(event: PeerReviewEvent) {
        val text = peerReviewText(event) ?: return
        val tabId = tabIdOf(event.sessionKey) ?: return
        if (hub.bindings.bindingForTab(tabId) == null) return
        // 与轮次完成信号一样，在这一刻同步记下读取边界
        val mark = if (event.followsTurn()) tails.mark(tabId) else null
        val followsTurn = event.followsTurn()
        val immediate = event.immediate()
        // 单线程依次发出：开始与结果分属不同协程时，先发生的先拿到标签锁
        scope.launch(peerEvents) {
            when {
                followsTurn -> poller.afterTurn(tabId, text, mark)
                immediate -> poller.statusNow(tabId, text)
                else -> poller.status(tabId, text)
            }
        }
    }

    /** 在 EDT 调用（TerminalHost 的回调线程）。 */
    private fun onSessionsChanged() {
        val host = TerminalHost.getInstance(project)
        val open = host.openTabsByTabId()
        sessionStates.update(open.mapNotNull { (tabId, key) ->
            host.terminalViewOf(key)?.sessionState?.let { tabId to it }
        }.toMap())
        seenTabs += open.keys
        val terminated = terminatedTabs(host, open)
        for (binding in bindingsInProject()) {
            if (binding.target.tabId in open) SessionTitles.remember(binding)
            when (val change = tabChangeFor(binding, open, seenTabs, projectClosing || project.isDisposed, terminated)) {
                TabChange.Keep -> Unit
                is TabChange.Reattach -> hub.bindings.reattach(binding.robotId, change.tabId)?.let(::reattached)
                TabChange.Release -> scheduleRelease(binding)
            }
        }
        // 标签关闭或恢复时，绑定可能没有变化，但“会话可用”已经变化。
        hub.changed()
    }

    /** 同一个会话在新标签里恢复（IDE 重启、项目重新打开）：告诉手机连接已自动恢复。 */
    private fun reattached(binding: Binding) {
        if (projectClosing || project.isDisposed) return
        LOG.info("会话标签恢复，机器人 ${binding.robotId.value} 自动接回")
        SessionTitles.remember(binding)
        val title = SessionTitles.of(binding)
        notifyConnection { hub.router.onReattached(binding, project.name, binding.target.agentType.displayName, title) }
    }

    /** 手动连接与自动恢复共用项目作用域，关闭后不再补发过期的连接提示。 */
    internal fun notifyConnection(block: suspend () -> Unit) {
        if (projectClosing || project.isDisposed) return
        scope.launch { block() }
    }

    private fun notifyClosing() {
        projectClosing = true
        // 取消还在等上线或限频重试的连接提示，再发送“已断开”。
        scope.cancel()
        hub.changed()
        // 先取好标题和署名，发送线程不再访问即将销毁的项目服务。
        closeNotifier.notifyOnce(bindingsInProject().map { ClosingBinding(it, SessionTitles.of(it), SessionTitles.label(it)) })
    }

    /**
     * 标签不见了先延迟复核，并等保存弹窗退出：正常关闭会取消本任务并保留绑定；
     * 保存失败后取消关闭则照常释放已关闭标签，不需要一个可能残留的“保存前关闭”标记。
     */
    private fun scheduleRelease(binding: Binding) {
        if (!pendingReleases.add(binding.robotId)) return
        scope.launch {
            try {
                delay(RELEASE_GRACE_MS)
                withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) {
                    if (projectClosing || project.isDisposed) return@withContext
                    val current = hub.bindings.bindingOf(binding.robotId)?.takeIf { it.epoch == binding.epoch }
                        ?: return@withContext
                    val host = TerminalHost.getInstance(project)
                    val open = host.openTabsByTabId()
                    val change = tabChangeFor(current, open, seenTabs, projectClosing = false, terminatedTabs(host, open))
                    if (change == TabChange.Release) release(current)
                }
            } finally {
                pendingReleases.remove(binding.robotId)
            }
        }
    }

    private fun release(binding: Binding) {
        LOG.info("绑定的标签已关闭或 CLI 已退出，断开机器人 ${binding.robotId.value}")
        // 标签可能已关闭，SessionTitles 用它还开着时记下的名字
        BindingService.release(binding, SessionTitles.of(binding))
        SessionTitles.forget(binding.target.tabId)
    }

    private fun onMigrated(to: String) {
        val tabId = tabIdOf(to) ?: return
        val binding = hub.bindings.migrateSession(tabId, to) ?: return
        tails.migrated(tabId, binding.epoch)
        // 同一个标签换成了另一个会话（/clear、新开会话）：之前会话的后台任务不再有结束通知
        hub.router.resetBackground(tabId)
    }

    /** 标签还开着、CLI 已经退出的终端。 */
    private fun terminatedTabs(
        host: TerminalHost,
        open: Map<String, String>,
    ): Set<String> =
        open.filterValues { key -> host.terminalViewOf(key)?.sessionState?.value is TerminalViewSessionState.Terminated }.keys

    private fun bindingsInProject(): List<Binding> =
        hub.bindings.snapshot().filter { it.target.projectPath == projectPath }

    private fun tabIdOf(sessionKey: String): String? =
        TerminalHost.getInstance(project).openTabsByTabId().entries.firstOrNull { it.value == sessionKey }?.key

    override fun dispose() = Unit

    companion object {
        private val LOG = logger<TerminalBridgeService>()
        private const val POLL_INTERVAL_MS = 1_000L
        private const val RELEASE_GRACE_MS = 2_000L

        fun getInstance(project: Project): TerminalBridgeService = project.getService(TerminalBridgeService::class.java)
    }
}
