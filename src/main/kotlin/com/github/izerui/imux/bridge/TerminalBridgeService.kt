package com.github.izerui.imux.bridge

import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.terminal.TabChange
import com.github.izerui.imux.bridge.terminal.tabChangeFor
import com.github.izerui.imux.bridge.transcript.SessionFile
import com.github.izerui.imux.bridge.transcript.TailRegistry
import com.github.izerui.imux.monitor.SessionMonitor
import com.github.izerui.imux.terminal.TerminalHost
import com.github.izerui.imux.turn.TurnEventsListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
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
            sessionFile = { id -> SessionMonitor.getInstance(project).model.sessionOf(id)?.let { SessionFile(it.filePath, it.createdAt) } },
            switchGeneration = { BridgeLifecycle.switchGeneration },
        )

    private val poller = TabPoller(tails, hub.router) { BridgeSettings.getInstance().state.enabled }
    private val seenTabs = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var projectClosing = false

    // 启动活动（后台线程）、开关切换与绑定动作（EDT）都会调用 start()，必须原子地只启动一次
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
                override fun projectClosing(project: Project) {
                    if (project === this@TerminalBridgeService.project) projectClosing = true
                }
            },
        )
        project.messageBus.connect(this).subscribe(
            TurnEventsListener.TOPIC,
            object : TurnEventsListener {
                override fun turnCompleted(sessionId: String) {
                    if (!BridgeSettings.getInstance().state.enabled) return
                    val tabId = tabIdOf(sessionId) ?: return
                    // 信号到达这一刻就记下读取边界，结算只认这之前的内容；不另发“本轮完成”通知
                    val mark = tails.mark(tabId)
                    scope.launch(Dispatchers.IO) { poller.completed(tabId, mark) }
                }

                override fun turnWaiting(
                    sessionId: String,
                    reason: String?,
                ) {
                    if (!BridgeSettings.getInstance().state.enabled) return
                    val tabId = tabIdOf(sessionId) ?: return
                    scope.launch(Dispatchers.IO) { poller.waiting(tabId, reason) }
                }
            },
        )
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                val enabled = BridgeSettings.getInstance().state.enabled
                tails.observeSwitch()
                if (enabled) {
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
        ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) onSessionsChanged() }, ModalityState.any())
    }

    /** 在 EDT 调用（TerminalHost 的回调线程）。 */
    private fun onSessionsChanged() {
        val open = TerminalHost.getInstance(project).openTabsByTabId()
        seenTabs += open.keys
        for (binding in bindingsInProject()) {
            if (binding.target.tabId in open) SessionTitles.remember(binding)
            when (val change = tabChangeFor(binding, open, seenTabs, projectClosing)) {
                TabChange.Keep -> Unit
                is TabChange.Reattach -> hub.bindings.reattach(binding.robotId, change.tabId)
                TabChange.Release -> release(binding)
            }
        }
    }

    private fun release(binding: Binding) {
        // 标签已关闭，SessionTitles 用它还开着时记下的名字
        BindingService.release(binding, SessionTitles.of(binding))
        SessionTitles.forget(binding.target.tabId)
    }

    private fun onMigrated(to: String) {
        val tabId = tabIdOf(to) ?: return
        val binding = hub.bindings.migrateSession(tabId, to) ?: return
        tails.migrated(tabId, binding.epoch)
    }

    private fun bindingsInProject(): List<Binding> = hub.bindings.snapshot().filter { it.target.projectPath == projectPath }

    private fun tabIdOf(sessionKey: String): String? =
        TerminalHost.getInstance(project).openTabsByTabId().entries.firstOrNull { it.value == sessionKey }?.key

    override fun dispose() = Unit

    companion object {
        private val LOG = logger<TerminalBridgeService>()
        private const val POLL_INTERVAL_MS = 1_000L

        fun getInstance(project: Project): TerminalBridgeService = project.getService(TerminalBridgeService::class.java)
    }
}
