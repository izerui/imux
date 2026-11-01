package com.github.izerui.imux.bridge.mcp

import com.github.izerui.imux.bridge.core.MediaKind
import com.github.izerui.imux.bridge.core.MediaRequest
import com.github.izerui.imux.bridge.core.MediaResult
import com.github.izerui.imux.mcp.McpJsonRpc
import com.github.izerui.imux.mcp.McpJsonRpc.rpcError
import com.github.izerui.imux.mcp.McpJsonRpc.rpcResult
import com.github.izerui.imux.mcp.McpJsonRpc.toolResult
import com.github.izerui.imux.mcp.McpRpcReply
import com.github.izerui.imux.mcp.text
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

interface MobileToolsBackend {
    fun status(): JsonObject
    fun send(request: MediaRequest): Deferred<MediaResult>
    fun cancel(operation: Deferred<MediaResult>) { operation.cancel() }
}

/** 每个调用凭证独立的活动 RPC 表，不把不同终端的同一个 JSON-RPC id 混在一起。 */
class MobileMcpProtocol(private val backend: MobileToolsBackend, private val scope: CoroutineScope) {
    private val active = ConcurrentHashMap<String, Deferred<MediaResult>>()

    fun receive(root: JsonObject): McpRpcReply = McpJsonRpc.receive(root, "imux_mobile", ::tools,
        notification = { method, params ->
            if (method == "notifications/cancelled") {
                params.get("requestId")?.let { key -> active[key.toString()]?.let(backend::cancel) }
            }
        },
        call = ::call,
    )

    private fun call(id: JsonElement, name: String, args: JsonObject): McpRpcReply {
        fun error(code: Int, text: String) = McpJsonRpc.reply(rpcError(id, code, text))
        if (name == "get_mobile_status") {
            if (args.size() != 0) return error(-32602, "This tool takes no arguments")
            return McpJsonRpc.reply(rpcResult(id, toolResult(backend.status(), false)))
        }
        val kind = when (name) {
            "send_image" -> MediaKind.IMAGE
            "send_video" -> MediaKind.VIDEO
            "send_file" -> MediaKind.FILE
            else -> return error(-32602, "Unknown tool")
        }
        val allowed = setOf("path", "file_name") + if (kind == MediaKind.VIDEO) setOf("duration_ms", "thumbnail_path") else emptySet()
        if (args.keySet().any { it !in allowed }) return error(-32602, "Unsupported argument")
        val path = args.text("path")?.takeIf(String::isNotBlank) ?: return error(-32602, "path must be a nonempty string")
        if (listOf("file_name", "thumbnail_path").any { args.has(it) && args.text(it) == null }) return error(-32602, "Expected a string")
        val duration = if (args.has("duration_ms")) {
            val raw = args.get("duration_ms")
            if (!raw.isJsonPrimitive || !raw.asJsonPrimitive.isNumber) return error(-32602, "duration_ms must be a nonnegative integer")
            val value = runCatching { raw.asBigDecimal.longValueExact() }.getOrNull()
                ?: return error(-32602, "Invalid duration_ms")
            if (value < 0 || value > Long.MAX_VALUE - 999) return error(-32602, "Invalid duration_ms")
            value
        } else null
        val request = MediaRequest(path, kind, args.text("file_name"), duration, args.text("thumbnail_path"))
        val operation: Deferred<MediaResult>
        synchronized(active) {
            if (active.containsKey(id.toString())) return error(-32600, "Request id is already active")
            // 入队同步发生，不依赖下面等待响应的协程先后被调度。
            operation = backend.send(request)
            active[id.toString()] = operation
        }
        return McpRpcReply(200, scope.async {
            try {
                val result = try { operation.await() } catch (_: CancellationException) { MediaResult.rejected(kind, "cancelled") }
                rpcResult(id, toolResult(result.json(), result.outcome != "accepted"))
            } finally { active.remove(id.toString(), operation) }
        })
    }

    companion object {
        fun tools(): JsonArray = JsonArray().apply {
            add(tool("get_mobile_status", "Read this terminal's current mobile binding, connection and media limits. Binding can change at any time.", false, false))
            add(tool("send_image", "Send an existing local image to this terminal's bound mobile chat as an image.", true, false))
            add(tool("send_video", "Send an existing local video using the bound channel's delivery mode. DingTalk sends a file attachment directly, without duration or thumbnail. Check delivered_as; a file attachment is not a native video message.", true, true))
            add(tool("send_file", "Send an existing local file, including audio, to this terminal's bound mobile chat as a file attachment.", true, false))
        }

        private fun tool(name: String, description: String, sends: Boolean, video: Boolean) = JsonObject().apply {
            addProperty("name", name)
            addProperty("description", description + if (sends) " Use only when the user requests delivery. Media rejection automatically falls back to a file once when possible. Check outcome and delivered_as; accepted is not a read receipt. Never retry unknown delivery or repeat a successful file fallback. Recipients are determined by the plugin, not arguments." else "")
            add("inputSchema", JsonObject().apply {
                addProperty("type", "object"); addProperty("additionalProperties", false)
                add("properties", JsonObject().apply {
                    if (sends) {
                        add("path", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Absolute path of an existing local file, after writing is complete.") })
                        add("file_name", JsonObject().apply { addProperty("type", "string") })
                    }
                    if (video) {
                        add("duration_ms", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0) })
                        add("thumbnail_path", JsonObject().apply { addProperty("type", "string") })
                    }
                })
                if (sends) add("required", JsonArray().apply { add("path") })
            })
            add("annotations", JsonObject().apply {
                addProperty("readOnlyHint", !sends); addProperty("destructiveHint", sends)
                addProperty("idempotentHint", !sends); addProperty("openWorldHint", sends)
            })
        }
    }
}

private fun MediaResult.json(): JsonObject = JsonObject().apply {
    addProperty("outcome", outcome); addProperty("requested_kind", requestedKind.name.lowercase())
    addProperty("delivered_as", deliveredAs?.name?.lowercase()); addProperty("channel", channel)
    addProperty("file_name", fileName); addProperty("reason", reason); addProperty("fallback_used", fallbackUsed)
    addProperty("primary_error", primaryError); addProperty("platform_message_id", messageId)
    addProperty("retryable", false)
}
