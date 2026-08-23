package com.github.izerui.imux.session

import com.github.izerui.imux.monitor.SessionMonitor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ControlFlowException
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
import kotlin.coroutines.cancellation.CancellationException

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
 * 判断这条请求是否是 codex hook 的上报。
 *
 * 形状与 [handlesPiReport] 完全一致，只是路径不同——两条并列而不是在 body 里加
 * 判别字段，好让 pi 那一侧逐字节不变。
 */
internal fun handlesCodexReport(uri: String, isPost: Boolean): Boolean {
    if (!isPost) return false
    val path = uri.substringBefore('?')
    return path == CODEX_REPORT_PATH
}

/**
 * 解析 codex hook 的上报体。
 *
 * 语法与 pi 完全相同（上报脚本刻意发的是同一套字段），只多一步：**把 cwd 换算成
 * 可比较的键**，见 [codexCwdKey]。
 *
 * 这一步不是可选的。codex 报上来的 cwd 是 Windows 原生写法（`C:\a\b`），而
 * [piReportBelongsToProject] 拿它跟 `Project.getBasePath()` 做精确字符串比较，
 * 后者标着 `@SystemIndependent`，在 Windows 上是 `C:/a/b`。不换算的话这条上报
 * **永远**匹配不上任何项目，被整条丢弃——症状与「Windows 上 codex 漂移探测
 * 没做」完全一样，且不报错。
 *
 * 只作用于 codex 这条路径：POSIX 上 `\` 是合法文件名字符，对 pi 的上报做同样的
 * 替换会改坏正在工作的行为。而 codex 的上报只在 Windows 上产生
 * （令牌与 hook 都只在那里下发），这里的替换因此没有 POSIX 的落点。
 */
internal fun parseCodexReport(body: String): PiSessionReport? = parsePiReport(body)?.let { it.copy(cwd = codexCwdKey(it.cwd)) }

/**
 * 校验请求携带的令牌。
 *
 * 与 [handlesPiReport] 同理抽成纯函数：令牌是这个接口唯一的门禁——平台在这一层
 * 不做任何校验，本机任意进程都能打进来。最容易写错的是 header 缺失的情形：
 * `headers().get()` 取不到时返回 null，若比较写得宽松一点（先 `orEmpty()` 再比、
 * 或用 `startsWith` / `contains`），空令牌与被截断的前缀就能蒙混过关。
 * 这类错误编译器查不出来、功能测试也碰不到，只能靠针对性用例钉住。
 */
internal fun piReportTokenMatches(actual: String?, expected: String): Boolean =
    actual != null && actual == expected

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

    override fun isSupported(request: FullHttpRequest): Boolean {
        val isPost = request.method() == HttpMethod.POST
        return handlesPiReport(request.uri(), isPost) || handlesCodexReport(request.uri(), isPost)
    }

    override fun process(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext,
    ): Boolean {
        val expected = ApplicationManager.getApplication()
            .getService(PiReportTokenHolder::class.java)
            .token
        // 两条路径共用同一个令牌校验：那个函数的 KDoc 列了四种「写宽一点就会漏」的
        // 写法，另写一套比较逻辑等于把那些坑重新踩一遍。
        if (!piReportTokenMatches(request.headers().get(TOKEN_HEADER), expected)) {
            // 记一笔：本机任意进程都能打到这个接口，被拒的请求是排查时唯一的线索
            LOG.warn("拒绝令牌不匹配的会话上报")
            HttpResponseStatus.FORBIDDEN.send(context.channel(), request)
            return true
        }

        val body = request.content().toString(CharsetUtil.UTF_8)
        // codex 那条只多一步 cwd 分隔符归一化，见 parseCodexReport
        val report =
            if (handlesCodexReport(request.uri(), isPost = true)) {
                parseCodexReport(body)
            } else {
                parsePiReport(body)
            }
        if (report == null) {
            HttpResponseStatus.BAD_REQUEST.send(context.channel(), request)
            return true
        }

        // 本方法跑在 netty 的 EventLoop 线程上，不能在这里做迁移（要碰 EDT 与文件系统）。
        // 交给各项目的 monitor 自己排期，这里立刻应答。
        //
        // 单个项目出错不该连累其他项目，所以逐个兜住；但取消类异常必须原样重抛——
        // 平台靠它们中断任务，吞掉会让取消机制失效。其余异常记 WARN：某个项目的
        // 迁移一直失败时，日志是排查者唯一的线索。
        ProjectManager.getInstance().openProjects
            .filterNot { it.isDisposed }
            .forEach { project ->
                try {
                    SessionMonitor.getInstance(project).onPiSessionReported(report)
                } catch (e: Exception) {
                    // ControlFlowException 是接口而非 Throwable 子类，catch 不了，
                    // 只能捕获后再判类型。ProcessCanceledException 实现了它。
                    if (e is ControlFlowException || e is CancellationException) throw e
                    LOG.warn("项目 ${project.name} 处理 pi 会话上报失败", e)
                }
            }

        HttpResponseStatus.OK.send(context.channel(), request)
        return true
    }

    private companion object {
        val LOG = logger<PiSessionReportHandler>()
    }
}
