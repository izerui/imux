package com.github.izerui.imux.code

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.settings.GuidanceCapability
import com.github.izerui.imux.settings.ImuxSettings
import com.github.izerui.imux.settings.PluginLanguage
import com.github.izerui.imux.terminal.TerminalMcpServer
import com.github.izerui.imux.terminal.piPluginMcpScript
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import org.jetbrains.ide.BuiltInServerManager
import java.nio.file.Path

/**
 * 一次 `imux_code` 挂载：服务配置、要带给 CLI 的环境变量（地址和凭证），用完必须 [release] 撤销凭证。
 *
 * 会话终端和副驾驶审查共用：前者随标签关闭撤销，后者每轮审查结束撤销。
 */
class CodeToolsLease internal constructor(
    val server: TerminalMcpServer,
    val environment: Map<String, String>,
    /** Pi 靠这个扩展登记插件内置 MCP；其它 CLI 为 null。 */
    val piExtension: Path?,
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
         * 副驾驶审查时追加在审查提示词后面：副驾驶的本职就是看搭档有没有漏改，
         * 改了签名、参数、字段却没跟着改调用方，是最常见也最难从对话里看出来的漏洞。
         * 写「如果你有这些工具」：老版本 Pi 没有原生 MCP 时工具挂不上，不能要求它调用不存在的工具。
         */
        fun reviewHint(language: PluginLanguage): String =
            if (language in setOf(PluginLanguage.SIMPLIFIED_CHINESE, PluginLanguage.TRADITIONAL_CHINESE)) {
                "\n\n## 核对调用链\n\n搭档改了函数、方法、参数、字段或返回值时，如果你有 `$CODE_MCP_SERVER` 的工具，用 `call_hierarchy` 和 `find_references` 查一下：" +
                    "调用方和用到它的地方是不是都跟着改了；改的是被重写或实现的方法、类型时，再用 `type_hierarchy` 查重写和实现有没有一起改。查出漏改的就当成问题提出来。"
            } else {
                "\n\n## Check the call chain\n\nWhen your partner changed a function, method, parameter, field, or return value and you have the `$CODE_MCP_SERVER` tools, use `call_hierarchy` and `find_references` " +
                    "to check whether every caller and usage was updated to match; for an overridden or implemented method or type, also use `type_hierarchy` to check the overrides and implementations. Raise anything left out as an issue."
            }

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
            return CodeToolsLease(
                TerminalMcpServer(CODE_MCP_SERVER, url, TOKEN_ENV),
                mapOf(URL_ENV to url, TOKEN_ENV to token),
                piExtension,
            ) { service.revoke(token) }
        }
    }
}
