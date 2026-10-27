package com.github.izerui.imux.bridge.robot

import com.github.izerui.imux.bridge.BridgeHub
import com.github.izerui.imux.bridge.BridgeSettings
import com.github.izerui.imux.bridge.BundleBridgeTexts
import com.github.izerui.imux.bridge.channel.ChannelConnection
import com.github.izerui.imux.bridge.channel.ChannelFactories
import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.core.InboundSink
import com.github.izerui.imux.bridge.core.OutboundMessage
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.core.SessionTarget
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.terminal.SessionDecorations
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal fun bindingsToSave(snapshot: List<Binding>): List<SavedBinding> =
    snapshot.mapNotNull { binding ->
        val sessionId = binding.target.sessionId ?: return@mapNotNull null
        SavedBinding(binding.robotId.value, binding.target.projectPath, binding.target.agentType.cli, sessionId)
    }

internal fun restoredTarget(saved: SavedBinding): SessionTarget? {
    val agentType = AgentType.entries.firstOrNull { it.cli == saved.agentCli } ?: return null
    return SessionTarget(saved.projectPath, RESTORED_TAB_PREFIX + saved.sessionId, agentType, saved.sessionId)
}

private const val RESTORED_TAB_PREFIX = "restored:"

/**
 * 机器人的生命周期：凭据、连接、认主、状态，以及绑定的持久化。
 *
 * 只在开关打开时建立连接。每个机器人一条连接；被别处接管后不重连，提示用户。
 */
