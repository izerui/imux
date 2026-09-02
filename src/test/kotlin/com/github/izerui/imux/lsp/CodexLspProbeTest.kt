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

    /**
     * pi 自己那份 pi-lens 安装的 `.bin` 路径。
     *
     * 只有测试还需要它——用来把「pi 侧装了 pi-lens」这个状态造出来，验证 Codex 的
     * 修复建议不再随它漂移。生产代码不能再指向这里：那份安装缺 host-provided 依赖，
     * 被 Codex 当独立子进程 spawn 必崩。
     */
    private fun piOwnedPiLensMcp(userHome: java.nio.file.Path) =
        userHome.resolve(".pi/agent/npm/node_modules/.bin/$PI_LENS_MCP_BIN")

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

        assertTrue(mountsPiLensMcp(toml, home, emptyMap()) { true })
    }

    /**
     * 文件可执行 ≠ server 起得来。
     *
     * pi-lens 把 `typebox` 与 `@earendil-works/pi-tui` 列为 optional peer 交给宿主
     * 解析（`scripts/lib/host-provided-deps.mjs`），pi 之外的宿主直接 spawn 会
     * `ERR_MODULE_NOT_FOUND` 立即退出——而文件位始终是可执行的。只验文件位就会报
     * 「已挂载 ✓」，Codex 每次启动却在拉起一个必崩的进程。
     */
    @Test
    fun `可执行但起不来的 server 不算挂载`() {
        val executable = temp.newFile("pi-lens-mcp").toPath()
        assertTrue(executable.toFile().setExecutable(true))
        val toml = "[mcp_servers.lens]\ncommand = \"$executable\""

        assertFalse(mountsPiLensMcp(toml, home, emptyMap()) { false })
    }

    /**
     * 启动器形态必须连 args 一起交给握手。
     *
     * `command = "/usr/bin/npx"` + `args = ["-y", "pi-lens-mcp"]` 只 spawn 那个 npx，
     * 等于拿半条命令去判活：npx 不带参数根本不会应答 `initialize`，一份完全正常的
     * 配置会被判成损坏，然后催用户去「修」一个没坏的东西。
     */
    @Test
    fun `启动器形态把完整启动命令交给握手`() {
        val npx = temp.newFile("npx").toPath()
        assertTrue(npx.toFile().setExecutable(true))
        val toml = "[mcp_servers.lens]\ncommand = \"$npx\"\nargs = [\"-y\", \"pi-lens-mcp\"]"
        var received: List<String>? = null

        mountsPiLensMcp(toml, home, emptyMap()) { argv ->
            received = argv
            true
        }

        assertEquals(listOf(npx.toString(), "-y", "pi-lens-mcp"), received)
    }

    /**
     * pi-lens 4.1.3 曾把 server.js 发布成 0644。Node 读取脚本不要求执行位，
     * 因此新配置必须把脚本放在 args，而不是继续直接 spawn `.bin/pi-lens-mcp`。
     */
    @Test
    fun `Node 启动方式把不可执行的 server 脚本作为参数握手`() {
        val node = temp.newFile("node").toPath()
        assertTrue(node.toFile().setExecutable(true))
        val script = home.resolve(".codex/mcp/pi-lens/node_modules/pi-lens/dist/mcp/server.js")
        Files.createDirectories(script.parent)
        Files.createFile(script)
        val toml = "[mcp_servers.pi-lens]\ncommand = \"$node\"\nargs = [\"$script\"]"
        var received: List<String>? = null

        mountsPiLensMcp(toml, home, emptyMap()) { argv ->
            received = argv
            true
        }

        assertEquals(listOf(node.toString(), script.toString()), received)
    }

    /** 裸命令同样要握手——PATH 解析出的绝对路径就是可 spawn 的完整命令。 */
    @Test
    fun `裸命令用 PATH 解析出的绝对路径握手`() {
        val toml = "[mcp_servers.lens]\ncommand = \"pi-lens-mcp\""
        var received: List<String>? = null

        mountsPiLensMcp(toml, home, mapOf(PI_LENS_MCP_BIN to "/usr/local/bin/pi-lens-mcp")) { argv ->
            received = argv
            true
        }

        assertEquals(listOf("/usr/local/bin/pi-lens-mcp"), received)
    }

    /**
     * PATH 里那个也可能是坏的。
     *
     * 把 pi 的私有 `.bin` 加进 PATH 是常见做法，而那份安装恰恰缺 host-provided 依赖。
     * 「PATH 里查得到」只说明文件在，不说明它起得来。
     */
    @Test
    fun `PATH 中的损坏安装不算挂载`() {
        val toml = "[mcp_servers.lens]\ncommand = \"pi-lens-mcp\""

        assertFalse(mountsPiLensMcp(toml, home, mapOf(PI_LENS_MCP_BIN to "/usr/local/bin/pi-lens-mcp")) { false })
    }

    /** 本机实测的真实成功响应，逐字节取自 `pi-lens-mcp` 0.1.0。 */
    @Test
    fun `带 result 的 initialize 响应算握手成功`() {
        val stdout =
            """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05",""" +
                """"capabilities":{"tools":{}},"serverInfo":{"name":"pi-lens-mcp","version":"0.1.0"}}}"""

        assertTrue(handshakeSucceeded(stdout))
    }

    /**
     * 崩溃时 stdout 一个字节都没有——`ERR_MODULE_NOT_FOUND` 整段走的是 stderr。
     * 这正是本次 Codex 启动报错的现场形态。
     */
    @Test
    fun `崩溃退出没有任何响应算握手失败`() {
        assertFalse(handshakeSucceeded(""))
        assertFalse(handshakeSucceeded("\n\n"))
    }

    @Test
    fun `error 响应算握手失败`() {
        val stdout = """{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Method not found"}}"""

        assertFalse(handshakeSucceeded(stdout))
    }

    /**
     * server 启动时往 stdout 混入的非 JSON 噪音不能被当成握手成功。
     *
     * pi-lens 自己把 `console.log` 改道到了 stderr 正是为了这个，但 imux 不能假定
     * 每个被挂上来的 server 都这么自律。
     */
    @Test
    fun `非 JSON 噪音不算握手成功`() {
        assertFalse(handshakeSucceeded("[pi-lens-mcp] ready (cwd=/tmp)\nlistening on socket\n"))
    }

    /** 噪音与真响应混在一起时，仍要认出那一行真响应。 */
    @Test
    fun `噪音之后的真响应仍算握手成功`() {
        val stdout =
            "some startup banner\n" +
                """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","capabilities":{}}}"""

        assertTrue(handshakeSucceeded(stdout))
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

    /**
     * 修复建议必须产出一份**自包含**的安装，而不是复用 pi 的那份。
     *
     * `npm --prefix` 会自行创建目录，所以不需要先 mkdir；把 `typebox` 与
     * `@earendil-works/pi-tui` 一并装进来，正是 pi 宿主替 pi-lens 解析的那两个
     * host-provided 包（pi-lens `scripts/lib/host-provided-deps.mjs`）。
     */
    @Test
    fun `未挂载时给出自包含的隔离安装与挂载`() {
        val node = "/usr/local/bin/node"
        val report =
            codexReport(
                mounted = false,
                binaries = mapOf(NODE_BIN to node),
                cliInstalled = true,
                userHome = home,
            )

        val dir = home.resolve(".codex/mcp/pi-lens")
        val script = dir.resolve("node_modules/pi-lens/dist/mcp/server.js")
        assertEquals(
            listOf(
                "npm --prefix '$dir' i pi-lens typebox @earendil-works/pi-tui",
                "codex mcp add pi-lens -- '$node' '$script'",
            ),
            report.groupRemedy?.commands,
        )
        assertTrue(canRun(report.groupRemedy!!, isMac = false))
        assertTrue(report.findings.isEmpty())
    }

    /**
     * Windows 同样由 Node 启动包内脚本，不再依赖 npm 生成的 `.cmd` 包装器。
     */
    @Test
    fun `Windows 挂载命令由 Node 启动 server 脚本`() {
        val node = "C:\\Program Files\\nodejs\\node.exe"
        val report = codexReport(false, mapOf(NODE_BIN to node), true, home)

        val script = codexPiLensServerScript(home)
        assertEquals("codex mcp add pi-lens -- '$node' '$script'", report.groupRemedy?.commands?.last())
    }

    /**
     * npm 确认不在 PATH 时，安装那条命令跑下去必然是 `npm: command not found`。
     *
     * 目录表把 npm 登记成 [LspTool]`(installCommand = null)`——安装方式牵扯 nvm/asdf
     * 与系统包管理器，imux 猜一条命令下去坏的是用户的开发环境。这正是 `blockingTool`
     * 服务的情形：命令清空，改指 Node.js 官网。旧建议用的是 `pi install`（pi 在就能跑），
     * 换成 npm 之后这道闸门才成为必需。
     */
    @Test
    fun `npm 确认缺失时挡下安装修复`() {
        val report = codexReport(false, mapOf(NPM_BIN to null), true, home)

        val remedy = report.groupRemedy!!
        assertEquals(emptyList<String>(), remedy.commands)
        assertEquals("npm", remedy.blockingTool)
        assertEquals(LspCatalog.tool("npm")!!.docsUrl, remedy.docsUrl)
        // 闸门真正的出口：按钮压根渲染不出来，退化成 fallbackCell 的「要先装什么」+ 官网。
        assertFalse("命令必然失败，按钮不能可点", canRun(remedy, isMac = true))
    }

    /**
     * 探测超时时键根本不存在（UNKNOWN），那不是「没有 npm」。
     *
     * 与 `LspDiagnostics.isInstalled` 同一把尺子：只有**确认查过且没查到**才算缺失。
     * 把 UNKNOWN 也挡掉，会在一次 shell 探测超时后把唯一能点的按钮也收走。
     */
    @Test
    fun `npm 探测结果未知时不挡`() {
        val remedy = codexReport(false, emptyMap(), true, home).groupRemedy!!

        assertTrue(remedy.commands.isNotEmpty())
        assertEquals(null, remedy.blockingTool)
    }

    @Test
    fun `Node 确认缺失时挡下挂载修复`() {
        val remedy = codexReport(false, mapOf(NODE_BIN to null), true, home).groupRemedy!!

        assertEquals(emptyList<String>(), remedy.commands)
        assertEquals(NODE_BIN, remedy.blockingTool)
    }

    /** 安装那条命令只由 npm 与目录决定，不受 Node 路径影响。 */
    @Test
    fun `安装命令不随 Node 路径变化`() {
        val posix = codexReport(false, mapOf(NODE_BIN to "/usr/bin/node"), true, home).groupRemedy!!.commands.first()
        val windows =
            codexReport(false, mapOf(NODE_BIN to "C:\\Program Files\\nodejs\\node.exe"), true, home)
                .groupRemedy!!
                .commands
                .first()

        assertEquals(posix, windows)
    }

    /**
     * 绝不能把 Codex 指向 pi 的私有 `.bin`。
     *
     * 那份安装缺 host-provided 依赖，pi 宿主内加载没事，被 Codex 当独立子进程 spawn
     * 就是 `ERR_MODULE_NOT_FOUND` 立即退出——这正是这次 Codex 启动报错的成因。
     */
    @Test
    fun `修复建议不指向 pi 的私有 bin`() {
        val commands = codexReport(false, emptyMap(), true, home).groupRemedy!!.commands

        assertFalse(commands.any { it.contains(".pi/agent/npm") })
    }

    /**
     * 隔离安装与 pi 侧装没装 pi-lens 无关，建议恒定。
     *
     * 旧实现按 pi 的 `.bin` 是否存在来决定要不要先 `pi install npm:pi-lens`，
     * 于是同一台机器上的建议会随 pi 的状态漂移；隔离安装之后这条耦合必须断掉。
     */
    @Test
    fun `修复建议不随 pi 侧安装状态变化`() {
        val withoutPiLens = codexReport(false, emptyMap(), true, home).groupRemedy?.commands

        val piLensBin = piOwnedPiLensMcp(home)
        Files.createDirectories(piLensBin.parent)
        Files.createFile(piLensBin)
        assertTrue(piLensBin.toFile().setExecutable(true))

        assertEquals(withoutPiLens, codexReport(false, emptyMap(), true, home).groupRemedy?.commands)
    }

    /**
     * 挂载后 Codex 跑的是同一个 pi-lens 扩展，语言状态按同一张能力矩阵**自己算**。
     *
     * 关键是「自己算」：旧实现直接照抄 pi 那份报告的 findings，于是 pi 侧没装 pi-lens
     * 时会把空列表一并抄过来，一个挂载成功的 Codex 显示成一门语言都没有。
     */
    @Test
    fun `挂载后按 pi-lens 能力矩阵给出语言状态`() {
        val gated = LspCatalog.languages.first { it.piLensBinary != null }
        val binary = gated.piLensBinary!!

        val report = codexReport(true, mapOf(binary to "/usr/bin/$binary"), true, home)

        assertEquals(null, report.groupRemedy)
        assertEquals(LspCatalog.languages.size, report.findings.size)
        assertEquals(LspStatus.READY, report.findings.single { it.language.id == gated.id }.status)
    }

    @Test
    fun `CLI 未安装时不产出任何缺口或建议`() {
        val report = codexReport(false, emptyMap(), false, home)

        assertFalse(report.installed)
        assertTrue(report.findings.isEmpty())
        assertEquals(null, report.groupRemedy)
    }
}
