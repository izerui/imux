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

    /**
     * 体检失败不能与「进行中」长得一样，也不能把异常吞得连日志都没有。
     *
     * 断言钉的是**分派那一行**而不是 `settings.lsp.failed` 这个标识符：把分派改回
     * `showChecking()` 而把 `showFailed()` 的定义留在原地，缺陷就复活了，
     * 而 Kotlin 对没人调用的 private 函数只报 warning，拦不住。
     */
    @Test
    fun `体检失败要留日志并显示错误态`() {
        assertTrue("异常必须落到 idea.log", source.contains("LOG.warn"))
        assertTrue(
            "失败必须分派到独立的错误态，不能复用「正在检测」",
            source.contains("if (report == null) showFailed() else showReport(report)"),
        )
    }

    /**
     * 每次探测都是一个 `zsh -l -i`。按钮不设防的话连点五次就是五个登录 shell 同时读
     * profile，而且**先发起的可能后返回**，最终显示的会是更旧的结果。
     */
    @Test
    fun `连点重新检测不会叠起多个登录 shell`() {
        assertTrue("探测期间必须禁用按钮", source.contains("refreshButton?.isEnabled = false"))
        // 钉回调里的比对本身：只断言 AtomicInteger 字段声明的话，
        // 把这行守卫删掉、字段留着，竞态就复活了而断言照样绿。
        assertTrue(
            "过期结果不得覆盖最新结果",
            source.contains("if (token == generation.get())"),
        )
    }

    /**
     * Codex 挂了 pi-lens-mcp 但本机没装 pi 时，findings 恒空，四个渲染分支全部落空，
     * 分组里会一行都没有。这是现实组合，必须有兜底行。
     */
    @Test
    fun `没有逐语言结果时也不留空分组`() {
        // 同样钉守卫条件而不是文案键：把条件退化成 `ready.isEmpty()`（gaps 非空时
        // 就再也进不来）或干脆改成永假，兜底行就没了而文案键还在源码里。
        assertTrue(
            "ready 与 gaps 双空时必须走兜底分支",
            source.contains("if (ready.isEmpty() && gaps.isEmpty())"),
        )
        assertTrue(
            "兜底行必须用 comment：label 不折行会撑宽整个设置对话框",
            source.contains("""comment(ImuxBundle.message("settings.lsp.no.findings"))"""),
        )
    }

    /**
     * 组级提示是没装 pi-lens / 没给 Codex 挂 MCP 的用户每次开页都会看到的一行，
     * 葡语 98、德语 90、俄语 93 字符。UI DSL 的 `label` 产出不折行的 JLabel，
     * 其 preferred width 会直接抬高整页最小宽度，把设置对话框撑宽或逼出横向滚动条。
     *
     * 断言钉的是**调用本身**而不是 `groupMessage` 这个标识符：只钉标识符的话，
     * 改回 `label(groupMessage(cliReport))` 断言照样绿，缺陷原样复活。
     */
    @Test
    fun `组级提示必须折行`() {
        assertTrue(
            "组级提示必须用 comment：label 不折行，这一行在多数语种下接近 100 字符",
            source.contains("comment(groupMessage(cliReport))"),
        )
        assertFalse(
            "组级提示不得退回 label",
            source.contains("label(groupMessage(cliReport))"),
        )
    }

    /**
     * 已就绪汇总是全页最长的一行：Claude 组的 ready 上限是 claudePlugin != null 的全集共
     * 13 门，语言显示名又不随语言包变化，所以**任何语种下**都是约 116 字符——比已被判定
     * 「必须折行」的 no.findings 还长，且配置完整的 Claude Code 用户每次都看到。
     *
     * 用 `text` 而不是 `comment`：两者都折行，但 comment 是灰色说明文字，
     * 会把「已就绪」这条正面结论降级成脚注。
     *
     * 汇总字符串刻意抽成 readySummary()，好让这条断言能钉住**整个调用**而不是某个
     * 标识符——只断言源码里出现过 "settings.lsp.ready" 的话，改回 label 断言照样绿。
     */
    @Test
    fun `已就绪汇总行必须折行且不降级成灰字`() {
        assertTrue(
            "已就绪汇总必须折行：全页最长的一行，label 会撑宽整个设置对话框",
            source.contains("text(readySummary(ready))"),
        )
        assertFalse(
            "已就绪汇总不得退回 label",
            source.contains("label(readySummary(ready))"),
        )
        assertFalse(
            "已就绪是正面结论，comment 的灰字会把它降级成脚注",
            source.contains("comment(readySummary(ready))"),
        )
    }

    /** 图标必须用官方语义图标，不自绘。 */
    @Test
    fun `状态图标取自 AllIcons`() {
        assertTrue(source.contains("AllIcons."))
        assertFalse("不得引用自定义 svg", source.contains(".svg"))
    }
}
