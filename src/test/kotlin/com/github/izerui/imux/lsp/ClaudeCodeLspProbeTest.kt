package com.github.izerui.imux.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeCodeLspProbeTest {
    private val marketplace =
        """
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
        val settings =
            """
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
        val report =
            claudeReport(
                configuredCommands = setOf("gopls"),
                binaries = mapOf("gopls" to "/Users/demo/go/bin/gopls"),
                cliInstalled = true,
            )

        val go = report.findings.single { it.language.id == "go" }
        assertEquals(LspStatus.READY, go.status)
    }

    /**
     * 本机真实场景：kotlin-lsp 二进制已装，Claude Code 却没启用插件。
     *
     * 链里**只能有装插件这一步**。多一条 `brew install --cask kotlin-lsp` 的话，
     * 用户点一下「启用」会被重新下载一遍已经有的 server（那是 1.3GB 量级的东西）。
     */
    @Test
    fun `二进制在但插件没启用时只跑装插件那一步`() {
        val report =
            claudeReport(
                configuredCommands = emptySet(),
                binaries = mapOf("kotlin-lsp" to "/opt/homebrew/bin/kotlin-lsp", "brew" to "/opt/homebrew/bin/brew"),
                cliInstalled = true,
            )

        val kotlin = report.findings.single { it.language.id == "kotlin" }
        assertEquals(LspStatus.MISSING_CONFIG, kotlin.status)
        assertEquals(
            listOf("claude plugin install kotlin-lsp@claude-plugins-official"),
            kotlin.remedy?.commands,
        )
        // 这条链只有 claude 自己的子命令，跨平台。判成 macOS-only 的话，Windows 与
        // Linux 用户看到的只剩一条要自己复制的命令——而它本来点一下就能跑。
        assertTrue(
            "claude 自己的子命令跨平台，不该被平台闸门挡下",
            canRun(kotlin.remedy!!, isMac = false),
        )
    }

    /**
     * 插件配好了、二进制不在：链里**只有装 server 这一步**，没有装插件那一步。
     *
     * 多跑一条 `claude plugin install` 不会出错，但它是噪音——而这一页刚刚才因为
     * 「让用户看见与他无关的实现细节」被返工过一轮。
     */
    @Test
    fun `配置了但二进制不在时只装 server`() {
        val report =
            claudeReport(
                configuredCommands = setOf("jdtls"),
                binaries = mapOf("jdtls" to null, "brew" to "/opt/homebrew/bin/brew"),
                cliInstalled = true,
            )

        val java = report.findings.single { it.language.id == "java" }
        assertEquals(LspStatus.MISSING_BINARY, java.status)
        assertEquals(listOf("brew install jdtls"), java.remedy?.commands)
        // 判成跨平台的话，`brew install jdtls` 会在 Windows 上也长出一个执行按钮，
        // 点下去就是在没有 brew 的机器上跑 brew。
        assertFalse(
            "brew 系安装命令只在 macOS 上核实过",
            canRun(java.remedy!!, isMac = false),
        )
    }

    /**
     * 三层全缺时链是完整的三步，**顺序固定**。
     *
     * 这是简报里 C# 那个例子在探针这一侧的落点：状态只说得出「没配」，
     * 二进制在不在、工具链在不在都是这一层现问出来的。
     */
    @Test
    fun `三层全缺时给出完整的三步链`() {
        val report =
            claudeReport(
                configuredCommands = emptySet(),
                binaries = mapOf("csharp-ls" to null, "dotnet" to null, "brew" to "/opt/homebrew/bin/brew"),
                cliInstalled = true,
            )

        assertEquals(
            listOf(
                "brew install --cask dotnet-sdk",
                "dotnet tool install --global csharp-ls",
                "claude plugin install csharp-lsp@claude-plugins-official",
            ),
            report.findings
                .single { it.language.id == "csharp" }
                .remedy
                ?.commands,
        )
    }

    /** 探测超时时映射为空，不能把「没查到」说成「没安装」。 */
    @Test
    fun `二进制映射里没有该键时状态为无法确定`() {
        val report = claudeReport(setOf("jdtls"), emptyMap(), cliInstalled = true)

        val java = report.findings.single { it.language.id == "java" }
        assertEquals(LspStatus.UNKNOWN, java.status)
        assertNull(java.remedy)
    }

    @Test
    fun `插件未启用且探测未知时只建议启用插件`() {
        val report = claudeReport(emptySet(), emptyMap(), cliInstalled = true)

        val kotlin = report.findings.single { it.language.id == "kotlin" }
        assertEquals(LspStatus.MISSING_CONFIG, kotlin.status)
        assertEquals(
            listOf("claude plugin install kotlin-lsp@claude-plugins-official"),
            kotlin.remedy?.commands,
        )
    }

    /**
     * 全量列表的核心契约：目录表里有多少门语言，这一组就有多少条 findings。
     *
     * 曾经这里 `.filter { it.claudePlugin != null }`，Haskell / Elixir / OCaml / Nix / F#
     * 于是从 Claude Code 组里静默消失。这条断言就是为了让「加回过滤」当场变红——
     * 只断言某几门语言在不在，加回一个只漏掉其它语言的过滤照样能绿。
     */
    @Test
    fun `列出目录表里的全部语言`() {
        val report = claudeReport(emptySet(), emptyMap(), cliInstalled = true)

        assertEquals(LspCatalog.languages.size, report.findings.size)
        assertEquals(
            LspCatalog.languages.map(LspLanguage::id),
            report.findings.map { it.language.id },
        )
    }

    /**
     * 官方没有对应插件的语言（Haskell 等）照样列出，标成「官方无对应插件」。
     *
     * 从前它们被过滤掉，用户看到的是一份没有说明的短名单，只能自己猜省略了什么。
     * 但**不能给建议**：Claude Code 上不存在「装个插件就能用」的路径，
     * 给一条无法执行的命令比不给更糟。
     */
    @Test
    fun `没有官方插件的语言标记为不可用且不给建议`() {
        val report = claudeReport(emptySet(), emptyMap(), cliInstalled = true)

        val haskell = report.findings.single { it.language.id == "haskell" }
        assertEquals(LspStatus.NOT_AVAILABLE, haskell.status)
        assertNull("没有可执行的动作时给建议就是误导", haskell.remedy)

        // 官方有插件的语言不能被顺手也标成不可用
        assertEquals(LspStatus.MISSING_CONFIG, report.findings.single { it.language.id == "kotlin" }.status)
    }

    /** NOT_AVAILABLE 不是「缺口」：用户对它做不了任何事，计进「待补充 N」只会制造焦虑。 */
    @Test
    fun `不可用的语言不计入缺口`() {
        val report = claudeReport(emptySet(), emptyMap(), cliInstalled = true)

        assertTrue(report.gaps.none { it.status == LspStatus.NOT_AVAILABLE })
        assertTrue(
            "缺口只该是用户真能采取行动的两种状态",
            report.gaps.all {
                it.status == LspStatus.MISSING_CONFIG || it.status == LspStatus.MISSING_BINARY
            },
        )
    }

    @Test
    fun `CLI 未安装时不产出任何缺口`() {
        val report = claudeReport(emptySet(), emptyMap(), cliInstalled = false)

        assertFalse(report.installed)
        assertTrue(report.findings.isEmpty())
    }
}
