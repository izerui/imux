package com.github.izerui.imux.agent

import com.github.izerui.imux.code.CodeToolsLease
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.settings.GuidanceRole
import com.github.izerui.imux.terminal.IdeaMcpEndpoint
import com.github.izerui.imux.terminal.ShellDialect
import com.github.izerui.imux.terminal.configuredIdeaMcpEndpoint
import com.github.izerui.imux.terminal.configuredAgentGuidance
import com.github.izerui.imux.terminal.dialectOf
import com.github.izerui.imux.terminal.ideaMcpCliArgument
import com.github.izerui.imux.terminal.ideaMcpEnvironment
import com.github.izerui.imux.terminal.piIdeaMcpScript
import com.github.izerui.imux.terminal.quote
import com.github.izerui.imux.terminal.shellArgs
import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * 单次调用或持续对话要挂的 IDE 工具：IDEA MCP（[endpoint] 为 null 表示不挂）和 `imux_code`（[codeTools]）。
 * 挂了 `imux_code` 就发了凭证，用完必须 [release]。
 */
internal data class AgentMcpConfig(
    val endpoint: IdeaMcpEndpoint?,
    val guidance: String?,
    val piExtensionScript: Path?,
    val codeTools: CodeToolsLease? = null,
) {
    fun release() { codeTools?.release() }

    companion object {
        /** 什么都不挂：会话报告只读会话记录，用不上 IDE，也就不带工具和引导词。 */
        val NONE = AgentMcpConfig(null, null, null)

        /**
         * 只查不改的调用（需求澄清）按设置挂工具：IDEA MCP 与会话终端、副驾驶的注入规则一致，
         * `imux_code` 随它自己的开关；引导词用只查不改的版本。等内置服务器端口会阻塞，须在后台线程调用。
         */
        fun investigator(project: Project, projectPath: String, agentType: AgentType): AgentMcpConfig {
            val endpoint = configuredIdeaMcpEndpoint(project, projectPath)
            val codeTools = CodeToolsLease.acquire(project, agentType)
            // 凭证已发出：后面任何一步出错都要撤销。
            return try {
                AgentMcpConfig(
                    endpoint = endpoint,
                    guidance = configuredAgentGuidance(endpoint != null, codeTools?.guidance.orEmpty(), GuidanceRole.INVESTIGATOR),
                    piExtensionScript = if (agentType == AgentType.PI && endpoint != null) piIdeaMcpScript() else null,
                    codeTools = codeTools,
                )
            } catch (e: Throwable) {
                codeTools?.release()
                throw e
            }
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
 * Codex 绕过审批与沙箱）；挂哪些 IDE 工具由 [mcp] 决定。
 * 开头先输出 [AGENT_OUTPUT_MARKER]，读取时跳过它之前 shell 启动打印的内容。
 */
internal fun oneShotAgentInvocation(
    shell: String,
    agentType: AgentType,
    projectPath: String,
    mcp: AgentMcpConfig = AgentMcpConfig.NONE,
): OneShotAgentInvocation {
    val dialect = dialectOf(shell)
    val ideaMcpArgument = ideaMcpCliArgument(dialect, agentType, mcp.endpoint, mcp.guidance, listOfNotNull(mcp.codeTools?.server))
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
                    mcp.codeTools?.piExtension?.let { append(" -e ${quote(dialect, it.toString())}") }
                    append(" --mode json")
                }
        }
    return shellInvocation(shell, cli, ideaMcpEnvironment(agentType, mcp.endpoint, mcp.guidance) + mcp.codeTools?.environment.orEmpty())
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
    val ideaMcpArgument = ideaMcpCliArgument(dialect, agentType, mcp.endpoint, mcp.guidance, listOfNotNull(mcp.codeTools?.server))
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
                    mcp.codeTools?.piExtension?.let { append(" -e ${quote(dialect, it.toString())}") }
                }
        }
    return shellInvocation(shell, cli, ideaMcpEnvironment(agentType, mcp.endpoint, mcp.guidance) + mcp.codeTools?.environment.orEmpty())
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
