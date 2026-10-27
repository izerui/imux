package com.github.izerui.imux.bridge

import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.core.SessionTarget
import com.github.izerui.imux.bridge.robot.RobotManager
import com.github.izerui.imux.bridge.terminal.TabChange
import com.github.izerui.imux.bridge.terminal.tabChangeFor
import com.github.izerui.imux.bridge.transcript.TailBoundary
import com.github.izerui.imux.bridge.transcript.TranscriptDecoders
import com.github.izerui.imux.bridge.transcript.TranscriptTail
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
    private val scope =
        CoroutineScope(
            parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) +
                CoroutineExceptionHandler { _, e -> LOG.warn("项目桥接任务异常，已隔离", e) },
        )
    private val projectPath = project.basePath ?: System.getProperty("user.home")
    private val hub get() = BridgeHub.getInstance()
    private val tails = ConcurrentHashMap<String, TailEntry>()
    private val pollLock = Mutex()
    private val seenTabs = ConcurrentHashMap.newKeySet<String>()
    private val migratedAt = ConcurrentHashMap<String, Instant>()

    @Volatile
    private var projectClosing = false

    // 启动活动（后台线程）、开关切换与绑定动作（EDT）都会调用 start()，必须原子地只启动一次
    private val started = AtomicBoolean(false)

    // 只在 pollLock 内读写（start 中的初始化除外）：重新开启过就丢弃旧读取位置
    private val switchWatch = SwitchWatch()

    /** 用户绑定那一刻记下的读取边界：尾随器稍后建立时从这里开始，不漏掉期间产生的回复。 */
    private val bindStarts = ConcurrentHashMap<String, BindStart>()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        switchWatch.reset(BridgeLifecycle.switchGeneration)
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
                    // 先把最后的回复读出来，再把本轮最终回复作为一条推送；不另发“本轮完成”通知
                    scope.launch(Dispatchers.IO) {
                        pollTab(tabId)
                        hub.router.onTurnCompleted(tabId)
                    }
                }

                override fun turnWaiting(
                    sessionId: String,
                    reason: String?,
                ) {
                    if (!BridgeSettings.getInstance().state.enabled) return
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
                val enabled = BridgeSettings.getInstance().state.enabled
                pollLock.withLock { observeSwitch() }
                if (enabled) {
                    for (binding in boundHere()) {
                        try {
                            pollTab(binding.target.tabId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            LOG.warn("轮询标签 ${binding.target.tabId} 失败", e)
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
        for (binding in boundHere()) {
            when (val change = tabChangeFor(binding, open, seenTabs, projectClosing)) {
                TabChange.Keep -> Unit
                is TabChange.Reattach -> {
                    hub.bindings.reattach(binding.robotId, change.tabId)
                    tails.remove(binding.target.tabId)
                    migratedAt.remove(binding.target.tabId)
                    bindStarts.remove(binding.target.tabId)
                    RobotManager.getInstance().syncBindings()
                }
                TabChange.Release -> release(binding)
            }
        }
    }

    private fun release(binding: Binding) {
        if (hub.bindings.bindingOf(binding.robotId)?.epoch != binding.epoch) return
        hub.bindings.unbind(binding.robotId)
        RobotManager.getInstance().syncBindings()
        tails.remove(binding.target.tabId)
        migratedAt.remove(binding.target.tabId)
        bindStarts.remove(binding.target.tabId)
        val title = titleOf(binding.target.sessionId)
        scope.launch(Dispatchers.IO) { hub.router.onReleased(binding, title) }
    }

    private fun onMigrated(to: String) {
        val tabId = tabIdOf(to) ?: return
        if (hub.bindings.migrateSession(tabId, to) != null) {
            RobotManager.getInstance().syncBindings()
            tails.remove(tabId)
            migratedAt[tabId] = Instant.now()
        }
    }

    /**
     * 用户把机器人绑到这个标签**之前**调用（EDT）：记下会话记录此刻的边界。
     * 尾随器要等轮询才建立，若从那时的末尾开始，会漏掉期间产生的回复；
     * 先记边界再绑定，绑定一生效、轮询立刻建立尾随器也能用上它。
     */
    fun markBoundary(target: SessionTarget) {
        val sessionId = target.sessionId ?: return
        val session = SessionMonitor.getInstance(project).model.sessionOf(sessionId) ?: return
        bindStarts[target.tabId] = BindStart(sessionId, BridgeLifecycle.switchGeneration, TranscriptTail.boundaryOf(session.filePath))
    }

    /** 绑定没有成功时调用，免得边界留到以后被别的绑定误用、倒灌期间的内容。 */
    fun forgetBoundary(tabId: String) {
        bindStarts.remove(tabId)
    }

    private suspend fun pollTab(tabId: String) {
        pollLock.withLock {
            observeSwitch()
            if (!BridgeSettings.getInstance().state.enabled) return
            // 以当前绑定为准：换绑（代次变了）或迁移（会话变了）后的旧尾随器一律丢弃重建，
            // 新绑定从当前记录边界开始，不倒灌期间产生的历史
            val binding = hub.bindings.bindingForTab(tabId) ?: return
            val cached = tails[tabId]
            val tail =
                if (cached != null && reuseTail(cached.epoch, cached.sessionId, binding.epoch, binding.target.sessionId)) {
                    cached.tail
                } else {
                    tails.remove(tabId)
                    newTail(binding) ?: return
                }
            val poll = tail.poll()
            if (poll.restarted) hub.router.onTranscriptRestarted(tabId)
            if (poll.events.isNotEmpty()) hub.router.onSessionEvents(tabId, poll.events)
        }
    }

    /**
     * 在 pollLock 内调用。关闭期间轮询暂停但读取位置还在；只要开启代次变了（哪怕关闭又开启
     * 发生在两次轮询之间）就全部丢弃，新尾随器从当前末尾开始，关闭期间的输出不推送到手机。
     */
    private fun observeSwitch() {
        if (switchWatch.observe(BridgeLifecycle.switchGeneration)) {
            tails.clear()
            migratedAt.clear()
            // 开启之后才记下的边界仍然有效（打开开关后马上绑定），只清更早的
            bindStarts.values.removeIf { it.generation != BridgeLifecycle.switchGeneration }
        }
    }

    /**
     * 会话文件出现后才建立尾随器。
     * 迁移到新会话时从头读取，恢复到旧会话时从末尾开始，避免倾倒历史。
     */
    private fun newTail(binding: Binding): TranscriptTail? {
        val sessionId = binding.target.sessionId ?: return null
        val session = SessionMonitor.getInstance(project).model.sessionOf(sessionId) ?: return null
        val tabId = binding.target.tabId
        val migrated = migratedAt[tabId]
        val tail = TranscriptTail(session.filePath, TranscriptDecoders.forAgent(binding.target.agentType))
        val bindStart = bindStarts[tabId]?.takeIf { it.sessionId == sessionId }
        when {
            readFromStart(session.createdAt, migrated) -> Unit
            bindStart != null -> tail.startAt(bindStart.boundary)
            else -> tail.startAtEnd()
        }
        // 建立期间绑定可能已在 EDT 上迁移或换绑：此时不登记、不消费迁移时间，留给下次轮询按新绑定重建
        val current = hub.bindings.bindingForTab(tabId) ?: return null
        if (!reuseTail(binding.epoch, sessionId, current.epoch, current.target.sessionId)) return null
        // 只移除本次用到的迁移时间；期间又发生的迁移会写入新值，不能被吞掉
        if (migrated != null) migratedAt.remove(tabId, migrated)
        if (bindStart != null) bindStarts.remove(tabId, bindStart)
        // 记下建立时依据的绑定代次与会话，下次轮询据此判断能否沿用
        tails[tabId] = TailEntry(binding.epoch, sessionId, tail)
        return tail
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

/**
 * 记住上次观察到的开启代次；[observe] 在代次变化（期间重新开启过）时返回 true。
 * 服务启动时以当时的代次为准，首次观察不会误清启动后记下的迁移时间。
 */
internal class SwitchWatch {
    @Volatile
    private var last = 0L

    fun reset(generation: Long) {
        last = generation
    }

    fun observe(generation: Long): Boolean {
        if (generation == last) return false
        last = generation
        return true
    }
}

/** 绑定前记下的读取边界：只对同一会话有效，第一次建立尾随器时用掉；[generation] 为记下时的开启代次。 */
private class BindStart(
    val sessionId: String,
    val generation: Long,
    val boundary: TailBoundary,
)

/** 尾随器连同建立它时的绑定代次与会话一起缓存。 */
private class TailEntry(
    val epoch: Long,
    val sessionId: String,
    val tail: TranscriptTail,
)

/**
 * 缓存的尾随器能否沿用：必须与当前绑定同一代次、同一会话。
 * 换绑后代次不同、迁移后会话不同、绑定尚无会话，都要重建。
 */
internal fun reuseTail(
    entryEpoch: Long,
    entrySessionId: String,
    bindingEpoch: Long,
    bindingSessionId: String?,
): Boolean = bindingSessionId != null && entryEpoch == bindingEpoch && entrySessionId == bindingSessionId

/**
 * 迁移后是否从头读取会话记录。
 * 会话创建时间在迁移前 60 秒以内视为新会话（/clear、/new），从头转发；
 * 更早的会话（resume 回旧会话）则从末尾开始，避免倾倒全部历史。
 */
internal fun readFromStart(createdAt: Instant, migratedAt: Instant?): Boolean {
    if (migratedAt == null) return false
    return !createdAt.isBefore(migratedAt.minusSeconds(60))
}
