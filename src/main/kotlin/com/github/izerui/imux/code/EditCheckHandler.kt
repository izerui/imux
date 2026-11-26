package com.github.izerui.imux.code

import com.github.izerui.imux.mcp.bearerToken
import com.github.izerui.imux.mcp.loopbackAllowed
import com.github.izerui.imux.settings.DEFAULT_IDEA_MCP_PORT
import com.github.izerui.imux.settings.ImuxSettings
import com.github.izerui.imux.settings.isChinese
import com.github.izerui.imux.terminal.IdeaMcpEndpoint
import com.google.gson.JsonParser
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.ManagingFS
import com.intellij.openapi.vfs.newvfs.RefreshQueue
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.ide.HttpRequestHandler
import org.jetbrains.io.send
import java.nio.file.Path
import kotlin.coroutines.resume

/**
 * 结束前自动检查的入口（见 [CODE_EDIT_CHECK_PATH]）：Claude 发话时和要结束时经 hook 打到这里。
 *
 * 只认本机请求和 `imux_code` 的令牌，令牌找到会话所在的项目。检查放到后台做，不占内置服务器的网络线程；
 * 开关、IDEA MCP 是否开着每次都现读设置，用户在设置页关掉后，已开着的会话立即不再检查。
 */
class EditCheckHandler : HttpRequestHandler() {
    override fun isSupported(request: FullHttpRequest): Boolean = request.uri().substringBefore('?') == CODE_EDIT_CHECK_PATH

    override fun process(urlDecoder: QueryStringDecoder, request: FullHttpRequest, context: ChannelHandlerContext): Boolean {
        val channel = context.channel()
        val version = request.protocolVersion()
        val keepAlive = HttpUtil.isKeepAlive(request)
        if (!loopbackAllowed(request, context)) { reply(channel, version, keepAlive, 403); return true }
        if (request.method() != HttpMethod.POST) { reply(channel, version, keepAlive, 405); return true }
        val service = CodeMcpService.getInstance()
        // 令牌无效（标签已关、插件更新后旧进程还在）时回空放行，免得终端每次都冒 hook 错误。
        val token = bearerToken(request)?.takeIf { service.project(it) != null } ?: run { reply(channel, version, keepAlive, 200); return true }
        // 请求体必须在返回前读完：返回后平台会释放它。
        val body = runCatching { JsonParser.parseString(request.content().toString(Charsets.UTF_8)).asJsonObject }.getOrNull()
        val settings = ImuxSettings.getInstance()
        when (val event = EditCheck.event(body)) {
            EditCheck.Event.TurnStart -> {
                service.startTurn(token)
                reply(channel, version, keepAlive, 200)
            }
            is EditCheck.Event.TurnEnd -> {
                val turn = service.turns.claim(token)
                if (turn == null) {
                    reply(channel, version, keepAlive, 200); return true
                }
                val project = service.project(token)
                if (project == null || event.alreadyBlocked) {
                    service.turns.finish(token, turn)
                    reply(channel, version, keepAlive, 200); return true
                }
                val port = settings.state.ideaMcpPort.takeIf { it in 1..65535 } ?: DEFAULT_IDEA_MCP_PORT
                val chinese = settings.effectiveLanguage.isChinese()
                service.scope.launch(Dispatchers.IO) {
                    val report = try {
                        finishEditCheck(service.turns, token, turn, {
                            settings.state.autoCheckAfterEdit && settings.state.injectIdeaMcp &&
                                service.project(token) === project && !project.isDisposed && channel.isActive
                        }, ::refreshChanges, { files -> check(project, files, port, chinese) })
                    } catch (e: CancellationException) {
                        reply(channel, version, keepAlive, 200); throw e
                    } catch (e: Exception) {
                        LOG.warn("结束前自动检查失败：${e.javaClass.simpleName}")
                        null
                    }
                    reply(channel, version, keepAlive, 200, EditCheck.stopResponse(report))
                }
            }
            EditCheck.Event.Other -> reply(channel, version, keepAlive, 200)
        }
        return true
    }

    /**
     * 先把 Claude 在 IDE 外写的改动同步进 IDE（文件监听上报的改动），再只留项目里的代码（排除构建产物、被排除的目录），
     * 交给 IDEA MCP 检查。项目已关闭、没剩下要查的文件、连不上 IDEA MCP 时返回 null。
     */
    private suspend fun refreshChanges() {
        // 完成通知指定 ModalityState.any()：开着设置等模态对话框时默认队列发不出来，会白等到超时。最多等 10 秒，刷不完照常查。
        withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { continuation ->
                RefreshQueue.getInstance().refresh(true, true, Runnable { if (continuation.isActive) continuation.resume(Unit) }, ModalityState.any(), *ManagingFS.getInstance().localRoots)
            }
        }
    }

    private suspend fun check(project: Project, changed: Set<String>, port: Int, chinese: Boolean): String? {
        if (project.isDisposed) return null
        val base = project.basePath ?: return null
        val files = readAction {
            val index = ProjectFileIndex.getInstance(project)
            changed.mapNotNull { path ->
                val file = LocalFileSystem.getInstance().findFileByPath(path)?.takeIf { !it.isDirectory } ?: return@mapNotNull null
                if (!index.isInContent(file) || index.isExcluded(file)) return@mapNotNull null
                runCatching { Path.of(base).relativize(Path.of(path)).toString() }.getOrNull()
            }
        }.sorted().take(EDIT_CHECK_MAX_FILES)
        if (files.isEmpty()) return null
        val lint = runInterruptible(Dispatchers.IO) {
            IdeaMcpLintClient(IdeaMcpEndpoint(port, base).url, base).lint(files, EDIT_CHECK_LINT_TIMEOUT_MILLIS)
        } ?: return null
        return EditCheck.report(lint, files.size, chinese)
    }

    private fun reply(channel: Channel, version: HttpVersion, keepAlive: Boolean, status: Int, body: String = "{}") {
        if (!channel.isActive) return
        val response = DefaultFullHttpResponse(version, HttpResponseStatus.valueOf(status), Unpooled.copiedBuffer(body, Charsets.UTF_8))
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8")
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes())
        if (status == 405) response.headers().set(HttpHeaderNames.ALLOW, "POST")
        response.send(channel, keepAlive)
    }

    private companion object {
        val LOG = logger<EditCheckHandler>()
    }
}

