package com.github.izerui.imux.bridge.mcp

import com.github.izerui.imux.bridge.BridgeHub
import com.github.izerui.imux.bridge.json.obj
import com.github.izerui.imux.bridge.json.string
import com.github.izerui.imux.mcp.loopbackBearer
import com.google.gson.JsonObject
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.QueryStringDecoder
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.ide.HttpRequestHandler
import org.jetbrains.io.send
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * imux 启动的 Claude 会话经 `--settings` 注入的 http hook 入口：按令牌找到标签，把请求交给路由器。
 *
 * PermissionRequest 的响应挂起到路由器给出决定（手机作答、电脑端允许、解绑等）时再回写；
 * 挂起期间连接被 Claude 关闭表示电脑端拒绝或 Esc。其余事件立即回 {}。
 */
class ClaudeHookHandler : HttpRequestHandler() {
    private val ids = AtomicLong()

    override fun isSupported(request: FullHttpRequest): Boolean = request.uri().substringBefore('?') == CLAUDE_HOOK_PATH

    override fun process(urlDecoder: QueryStringDecoder, request: FullHttpRequest, context: ChannelHandlerContext): Boolean {
        // 响应只保留不可变元信息；process 返回后平台会释放 FullHttpRequest 的 ByteBuf。
        val version = request.protocolVersion()
        val keepAlive = HttpUtil.isKeepAlive(request)
        val channel = context.channel()
        // 一个请求只回写一次：挂起的请求可能同时被决定与异常兜底竞争
        val responded = AtomicBoolean()
        /** 返回是否真的写出：已回过或连接已关闭时为 false。 */
        fun respond(status: Int, body: String = "{}"): Boolean {
            if (!responded.compareAndSet(false, true) || !channel.isActive) return false
            val response = DefaultFullHttpResponse(version, HttpResponseStatus.valueOf(status), Unpooled.copiedBuffer(body, Charsets.UTF_8))
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8")
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes())
            if (status == 405) response.headers().set(HttpHeaderNames.ALLOW, "POST")
            response.send(channel, keepAlive)
            return true
        }
        val service = MobileMcpService.getInstance()
        val tabId = loopbackBearer(request, context)?.let { service.callers.get(it)?.tabId }
        if (tabId == null) { respond(403); return true }
        if (request.method() != HttpMethod.POST) { respond(405); return true }
        if (!request.headers().get(HttpHeaderNames.CONTENT_TYPE).orEmpty().startsWith("application/json", true)) {
            respond(415); return true
        }
        val body = when (val parsed = ClaudeHookJson.body(request.content().readableBytes()) { request.content().toString(Charsets.UTF_8) }) {
            is ClaudeHookJson.Body.Parsed -> parsed.json
            is ClaudeHookJson.Body.Reply -> { respond(parsed.status); return true }
        }
        val router = BridgeHub.getInstance().router
        when (body.string("hook_event_name")) {
            "PostToolUse", "PostToolUseFailure" -> {
                respond(200)
                val done = ClaudeHookJson.toolDone(body) ?: return true
                val answers = ClaudeHookJson.localAnswers(body["tool_response"])
                service.scope.launch { router.onHookToolDone(tabId, done, answers) }
            }
            "PermissionRequest" -> {
                val id = ids.incrementAndGet()
                val hookRequest = ClaudeHookJson.request(body, id) ?: run { respond(200); return true }
                val toolInput = body.obj("tool_input") ?: JsonObject()
                // 只在挂起期间关闭才算电脑端拒绝；自己回写后 keep-alive 连接可能复用很久，回写时摘掉监听
                val closed = ChannelFutureListener {
                    if (!responded.get()) service.scope.launch { router.onHookClosed(tabId, id) }
                }
                service.scope.launch {
                    try {
                        router.onHookRequest(tabId, hookRequest) { decision ->
                            channel.closeFuture().removeListener(closed)
                            respond(200, ClaudeHookJson.response(decision, toolInput).toString())
                        }
                    } catch (e: CancellationException) {
                        respond(500)
                        throw e
                    } catch (e: Exception) {
                        // 回 500 让 Claude 只走电脑端确认框；可能已入队，清掉条目免得卡住队首
                        LOG.warn("Claude hook 请求处理失败", e)
                        respond(500)
                        try {
                            router.onHookClosed(tabId, id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            LOG.warn("Claude hook 失败请求出队失败", e)
                        }
                    }
                    // 入队之后再监听：连接若在入队前就已关闭，监听会立即触发，不会漏掉
                    if (!responded.get()) channel.closeFuture().addListener(closed)
                }
            }
            else -> respond(200)
        }
        return true
    }

    private companion object {
        val LOG = logger<ClaudeHookHandler>()
    }
}
