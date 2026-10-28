package com.github.izerui.imux.bridge

import com.github.izerui.imux.bridge.channel.ChannelConnection
import com.github.izerui.imux.bridge.channel.ChannelFactories
import com.github.izerui.imux.bridge.channel.ChannelKind
import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.core.InboundSink
import com.github.izerui.imux.bridge.core.OutboundMessage
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.robot.CredentialVault
import com.github.izerui.imux.bridge.robot.PairDecision
import com.github.izerui.imux.bridge.robot.Pairing
import com.github.izerui.imux.bridge.robot.PasswordSafeVault
import com.github.izerui.imux.bridge.robot.RobotRecord
import com.github.izerui.imux.bridge.robot.RobotStore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 机器人的生命周期：凭据、连接、认主与连接状态。绑定的恢复与持久化在 [BridgeHub]。
 *
 * 只在开关打开时建立连接。每个机器人一条连接；被别处接管后不重连，提示用户。
 */
@Service(Service.Level.APP)
class RobotManager(parentScope: CoroutineScope) {
    private val scope = isolatedScope(parentScope, LOG, "机器人任务")
    private val store get() = RobotStore.getInstance()
    private val hub get() = BridgeHub.getInstance()
    private val vault: CredentialVault = PasswordSafeVault()
    private val connections = ConcurrentHashMap<RobotId, ChannelConnection>()
    private val statuses = ConcurrentHashMap<RobotId, ConnectionStatus>()

    /** 当前配对码的连续错误次数，只在内存里；配对成功或重新生成时清零。 */
    private val wrongPairAttempts = ConcurrentHashMap<RobotId, Int>()

    /** 登记/摘除连接与写入状态共用一把锁：start()/stop() 都不阻塞，持锁时间很短。 */
    private val lifecycleLock = Any()

    /** 由界面层设置：机器人被别处接管时的提示。 */
    @Volatile
    var notifyKicked: (RobotRecord) -> Unit = {}

    fun robots(): List<RobotRecord> = store.book().robots

    fun robot(id: RobotId): RobotRecord? = store.book().robot(id)

    fun statusOf(id: RobotId): ConnectionStatus? = statuses[id]

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
    fun reconnect(id: RobotId) {
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
        val id = RobotId(UUID.randomUUID().toString())
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

    fun renewPairCode(id: RobotId): RobotRecord? {
        wrongPairAttempts.remove(id)
        val book = store.update { it.withPairCode(id, Pairing.newCode(), System.currentTimeMillis() + Pairing.TTL_MILLIS) }
        bump()
        return book.robot(id)
    }

    fun rename(
        id: RobotId,
        name: String,
    ) {
        store.update { it.rename(id, name) }
        bump()
    }

    suspend fun remove(id: RobotId) {
        disconnect(id)
        statuses.remove(id)
        hub.unregisterRobot(id)
        store.update { it.remove(id) }
        withContext(Dispatchers.IO) { vault.delete(id) }
        bump()
    }

    private fun connect(record: RobotRecord) {
        if (!BridgeSettings.getInstance().state.enabled) return
        if (connections.containsKey(record.id)) return
        val factory = ChannelFactories.forKind(record.kind) ?: return
        val credentials = vault.load(record.id)
        if (credentials == null) {
            setStatus(record.id, ConnectionStatus.NoCredentials)
            return
        }
        val connection = factory.connect(record.id, credentials, scope)
        synchronized(lifecycleLock) {
            if (connections.putIfAbsent(record.id, connection) != null) return
            // 加载凭据期间开关可能已关闭或机器人已被删除：不留残余连接
            if (!BridgeSettings.getInstance().state.enabled || robot(record.id) == null) {
                connections.remove(record.id)
                connection.stop()
                hub.detachRobot(record.id)
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

    /** 先摘下再停止：连接自己报的 Stopped 会被当成旧连接的回调丢掉，所以这里直接记下。 */
    private fun disconnect(id: RobotId) {
        synchronized(lifecycleLock) {
            connections.remove(id)?.stop()
            hub.detachRobot(id)
            statuses[id] = ConnectionStatus.Stopped
        }
    }

    private fun pairingSink(
        id: RobotId,
        connection: ChannelConnection,
    ) = InboundSink { message ->
        if (!BridgeSettings.getInstance().state.enabled) return@InboundSink
        val record = robot(id) ?: return@InboundSink
        fun reply(
            chatId: String,
            text: String,
        ) = scope.launch { connection.send(OutboundMessage(connection.robotId, chatId, text)) }
        when (val decision = Pairing.decide(record, message, System.currentTimeMillis())) {
            PairDecision.Pass -> hub.inboundSink.accept(message)
            is PairDecision.Paired -> {
                wrongPairAttempts.remove(id)
                store.update { it.withOwner(id, decision.owner) }
                hub.registerRobot(connection, decision.owner)
                bump()
                reply(decision.owner.chatId, BundleBridgeTexts.pairingSucceeded())
            }
            PairDecision.Expired -> reply(message.chatId, BundleBridgeTexts.pairingExpired())
            PairDecision.Wrong -> {
                val attempts = wrongPairAttempts.merge(id, 1, Int::plus) ?: 1
                if (attempts < Pairing.MAX_WRONG_ATTEMPTS) return@InboundSink
                // 连续猜错：作废配对码，只在作废这一下回复一次
                wrongPairAttempts.remove(id)
                store.update { it.withPairCode(id, null, 0) }
                bump()
                reply(message.chatId, BundleBridgeTexts.pairingLocked())
            }
            PairDecision.Ignore -> Unit
        }
    }

    private fun setStatus(
        id: RobotId,
        status: ConnectionStatus,
    ) {
        // 联调与排障依据：只记状态变化，不记消息内容
        if (statuses.put(id, status) != status) LOG.info("机器人 ${robot(id)?.name ?: id} 连接状态：$status")
        bump()
    }

    private fun bump() = hub.changed()

    companion object {
        private val LOG = logger<RobotManager>()

        fun getInstance(): RobotManager = ApplicationManager.getApplication().getService(RobotManager::class.java)
    }
}
