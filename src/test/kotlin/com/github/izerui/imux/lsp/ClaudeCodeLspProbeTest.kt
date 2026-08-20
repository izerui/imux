package com.github.izerui.imux.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeCodeLspProbeTest {

    private val marketplace = """
        {"plugins":[
          {"name":"gopls-lsp","lspServers":{"gopls":{"command":"gopls","extensionToLanguage":{".go":"go"}}}},
          {"name":"kotlin-lsp","lspServers":{"kotlin":{"command":"kotlin-lsp","extensionToLanguage":{".kt":"kotlin"}}}},
          {"name":"not-an-lsp-plugin","category":"development"}
        ]}
    """.trimIndent()

    @Test
    fun `从 settings 里直接定义的 lspServers 取 command`() {
        val settings = """{"lspServers":{"gopls":{"command":"gopls","args":["--background-index"]}}}"""

        assertEquals(setOf("gopls"), parseConfiguredCommands(settings, null))
    }

    /** 官方 LSP 插件把 command 藏在 marketplace 清单里，settings 里只有一个开关。 */
    @Test
    fun `启用的插件带来其 marketplace 中声明的 command`() {
        val settings = """{"enabledPlugins":{"gopls-lsp@claude-plugins-official":true}}"""

        assertEquals(setOf("gopls"), parseConfiguredCommands(settings, marketplace))
    }

    @Test
    fun `未启用的插件不计入`() {
        val settings = """{"enabledPlugins":{"kotlin-lsp@claude-plugins-official":false}}"""

        assertTrue(parseConfiguredCommands(settings, marketplace).isEmpty())
    }

    @Test
    fun `两个来源合并`() {
        val settings = """
            {"lspServers":{"custom":{"command":"my-server"}},
             "enabledPlugins":{"kotlin-lsp@claude-plugins-official":true}}
        """.trimIndent()

        assertEquals(setOf("my-server", "kotlin-lsp"), parseConfiguredCommands(settings, marketplace))
    }

    /** 配置文件随时可能不存在或被用户改坏，任何一种都只该降级为「未配置」。 */
    @Test
    fun `缺失或损坏的配置解析为空集合`() {
        assertTrue(parseConfiguredCommands(null, null).isEmpty())
        assertTrue(parseConfiguredCommands("", marketplace).isEmpty())
        assertTrue(parseConfiguredCommands("这不是 json", marketplace).isEmpty())
        assertTrue(parseConfiguredCommands("[1,2,3]", marketplace).isEmpty())
        assertTrue(parseConfiguredCommands("""{"lspServers":"应该是对象"}""", marketplace).isEmpty())
        assertTrue(parseConfiguredCommands("""{"enabledPlugins":{"gopls-lsp@x":true}}""", "坏掉的清单").isEmpty())
    }

    @Test
    fun `配置到位且二进制在则为就绪`() {
        val report = claudeReport(
            configuredCommands = setOf("gopls"),
            binaries = mapOf("gopls" to "/Users/demo/go/bin/gopls"),
            cliInstalled = true,
        )

        val go = report.findings.single { it.language.id == "go" }
        assertEquals(LspStatus.READY, go.status)
    }

    /** 本机真实场景：kotlin-lsp 二进制已装，Claude Code 却没启用插件。 */
    @Test
    fun `二进制在但插件没启用则是配置缺口并给出装插件的命令`() {
        val report = claudeReport(
            configuredCommands = emptySet(),
            binaries = mapOf("kotlin-lsp" to "/opt/homebrew/bin/kotlin-lsp"),
            cliInstalled = true,
        )

        val kotlin = report.findings.single { it.language.id == "kotlin" }
        assertEquals(LspStatus.MISSING_CONFIG, kotlin.status)
        assertEquals(
            "claude plugin install kotlin-lsp@claude-plugins-official",
            kotlin.remedy?.command,
        )
    }

    @Test
    fun `配置了但二进制不在则是二进制缺口并给出安装命令`() {
        val report = claudeReport(
            configuredCommands = setOf("jdtls"),
            binaries = mapOf("jdtls" to null),
            cliInstalled = true,
        )

        val java = report.findings.single { it.language.id == "java" }
        assertEquals(LspStatus.MISSING_BINARY, java.status)
        assertEquals("brew install jdtls", java.remedy?.command)
    }

    /** 探测超时时映射为空，不能把「没查到」说成「没安装」。 */
    @Test
    fun `二进制映射里没有该键时状态为无法确定`() {
        val report = claudeReport(setOf("jdtls"), emptyMap(), cliInstalled = true)

        assertEquals(LspStatus.UNKNOWN, report.findings.single { it.language.id == "java" }.status)
    }

    /** 官方没有对应插件的语言（Haskell 等）不该出现在 Claude Code 这一组。 */
    @Test
    fun `没有官方插件的语言不列入`() {
        val report = claudeReport(emptySet(), emptyMap(), cliInstalled = true)

        assertTrue(report.findings.none { it.language.id == "haskell" })
        assertTrue(report.findings.any { it.language.id == "kotlin" })
    }

    @Test
    fun `CLI 未安装时不产出任何缺口`() {
        val report = claudeReport(emptySet(), emptyMap(), cliInstalled = false)

        assertFalse(report.installed)
        assertTrue(report.findings.isEmpty())
    }
}
