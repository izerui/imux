package com.github.izerui.imux.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class CodexLspProbeTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val home get() = temp.root.toPath()

    @Test
    fun `PATH 中可解析的裸命令算有效挂载`() {
        val toml =
            """
            model = "gpt-5"

            [mcp_servers.pi-lens]
            command = "pi-lens-mcp"
            """.trimIndent()

        assertTrue(mountsPiLensMcp(toml, home, mapOf(PI_LENS_MCP_BIN to "/usr/local/bin/pi-lens-mcp")))
    }

    /** command 走 npx 之类的启动器时，真正的标识在 args 里。 */
    @Test
    fun `command 在 args 里且启动器可达算有效挂载`() {
        val toml =
            """
            [mcp_servers.lens]
            command = "npx"
            args = ["-y", "pi-lens-mcp"]
            """.trimIndent()

        assertTrue(mountsPiLensMcp(toml, home, mapOf(NPX_BIN to "/usr/local/bin/npx")))
    }

    @Test
    fun `内联表形式的 mcp_servers`() {
        val toml = """mcp_servers.lens = { command = "pi-lens-mcp" }"""

        assertTrue(mountsPiLensMcp(toml, home, mapOf(PI_LENS_MCP_BIN to "/usr/bin/pi-lens-mcp")))
    }

    @Test
    fun `有效绝对路径算挂载`() {
        val executable = temp.newFile("pi-lens-mcp").toPath()
        assertTrue(executable.toFile().setExecutable(true))
        val toml = "[mcp_servers.lens]\ncommand = \"$executable\""

        assertTrue(mountsPiLensMcp(toml, home, emptyMap()))
    }

    @Test
    fun `配置引用的启动器不可达时不算挂载`() {
        val bare = "[mcp_servers.lens]\ncommand = \"pi-lens-mcp\""
        val launcher = "[mcp_servers.lens]\ncommand = \"npx\"\nargs = [\"pi-lens-mcp\"]"
        val missing = home.resolve("missing/pi-lens-mcp")
        val absolute = "[mcp_servers.lens]\ncommand = \"$missing\""

        assertFalse(mountsPiLensMcp(bare, home, emptyMap()))
        assertFalse(mountsPiLensMcp(launcher, home, emptyMap()))
        assertFalse(mountsPiLensMcp(absolute, home, emptyMap()))
    }

    /** 别的段落里出现这个字符串不能算数，例如注释掉的旧配置。 */
    @Test
    fun `mcp_servers 之外出现的同名字符串不算`() {
        val toml =
            """
            [history]
            note = "pi-lens-mcp"

            [mcp_servers.other]
            command = "some-other-server"
            """.trimIndent()

        assertFalse(mountsPiLensMcp(toml, home, mapOf(PI_LENS_MCP_BIN to "/usr/bin/pi-lens-mcp")))
    }

    @Test
    fun `注释行不算`() {
        val toml =
            """
            [mcp_servers.lens]
            # command = "pi-lens-mcp"
            command = "something-else"
            """.trimIndent()
        val trailingComment =
            "[mcp_servers.lens]\ncommand = \"something-else\" # pi-lens-mcp"

        assertFalse(mountsPiLensMcp(toml, home, mapOf(PI_LENS_MCP_BIN to "/usr/bin/pi-lens-mcp")))
        assertFalse(
            mountsPiLensMcp(trailingComment, home, mapOf("something-else" to "/usr/bin/something-else")),
        )
    }

    @Test
    fun `缺失或损坏的配置算未挂载`() {
        val binaries = mapOf(PI_LENS_MCP_BIN to "/usr/bin/pi-lens-mcp")
        assertFalse(mountsPiLensMcp(null, home, binaries))
        assertFalse(mountsPiLensMcp("", home, binaries))
        assertFalse(mountsPiLensMcp("[[[乱码", home, binaries))
        assertFalse(mountsPiLensMcp("[mcp_servers.x]\ncommand = \"pi-lens-mcp\u0000\"", home, binaries))
        assertFalse(mountsPiLensMcp("""[mcp_servers.x]${"\n"}command = "gopls"""", home, binaries))
    }

    @Test
    fun `未安装 pi-lens 时先安装再用绝对路径挂载`() {
        val executable = standardPiLensMcp(home)
        val report =
            codexReport(
                mounted = false,
                piFindings = emptyList(),
                cliInstalled = true,
                piLensMcpExecutable = executable,
            )

        assertEquals(
            listOf(
                "pi install npm:pi-lens",
                "codex mcp add pi-lens -- '$executable'",
            ),
            report.groupRemedy?.commands,
        )
        assertTrue(canRun(report.groupRemedy!!, isMac = false, hasPosixShell = true))
        assertTrue(report.findings.isEmpty())
    }

    @Test
    fun `标准可执行文件已存在时不重复安装`() {
        val executable = standardPiLensMcp(home)
        Files.createDirectories(executable.parent)
        Files.createFile(executable)
        assertTrue(executable.toFile().setExecutable(true))

        val report = codexReport(false, emptyList(), true, executable)

        assertEquals(listOf("codex mcp add pi-lens -- '$executable'"), report.groupRemedy?.commands)
    }

    @Test
    fun `PATH 中已有可执行文件时直接使用探测到的绝对路径`() {
        val executable = home.resolve("custom/bin/pi-lens-mcp")

        val report =
            codexReport(
                mounted = false,
                piFindings = emptyList(),
                cliInstalled = true,
                piLensMcpExecutable = executable,
                piLensMcpAvailable = true,
            )

        assertEquals(listOf("codex mcp add pi-lens -- '$executable'"), report.groupRemedy?.commands)
    }

    /** 挂载后 Codex 用的是同一套 pi-lens server，语言状态与 pi 完全一致。 */
    @Test
    fun `挂载后复用 pi 的语言结果`() {
        val kotlin = LspCatalog.languages.single { it.id == "kotlin" }
        val piFindings = listOf(LanguageFinding(kotlin, LspStatus.MISSING_BINARY, Remedy(emptyList(), "https://x")))

        val report = codexReport(true, piFindings, true, standardPiLensMcp(home))

        assertEquals(piFindings, report.findings)
        assertEquals(null, report.groupRemedy)
    }

    @Test
    fun `CLI 未安装时不产出任何缺口或建议`() {
        val report = codexReport(false, emptyList(), false, standardPiLensMcp(home))

        assertFalse(report.installed)
        assertTrue(report.findings.isEmpty())
        assertEquals(null, report.groupRemedy)
    }
}
