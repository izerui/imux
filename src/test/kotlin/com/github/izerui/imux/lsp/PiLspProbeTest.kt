package com.github.izerui.imux.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiLspProbeTest {

    @Test
    fun `packages 里有 pi-lens`() {
        assertTrue(hasPiLens("""{"packages":["npm:pi-lens"],"theme":"dark"}"""))
    }

    /** pi 允许写死版本，`pi install npm:pi-lens@4.0.1` 会原样落进 packages。 */
    @Test
    fun `带版本号的写法也算装了`() {
        assertTrue(hasPiLens("""{"packages":["npm:pi-lens@4.0.1"]}"""))
    }

    /** 名字前缀相同的另一个包不能被误认。 */
    @Test
    fun `前缀相同的其他包不算`() {
        assertFalse(hasPiLens("""{"packages":["npm:pi-lens-extras"]}"""))
        assertFalse(hasPiLens("""{"packages":["npm:pi-lensify"]}"""))
    }

    @Test
    fun `没装或配置损坏都算没装`() {
        assertFalse(hasPiLens(null))
        assertFalse(hasPiLens(""))
        assertFalse(hasPiLens("这不是 json"))
        assertFalse(hasPiLens("""{"packages":[]}"""))
        assertFalse(hasPiLens("""{"packages":"应该是数组"}"""))
        assertFalse(hasPiLens("""{"theme":"dark"}"""))
    }

    /** 没装 pi-lens 时逐语言的缺口没有意义，先把前置补上。 */
    @Test
    fun `未装 pi-lens 时给出整组修复建议且不列逐语言缺口`() {
        val report = piReport(piLensInstalled = false, binaries = emptyMap(), cliInstalled = true)

        assertEquals("pi install npm:pi-lens", report.groupRemedy?.command)
        // 标成 INSTALL 的话，这条跨平台的 pi 子命令会在非 macOS 上被闸掉，
        // 而它恰恰是没装 pi-lens 的用户唯一要做的那件事。
        assertEquals(
            "pi 自己的子命令跨平台，必须是 ACTIVATE",
            RemedyKind.ACTIVATE,
            report.groupRemedy?.kind,
        )
        assertTrue(report.findings.isEmpty())
    }

    /**
     * 全量列表的核心契约：目录表里有多少门语言，这一组就有多少条 findings。
     *
     * 曾经这里 `.filter(LspLanguage::piLensGated)`，TypeScript / Python / Ruby / Rust /
     * PHP / C# 于是从 pi 组里静默消失，真实用户据此得出「pi 不支持 TypeScript LSP」。
     * 这条断言就是为了让「加回过滤」当场变红——只断言某几门语言在不在的话，
     * 加回一个只漏掉其它语言的过滤照样能绿。
     */
    @Test
    fun `列出目录表里的全部语言`() {
        val report = piReport(piLensInstalled = true, binaries = emptyMap(), cliInstalled = true)

        assertEquals(LspCatalog.languages.size, report.findings.size)
        assertEquals(
            LspCatalog.languages.map(LspLanguage::id),
            report.findings.map { it.language.id },
        )
    }

    /**
     * 非 gated 语言由 pi-lens 按需自动安装，标成 AUTO_MANAGED 且**不给建议**。
     *
     * 这里刻意不查 PATH：pi-lens 是懒安装的，装在哪也不归 imux 管。在 PATH 里 pi-lens
     * 直接用、不在 PATH 里 pi-lens 按需装，两种情况最终都可用——所以传进一个明确
     * 「不在 PATH」的映射，结论也必须还是 AUTO_MANAGED，绝不能退化成「未安装」。
     */
    @Test
    fun `非 gated 语言标记为自动管理且不给建议`() {
        val report = piReport(
            piLensInstalled = true,
            binaries = mapOf("typescript-language-server" to null, "pyright-langserver" to null),
            cliInstalled = true,
        )

        listOf("typescript", "python", "ruby", "rust", "php", "csharp").forEach { id ->
            val finding = report.findings.single { it.language.id == id }
            assertEquals("$id 由 pi-lens 按需安装，查 PATH 的结果与真相无关", LspStatus.AUTO_MANAGED, finding.status)
            assertNull("$id 没有任何用户可执行的动作，给建议就是误导", finding.remedy)
        }
        // gated 的那些不能被顺手也标成自动管理
        assertEquals(LspStatus.UNKNOWN, report.findings.single { it.language.id == "kotlin" }.status)
    }

    /** AUTO_MANAGED 不是「缺口」：它是好消息，计进「待补充 N」等于把好消息说成坏消息。 */
    @Test
    fun `自动管理的语言不计入缺口`() {
        val report = piReport(piLensInstalled = true, binaries = emptyMap(), cliInstalled = true)

        assertTrue(report.gaps.none { it.status == LspStatus.AUTO_MANAGED })
        assertTrue(
            "缺口只该是用户真能采取行动的两种状态",
            report.gaps.all {
                it.status == LspStatus.MISSING_CONFIG || it.status == LspStatus.MISSING_BINARY
            },
        )
    }

    @Test
    fun `gated 语言的二进制在则就绪`() {
        val report = piReport(
            piLensInstalled = true,
            binaries = mapOf("gopls" to "/Users/demo/go/bin/gopls"),
            cliInstalled = true,
        )

        assertEquals(LspStatus.READY, report.findings.single { it.language.id == "go" }.status)
    }

    /** 本机真实场景：pi-lens 装了，Kotlin 的 server 没装，状态栏显示 LSP Inactive。 */
    @Test
    fun `gated 语言的二进制不在则是二进制缺口`() {
        val report = piReport(
            piLensInstalled = true,
            binaries = mapOf("kotlin-language-server" to null),
            cliInstalled = true,
        )

        val kotlin = report.findings.single { it.language.id == "kotlin" }
        assertEquals(LspStatus.MISSING_BINARY, kotlin.status)
        assertNotNull("没有已知安装命令时也要给文档链接", kotlin.remedy?.docsUrl)
    }

    /**
     * 二进制缺口的修复走的是目录表里的安装命令，它们只在 macOS 上核实过。
     *
     * 用 go 而不是 kotlin：kotlin-language-server 的 installCommand 是 null，
     * 就算 kind 标错也没有按钮长出来，测不到这条闸门真正要拦的东西。
     * 标成 ACTIVATE 的话，Windows 用户会看到「安装」按钮，点下去执行 `go install`。
     */
    @Test
    fun `二进制缺口的安装命令是只在 macOS 验证过的那一类`() {
        val report = piReport(
            piLensInstalled = true,
            binaries = mapOf("gopls" to null),
            cliInstalled = true,
        )

        val go = report.findings.single { it.language.id == "go" }
        assertEquals("go install golang.org/x/tools/gopls@latest", go.remedy?.command)
        assertEquals(RemedyKind.INSTALL, go.remedy?.kind)
    }

    @Test
    fun `二进制映射里没有该键时状态为无法确定`() {
        val report = piReport(piLensInstalled = true, binaries = emptyMap(), cliInstalled = true)

        assertEquals(LspStatus.UNKNOWN, report.findings.single { it.language.id == "go" }.status)
    }

    @Test
    fun `CLI 未安装时不产出任何缺口或建议`() {
        val report = piReport(piLensInstalled = false, binaries = emptyMap(), cliInstalled = false)

        assertFalse(report.installed)
        assertTrue(report.findings.isEmpty())
        assertEquals(null, report.groupRemedy)
    }
}
