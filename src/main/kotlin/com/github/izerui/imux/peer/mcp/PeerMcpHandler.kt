package com.github.izerui.imux.peer.mcp

import com.github.izerui.imux.mcp.LocalMcpHandler
import com.github.izerui.imux.mcp.McpRequestTooLargeException
import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import io.netty.buffer.ByteBufInputStream
import io.netty.handler.codec.http.FullHttpRequest
import java.io.Reader

const val SESSION_MCP_PATH = "/imux/session-mcp"

class PeerMcpHandler : LocalMcpHandler(SESSION_MCP_PATH) {
    private val service get() = PeerMcpService.getInstance()
    override val scope get() = service.scope
    override fun receiver(token: String) = service.protocol(token)?.let { it::receive }

    override fun readBody(request: FullHttpRequest) =
        if (request.uri() == "$SESSION_MCP_PATH?progress-hook") {
            // Claude attaches prompt/answer text. Skip it without building a second in-memory JSON tree.
            // The higher bound applies only to this authenticated metadata-only endpoint.
            if (request.content().readableBytes() > 16 * 1024 * 1024) throw McpRequestTooLargeException()
            ByteBufInputStream(request.content().duplicate()).reader(Charsets.UTF_8).use(::readProgressHook)
        } else super.readBody(request)
}

internal fun readProgressHook(input: Reader): JsonObject = JsonReader(input).use { reader ->
    val result = JsonObject()
    reader.beginObject()
    while (reader.hasNext()) {
        val name = reader.nextName()
        if (name in setOf("hook_event_name", "session_id", "agent_id")) {
            require(reader.peek() == JsonToken.STRING)
            val value = reader.nextString()
            require(value.length <= 256)
            result.addProperty(name, value)
        } else reader.skipValue()
    }
    reader.endObject()
    require(reader.peek() == JsonToken.END_DOCUMENT)
    require(result.has("hook_event_name"))
    result
}
