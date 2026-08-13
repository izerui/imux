package com.github.izerui.imux.session

import com.github.izerui.imux.monitor.SessionMonitor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.ProjectManager
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.QueryStringDecoder
import io.netty.util.CharsetUtil
import org.jetbrains.ide.HttpRequestHandler
import org.jetbrains.io.send

/** 请求头里携带令牌的字段名。 */
private const val TOKEN_HEADER = "x-imux-token"

/**
 * 判断这条请求是否该由本 handler 处理。
 *
 * 抽成纯函数是为了可测：构造真实的 [FullHttpRequest] 代价不小，而这里的判定
 * 恰恰是最容易出错的地方——平台的 `isSupported` 默认只放行 GET/HEAD，
 * 不覆盖它 POST 永远进不来，且症状是 404 而不是 405。
 */
internal fun handlesPiReport(uri: String, isPost: Boolean): Boolean {
    if (!isPost) return false
    val path = uri.substringBefore('?')
    return path == PI_REPORT_PATH
}

/**
 * 接收 pi 扩展的会话上报。
 *
 * 为什么需要它：pi 不像 claude 有运行态文件、也不像 codex 长期持有会话文件句柄，
 * 又因为设了 `process.title` 而让 `ps` 读不到它的环境变量——三条观测面全断，
 * 只能由 pi 自己说。详见 docs/superpowers/specs/2026-08-13-pi-new-session-tracking-design.md。
 *
 * **令牌是唯一的门禁**：平台在本层不做任何校验，本机任意进程都能打进来。
 * loopback 限制由父类的 `isAccessible` 保证，不必自己再判。
 */
internal class PiSessionReportHandler : HttpRequestHandler() {

    override fun isSupported(request: FullHttpRequest): Boolean =
        handlesPiReport(request.uri(), isPost = request.method() == HttpMethod.POST)

    override fun process(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext,
    ): Boolean {
        val expected = ApplicationManager.getApplication()
            .getService(PiReportTokenHolder::class.java)
            .token
        if (request.headers().get(TOKEN_HEADER) != expected) {
            // 记一笔：本机任意进程都能打到这个接口，被拒的请求是排查时唯一的线索
            LOG.warn("拒绝令牌不匹配的 pi 会话上报")
            HttpResponseStatus.FORBIDDEN.send(context.channel(), request)
            return true
        }

        val report = parsePiReport(request.content().toString(CharsetUtil.UTF_8))
        if (report == null) {
            HttpResponseStatus.BAD_REQUEST.send(context.channel(), request)
            return true
        }

        // 本方法跑在 netty 的 EventLoop 线程上，不能在这里做迁移（要碰 EDT 与文件系统）。
        // 交给各项目的 monitor 自己排期，这里立刻应答。
        ProjectManager.getInstance().openProjects
            .filterNot { it.isDisposed }
            .forEach { runCatching { SessionMonitor.getInstance(it).onPiSessionReported(report) } }

        HttpResponseStatus.OK.send(context.channel(), request)
        return true
    }

    private companion object {
        val LOG = logger<PiSessionReportHandler>()
    }
}
