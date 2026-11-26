package com.github.izerui.imux.code

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.settings.GuidanceCapability
import com.github.izerui.imux.settings.ImuxSettings
import com.github.izerui.imux.terminal.TerminalMcpServer
import com.github.izerui.imux.terminal.piPluginMcpScript
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import org.jetbrains.ide.BuiltInServerManager
import java.nio.file.Path

/**
 * 一次 `imux_code` 挂载：服务配置、要带给 CLI 的环境变量（地址和凭证），用完必须 [release] 撤销凭证。
 *
 * 会话终端、副驾驶审查、需求澄清共用：标签关闭、每轮审查结束、澄清对话关闭时各自撤销。
 */
class CodeToolsLease internal constructor(
    val server: TerminalMcpServer,
    val environment: Map<String, String>,
    /** Pi 靠这个扩展登记插件内置 MCP；其它 CLI 为 null。 */
    val piExtension: Path?,
    /**
     * 只有 Claude、且开着「结束前自动检查」时才有：发话时、要结束时通知 imux（见 EditCheck）。
     * 只给会话终端用；副驾驶审查、需求澄清只查不改，不挂。
     */
    val claudeSettings: String? = null,
    private val revoke: () -> Unit,
) {
    /** 挂上这组工具，引导词就要带上「摸清链路」一节。 */
    val guidance: Set<GuidanceCapability> get() = GUIDANCE

    fun release() = revoke()

    companion object {
        const val URL_ENV = "IMUX_CODE_URL"
        const val TOKEN_ENV = "IMUX_CODE_TOKEN"
        private val GUIDANCE = setOf(GuidanceCapability.CODE_TOOLS)

        /**
         * 「查用法、查调用链工具」关着、内置服务器还没起来，或 Pi 缺少扩展脚本时返回 null：
         * 少的只是这组工具，调用方照常启动 CLI。端口在内置服务器起来前会阻塞，须在后台线程调用。
         */
        fun acquire(project: Project, agentType: AgentType): CodeToolsLease? {
            if (!ImuxSettings.getInstance().state.injectCodeTools) return null
            // 这组工具是可选的：内置服务器没起来、启动出错，只是少挂这组工具，不能拖垮会话启动或副驾驶审查。
            return try {
                acquireOrThrow(project, agentType)
            } catch (e: Exception) {
                if (e is com.intellij.openapi.progress.ProcessCanceledException || e is java.util.concurrent.CancellationException) throw e
                logger<CodeToolsLease>().warn("imux_code 未挂上：${e.javaClass.simpleName}")
                null
            }
        }

        private fun acquireOrThrow(project: Project, agentType: AgentType): CodeToolsLease? {
            val piExtension = if (agentType == AgentType.PI) piPluginMcpScript() ?: return null else null
            val port = BuiltInServerManager.getInstance().waitForStart().port.takeIf { it > 0 } ?: return null
            val service = CodeMcpService.getInstance()
            val token = service.register(project)
            val url = "http://127.0.0.1:$port$CODE_MCP_PATH"
            val editCheck = if (agentType == AgentType.CLAUDE && ImuxSettings.getInstance().state.autoCheckAfterEdit) {
                editCheckHookSettings("http://127.0.0.1:$port$CODE_EDIT_CHECK_PATH", TOKEN_ENV)
            } else null
            return CodeToolsLease(
                TerminalMcpServer(CODE_MCP_SERVER, url, TOKEN_ENV),
                mapOf(URL_ENV to url, TOKEN_ENV to token),
                piExtension,
                editCheck,
            ) { service.revoke(token) }
        }
    }
}
