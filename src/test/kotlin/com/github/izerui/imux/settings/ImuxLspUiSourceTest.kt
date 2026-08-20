package com.github.izerui.imux.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UI 无法在本项目里跑起来做行为测试（未引入平台 test-framework，见 build.gradle.kts），
 * 因此对源码做结构断言，守住几条一旦破坏就只能在真机上才发现的约定。
 */
class ImuxLspUiSourceTest {
    private val source: String by lazy {
        File("src/main/kotlin/com/github/izerui/imux/settings/ImuxLspConfigurable.kt").readText()
    }

    /**
     * shell 探测要起登录 shell 读 profile，绝不能落在 EDT 上——
     * 与 PiReportEndpointCache 记录的是同一类教训。
     */
    @Test
    fun `体检在后台执行且回到 EDT 刷新`() {
        assertTrue("探测必须放到后台线程", source.contains("executeOnPooledThread"))
        assertTrue("刷新 UI 必须回到 EDT", source.contains("invokeLater"))
    }

    /**
     * CLI 是否安装必须并进那一次批量探测。页面里凡是另起 ProcessBuilder 的，
     * 都是又一个登录 shell——读一遍 profile 的钱要重付一次。
     */
    @Test
    fun `设置页自己不起 shell 进程`() {
        assertFalse("CLI 探测应并入 BinaryProbe，不在页面里另起进程", source.contains("ProcessBuilder"))
    }

    @Test
    fun `页面是只读的`() {
        assertTrue("体检页没有可保存状态", source.contains("override fun isModified(): Boolean = false"))
    }

    @Test
    fun `按 CLI 分组并给出重新检测入口`() {
        assertTrue(source.contains("settings.lsp.refresh"))
        assertTrue(source.contains("settings.lsp.checking"))
        assertTrue("必须说明只检查全局配置", source.contains("settings.lsp.scope.note"))
    }

    @Test
    fun `复制按钮走平台剪贴板`() {
        assertTrue(source.contains("CopyPasteManager"))
        assertTrue(source.contains("settings.lsp.copy"))
    }

    /**
     * 回 EDT 的模态是这一页唯一「三道现有防线全都发现不了」的约定：
     * 设置对话框弹出前的默认模态是 NON_MODAL，无参 `invokeLater` 会被压到关窗之后才派发，
     * 页面停在「正在检测…」却毫不卡顿——编译期查不出，buildSearchableOptions 查不出，
     * 人工「界面不卡顿」那一项照样判通过。守住它的只有这一行断言。
     */
    @Test
    fun `回 EDT 必须显式指定模态`() {
        assertTrue(
            "invokeLater 必须带 ModalityState.any()，否则设置对话框开着时根本不会刷新",
            source.contains("ModalityState.any()"),
        )
    }

    /**
     * pi 的组级修复**只在 pi-lens 未安装时**才存在（见 piReport），
     * 所以这里说的必须是「未安装」。曾经错用 settings.lsp.pi.auto（「pi-lens 已安装」），
     * 页面于是在体检唯一该说真话的地方说了反话，且后面跟着一条 `pi install` 命令自相矛盾。
     */
    @Test
    fun `pi 的组级提示说的是 pi-lens 未安装`() {
        assertTrue(
            "groupRemedy 分支只在 pi-lens 未安装时出现，不能说成「已安装」",
            source.contains("""else -> ImuxBundle.message("settings.lsp.pi.missing")"""),
        )
        assertTrue(
            "「pi-lens 已安装、其余语言自动覆盖」是缺口列表的前置说明，不是组级修复的说明",
            source.contains("""comment(ImuxBundle.message("settings.lsp.pi.auto"))"""),
        )
    }

    /** 体检失败不能与「进行中」长得一样，也不能把异常吞得连日志都没有。 */
    @Test
    fun `体检失败要留日志并显示错误态`() {
        assertTrue("异常必须落到 idea.log", source.contains("LOG.warn"))
        assertTrue("失败要有独立文案，不能复用「正在检测」", source.contains("settings.lsp.failed"))
    }

    /**
     * 每次探测都是一个 `zsh -l -i`。按钮不设防的话连点五次就是五个登录 shell 同时读
     * profile，而且**先发起的可能后返回**，最终显示的会是更旧的结果。
     */
    @Test
    fun `连点重新检测不会叠起多个登录 shell`() {
        assertTrue("探测期间必须禁用按钮", source.contains("refreshButton?.isEnabled = false"))
        assertTrue("过期结果不得覆盖最新结果", source.contains("AtomicInteger"))
    }

    /**
     * Codex 挂了 pi-lens-mcp 但本机没装 pi 时，findings 恒空，四个渲染分支全部落空，
     * 分组里会一行都没有。这是现实组合，必须有兜底行。
     */
    @Test
    fun `没有逐语言结果时也不留空分组`() {
        assertTrue(source.contains("settings.lsp.no.findings"))
    }

    /** 图标必须用官方语义图标，不自绘。 */
    @Test
    fun `状态图标取自 AllIcons`() {
        assertTrue(source.contains("AllIcons."))
        assertFalse("不得引用自定义 svg", source.contains(".svg"))
    }
}
