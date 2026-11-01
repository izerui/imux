package com.github.izerui.imux.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

data class McpRpcReply(val status: Int, val body: Deferred<JsonObject>?)

/** 插件内置 MCP 服务共用的 JSON-RPC 信封校验与握手；各服务只处理自己的通知和 tools/call。 */
object McpJsonRpc {
    val VERSIONS = listOf("2025-03-26", "2025-06-18", "2025-11-25")

    fun receive(
        root: JsonObject,
        serverName: String,
        tools: () -> JsonArray,
        notification: (method: String, params: JsonObject) -> Unit = { _, _ -> },
        call: (id: JsonElement, name: String, args: JsonObject) -> McpRpcReply,
    ): McpRpcReply {
        val id = root.get("id") ?: JsonNull.INSTANCE
        fun error(code: Int, text: String) = reply(rpcError(id, code, text))
        if (root.text("jsonrpc") != "2.0") return error(-32600, "Invalid JSON-RPC request")
        if (!id.isJsonNull && !(id.isJsonPrimitive && (id.asJsonPrimitive.isString || id.asJsonPrimitive.isNumber))) {
            return reply(rpcError(JsonNull.INSTANCE, -32600, "Invalid request id"))
        }
        val method = root.text("method") ?: return error(-32600, "Missing method")
        val params = root.get("params")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        if (id.isJsonNull) {
            notification(method, params)
            return McpRpcReply(202, null)
        }
        return when (method) {
            "initialize" -> reply(rpcResult(id, JsonObject().apply {
                val requested = params.text("protocolVersion")
                addProperty("protocolVersion", requested?.takeIf { it in VERSIONS } ?: VERSIONS.last())
                add("capabilities", JsonObject().apply { add("tools", JsonObject()) })
                add("serverInfo", JsonObject().apply { addProperty("name", serverName); addProperty("version", "1.0.0") })
            }))
            "ping" -> reply(rpcResult(id, JsonObject()))
            "tools/list" -> reply(rpcResult(id, JsonObject().apply { add("tools", tools()) }))
            "tools/call" -> {
                val name = params.text("name") ?: return error(-32602, "Missing tool name")
                val args = params.get("arguments")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?: if (params.has("arguments")) return error(-32602, "arguments must be an object") else JsonObject()
                call(id, name, args)
            }
            else -> error(-32601, "Method not found")
        }
    }

    fun reply(value: JsonObject) = McpRpcReply(200, CompletableDeferred(value))

    fun rpcError(id: JsonElement, code: Int, message: String): JsonObject = JsonObject().apply {
        addProperty("jsonrpc", "2.0"); add("id", id)
        add("error", JsonObject().apply { addProperty("code", code); addProperty("message", message) })
    }

    fun rpcResult(id: JsonElement, result: JsonObject) = JsonObject().apply {
        addProperty("jsonrpc", "2.0"); add("id", id); add("result", result)
    }

    fun toolResult(value: JsonObject, failed: Boolean) = JsonObject().apply {
        add("structuredContent", value)
        add("content", JsonArray().apply { add(JsonObject().apply { addProperty("type", "text"); addProperty("text", value.toString()) }) })
        addProperty("isError", failed)
    }
}

internal fun JsonObject.text(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
