package com.github.izerui.imux.bridge

import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.robot.RobotManager
import com.github.izerui.imux.bridge.terminal.TabChange
import com.github.izerui.imux.bridge.terminal.tabChangeFor
import com.github.izerui.imux.bridge.transcript.TranscriptDecoders
import com.github.izerui.imux.bridge.transcript.TailPoll
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
    // 读取（尾随器、开关代次、迁移记录）共用一把锁，只做本地读文件；发往手机按标签各一把锁，
    // 一个标签限频等待或网络卡住时，不拖住其它标签的轮询
    private val pollLock = Mutex()
    private val tabLocks = ConcurrentHashMap<String, Mutex>()
    private val seenTabs = ConcurrentHashMap.newKeySet<String>()
    private val migratedAt = ConcurrentHashMap<String, Migration>()

    /** 绑定标签最近一次的显示名，标签关闭发断开提示时用。 */
    private val knownTitles = ConcurrentHashMap<String, String>()

    @Volatile
    private var projectClosing = false

    // 启动活动（后台线程）、开关切换与绑定动作（EDT）都会调用 start()，必须原子地只启动一次
    private val started = AtomicBoolean(false)

    // 只在 pollLock 内读写（start 中的初始化除外）：重新开启过就丢弃旧读取位置
    private val switchWatch = SwitchWatch()

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
                    // 记下信号这一刻的读取边界：只读到这里再结算，信号就落在它所属的那一轮；
                    // 之后写下的下一轮内容留给后续轮询，迟到的信号不会把新一轮的过程说明当结论发出
                    val mark = tails[tabId]?.tail?.let { ReadMark(it, it.size()) }
                    scope.launch(Dispatchers.IO) {
                        pollTab(tabId, mark) { hub.router.onTurnCompleted(tabId, hub.router.currentTurn(tabId)) }
                    }
                }

                override fun turnWaiting(
                    sessionId: String,
                    reason: String?,
                ) {
                    if (!BridgeSettings.getInstance().state.enabled) return
                    val tabId = tabIdOf(sessionId) ?: return
                    scope.launch(Dispatchers.IO) {
                        pollTab(tabId) { hub.router.onWaiting(tabId, reason) }
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
                        val tabId = binding.target.tabId
                        // 上一次还在发送的标签这轮跳过，等它发完再读
                        if (tabLock(tabId).isLocked) continue
                        launch {
                            try {
                                pollTab(tabId)
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
        for (binding in boundHere()) {
            if (binding.target.tabId in open) knownTitles[binding.target.tabId] = BridgeLifecycle.sessionTitle(binding)
            when (val change = tabChangeFor(binding, open, seenTabs, projectClosing)) {
                TabChange.Keep -> Unit
                is TabChange.Reattach -> {
                    hub.bindings.reattach(binding.robotId, change.tabId)
                    tails.remove(binding.target.tabId)
                    migratedAt.remove(binding.target.tabId)
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
        // 标签关闭后已取不到标签标题，用它还开着时记下的名字
        val title = knownTitles.remove(binding.target.tabId) ?: BridgeLifecycle.sessionTitle(binding)
        scope.launch(Dispatchers.IO) { hub.router.onReleased(binding, title) }
    }

    private fun onMigrated(to: String) {
        val tabId = tabIdOf(to) ?: return
        val binding = hub.bindings.migrateSession(tabId, to) ?: return
        RobotManager.getInstance().syncBindings()
        tails.remove(tabId)
        migratedAt[tabId] = Migration(binding.epoch, Instant.now())
    }

    private fun tabLock(tabId: String): Mutex = tabLocks.computeIfAbsent(tabId) { Mutex() }

    /** 读出这个标签的新事件交给路由器，再执行 [then]；同一标签的读取与发送按顺序进行。 */
    private suspend fun pollTab(
        tabId: String,
        mark: ReadMark? = null,
        then: suspend () -> Unit = {},
    ) {
        tabLock(tabId).withLock {
            val poll = readTab(tabId, mark)
            if (poll != null) {
                if (poll.restarted) hub.router.onTranscriptRestarted(tabId)
                if (poll.events.isNotEmpty()) hub.router.onSessionEvents(tabId, poll.events)
            }
            then()
        }
    }

    private suspend fun readTab(
        tabId: String,
        mark: ReadMark?,
    ): TailPoll? {
        pollLock.withLock {
            observeSwitch()
            if (!BridgeSettings.getInstance().state.enabled) return null
            // 以当前绑定为准：换绑（代次变了）或迁移（会话变了）后的旧尾随器一律丢弃重建，
            // 新绑定从当前记录边界开始，不倒灌期间产生的历史
            val binding = hub.bindings.bindingForTab(tabId) ?: return null
            val cached = tails[tabId]
            val tail =
                if (cached != null && reuseTail(cached.epoch, cached.sessionId, binding.epoch, binding.target.sessionId)) {
                    cached.tail
                } else {
                    tails.remove(tabId)
                    newTail(binding) ?: return null
                }
            // 边界只对记下它的那个尾随器有效；期间重建过就照常读
            return tail.poll(mark?.takeIf { it.tail === tail }?.upTo)
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
        // 只认本次绑定期间记下的迁移；换绑、断开后残留的旧值不能让新绑定从头读
        val migrated = migratedAt[tabId]?.takeIf { it.epoch == binding.epoch }
        val fromStart = readFromStart(session.createdAt, migrated?.at)
        val tail =
            TranscriptTail(
                session.filePath,
                TranscriptDecoders.forAgent(binding.target.agentType),
                skipBefore = if (fromStart) copiedHistoryBefore(session.createdAt, migrated!!.at) else null,
            )
        if (!fromStart) tail.startAtEnd()
        // 建立期间绑定可能已在 EDT 上迁移或换绑：此时不登记、不消费迁移时间，留给下次轮询按新绑定重建
        val current = hub.bindings.bindingForTab(tabId) ?: return null
        if (!reuseTail(binding.epoch, sessionId, current.epoch, current.target.sessionId)) return null
        // 只移除本次用到的迁移时间；期间又发生的迁移会写入新值，不能被吞掉
        if (migrated != null) migratedAt.remove(tabId, migrated)
        // 记下建立时依据的绑定代次与会话，下次轮询据此判断能否沿用
        tails[tabId] = TailEntry(binding.epoch, sessionId, tail)
        return tail
    }

    private fun boundHere(): List<Binding> = hub.bindings.snapshot().filter { it.target.projectPath == projectPath }

    private fun tabIdOf(sessionKey: String): String? =
        TerminalHost.getInstance(project).openTabsByTabId().entries.firstOrNull { it.value == sessionKey }?.key

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


/** IDE 信号发出那一刻某个尾随器所在文件的大小。 */
private class ReadMark(
    val tail: TranscriptTail,
    val upTo: Long?,
)

/** 会话迁移的时间，连同当时的绑定代次：只对同一次绑定有效。 */
private data class Migration(
    val epoch: Long,
    val at: Instant,
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

/**
 * 从头读时，早于这个时间的行是复制来的历史。fork 或交给 daemon 生成的新文件会带着原会话的全部历史
 * （保留原时间戳），这些行都写在文件创建之前；新内容写在创建之后。取创建时间与迁移时间的较早者再留
 * 2 秒余量：不支持创建时间的文件系统会退回修改时间，此时以迁移时间为准，最多漏掉迁移前的首轮。
 */
internal fun copiedHistoryBefore(
    createdAt: Instant,
    migratedAt: Instant,
): Instant = minOf(createdAt, migratedAt).minusSeconds(2)
