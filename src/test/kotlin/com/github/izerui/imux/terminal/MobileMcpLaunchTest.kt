package com.github.izerui.imux.terminal

import com.github.izerui.imux.model.AgentType
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class MobileMcpLaunchTest {
    private val mobile = TerminalMcpServer("imux_mobile", "http://127.0.0.1:63342/imux/mobile-mcp", "IMUX_MOBILE_TOKEN")
    private val hookUrl = "http://127.0.0.1:63342/imux/claude-hook"
    private val hookSettings = claudeHookSettings(hookUrl, "IMUX_MOBILE_TOKEN")

    // region --settings

    @Test fun `hook 设置含四种事件且凭证保持环境引用`() {
        val root = JsonParser.parseString(hookSettings).asJsonObject.getAsJsonObject("hooks")
        assertEquals(setOf("PermissionRequest", "PostToolUse", "PostToolUseFailure", "SubagentStop"), root.keySet())
        for (event in listOf("PermissionRequest", "PostToolUse", "PostToolUseFailure", "SubagentStop")) {
            val hook = root.getAsJsonArray(event)[0].asJsonObject.getAsJsonArray("hooks")[0].asJsonObject
            assertEquals(event, hookUrl, hook["url"].asString)
            assertEquals(event, "Bearer \$IMUX_MOBILE_TOKEN", hook.getAsJsonObject("headers")["Authorization"].asString)
            val allowed = hook.getAsJsonArray("allowedEnvVars")
            assertEquals(event, 1, allowed.size())
            assertEquals(event, "IMUX_MOBILE_TOKEN", allowed[0].asString)
        }
        // 超时各异
        val permHook = root.getAsJsonArray("PermissionRequest")[0].asJsonObject.getAsJsonArray("hooks")[0].asJsonObject
        assertEquals(86400, permHook["timeout"].asInt)
        val postHook = root.getAsJsonArray("PostToolUse")[0].asJsonObject.getAsJsonArray("hooks")[0].asJsonObject
        assertEquals(5, postHook["timeout"].asInt)
        val failHook = root.getAsJsonArray("PostToolUseFailure")[0].asJsonObject.getAsJsonArray("hooks")[0].asJsonObject
        assertEquals(5, failHook["timeout"].asInt)
        val stopRule = root.getAsJsonArray("SubagentStop")[0].asJsonObject
        assertEquals("*", stopRule["matcher"].asString)
        assertEquals(5, stopRule.getAsJsonArray("hooks")[0].asJsonObject["timeout"].asInt)
    }

    @Test fun `Claude 新建与恢复都带 --settings 且在 --resume 之前`() {
        val fresh = launchCommand("/bin/zsh", AgentType.CLAUDE, null, claudeSettings = hookSettings, additionalMcpServers = listOf(mobile)).last()
        val restored = launchCommand("/bin/zsh", AgentType.CLAUDE, "session", claudeSettings = hookSettings, additionalMcpServers = listOf(mobile)).last()
        assertTrue("新建含 --settings", fresh.contains("--settings '"))
        assertTrue("恢复含 --settings", restored.contains("--settings "))
        val settingsIdx = restored.indexOf("--settings ")
        val resumeIdx = restored.indexOf("--resume ")
        assertTrue("--settings 在 --resume 之前", settingsIdx < resumeIdx)
    }

    @Test fun `POSIX 与 PowerShell 都保留 hook 的变量占位符`() {
        val posix = launchCommand("/bin/zsh", AgentType.CLAUDE, null, claudeSettings = hookSettings).last()
        val windows = launchCommand("powershell.exe", AgentType.CLAUDE, null, claudeSettings = hookSettings).last()
        assertTrue("POSIX 保留占位符", posix.contains("\$IMUX_MOBILE_TOKEN"))
        assertTrue("PowerShell 保留占位符", windows.contains("\$IMUX_MOBILE_TOKEN"))
        assertTrue("PowerShell 用 [char]34", windows.contains("[char]34"))
    }

    @Test fun `Codex 与 Pi 各自不带 --settings`() {
        val codex = launchCommand("/bin/zsh", AgentType.CODEX, null, claudeSettings = hookSettings).last()
        val pi = launchCommand("/bin/zsh", AgentType.PI, "session-id", claudeSettings = hookSettings).last()
        assertFalse("Codex 不带 --settings", codex.contains("--settings"))
        assertFalse("Pi 不带 --settings", pi.contains("--settings"))
    }

    @Test fun `带初始 prompt 时 --settings 在分隔符之前`() {
        val cmd = launchCommand("/bin/zsh", AgentType.CLAUDE, null, claudeSettings = hookSettings, initialPrompt = "说 hi").last()
        val settingsIdx = cmd.indexOf("--settings ")
        val separatorIdx = cmd.indexOf(" -- ")
        assertTrue("含 --settings", settingsIdx >= 0)
        assertTrue("含分隔符", separatorIdx >= 0)
        assertTrue("--settings 在 -- 之前", settingsIdx < separatorIdx)
    }

    // endregion

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
