package com.github.izerui.imux.terminal

import com.github.izerui.imux.model.AgentType
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class MobileMcpLaunchTest {
    private val mobile = TerminalMcpServer("imux_mobile", "http://127.0.0.1:63342/imux/mobile-mcp", "IMUX_MOBILE_TOKEN")

    @Test fun `Claude 配置与 IDEA 并存且凭证保持环境引用`() {
        val config = JsonParser.parseString(claudeIdeaMcpConfig(IdeaMcpEndpoint(64342, "/project"), listOf(mobile))).asJsonObject.getAsJsonObject("mcpServers")
        assertTrue(config.has("idea"))
        val added = config.getAsJsonObject("imux_mobile")
        assertEquals(180000, added["timeout"].asInt)
        assertEquals("Bearer \${IMUX_MOBILE_TOKEN}", added.getAsJsonObject("headers")["Authorization"].asString)
    }

    @Test fun `关闭 IDEA 注入后 Claude 新建与恢复仍带手机工具`() {
        val fresh = launchCommand("/bin/zsh", AgentType.CLAUDE, null, additionalMcpServers = listOf(mobile)).last()
        val restored = launchCommand("/bin/zsh", AgentType.CLAUDE, "session", additionalMcpServers = listOf(mobile)).last()
        assertTrue(fresh, fresh.contains("--mcp-config") && fresh.contains("imux_mobile"))
        assertTrue(restored, restored.contains("imux_mobile") && restored.endsWith("--resume 'session'"))
        assertFalse(fresh.contains("--append-system-prompt"))
    }

    @Test fun `Codex 新建与恢复独立设置秒级工具超时`() {
        val fresh = launchCommand("/bin/zsh", AgentType.CODEX, null, additionalMcpServers = listOf(mobile)).last()
        val restored = launchCommand("/bin/zsh", AgentType.CODEX, "session", additionalMcpServers = listOf(mobile)).last()
        assertTrue(fresh, fresh.contains("mcp_servers.imux_mobile.tool_timeout_sec=180"))
        assertTrue(restored, restored.contains("mcp_servers.imux_mobile.tool_timeout_sec=180") && restored.endsWith("resume 'session'"))
        assertTrue(fresh.contains("bearer_token_env_var=\"IMUX_MOBILE_TOKEN\""))
    }

    @Test fun `POSIX 与 PowerShell 都保留 Claude 的变量占位符`() {
        val posix = launchCommand("/bin/zsh", AgentType.CLAUDE, null, additionalMcpServers = listOf(mobile)).last()
        val windows = launchCommand("powershell.exe", AgentType.CLAUDE, null, additionalMcpServers = listOf(mobile)).last()
        assertTrue(posix, posix.contains("\${IMUX_MOBILE_TOKEN}"))
        assertTrue(windows, windows.contains("\${IMUX_MOBILE_TOKEN}"))
        assertTrue(posix, posix.contains("--mcp-config '"))
        assertTrue(windows, windows.contains("--mcp-config (") && windows.contains("[char]34"))
    }
}
