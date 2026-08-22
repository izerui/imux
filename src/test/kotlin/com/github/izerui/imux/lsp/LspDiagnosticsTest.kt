package com.github.izerui.imux.lsp

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class LspDiagnosticsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun write(
        relative: String,
        content: String,
    ) {
        val file = temp.root.toPath().resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    /** [installed] 里的 CLI 会在探测结果中带上一个路径，其余带 null（= 确认未安装）。 */
    private fun diagnostics(
        locatedBinaries: Map<String, String?> = emptyMap(),
        installed: Set<AgentType> = AgentType.entries.toSet(),
    ) = LspDiagnostics(
        userHome = temp.root.toPath(),
        binaryProbe =
            object : BinaryProbe {
                override fun locate(binaries: Set<String>): Map<String, String?> =
                    locatedBinaries +
                        AgentType.entries.associate { type ->
                            type.cli to if (type in installed) "/usr/local/bin/${type.cli}" else null
                        }
            },
    )

    private fun LspReport.of(type: AgentType) = cliReports.single { it.agentType == type }

    @Test
    fun `三个 CLI 各产出一份报告`() {
        val report = diagnostics().run()

        assertEquals(3, report.cliReports.size)
        AgentType.entries.forEach { report.of(it) }
    }

    @Test
    fun `读取三个 CLI 的全局配置`() {
        write(".claude/settings.json", """{"lspServers":{"gopls":{"command":"gopls"}}}""")
        write(".pi/agent/settings.json", """{"packages":["npm:pi-lens"]}""")
        write(".codex/config.toml", "[mcp_servers.lens]\ncommand = \"pi-lens-mcp\"")

        val report =
            diagnostics(
                locatedBinaries =
                    mapOf(
                        "gopls" to "/usr/bin/gopls",
                        PI_LENS_MCP_BIN to "/usr/bin/pi-lens-mcp",
                    ),
            ).run()

        assertEquals(
            LspStatus.READY,
            report
                .of(AgentType.CLAUDE)
                .findings
                .single { it.language.id == "go" }
                .status,
        )
        assertEquals("装了 pi-lens 就不该再给整组建议", null, report.of(AgentType.PI).groupRemedy)
        assertEquals("挂了 MCP 就不该再给整组建议", null, report.of(AgentType.CODEX).groupRemedy)
    }

    /** 配置文件一个都不存在是全新机器的正常状态，不能抛异常。 */
    @Test
    fun `配置文件全部缺失时仍产出完整报告`() {
        val report = diagnostics().run()

        assertEquals(listOf("pi install npm:pi-lens"), report.of(AgentType.PI).groupRemedy?.commands)
        assertEquals(
            listOf(
                "pi install npm:pi-lens",
                "codex mcp add pi-lens -- '${standardPiLensMcp(temp.root.toPath())}'",
            ),
            report.of(AgentType.CODEX).groupRemedy?.commands,
        )
        // 全量列表：有官方插件的都是「没配」，官方没插件的（Haskell 等）是「无对应插件」，
        // 两者加起来必须正好是目录表的全部语言——一门都不能被静默省略。
        val claude = report.of(AgentType.CLAUDE)
        assertEquals(LspCatalog.languages.size, claude.findings.size)
        assertTrue(
            claude.findings.all { finding ->
                val expected =
                    if (finding.language.claudePlugin == null) LspStatus.NOT_AVAILABLE else LspStatus.MISSING_CONFIG
                finding.status == expected
            },
        )
    }

    @Test
    fun `未安装的 CLI 整组跳过`() {
        write(".pi/agent/settings.json", """{"packages":["npm:pi-lens"]}""")

        val report = diagnostics(installed = setOf(AgentType.PI)).run()

        assertFalse(report.of(AgentType.CLAUDE).installed)
        assertTrue(report.of(AgentType.CLAUDE).findings.isEmpty())
        assertTrue(report.of(AgentType.PI).installed)
    }

    /**
     * 语言服务器、**它们的安装命令依赖的工具链**、三个 CLI，必须在同一次调用里问完
     * ——登录 shell 只该起一次。
     *
     * 前置工具（brew / go / npm / gem / dotnet / rustup / opam）是这一轮才进探测的。
     * 为它们另起一次探测就是再付一遍读 profile 的钱；而**漏掉不探测**更糟：
     * `isToolPresent` 对一个没被问过的名字只会答「不在」，于是每条命令链都会白白多出
     * 一层 `brew install …`——用户点一下「启用」，先被装一遍已经有的 .NET SDK。
     */
    @Test
    fun `一次探测同时覆盖语言服务器 前置工具链与三个 CLI`() {
        val calls = mutableListOf<Set<String>>()
        LspDiagnostics(
            userHome = temp.root.toPath(),
            binaryProbe =
                object : BinaryProbe {
                    override fun locate(binaries: Set<String>): Map<String, String?> {
                        calls += binaries
                        return emptyMap()
                    }
                },
        ).run()

        assertEquals("只允许探测一次", 1, calls.size)
        assertEquals(
            LspCatalog.allProbeTargets + AgentType.entries.map { it.cli } + setOf(PI_LENS_MCP_BIN, NPX_BIN),
            calls.single(),
        )
        assertTrue(
            "前置工具链必须在这一次里一起问完：漏掉的话每条链都会白白多出一层安装命令",
            calls.single().containsAll(LspCatalog.tools.keys),
        )
    }

    /**
     * 探测超时会返回空映射。此时不能把「没查到」说成「CLI 没装」——
     * 那会让整个页面变成三行「未安装」，而真实情况只是探测失败。
     */
    @Test
    fun `探测结果里没有 CLI 键时视为已安装并逐项标记无法确定`() {
        val report =
            LspDiagnostics(
                userHome = temp.root.toPath(),
                binaryProbe =
                    object : BinaryProbe {
                        override fun locate(binaries: Set<String>): Map<String, String?> = emptyMap()
                    },
            ).run()

        assertTrue("不得因探测失败而谎报未安装", report.of(AgentType.CLAUDE).installed)
    }
}
