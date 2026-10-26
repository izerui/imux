package com.github.izerui.imux.bridge

import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.terminal.TabChange
import com.github.izerui.imux.bridge.terminal.tabChangeFor
import com.github.izerui.imux.bridge.transcript.TranscriptDecoders
import com.github.izerui.imux.bridge.transcript.TranscriptTail
import com.github.izerui.imux.monitor.SessionMonitor
import com.github.izerui.imux.terminal.TerminalHost
import com.github.izerui.imux.turn.TurnEventsListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
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
    private val scope =
        CoroutineScope(
            parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) +
                CoroutineExceptionHandler { _, e -> LOG.warn("项目桥接任务异常，已隔离", e) },
        )
    private val projectPath = project.basePath ?: System.getProperty("user.home")
    private val hub get() = BridgeHub.getInstance()
    private val tails = ConcurrentHashMap<String, TranscriptTail>()
    private val pollLock = Mutex()
    private val seenTabs = ConcurrentHashMap.newKeySet<String>()
    private val migratedAt = ConcurrentHashMap<String, Instant>()

    @Volatile
    private var projectClosing = false
    private var started = false

    fun start() {
        if (started) return
        started = true
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
                    val tabId = tabIdOf(sessionId) ?: return
                    scope.launch(Dispatchers.IO) {
                        // 先把最后的回复读出来，再报完成，手机上的顺序才和终端一致
                        pollTab(tabId)
                        hub.router.onTurnCompleted(tabId)
                    }
                }

                override fun turnWaiting(
                    sessionId: String,
                    reason: String?,
                ) {
                    val tabId = tabIdOf(sessionId) ?: return
                    scope.launch(Dispatchers.IO) {
                        pollTab(tabId)
                        hub.router.onWaiting(tabId, reason)
                    }
                }
            },
        )
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                for (binding in boundHere()) {
                    try {
                        pollTab(binding.target.tabId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        LOG.warn("轮询标签 ${binding.target.tabId} 失败", e)
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** 在 EDT 调用（TerminalHost 的回调线程）。 */
    private fun onSessionsChanged() {
        val open = TerminalHost.getInstance(project).openTabsByTabId()
        seenTabs += open.keys
        for (binding in boundHere()) {
            when (val change = tabChangeFor(binding, open, seenTabs, projectClosing)) {
                TabChange.Keep -> Unit
                is TabChange.Reattach -> {
                    hub.bindings.reattach(binding.robotId, change.tabId)
                    tails.remove(binding.target.tabId)
                    migratedAt.remove(binding.target.tabId)
                }
                TabChange.Release -> release(binding)
            }
        }
    }

    private fun release(binding: Binding) {
        if (hub.bindings.bindingOf(binding.robotId)?.epoch != binding.epoch) return
        hub.bindings.unbind(binding.robotId)
        tails.remove(binding.target.tabId)
        migratedAt.remove(binding.target.tabId)
        val title = titleOf(binding.target.sessionId)
        scope.launch(Dispatchers.IO) { hub.router.onReleased(binding, title) }
    }

    private fun onMigrated(to: String) {
        val tabId = tabIdOf(to) ?: return
        if (hub.bindings.migrateSession(tabId, to) != null) {
            tails.remove(tabId)
            migratedAt[tabId] = Instant.now()
        }
    }

    private suspend fun pollTab(tabId: String) {
        pollLock.withLock {
            val binding = hub.bindings.bindingForTab(tabId) ?: return
            val tail = tails[tabId] ?: newTail(binding) ?: return
            val poll = tail.poll()
            if (poll.restarted) hub.router.onTranscriptRestarted(tabId)
            if (poll.events.isNotEmpty()) hub.router.onSessionEvents(tabId, poll.events)
        }
    }

    /**
     * 会话文件出现后才建立尾随器。
     * 迁移到新会话时从头读取，恢复到旧会话时从末尾开始，避免倾倒历史。
     */
    private fun newTail(binding: Binding): TranscriptTail? {
        val sessionId = binding.target.sessionId ?: return null
        val session = SessionMonitor.getInstance(project).model.sessionOf(sessionId) ?: return null
        val migrated = migratedAt.remove(binding.target.tabId)
        val tail = TranscriptTail(session.filePath, TranscriptDecoders.forAgent(binding.target.agentType))
        if (!readFromStart(session.createdAt, migrated)) tail.startAtEnd()
        tails[binding.target.tabId] = tail
        return tail
    }

    /**
     * 迁移后是否从头读取会话记录。
     * 会话创建时间在迁移前 60 秒以内视为新会话（/clear、/new），从头转发；
     * 更早的会话（resume 回旧会话）则从末尾开始，避免倾倒全部历史。
     */
    internal fun readFromStart(createdAt: Instant, migratedAt: Instant?): Boolean {
        if (migratedAt == null) return false
        return !createdAt.isBefore(migratedAt.minusSeconds(60))
    }

    private fun boundHere(): List<Binding> = hub.bindings.snapshot().filter { it.target.projectPath == projectPath }

    private fun tabIdOf(sessionKey: String): String? =
        TerminalHost.getInstance(project).openTabsByTabId().entries.firstOrNull { it.value == sessionKey }?.key

    private fun titleOf(sessionId: String?): String =
        sessionId?.let { SessionMonitor.getInstance(project).model.sessionOf(it)?.title } ?: sessionId?.take(8).orEmpty()

    override fun dispose() = Unit

    companion object {
        private val LOG = logger<TerminalBridgeService>()
        private const val POLL_INTERVAL_MS = 1_000L

        fun getInstance(project: Project): TerminalBridgeService = project.getService(TerminalBridgeService::class.java)
    }
}
