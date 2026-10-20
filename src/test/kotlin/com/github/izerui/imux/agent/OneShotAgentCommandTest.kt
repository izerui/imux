package com.github.izerui.imux.agent

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.terminal.IdeaMcpEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class OneShotAgentCommandTest {
    private val ideaMcp = IdeaMcpEndpoint(64342, "/workspace")
    private val withMcp = AgentMcpConfig(ideaMcp, "Prefer IDEA MCP", Path.of("/tmp/pi-imux-idea-mcp.js"))

    private fun script(agentType: AgentType, mcp: AgentMcpConfig = AgentMcpConfig.NONE) =
        oneShotAgentInvocation("/bin/zsh", agentType, "/tmp/project", mcp).command.last()

    @Test
    fun `Claude 启用全部工具跳过权限确认且不保存会话并输出事件流`() {
        val script = script(AgentType.CLAUDE)

        assertTrue(script.startsWith("printf '%s\\n' '$AGENT_OUTPUT_MARKER'; claude -p "))
        assertTrue(script.contains("--permission-mode bypassPermissions"))
        assertTrue(script.contains("--tools default"))
        assertTrue(script.contains("--no-session-persistence"))
        assertTrue(script.contains("--output-format stream-json --verbose"))
        assertFalse(script.contains("--safe-mode"))
        assertFalse(script.contains("--mcp-config"))
    }

    @Test
    fun `Codex 绕过审批与沙箱且不保存会话并输出事件流`() {
        val script = script(AgentType.CODEX)

        assertTrue(script.contains("codex exec --ephemeral --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox"))
        assertTrue(script.contains("--json -C '/tmp/project'"))
        assertFalse(script.contains("--sandbox read-only"))
        assertFalse(script.contains("mcp_servers.idea"))
    }

    @Test
    fun `Pi 不限制工具且不保存会话并输出事件流`() {
        val script = script(AgentType.PI)

        assertTrue(script.contains("pi -p --no-session --mode json"))
        assertFalse(script.contains("--no-tools"))
        assertFalse(script.contains("--tools"))
    }

    @Test
    fun `按设置注入 IDEA MCP`() {
        val claude = oneShotAgentInvocation("/bin/zsh", AgentType.CLAUDE, "/tmp/project", withMcp)
        assertTrue(claude.command.last().contains("--mcp-config"))
        assertTrue(claude.command.last().contains(ideaMcp.url))
        assertTrue(claude.command.last().contains("--append-system-prompt 'Prefer IDEA MCP'"))

        val codex = oneShotAgentInvocation("/bin/zsh", AgentType.CODEX, "/tmp/project", withMcp)
        assertTrue(codex.command.last().contains("""mcp_servers.idea.url="${ideaMcp.url}""""))

        val pi = oneShotAgentInvocation("/bin/zsh", AgentType.PI, "/tmp/project", withMcp)
        assertTrue(pi.command.last().contains("-e '/tmp/pi-imux-idea-mcp.js'"))
        assertEquals("Pi 通过环境变量拿到 MCP 地址", ideaMcp.url, pi.environment["IMUX_IDEA_MCP_URL"])
    }

    @Test
    fun `PowerShell 使用对应方言输出标记`() {
        val command = oneShotAgentInvocation("powershell.exe", AgentType.CODEX, "C:\\work dir").command

        assertTrue(command.contains("-ExecutionPolicy"))
        assertTrue(command.last().contains("Write-Output '$AGENT_OUTPUT_MARKER'"))
        assertTrue(command.last().contains("-C 'C:\\work dir'"))
    }

    @Test
    fun `命令行不包含 prompt`() {
        // prompt 由调用方经 stdin 写入，命令里只有固定参数
        for (type in AgentType.entries) {
            val script = script(type)
            assertFalse(script.contains("会话记录"))
        }
    }
}
