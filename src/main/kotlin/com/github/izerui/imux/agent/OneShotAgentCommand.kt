package com.github.izerui.imux.agent

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.terminal.IdeaMcpEndpoint
import com.github.izerui.imux.terminal.ShellDialect
import com.github.izerui.imux.terminal.configuredIdeaMcpEndpoint
import com.github.izerui.imux.terminal.configuredIdeaMcpGuidance
import com.github.izerui.imux.terminal.dialectOf
import com.github.izerui.imux.terminal.ideaMcpCliArgument
import com.github.izerui.imux.terminal.ideaMcpEnvironment
import com.github.izerui.imux.terminal.piIdeaMcpScript
import com.github.izerui.imux.terminal.quote
import com.github.izerui.imux.terminal.shellArgs
import com.intellij.openapi.project.Project
import java.nio.file.Path

/** 单次调用要注入的 IDEA MCP；endpoint 为 null 表示不注入。 */
internal data class AgentMcpConfig(
    val endpoint: IdeaMcpEndpoint?,
    val guidance: String?,
    val piExtensionScript: Path?,
) {
    companion object {
        val NONE = AgentMcpConfig(null, null, null)

        /** 按用户在设置里的 IDEA MCP 配置解析，与会话终端、副驾驶的注入规则一致。 */
        fun configured(project: Project, projectPath: String, agentType: AgentType): AgentMcpConfig {
            val endpoint = configuredIdeaMcpEndpoint(project, projectPath)
            return AgentMcpConfig(
                endpoint = endpoint,
                guidance = configuredIdeaMcpGuidance(endpoint),
                piExtensionScript = if (agentType == AgentType.PI && endpoint != null) piIdeaMcpScript() else null,
            )
        }
    }
}

internal data class OneShotAgentInvocation(
    val command: List<String>,
    val environment: Map<String, String>,
)

/**
 * 单次智能体调用的命令：一次性运行、不保存会话、输出 JSON 事件流，prompt 由调用方经 stdin 写入。
 *
 * 权限与副驾驶审查相同：全部工具、跳过权限确认（Claude `bypassPermissions`、
 * Codex 绕过审批与沙箱），并按设置注入 IDEA MCP。
 * 开头先输出 [AGENT_OUTPUT_MARKER]，读取时跳过它之前 shell 启动打印的内容。
 */
internal fun oneShotAgentInvocation(
    shell: String,
    agentType: AgentType,
    projectPath: String,
    mcp: AgentMcpConfig = AgentMcpConfig.NONE,
): OneShotAgentInvocation {
    val dialect = dialectOf(shell)
    val ideaMcpArgument = ideaMcpCliArgument(dialect, agentType, mcp.endpoint, mcp.guidance)
    val cli =
        when (agentType) {
            AgentType.CLAUDE ->
                listOfNotNull(
                    "claude -p --permission-mode bypassPermissions --tools default --no-session-persistence",
                    ideaMcpArgument,
                    "--output-format stream-json --verbose",
                ).joinToString(" ")

            AgentType.CODEX ->
                listOfNotNull(
                    "codex exec --ephemeral --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox --color never",
                    ideaMcpArgument,
                    "--json -C ${quote(dialect, projectPath)}",
                ).joinToString(" ")

            AgentType.PI ->
                buildString {
                    append("pi -p --no-session")
                    mcp.piExtensionScript?.let { append(" -e ${quote(dialect, it.toString())}") }
                    append(" --mode json")
                }
        }
    return shellInvocation(shell, cli, ideaMcpEnvironment(agentType, mcp.endpoint, mcp.guidance))
}

/**
 * 持续对话（需求澄清）的命令：权限、IDEA MCP 注入与 [oneShotAgentInvocation] 相同，区别只在对话方式：
 * - Claude：常驻进程，stdin 逐行写入 stream-json 的 user 消息，每轮读到 `result` 为止；不保存会话。
 * - Pi：常驻进程，RPC 模式（stdin 写 `prompt` 命令，每轮读到 `agent_settled` 为止）；不保存会话。
 * - Codex：每轮一次 `codex exec`，首轮不加 `--ephemeral`（需要会话文件才能续接），
 *   之后以 [codexResumeId] 续接（`resume <id> -`，消息仍经 stdin 写入）。
 */
internal fun conversationInvocation(
    shell: String,
    agentType: AgentType,
    projectPath: String,
    mcp: AgentMcpConfig = AgentMcpConfig.NONE,
    codexResumeId: String? = null,
): OneShotAgentInvocation {
    val dialect = dialectOf(shell)
    val ideaMcpArgument = ideaMcpCliArgument(dialect, agentType, mcp.endpoint, mcp.guidance)
    val cli =
        when (agentType) {
            AgentType.CLAUDE ->
                listOfNotNull(
                    "claude -p --permission-mode bypassPermissions --tools default --no-session-persistence",
                    ideaMcpArgument,
                    "--input-format stream-json --output-format stream-json --verbose",
                ).joinToString(" ")

            AgentType.CODEX ->
                listOfNotNull(
                    "codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox --color never",
                    ideaMcpArgument,
                    "--json -C ${quote(dialect, projectPath)}",
                    codexResumeId?.let { "resume ${quote(dialect, it)} -" },
                ).joinToString(" ")

            AgentType.PI ->
                buildString {
                    append("pi --mode rpc --no-session")
                    mcp.piExtensionScript?.let { append(" -e ${quote(dialect, it.toString())}") }
                }
        }
    return shellInvocation(shell, cli, ideaMcpEnvironment(agentType, mcp.endpoint, mcp.guidance))
}

/** 经登录 shell 运行 [cli]（PATH 与用户终端一致），开头先输出 [AGENT_OUTPUT_MARKER]。 */
private fun shellInvocation(shell: String, cli: String, environment: Map<String, String>): OneShotAgentInvocation {
    val dialect = dialectOf(shell)
    val marker =
        when (dialect) {
            ShellDialect.POSIX -> "printf '%s\\n' ${quote(dialect, AGENT_OUTPUT_MARKER)}"
            ShellDialect.POWERSHELL -> "Write-Output ${quote(dialect, AGENT_OUTPUT_MARKER)}"
        }
    return OneShotAgentInvocation(command = listOf(shell) + shellArgs(dialect) + "$marker; $cli", environment = environment)
}

internal const val AGENT_OUTPUT_MARKER = "__IMUX_AGENT_OUTPUT_BEGIN__"
