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

    /**
     * [installed] 里的 CLI 会在探测结果中带上一个路径，其余带 null（= 确认未安装）。
     *
     * [handshake] 必须注入：默认实参是真去 spawn 进程的 [spawnMcpHandshake]，
     * 留给单测会让结果取决于跑测试这台机器上装没装 pi-lens。`isWindows` 同理钉成
     * false——默认实参读的是 `SystemInfo`，会让 Codex 那条建议的路径后缀随跑测试的
     * 机器变化。
     */
    private fun diagnostics(
        locatedBinaries: Map<String, String?> = emptyMap(),
        installed: Set<AgentType> = AgentType.entries.toSet(),
        handshake: (List<String>) -> Boolean = { true },
    ) = LspDiagnostics(
        isWindows = false,
        userHome = temp.root.toPath(),
        binaryProbe =
            object : BinaryProbe {
                override fun locate(binaries: Set<String>): Map<String, String?> =
                    locatedBinaries +
                        AgentType.entries.associate { type ->
                            type.cli to if (type in installed) "/usr/local/bin/${type.cli}" else null
                        }
            },
        handshake = handshake,
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

    /**
     * 本次 Codex 启动报错的完整形态。
     *
     * 配置里挂着一个真实存在、可执行的 `pi-lens-mcp`，但它被 spawn 后因缺
     * host-provided 依赖立刻退出。只验文件位会报「已挂载 ✓」，用户看到一切正常，
     * 而 Codex 每次启动都在拉起一个必崩的进程。
     */
    @Test
    fun `挂载的 server 起不来时报未挂载并给出修复建议`() {
        val broken = temp.root.toPath().resolve("broken/pi-lens-mcp")
        Files.createDirectories(broken.parent)
        Files.createFile(broken)
        assertTrue(broken.toFile().setExecutable(true))
        write(".codex/config.toml", "[mcp_servers.pi-lens]\ncommand = \"$broken\"")

        val report = diagnostics(handshake = { false }).run()

        assertEquals(
            listOf(
                "npm --prefix '${codexPiLensHome(temp.root.toPath())}' i pi-lens typebox @earendil-works/pi-tui",
                "codex mcp add pi-lens -- '${codexPiLensMcp(temp.root.toPath(), isWindows = false)}'",
            ),
            report.of(AgentType.CODEX).groupRemedy?.commands,
        )
    }

    /**
     * 隔离安装之后，Codex 那份 pi-lens 与 pi 那份彼此独立。
     *
     * pi 侧没装 pi-lens，不该让一个挂载成功的 Codex 显示成「一门语言都没有」——
     * 它跑的是自己 `~/.codex/mcp/pi-lens` 下那份，pi 装没装与它无关。
     */
    @Test
    fun `pi 未装 pi-lens 不影响已挂载 Codex 的语言列表`() {
        val codexOwned = temp.root.toPath().resolve("codex/pi-lens-mcp")
        Files.createDirectories(codexOwned.parent)
        Files.createFile(codexOwned)
        assertTrue(codexOwned.toFile().setExecutable(true))
        write(".codex/config.toml", "[mcp_servers.pi-lens]\ncommand = \"$codexOwned\"")
        // 刻意不写 .pi/agent/settings.json ⟹ pi 侧未装 pi-lens

        val report = diagnostics(handshake = { true }).run()

        assertEquals("pi 未装 pi-lens", listOf("pi install npm:pi-lens"), report.of(AgentType.PI).groupRemedy?.commands)
        assertEquals("Codex 已挂载，不该再给建议", null, report.of(AgentType.CODEX).groupRemedy)
        assertEquals(LspCatalog.languages.size, report.of(AgentType.CODEX).findings.size)
    }

    /**
     * npm 是隔离安装那条链的第一步，必须真的被探测到、并且结果传得到 Codex 那一组。
     *
     * 这条钉的是接线而不是判据：`codexReport` 自己的两条分支已由 CodexLspProbeTest
     * 覆盖，这里防的是 npm 压根没进 `allProbeTargets`、或 `located` 没传下去——那样
     * 闸门永远看到 UNKNOWN，等于不存在。
     */
    @Test
    fun `npm 缺失时 Codex 组给出被挡下的修复`() {
        val report = diagnostics(locatedBinaries = mapOf("npm" to null)).run()

        val remedy = report.of(AgentType.CODEX).groupRemedy!!
        assertEquals(emptyList<String>(), remedy.commands)
        assertEquals("npm", remedy.blockingTool)
    }

    /** 同一份配置，server 真起得来时就不该再给建议——否则修好了也一直催。 */
    @Test
    fun `挂载的 server 起得来时不再给建议`() {
        val working = temp.root.toPath().resolve("working/pi-lens-mcp")
        Files.createDirectories(working.parent)
        Files.createFile(working)
        assertTrue(working.toFile().setExecutable(true))
        write(".codex/config.toml", "[mcp_servers.pi-lens]\ncommand = \"$working\"")

        val report = diagnostics(handshake = { true }).run()

        assertEquals(null, report.of(AgentType.CODEX).groupRemedy)
    }

    /** 配置文件一个都不存在是全新机器的正常状态，不能抛异常。 */
    @Test
    fun `配置文件全部缺失时仍产出完整报告`() {
        val report = diagnostics().run()

        assertEquals(listOf("pi install npm:pi-lens"), report.of(AgentType.PI).groupRemedy?.commands)
        assertEquals(
            listOf(
                "npm --prefix '${codexPiLensHome(temp.root.toPath())}' i pi-lens typebox @earendil-works/pi-tui",
                "codex mcp add pi-lens -- '${codexPiLensMcp(temp.root.toPath(), isWindows = false)}'",
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
