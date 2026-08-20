package com.github.izerui.imux.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexLspProbeTest {

    @Test
    fun `标准段落形式的 mcp_servers`() {
        val toml = """
            model = "gpt-5"

            [mcp_servers.pi-lens]
            command = "pi-lens-mcp"
        """.trimIndent()

        assertTrue(mountsPiLensMcp(toml))
    }

    /** command 走 npx 之类的启动器时，真正的标识在 args 里。 */
    @Test
    fun `command 在 args 里也算挂载`() {
        val toml = """
            [mcp_servers.lens]
            command = "npx"
            args = ["-y", "pi-lens-mcp"]
        """.trimIndent()

        assertTrue(mountsPiLensMcp(toml))
    }

    @Test
    fun `内联表形式的 mcp_servers`() {
        assertTrue(mountsPiLensMcp("""mcp_servers.lens = { command = "pi-lens-mcp" }"""))
    }

    /** 别的段落里出现这个字符串不能算数——比如注释掉的旧配置。 */
    @Test
    fun `mcp_servers 之外出现的同名字符串不算`() {
        val toml = """
            [history]
            note = "pi-lens-mcp"

            [mcp_servers.other]
            command = "some-other-server"
        """.trimIndent()

        assertFalse(mountsPiLensMcp(toml))
    }

    @Test
    fun `注释行不算`() {
        val toml = """
            [mcp_servers.lens]
            # command = "pi-lens-mcp"
            command = "something-else"
        """.trimIndent()

        assertFalse(mountsPiLensMcp(toml))
    }

    @Test
    fun `缺失或损坏的配置算未挂载`() {
        assertFalse(mountsPiLensMcp(null))
        assertFalse(mountsPiLensMcp(""))
        assertFalse(mountsPiLensMcp("[[[乱码"))
        assertFalse(mountsPiLensMcp("""[mcp_servers.x]${"\n"}command = "gopls""""))
    }

    @Test
    fun `未挂载时给出挂载命令且不列逐语言缺口`() {
        val report = codexReport(mounted = false, piFindings = emptyList(), cliInstalled = true)

        assertEquals("codex mcp add pi-lens -- pi-lens-mcp", report.groupRemedy?.command)
        assertTrue(report.findings.isEmpty())
    }

    /** 挂载后 Codex 用的是同一套 pi-lens server，语言状态与 pi 完全一致。 */
    @Test
    fun `挂载后复用 pi 的语言结果`() {
        val kotlin = LspCatalog.languages.single { it.id == "kotlin" }
        val piFindings = listOf(LanguageFinding(kotlin, LspStatus.MISSING_BINARY, Remedy(null, "https://x")))

        val report = codexReport(mounted = true, piFindings = piFindings, cliInstalled = true)

        assertEquals(piFindings, report.findings)
        assertEquals(null, report.groupRemedy)
    }

    @Test
    fun `CLI 未安装时不产出任何缺口或建议`() {
        val report = codexReport(mounted = false, piFindings = emptyList(), cliInstalled = false)

        assertFalse(report.installed)
        assertTrue(report.findings.isEmpty())
        assertEquals(null, report.groupRemedy)
    }
}