@Service(Service.Level.APP)
class RobotManager(parentScope: CoroutineScope) {
    private val scope =
        CoroutineScope(
            parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) +
                CoroutineExceptionHandler { _, e -> LOG.warn("机器人任务异常，已隔离", e) },
        )
    private val store get() = RobotStore.getInstance()
    private val hub get() = BridgeHub.getInstance()
    private val vault: CredentialVault = PasswordSafeVault()
    private val connections = ConcurrentHashMap<String, ChannelConnection>()
    private val statuses = ConcurrentHashMap<String, ConnectionStatus>()

    /** 登记/摘除连接与写入状态共用一把锁：start()/stop() 都不阻塞，持锁时间很短。 */
    private val lifecycleLock = Any()
    private val _revision = MutableStateFlow(0L)

    /** 机器人、状态或绑定任何变化时加一，界面据此刷新。 */
    val revision: StateFlow<Long> = _revision

    /** 由界面层设置：机器人被别处接管时的提示。 */
    @Volatile
    var notifyKicked: (RobotRecord) -> Unit = {}

    init {
        store.book().bindings.forEach { saved ->
            val target = restoredTarget(saved) ?: return@forEach
            hub.bindings.bind(RobotId(saved.robotId), target)
        }
        // 机器人、状态或绑定变化时，让标签、会话树、提示条、状态栏刷新
        scope.launch {
            revision.collect {
                ApplicationManager.getApplication().invokeLater({ SessionDecorations.changed() }, ModalityState.any())
            }
        }
    }

    fun robots(): List<RobotRecord> = store.book().robots

    fun robot(id: String): RobotRecord? = store.book().robot(id)

    fun statusOf(id: String): ConnectionStatus? = statuses[id]

    fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

    fun startAll() {
        if (!BridgeSettings.getInstance().state.enabled) return
        robots().forEach { record -> scope.launch(Dispatchers.IO) { connect(record) } }
    }

    fun stopAll() {
        connections.keys.toList().forEach(::disconnect)
        bump()
    }

    /** 断开后重新连接；用于被别处接管、连接失败或已停止的机器人。开关关闭时只断开。 */
    fun reconnect(id: String) {
        scope.launch(Dispatchers.IO) {
            disconnect(id)
            robot(id)?.let { connect(it) }
            bump()
        }
    }

    suspend fun add(
        kind: ChannelKind,
        credentials: Map<String, String>,
        ownerId: String?,
        name: String,
    ): RobotRecord {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        withContext(Dispatchers.IO) { vault.save(id, credentials) }
        val record =
            RobotRecord(
                id = id,
                name = name,
                kind = kind,
                ownerSenderId = ownerId,
                ownerChatId = ownerId,
                pairCode = if (ownerId == null) Pairing.newCode() else null,
                pairCodeExpiresAtMillis = if (ownerId == null) now + Pairing.TTL_MILLIS else 0,
                createdAtMillis = now,
            )
        store.update { it.add(record) }
        bump()
        withContext(Dispatchers.IO) { connect(record) }
        return record
    }

    fun renewPairCode(id: String): RobotRecord? {
        val book = store.update { it.withPairCode(id, Pairing.newCode(), System.currentTimeMillis() + Pairing.TTL_MILLIS) }
        bump()
        return book.robot(id)
    }

    fun rename(
        id: String,
        name: String,
    ) {
        store.update { it.rename(id, name) }
        bump()
    }

    suspend fun remove(id: String) {
        disconnect(id)
        hub.unregisterRobot(RobotId(id))
        store.update { it.remove(id) }
        withContext(Dispatchers.IO) { vault.delete(id) }
        syncBindings()
    }

    /** 绑定表是权威来源；把当前快照写入持久化。 */
    fun syncBindings() {
        store.update { it.withBindings(bindingsToSave(hub.bindings.snapshot())) }
        bump()
    }

    private fun connect(record: RobotRecord) {
        if (!BridgeSettings.getInstance().state.enabled) return
        if (connections.containsKey(record.id)) return
        val factory = ChannelFactories.forKind(record.kind) ?: return
        val credentials = vault.load(record.id)
        if (credentials == null) {
            setStatus(record.id, ConnectionStatus.Failed("noCredentials"))
            return
        }
        val connection = factory.connect(RobotId(record.id), credentials, scope)
        synchronized(lifecycleLock) {
            if (connections.putIfAbsent(record.id, connection) != null) return
            // 加载凭据期间开关可能已关闭或机器人已被删除：不留残余连接
            if (!BridgeSettings.getInstance().state.enabled || robot(record.id) == null) {
                connections.remove(record.id)
                connection.stop()
                hub.detachRobot(RobotId(record.id))
                return
            }
            hub.registerRobot(connection, record.owner())
            connection.start(pairingSink(record.id, connection)) { status ->
                val current =
                    synchronized(lifecycleLock) {
                        // 已被断开或替换的旧连接，状态不能覆盖新连接
                        if (connections[record.id] !== connection) return@synchronized false
                        setStatus(record.id, status)
                        true
                    }
                if (current && status == ConnectionStatus.Kicked) robot(record.id)?.let { notifyKicked(it) }
            }
        }
    }

    private fun disconnect(id: String) {
        synchronized(lifecycleLock) {
            connections.remove(id)?.stop()
            hub.detachRobot(RobotId(id))
            statuses.remove(id)
        }
    }

    private fun pairingSink(
        id: String,
        connection: ChannelConnection,
    ) = InboundSink { message ->
        if (!BridgeSettings.getInstance().state.enabled) return@InboundSink
        val record = robot(id) ?: return@InboundSink
        when (val decision = Pairing.decide(record, message, System.currentTimeMillis())) {
            PairDecision.Pass -> hub.inboundSink.accept(message)
            is PairDecision.Paired -> {
                store.update { it.withOwner(id, decision.owner) }
                hub.registerRobot(connection, decision.owner)
                bump()
                scope.launch {
                    connection.send(OutboundMessage(connection.robotId, decision.owner.chatId, null, BundleBridgeTexts.pairingSucceeded()))
                }
            }
            PairDecision.Expired ->
                scope.launch {
                    connection.send(OutboundMessage(connection.robotId, message.chatId, null, BundleBridgeTexts.pairingExpired()))
                }
            PairDecision.Ignore -> Unit
        }
    }

    private fun setStatus(
        id: String,
        status: ConnectionStatus,
    ) {
        statuses[id] = status
        bump()
    }

    private fun bump() {
        _revision.value = _revision.value + 1
    }

    companion object {
        private val LOG = logger<RobotManager>()

        fun getInstance(): RobotManager = ApplicationManager.getApplication().getService(RobotManager::class.java)
    }
}
