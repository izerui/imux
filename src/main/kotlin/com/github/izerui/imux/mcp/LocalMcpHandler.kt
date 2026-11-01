package com.github.izerui.imux.mcp

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.QueryStringDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.ide.HttpRequestHandler
import org.jetbrains.io.send
import java.net.InetSocketAddress
import java.net.URI

internal fun localOriginAllowed(host: String?, origin: String?, port: Int): Boolean {
    fun allowed(raw: String, authority: Boolean): Boolean = runCatching {
        val uri = URI(if (authority) "http://$raw" else raw)
        uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "[::1]", "::1") &&
            uri.port == port && uri.userInfo == null && uri.query == null && uri.fragment == null &&
            (uri.path.isNullOrEmpty() || !authority && uri.path == "/")
    }.getOrDefault(false)
    return host != null && allowed(host, true) && (origin == null || allowed(origin, false))
}

/** 内置 HTTP 服务上按终端凭证鉴权的 MCP 入口：只接受本机、本端口、持有效 Bearer 的 JSON POST。 */
abstract class LocalMcpHandler(private val path: String) : HttpRequestHandler() {
    /** 凭证对应的 RPC 接收者；凭证无效或已撤销时返回 null。 */
    protected abstract fun receiver(token: String): ((JsonObject) -> McpRpcReply)?
    protected abstract val scope: CoroutineScope

    override fun isSupported(request: FullHttpRequest): Boolean = request.uri().substringBefore('?') == path

    override fun process(urlDecoder: QueryStringDecoder, request: FullHttpRequest, context: ChannelHandlerContext): Boolean {
        // 响应只保留不可变元信息；process 返回后平台会释放 FullHttpRequest 的 ByteBuf。
        val version = request.protocolVersion()
        val keepAlive = HttpUtil.isKeepAlive(request)
        fun respond(status: Int, body: String? = null) {
            if (!context.channel().isActive) return
            val response = DefaultFullHttpResponse(version, HttpResponseStatus.valueOf(status), Unpooled.copiedBuffer(body.orEmpty(), Charsets.UTF_8))
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8")
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes())
            if (status == 405) response.headers().set(HttpHeaderNames.ALLOW, "POST")
            response.send(context.channel(), keepAlive)
        }
        val remote = context.channel().remoteAddress() as? InetSocketAddress
        val local = context.channel().localAddress() as? InetSocketAddress
        if (remote?.address?.isLoopbackAddress != true ||
            !localOriginAllowed(request.headers().get(HttpHeaderNames.HOST), request.headers().get(HttpHeaderNames.ORIGIN), local?.port ?: -1)
        ) { respond(403); return true }
        val auth = request.headers().get(HttpHeaderNames.AUTHORIZATION).orEmpty()
        val receive = if (auth.startsWith("Bearer ")) receiver(auth.removePrefix("Bearer ")) else null
        if (receive == null) { respond(403); return true }
        if (request.method() != HttpMethod.POST) { respond(405); return true }
        if (!request.headers().get(HttpHeaderNames.CONTENT_TYPE).orEmpty().startsWith("application/json", true)) {
            respond(415); return true
        }
        val accept = request.headers().getAll(HttpHeaderNames.ACCEPT).joinToString(",")
        if (!accept.contains("application/json") || !accept.contains("text/event-stream")) { respond(406); return true }
        val protocolVersion = request.headers().get("MCP-Protocol-Version")
        if (protocolVersion != null && protocolVersion !in McpJsonRpc.VERSIONS) { respond(400); return true }
        if (request.content().readableBytes() > 64 * 1024) { respond(413); return true }
        val root = runCatching { JsonParser.parseString(request.content().toString(Charsets.UTF_8)) }.getOrNull()
        if (root == null || !root.isJsonObject) {
            respond(400, McpJsonRpc.rpcError(JsonNull.INSTANCE, -32700, "Invalid JSON object").toString())
            return true
        }
        val reply = receive(root.asJsonObject)
        if (reply.body == null) respond(reply.status)
        else scope.launch {
            try { respond(reply.status, reply.body.await().toString()) }
            catch (_: Exception) { respond(500) }
        }
        return true
    }
}
