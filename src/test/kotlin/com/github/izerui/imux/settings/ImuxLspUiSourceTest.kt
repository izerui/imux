package com.github.izerui.imux.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UI 无法在本项目里跑起来做行为测试（未引入平台 test-framework，见 build.gradle.kts），
 * 因此对源码做结构断言，守住几条一旦破坏就只能在真机上才发现的约定。
 *
 * **这里刻意不再断言「状态 → 文案 / 图标」的对应**。源码文本断言可以被
 * 「原有字面量一个字不改、在它前面加一句守卫」整个绕过，这一类缺陷在本文件上
 * 反复复活过。那组映射已经搬到 `lsp/LspStatusPresentation.kt`——它不碰任何平台 API，
 * 因此能被 `LspStatusPresentationTest` 真正调用着测。本文件只负责守住
 * 「设置页确实走了那套映射、而且壳里没有自己的判断」。
 */
class ImuxLspUiSourceTest {
    private val source: String by lazy {
        File("src/main/kotlin/com/github/izerui/imux/settings/ImuxLspConfigurable.kt").readText()
    }

    /**
     * 剥掉注释、再把空白归一后的源码，供需要钉住**整段结构**（而不是单行）的断言使用。
     *
     * 两步缺一不可。不归一空白，断言会连缩进和换行位置一起钉死，重新格式化一次就误报；
     * 不剥注释，在被钉住的那段代码里补一行说明就会红——而本代码库的注释密度极高，
     * 那是大概率发生的误报，报错信息还会把维护者往「你改坏了逻辑」的方向指。
     *
     * 行注释用 `(?<!:)` 排除 `https://`，免得把字符串里的 URL 当成注释吃掉。
     */
    private val normalized: String by lazy {
        source.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""(?<!:)//[^\n]*"""), " ")
            .replace(Regex("""\s+"""), " ")
    }

    /**
     * 把空白**全部**去掉，供整段代码的等价比对使用。
     *
     * [normalized] 只把连续空白压成一个空格，于是「这里必须有一个空格」变成了隐含要求：
     * `CappedHeightView(panel {` 与 `CappedHeightView( panel {` 行为完全一样，
     * 前者却会让「归一后不在意排成几行」的断言变红。彻底去空白之后这类改写不再误报，
     * 而任何真正的结构改动（多一个 if、少一行渲染、换一个常量）仍然会改变结果。
     *
     * 失败信息里打印的仍是 [normalized] 版本——那个是人能读的。
     */
    private fun compact(code: String): String = code.replace(Regex("""\s+"""), "")

