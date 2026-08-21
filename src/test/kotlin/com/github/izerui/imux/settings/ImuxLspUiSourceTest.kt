package com.github.izerui.imux.settings

import org.junit.Assert.assertEquals
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
     * 空白归一后的源码，供需要钉住**整段结构**（而不是单行）的断言使用。
     *
     * 断言一旦跨行，就会连缩进和换行位置一起钉死，重新格式化一次就误报。
     * 归一后既能钉住整个循环体，又不在意它排成几行。
     */
    private val normalized: String by lazy { source.replace(Regex("""\s+"""), " ") }

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
        // settings.lsp.pi.auto 那句补丁式说明随全量列表一起删了（10 个语言文件都删了）：
        // 它写在「只列缺口」的前面，只有存在缺口时才显示，而且措辞只说「下列语言需要自行
        // 安装」，从没说过「没列出来的都自动装好了」。现在每门语言各自说明状态，不再需要它。
        // 键已不存在，再引用就是取一条空消息——断言挡住这条回头路。
        assertFalse(
            "settings.lsp.pi.auto 已删除，不得再被引用",
            source.contains("settings.lsp.pi.auto"),
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
        // 同样钉守卫条件而不是文案键：把条件改成永假或换成 `cliReport.gaps.isEmpty()`
        //（全量列表下 gaps 空是常态，兜底行会盖掉整张表），缺陷就复活了而文案键还在源码里。
        assertTrue(
            "findings 为空时必须走兜底分支",
            source.contains("if (cliReport.findings.isEmpty())"),
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
     * 全量列表是这次改动的**全部意义**：每个分组列出目录表里的 18 门语言，一门不少。
     *
     * 从前每组只显示「有问题的」语言，其余静默省略：pi 组里没有 TypeScript，
     * 真实用户据此得出「pi 不支持 TypeScript LSP」——而 pi-lens 恰恰会自动装它。
     *
     * 断言钉的是**整个传参调用**而不是 `findingsPanel` 这个标识符：只钉标识符的话，
     * 改成 `findingsPanel(cliReport.gaps, …)` 断言照样绿，缺陷原样复活。
     * 第二条挡的是另一条回头路——把过滤挪进 findingsPanel 内部。
     */
    @Test
    fun `逐语言列表必须传入完整的 findings`() {
        assertTrue(
            "列表必须拿到完整的 findings，不能退回 gaps 或 ready",
            source.contains("scrollCell(findingsPanel(cliReport.findings, cliReport.agentType))"),
        )
        // 钉住**整个循环体**而不只是「有没有 filter」。只挡链式过滤的话，
        // 在循环体里写一句 `if (finding.status == LspStatus.READY) return@forEach`
        // 就能让整组语言重新消失，而断言照样绿——省略哪一门都是同一个缺陷。
        // 比对前把空白归一，这样重新格式化不会误伤。
        assertTrue(
            "语言行的渲染必须是「一条 finding 一行、无条件」，中间不得插入任何跳过逻辑",
            normalized.contains(
                "findings.forEach { finding -> " +
                    "row { " +
                    "icon(statusIcon(finding.status)) " +
                    "label(finding.language.displayName) " +
                    "label(statusText(finding, agentType)) " +
                    "}.layout(RowLayout.PARENT_GRID) " +
                    "finding.remedy?.let { renderRemedy(it) } " +
                    "}",
            ),
        )
    }

    /**
     * 三列必须对得齐。
     *
     * `Panel.row` 不带 label 时构造的是 `RowLayout.INDEPENDENT`，而 `PanelBuilder`
     * 对 INDEPENDENT 的处理是给每行开一个子网格——**列宽跨行不共享**。默认值下
     * `C | clangd` 与 `TypeScript/JavaScript | installed on demand by pi-lens`
     * 的第三列起点差出上百像素，18 行是一份参差的清单而不是一张表。
     *
     * 第二条断言同样重要：只有语言行该进父网格。把 renderRemedy 的命令行也拉进去，
     * 第一列（图标）会被 `npm install -g typescript-language-server typescript`
     * 撑成那条命令的宽度，整张表当场散架——那是「修对齐」时最顺手的一个错解法。
     */
    @Test
    fun `语言行必须共享列宽而命令行必须独立`() {
        assertTrue(
            "语言行必须显式 PARENT_GRID，默认的 INDEPENDENT 每行各占一个子网格、列宽不共享",
            source.contains(".layout(RowLayout.PARENT_GRID)"),
        )
        // 数的是**调用**而不是标识符：KDoc 里也会出现 RowLayout.PARENT_GRID。
        assertEquals(
            "只有语言行该进父网格；命令行进去会把图标列撑到整条安装命令的宽度",
            1,
            Regex("""\.layout\(RowLayout\.PARENT_GRID\)""").findAll(source).count(),
        )
    }

    /**
     * 状态 → 文案的对应逐条钉死。
     *
     * 只钉图标是不够的：把 `AUTO_MANAGED -> settings.lsp.status.auto` 改成
     * `settings.lsp.status.binary`，pi 组的 TypeScript / Python / Ruby / Rust / PHP / C#
     * 就会显示成「服务器不在 PATH 中」——这次改动要消灭的那条假消息换个壳原样复活，
     * 而图标断言、探针测试、资源包一致性测试**全都发现不了**（键还在，只是没人用了）。
     */
    @Test
    fun `每个状态的文案必须逐条钉死`() {
        listOf(
            """LspStatus.READY -> serverBinary(finding.language, agentType).orEmpty()""",
            """LspStatus.MISSING_CONFIG -> ImuxBundle.message("settings.lsp.status.config")""",
            """LspStatus.MISSING_BINARY -> ImuxBundle.message("settings.lsp.status.binary")""",
            """LspStatus.UNKNOWN -> ImuxBundle.message("settings.lsp.status.unknown")""",
            """LspStatus.AUTO_MANAGED -> ImuxBundle.message("settings.lsp.status.auto")""",
            """LspStatus.NOT_AVAILABLE -> ImuxBundle.message("settings.lsp.status.unavailable")""",
        ).forEach { branch ->
            assertTrue("状态与文案的对应被改动：$branch", source.contains(branch))
        }
    }

    /**
     * 18 门语言 × 3 个分组，光语言行就 54 行，再加上缺口下面的命令行——
     * 不放进滚动区的话设置页会被撑到上千像素，「重新检测」以外的一切都得靠滚。
     *
     * 只钉 `scrollCell` 是不够的：`Row.scrollCell` 包出来的 JBScrollPane 会原样跟着
     * 视图的 preferred height 长，视图不封顶等于白包一层，而封顶必须走 Scrollable
     *（JViewport 的布局器只在视图实现该接口时才取 getPreferredScrollableViewportSize，
     * 直接改 preferredSize 只会把内容压扁、连滚都滚不动）。所以两条一起钉。
     */
    @Test
    fun `列表放进能真正滚动的滚动区`() {
        assertTrue("列表必须放进滚动区", source.contains("scrollCell("))
        assertTrue(
            "视图必须实现 Scrollable，否则滚动面板会跟着内容一起长，等于没滚动区",
            source.contains("Scrollable") && source.contains("override fun getPreferredScrollableViewportSize()"),
        )
        assertTrue(
            "可视高度必须封顶，否则 Scrollable 也拦不住",
            source.contains("minOf(preferredSize.height, JBUI.scale(MAX_VISIBLE_HEIGHT))"),
        )
        // 封顶只是一半。tracksViewportHeight 为 true 时 JViewport 会把视图高度直接压成
        // 视口高度：内容被挤扁、纵向再也滚不动——与「去掉封顶」是同一个终局，
        // 而上面三条断言一条都拦不住它。
        assertTrue(
            "视图高度不得跟随视口，否则 18 行被压扁且滚不动",
            source.contains("override fun getScrollableTracksViewportHeight(): Boolean = false"),
        )
    }

    /**
     * 顶部汇总只给计数。
     *
     * 曾经这行把 ready 的语言名拼成一串（任何语种下都约 116 字符，语言显示名不随语言包
     * 变化），不折行的 `label` 会直接把设置对话框撑宽——本页已为此返工过两轮。
     * 语言名现在各自成行进了滚动区，汇总必须**保持只有计数**：
     * 断言禁掉整个文件里的 joinToString，把「顺手再拼一串语言名」这条路一起堵死。
     */
    @Test
    fun `顶部汇总只给计数不再拼接语言名`() {
        assertTrue(
            "汇总必须走 summaryText，且只喂两个计数",
            source.contains("ImuxBundle.message(\"settings.lsp.ready\", cliReport.ready.size)") &&
                source.contains("ImuxBundle.message(\"settings.lsp.gaps\", cliReport.gaps.size)"),
        )
        assertFalse(
            "再把语言名拼成一行就会变回那条撑宽对话框的 116 字符 label",
            source.contains("joinToString"),
        )
    }

    /**
     * `CellImpl.align(Align)` 在 `maxLineLength == -1` 时把 `limitPreferredSize` 覆写成
     * `horizontalAlign == FILL`，而 `text()` / `comment()` 的折行**正依赖**
     * `limitPreferredSize == true`。于是 `text(x).align(AlignX.LEFT)` 会静默关掉折行：
     * 编译通过、界面看着也正常，直到某个语种的长句把设置对话框撑宽。
     *
     * 本页所有需要折行的文字都不得链 `.align()`——这条路径三道现有防线全都发现不了。
     */
    @Test
    fun `折行文本不得被 align 静默关掉折行`() {
        assertFalse(
            "text()/comment() 后面链 .align() 会把折行关掉",
            Regex("""\b(text|comment)\([^\n]*\)\s*\.align\b""").containsMatchIn(source),
        )
    }

    /** 图标必须用官方语义图标，不自绘。 */
    @Test
    fun `状态图标取自 AllIcons`() {
        assertTrue(source.contains("AllIcons."))
        assertFalse("不得引用自定义 svg", source.contains(".svg"))
    }

    /**
     * 状态 → 图标的对应逐条钉死，与「每个状态的文案必须逐条钉死」同一把尺子。
     *
     * 最要紧的两条：「pi-lens 会自动装」是**好消息**，挂个警告牌等于把这次改动要纠正的
     * 误解换个形式又说了一遍；「官方无对应插件」用户做什么都改变不了，同样不该是警告。
     * 但另外三条也得钉——把 READY 改成 Warning，18 行会集体变成一片黄色感叹号，
     * 而「图标取自 AllIcons」那条断言只看有没有出现过 `AllIcons.`，一点都拦不住。
     *
     * 钉的是整条分支而不是图标标识符：`AllIcons.General.Information`
     * 在「CLI 未安装」那一行也在用，只断言它出现过等于没断言。
     */
    @Test
    fun `每个状态的图标必须逐条钉死`() {
        listOf(
            "LspStatus.READY -> AllIcons.General.InspectionsOK",
            "LspStatus.MISSING_CONFIG, LspStatus.MISSING_BINARY -> AllIcons.General.Warning",
            "LspStatus.AUTO_MANAGED -> AllIcons.General.Information",
            "LspStatus.NOT_AVAILABLE -> AllIcons.General.Note",
            "LspStatus.UNKNOWN -> AllIcons.General.QuestionDialog",
        ).forEach { branch ->
            assertTrue("状态与图标的对应被改动：$branch", source.contains(branch))
        }
    }
}
