package com.github.izerui.imux.bridge

import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.terminal.TabChange
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
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    @Volatile
    private var projectClosing = false

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
            object : ProjectCloseListener {
                // 保存前就记下：退出时终端可能先于 projectClosing 被关掉，不能当成用户关了标签去断开手机
                override fun projectClosingBeforeSave(project: Project) {
                    if (project === this@TerminalBridgeService.project) projectClosing = true
                }

                override fun projectClosing(project: Project) {
                    if (project === this@TerminalBridgeService.project) projectClosing = true
                }
            },
        )
        // 确定要退出时记下（appClosing 之后还可能被取消，不用它）：之后关掉的标签都是退出顺带关的，绑定要留到重启后接回
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            AppLifecycleListener.TOPIC,
            object : AppLifecycleListener {
                override fun appWillBeClosed(isRestart: Boolean) {
                    projectClosing = true
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
        val open = TerminalHost.getInstance(project).openTabsByTabId()
        seenTabs += open.keys
        for (binding in bindingsInProject()) {
            if (binding.target.tabId in open) SessionTitles.remember(binding)
            when (val change = tabChangeFor(binding, open, seenTabs, projectClosing || project.isDisposed)) {
                TabChange.Keep -> Unit
                is TabChange.Reattach -> hub.bindings.reattach(binding.robotId, change.tabId)?.let(::reattached)
                TabChange.Release -> scheduleRelease(binding)
            }
        }
    }

    /** 同一个会话在新标签里恢复（IDE 重启、项目重新打开）：告诉手机连接已自动恢复。 */
    private fun reattached(binding: Binding) {
        LOG.info("会话标签恢复，机器人 ${binding.robotId.value} 自动接回")
        SessionTitles.remember(binding)
        val title = SessionTitles.of(binding)
        hub.launch { hub.router.onReattached(binding, project.name, binding.target.agentType.displayName, title) }
    }

    /**
     * 标签不见了先等一会儿再断开：IDE 退出、关项目时，终端可能比关闭信号先被关掉，立即断开会给手机发一条
     * 「已断开」，重启后绑定却又自动接回。等待期间收到关闭信号就不断开；服务随项目关闭销毁时，这次等待一并取消。
     */
    private fun scheduleRelease(binding: Binding) {
        if (!pendingReleases.add(binding.robotId)) return
        scope.launch {
            try {
                delay(RELEASE_GRACE_MS)
                withContext(Dispatchers.EDT) {
                    if (projectClosing || project.isDisposed) return@withContext
                    val current = hub.bindings.bindingOf(binding.robotId)?.takeIf { it.epoch == binding.epoch }
                        ?: return@withContext
                    val open = TerminalHost.getInstance(project).openTabsByTabId()
                    if (tabChangeFor(current, open, seenTabs, projectClosing = false) == TabChange.Release) release(
                        current
                    )
                }
            } finally {
                pendingReleases.remove(binding.robotId)
            }
        }
    }

    private fun release(binding: Binding) {
        LOG.info("绑定的标签已关闭，断开机器人 ${binding.robotId.value}")
        // 标签已关闭，SessionTitles 用它还开着时记下的名字
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