    /** 忽略空白差异地比对整段代码。 */
    private fun assertSameCode(message: String, expected: String, actual: String) {
        assertTrue(
            "$message\n期望（忽略空白）：${expected.replace(Regex("""\s+"""), " ").trim()}\n实际：$actual",
            compact(expected) == compact(actual),
        )
    }

    /**
     * 取出 [anchor] **之后**、直到与第一个 [open] 配对的定界符为止的整段源码。
     *
     * 返回值从 anchor 末尾算起而不是从 [open] 算起，这样表达式体函数的分派表达式
     *（`= when (statusIconKind(status)) {`）也在结果里——它恰恰是最该被断言的那部分。
     *
     * 在 [normalized] 上做，所以不必担心注释里的括号打乱配平。
     * 用它把断言限定在**某个函数体内**：「renderRemedy 里不得出现 X」这种话，
     * 在整份源码上说会被别处的合法用法搅黄，在函数体内说才是准的。
     */
    private fun bodyAfter(anchor: String, open: Char): String {
        val start = normalized.indexOf(anchor)
        assertTrue("源码里找不到锚点：$anchor", start >= 0)
        val from = normalized.indexOf(open, start + anchor.length)
        assertTrue("锚点 $anchor 之后找不到 $open", from >= 0)
        val close = if (open == '{') '}' else ')'
        var depth = 0
        for (i in from until normalized.length) {
            when (normalized[i]) {
                open -> depth++
                close -> if (--depth == 0) return normalized.substring(start + anchor.length, i + 1).trim()
            }
        }
        throw AssertionError("锚点 $anchor 之后的 $open 没有配对")
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
     *
     * 三道网并存，各挡一类入口，缺一不可：
     *
     * 1. **否定断言**挡最常见的跳过写法，失败信息直接点名是哪一种；
     * 2. **前缀链**从函数签名一路连到 `panel {` 与 `forEach`，挡「过滤挪到循环之前」
     *    与「形参遮蔽」——这两种写法循环体一字未动，否定断言看不见；
     * 3. **整个函数体钉死**挡剩下的全部：循环体内套一层 `if`（不用 return、不用 filter、
     *    前缀链完好）能让整组语言重新消失；把 `icon(statusIcon(...))` 换成写死的
     *    `icon(AllIcons.General.Warning)` 能绕开整套映射；删掉
     *    `finding.remedy?.let { renderRemedy(it) }` 能让所有安装命令消失。
     *    前两道网对这三种改法全是绿的。
     *
     * 第 3 条比对时把空白**全部**去掉（见 [compact]），所以换行、缩进乃至
     * `CappedHeightView(panel {` 这种等价改写都不会误红。
     */
    @Test
    fun `逐语言列表必须无条件渲染每一条 finding`() {
        assertTrue(
            "列表必须拿到完整的 findings，不能退回 gaps 或 ready",
            source.contains("scrollCell(findingsPanel(cliReport.findings, cliReport.agentType))"),
        )

        val body = findingsPanelBody()

        listOf("return@forEach", "continue", ".filter", ".take", ".drop").forEach { escape ->
            assertFalse("逐语言渲染里不得出现跳过逻辑：$escape", body.contains(escape))
        }

        assertTrue(
            "形参 findings 必须直接进 panel 的 forEach，中间不得有任何加工或遮蔽：$body",
            Regex("""^CappedHeightView\(\s*panel\s*\{\s*findings\.forEach\s*\{\s*finding\s*->""")
                .containsMatchIn(body),
        )

        assertSameCode(
            "逐语言渲染必须是「一条 finding 一行、无条件」——多一层 if、换一个写死的图标、" +
                "少一句 renderRemedy，都会让用户看到错的东西",
            """
            CappedHeightView(
                panel {
                    findings.forEach { finding ->
                        row {
                            icon(statusIcon(finding.status))
                            label(finding.language.displayName)
                            label(statusText(finding, agentType))
                        }.layout(RowLayout.PARENT_GRID)
                        finding.remedy?.let { renderRemedy(it) }
                    }
                },
            )
            """,
            body,
        )
    }

    private fun findingsPanelBody(): String = bodyAfter(
        "private fun findingsPanel(findings: List<LanguageFinding>, agentType: AgentType): JComponent =",
        '(',
    )

    /**
     * 上一条的兜底：整份源码里都不该出现 `findings.filter`。
     *
     * 与函数体内那条并存而不是二选一。它挡的是形参遮蔽写法
     *（`val findings = findings.filter { … }`）——那种写法下 `blockAfter` 的链式匹配
     * 也会断，但两道网各自还能挡住对方漏掉的变体，成本又只有一行。
     */
    @Test
    fun `源码里不得对 findings 做过滤`() {
        assertFalse(
            "全量列表是这轮改造的全部意义，findings 不得被过滤",
            Regex("""findings\s*\.\s*filter""").containsMatchIn(normalized),
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
     * 它写成**在 renderRemedy 函数体内的否定断言**：数整份源码里 PARENT_GRID 出现几次
     * 两个方向都会失效——`import RowLayout.PARENT_GRID` 之后写 `.layout(PARENT_GRID)`
     * 数不到，而按本文件的风格在 KDoc 里提一句 PARENT_GRID 就会误红。
     */
    @Test
    fun `语言行必须共享列宽而命令行必须独立`() {
        assertTrue(
            "语言行必须进父网格，默认的 INDEPENDENT 每行各占一个子网格、列宽不共享",
            findingsPanelBody().contains("PARENT_GRID"),
        )
        assertFalse(
            "命令行进父网格会把图标列撑成整条安装命令的宽度",
            bodyAfter("private fun Panel.renderRemedy(remedy: Remedy)", '{').contains("PARENT_GRID"),
        )
    }

    /**
     * 状态 → 文案的对应由 `LspStatusPresentationTest` 真正调用着测；这里守的是**壳**。
     *
     * 壳里补一句
     * `if (finding.status == LspStatus.AUTO_MANAGED) return ImuxBundle.message("…binary")`，
     * 纯映射再正确也没用——pi 组的 TypeScript / Python / Ruby / Rust / PHP / C# 照样会
     * 显示成「服务器不在 PATH 中」，而那组行为测试全绿。
     *
     * 前两条否定断言挡最典型的两种写法并给出准确的失败原因；最后整体钉死则挡剩下的：
     * 把 `?: return serverBinaryFor(…)` 改成 `?: return ""`，就绪那一列当场变成一整列
     * 空白（那正是 D2 决策要避免的形态）；在 `ImuxBundle.message(key)` 后面接一句
     * `.replace(…)`，文案照样可以被改写——两种都不含 `LspStatus.`、也不含写死的键。
     */
    @Test
    fun `状态文案必须全部来自纯映射，壳里不得自己判断`() {
        val text = bodyAfter(
            "private fun statusText(finding: LanguageFinding, agentType: AgentType): String",
            '{',
        )
        assertFalse("壳里不得再按状态分支，那正是绕过行为测试的路：$text", text.contains("LspStatus."))
        assertFalse("文案键只能来自 statusMessageKey，壳里不得写死：$text", text.contains("\"settings.lsp.status."))

        assertSameCode(
            "statusText 必须是取键 → 查 bundle 的薄壳；没有键的（只有 READY）显示 server 二进制名",
            """
            {
                val key = statusMessageKey(finding.status)
                    ?: return serverBinaryFor(finding.language, agentType).orEmpty()
                return ImuxBundle.message(key)
            }
            """,
            text,
        )
    }

    /**
     * 用户看见的是**图标**，不是枚举常量——所以这五条必须钉在壳里。
     *
     * `LspStatusPresentationTest` 那条「只有可行动的缺口才配警告图标」约束的其实是
     * `StatusIconKind.WARNING` 这个**枚举值**的归属，不是界面上那个黄色感叹号：
     * 把壳里的 `StatusIconKind.INFO -> AllIcons.General.Information` 改成
     * `-> AllIcons.General.Warning`，WARNING 集合原封不动仍是
     * {MISSING_CONFIG, MISSING_BINARY}，行为测试全绿，而 pi 组的 TypeScript / Python /
     * Ruby / Rust / PHP / C# 在界面上集体挂起黄色警告牌——正是这轮改造要消灭的那条误解。
     * `OK -> Warning` 更狠，18 行一起变黄。用户可见的不变量住在这一层。
     *
     * 断言必须**限定在 statusIcon 的函数体内**：`AllIcons.General.Warning` 在组级提示
     * 和顶部汇总那两行都有合法用法，在整份源码上数没有意义。
     *
     * 代价是「换一个语义更贴的 AllIcons 常量」要同时改这里。那是刻意的：
     * 这一层的每一次改动都直接改变用户看到的东西，值得被要求确认一次。
     */
    @Test
    fun `每个语义类别映到的图标必须钉在壳里`() {
        val icon = bodyAfter("private fun statusIcon(status: LspStatus): Icon", '{')

        assertFalse("壳里不得再按状态分支：$icon", icon.contains("LspStatus."))

        assertSameCode(
            "语义类别与图标的对应变了，界面上看到的图标就变了",
            """
            = when (statusIconKind(status)) {
                StatusIconKind.OK -> AllIcons.General.InspectionsOK
                StatusIconKind.WARNING -> AllIcons.General.Warning
                StatusIconKind.INFO -> AllIcons.General.Information
                StatusIconKind.NEUTRAL -> AllIcons.General.Note
                StatusIconKind.QUESTION -> AllIcons.General.QuestionDialog
            }
            """,
            icon,
        )
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

}
