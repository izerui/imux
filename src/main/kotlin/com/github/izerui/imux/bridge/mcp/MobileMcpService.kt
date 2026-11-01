package com.github.izerui.imux.bridge.mcp

import com.github.izerui.imux.bridge.BridgeHub
import com.github.izerui.imux.bridge.RobotManager
import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.core.*
import com.github.izerui.imux.bridge.media.MediaRoute
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.APP)
class MobileMcpService(val scope: CoroutineScope) : Disposable {
    val callers = MobileCallerRegistry(scope)
    private val protocols = ConcurrentHashMap<String, MobileMcpProtocol>()
    private val hub get() = BridgeHub.getInstance()
    private val robots get() = RobotManager.getInstance()

    init {
        scope.launch { hub.revision.collect { callers.invalidateRoutes() } }
    }

    fun register(projectPath: String, tabId: String): String {
        val (token, caller) = callers.register(projectPath, tabId)
        protocols[token] = MobileMcpProtocol(object : MobileToolsBackend {
            override fun status() = status(caller)
            override fun send(request: MediaRequest): Deferred<MediaResult> {
                val route = capture(caller)
                    ?: return CompletableDeferred(MediaResult.rejected(request.kind, status(caller).get("send_state").asString))
                return caller.queue.enqueue(request, route)
            }
            override fun cancel(operation: Deferred<MediaResult>) { caller.queue.cancel(operation) }
        }, scope)
        return token
    }

    fun protocol(token: String): MobileMcpProtocol? = protocols[token]?.takeIf { callers.get(token) != null }
    fun revoke(token: String) { callers.revoke(token); protocols.remove(token) }

    private fun status(caller: MobileCaller): JsonObject = JsonObject().apply {
        val binding = hub.bindings.bindingForTab(caller.tabId)?.takeIf { it.target.projectPath == caller.projectPath }
        val record = binding?.let { robots.robot(it.robotId) }
        val status = record?.let { robots.statusOf(it.id) }
        val sender = record?.let { hub.mediaChannel(it.id) } as? MediaSender
        addProperty("binding", if (binding == null) "unbound" else "bound")
        addProperty("channel", record?.kind?.id)
        addProperty("robot_name", record?.name)
        addProperty("connection", when (status) {
            ConnectionStatus.Online, is ConnectionStatus.SendFailing -> "online"
            ConnectionStatus.Connecting, is ConnectionStatus.Retrying -> "connecting"
            ConnectionStatus.Kicked -> "kicked"
            is ConnectionStatus.Failed, ConnectionStatus.NoCredentials -> "failed"
            else -> "offline"
        })
        addProperty("send_state", when {
            !caller.active -> "session_closed"
            binding == null -> "unbound"
            record?.owner() == null -> "not_paired"
            !online(status) -> "offline"
            sender == null -> "unsupported"
            status is ConnectionStatus.SendFailing -> "attention"
            else -> "ready"
        })
        if (status is ConnectionStatus.SendFailing) addProperty("reason", status.messageKey)
        if (record?.kind?.id == "weixin") addProperty("notice", "上下文可能过期；每次用户输入后约 10 条为经验值，媒体与文字共用，以平台回执为准。被拒时请在微信给机器人发任意一条消息后再试。")
        if (record?.kind?.id == "dingtalk") addProperty("notice", "视频直接按文件附件发送，不需要封面或时长，不保证原生视频播放；MP4 文件是否接受以平台回执为准。上传和发送计入组织适用 API 额度。")
        add("capabilities", mobileMediaCapabilitiesJson(sender?.mediaCapabilities.orEmpty()))
    }

    private fun capture(caller: MobileCaller): MediaRoute? {
        if (!caller.active) return null
        val binding = hub.bindings.bindingForTab(caller.tabId)?.takeIf { it.target.projectPath == caller.projectPath } ?: return null
        val record = robots.robot(binding.robotId) ?: return null
        val owner = hub.mediaOwner(binding.robotId) ?: return null
        val connection = hub.mediaChannel(binding.robotId) ?: return null
        val sender = connection as? MediaSender ?: return null
        if (!online(robots.statusOf(binding.robotId))) return null
        val generation = robots.mediaGeneration(binding.robotId)
        return object : MediaRoute {
            override val channel = record.kind.id
            override val chatId = owner.chatId
            override val sender = sender
            override fun isCurrent(): Boolean = caller.active &&
                hub.bindings.isCurrent(binding.robotId, binding.epoch) &&
                hub.bindings.bindingOf(binding.robotId)?.target?.tabId == caller.tabId &&
                hub.mediaChannel(binding.robotId) === connection &&
                hub.mediaOwner(binding.robotId) == owner &&
                robots.mediaGeneration(binding.robotId) == generation && online(robots.statusOf(binding.robotId))
            override fun commit(mark: () -> Unit): Boolean = hub.bindings.claimCurrent(binding.robotId, binding.epoch) {
                if (!isCurrent()) false else { mark(); true }
            }
        }
    }

    override fun dispose() { callers.close(); protocols.clear() }

    companion object {
        fun getInstance(): MobileMcpService = ApplicationManager.getApplication().getService(MobileMcpService::class.java)
        private fun online(status: ConnectionStatus?) = status == ConnectionStatus.Online || status is ConnectionStatus.SendFailing
    }
}

internal fun mobileMediaCapabilitiesJson(capabilities: Map<MediaKind, MediaCapability>): JsonObject = JsonObject().apply {
    MediaKind.entries.forEach { kind ->
        val capability = capabilities[kind]
        val delivery = capability?.deliveryAs ?: kind
        add(kind.name.lowercase(), JsonObject().apply {
            addProperty("supported", capability?.supported == true)
            addProperty("native", capability?.supported == true && delivery == kind)
            addProperty("delivery_as", delivery.name.lowercase())
            addProperty("max_bytes", capability?.maxBytes)
            addProperty("local_limit", capability?.localLimit ?: false)
            add("formats", JsonArray().apply { capability?.formats?.sorted()?.forEach { add(it) } })
            add("required_fields", JsonArray().apply { capability?.requiredFields?.sorted()?.forEach { add(it) } })
        })
    }
}
