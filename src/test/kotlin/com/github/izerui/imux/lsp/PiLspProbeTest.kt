package com.github.izerui.imux.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        assertTrue(report.findings.isEmpty())
    }

    /** 非 gated 语言由 pi-lens 自动安装 server，不需要体检，也不该占版面。 */
    @Test
    fun `装了 pi-lens 后只列 toolchain-gated 语言`() {
        val report = piReport(piLensInstalled = true, binaries = emptyMap(), cliInstalled = true)

        assertTrue(report.findings.all { it.language.piLensGated })
        assertTrue(report.findings.any { it.language.id == "kotlin" })
        assertTrue("Python 由 pi-lens 自动安装，不该出现", report.findings.none { it.language.id == "python" })
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
