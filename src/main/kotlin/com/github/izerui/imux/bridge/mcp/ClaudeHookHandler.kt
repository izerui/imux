package com.github.izerui.imux.bridge.mcp

import com.github.izerui.imux.bridge.BridgeHub
import com.github.izerui.imux.bridge.core.BridgeRouter
import com.github.izerui.imux.bridge.core.HookDecision
import com.github.izerui.imux.bridge.core.HookRequest
import com.github.izerui.imux.bridge.core.HookToolDone
import com.github.izerui.imux.bridge.json.obj
import com.github.izerui.imux.bridge.json.string
import com.github.izerui.imux.mcp.loopbackAllowed
import com.github.izerui.imux.mcp.bearerToken
import com.google.gson.JsonObject
import io.netty.buffer.ByteBufInputStream
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.ide.HttpRequestHandler
import org.jetbrains.io.send
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * imux 启动的 Claude 会话经 `--settings` 注入的 http hook 入口：按令牌找到标签，把请求交给路由器。
 * 处理逻辑在 [handleClaudeHook]，这里只取平台对象。
 */
class ClaudeHookHandler : HttpRequestHandler() {
    private val ids = AtomicLong()

    override fun isSupported(request: FullHttpRequest): Boolean = request.uri().substringBefore('?') == CLAUDE_HOOK_PATH

    override fun process(urlDecoder: QueryStringDecoder, request: FullHttpRequest, context: ChannelHandlerContext): Boolean {
        val service = MobileMcpService.getInstance()
        val caller = hookCaller(loopbackAllowed(request, context), bearerToken(request)) { service.callers.get(it)?.tabId }
        handleClaudeHook(request, context.channel(), caller, service.scope, BridgeHub.getInstance().router.hookRoutes(), ids::incrementAndGet)
        return true
    }
}

/** hook 入口的调用方。 */
internal sealed interface HookCaller {
    /** 不是本机、端口不对或 Origin 不合法：回 403。 */
    data object Forbidden : HookCaller

    /**
     * 本机校验通过，但令牌缺失、无效或已撤销（典型是插件热更新后没重启 IDE，旧 Claude 进程每次工具调用都打过来）：
     * 回 200 {}，不做任何决定，免得终端每次工具调用都冒 hook 错误。
     */
    data object Stale : HookCaller

    data class Tab(val tabId: String) : HookCaller
}

/** 按本机校验结果 [loopback]、请求带的令牌 [token] 与令牌到标签的查找 [tabOf] 分类。 */
internal fun hookCaller(
    loopback: Boolean,
    token: String?,
    tabOf: (String) -> String?,
): HookCaller {
    if (!loopback) return HookCaller.Forbidden
    return token?.let(tabOf)?.let(HookCaller::Tab) ?: HookCaller.Stale
}

/** 路由器上 hook 入口用到的方法；测试换成假的。 */
internal interface ClaudeHookRoutes {
    suspend fun onHookRequest(tabId: String, request: HookRequest, respond: (HookDecision) -> Boolean)

    suspend fun onHookToolDone(tabId: String, done: HookToolDone, answers: String?)

    suspend fun onHookClosed(tabId: String, id: Long)
}

internal fun BridgeRouter.hookRoutes(): ClaudeHookRoutes {
    val router = this
    return object : ClaudeHookRoutes {
        override suspend fun onHookRequest(tabId: String, request: HookRequest, respond: (HookDecision) -> Boolean) =
            router.onHookRequest(tabId, request, respond)

        override suspend fun onHookToolDone(tabId: String, done: HookToolDone, answers: String?) =
            router.onHookToolDone(tabId, done, answers)

        override suspend fun onHookClosed(tabId: String, id: Long) = router.onHookClosed(tabId, id)
    }
}

private val LOG = logger<ClaudeHookHandler>()

/**
 * 处理一次 hook 请求。PermissionRequest 的响应挂起到路由器给出决定（手机作答、电脑端允许、解绑等）时再回写；
 * 挂起期间连接被 Claude 关闭表示电脑端拒绝或 Esc，交给 [ClaudeHookRoutes.onHookClosed]。其余事件立即回 {}。
 * 请求体在返回前读完：返回后平台会释放 [request] 的 ByteBuf。
 */
internal fun handleClaudeHook(
    request: FullHttpRequest,
    channel: Channel,
    caller: HookCaller,
    scope: CoroutineScope,
    routes: ClaudeHookRoutes,
    nextId: () -> Long,
) {
    // 响应只保留不可变元信息
    val version = request.protocolVersion()
    val keepAlive = HttpUtil.isKeepAlive(request)
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
    val tabId = when (caller) {
        HookCaller.Forbidden -> { respond(403); return }
        HookCaller.Stale -> { respond(200); return }
        is HookCaller.Tab -> caller.tabId
    }
    if (request.method() != HttpMethod.POST) { respond(405); return }
    if (!request.headers().get(HttpHeaderNames.CONTENT_TYPE).orEmpty().startsWith("application/json", true)) {
        respond(415); return
    }
    val content = request.content()
    val body = when (val parsed = ClaudeHookJson.body(content.readableBytes()) { InputStreamReader(ByteBufInputStream(content.duplicate()), Charsets.UTF_8) }) {
        is ClaudeHookJson.Body.Parsed -> parsed.json
        is ClaudeHookJson.Body.Reply -> { respond(parsed.status); return }
    }
    when (body.string("hook_event_name")) {
        "PostToolUse", "PostToolUseFailure" -> {
            respond(200)
            val done = ClaudeHookJson.toolDone(body) ?: return
            val answers = ClaudeHookJson.localAnswers(body["tool_response"])
            scope.launch { routes.onHookToolDone(tabId, done, answers) }
        }
        "PermissionRequest" -> {
            val id = nextId()
            val hookRequest = ClaudeHookJson.request(body, id) ?: run { respond(200); return }
            val toolInput = body.obj("tool_input") ?: JsonObject()
            // 只在挂起期间关闭才算电脑端拒绝；自己回写后 keep-alive 连接可能复用很久，回写时摘掉监听
            val closed = ChannelFutureListener {
                if (!responded.get()) scope.launch { routes.onHookClosed(tabId, id) }
            }
            scope.launch {
                try {
                    routes.onHookRequest(tabId, hookRequest) { decision ->
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
                        routes.onHookClosed(tabId, id)
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
}
