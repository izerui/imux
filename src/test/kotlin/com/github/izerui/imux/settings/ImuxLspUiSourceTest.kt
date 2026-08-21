package com.github.izerui.imux.settings

import org.junit.Assert.assertEquals
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
 *
 * 写在这里的每一条断言都必须能回答一个问题：**它红的时候，用户看到的是什么？**
 * 答不上来的断言守的多半不是用户在乎的东西，只会在重新格式化时制造误报。
 */
class ImuxLspUiSourceTest {
    private val source: String by lazy {
        File("src/main/kotlin/com/github/izerui/imux/settings/ImuxLspConfigurable.kt").readText()
    }

    /**
     * 剥掉注释、再把空白归一后的源码。**几乎所有断言都必须跑在它上面，而不是 [source]。**
     *
     * 三个理由，每一个都被实测击穿过：
     *
     * 1. 跑在 [source] 上的 `contains` **可以用注释满足**。把调用点改成
     *    `findingsPanel(cliReport.gaps, …)`、在下面补一行注释放上原来的字面量，
     *    断言照样绿，而 pi 组里的 TypeScript 又不见了——正是这几轮要消灭的那条缺陷。
     * 2. 不归一空白，断言会连缩进和换行位置一起钉死，重新格式化一次就误报。
     * 3. 不剥注释，在被钉住的那段代码里补一行说明就会红——本代码库注释密度极高，
     *    那是大概率发生的误报，报错信息还会把维护者往「你改坏了逻辑」的方向指。
     *
     * 行注释用 `(?<!:)` 排除 `https://`，免得把字符串里的 URL 当成注释吃掉。
     */
    private val normalized: String by lazy {
        source.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""(?<!:)//[^\n]*"""), " ")
            .replace(Regex("""\s+"""), " ")
    }

    /**
     * 把空白**全部**去掉、并抹平尾逗号，供代码片段的等价比对使用。
     *
     * 两步各治一类误报，都是实测出来的：
     *
     * 1. [normalized] 只把连续空白压成一个空格，于是「这里必须有一个空格」变成了隐含要求：
     *    `CappedHeightView(panel {` 与 `CappedHeightView( panel {` 行为完全一样，
     *    前者却会让「归一后不在意排成几行」的断言变红。
     * 2. IDEA 一拆行就会自动补尾逗号——`label(command)` 变成 `label(\n command,\n)`，
     *    去空白之后是 `label(command,)`，`contains("label(command)")` 当场误报。
     *    ktlint 把 trailing-comma 规则关掉则是反方向的同一件事。尾逗号在 Kotlin 里
     *    **没有任何语义**，抹掉它两个方向都不再误报，而真正的结构改动仍然会改变结果。
     *
     * 只抹**尾**逗号（后面紧跟收尾定界符的那个），`f(a, b)` 里的分隔逗号原样保留。
     */
    private fun compact(code: String): String =
        code.replace(Regex("""\s+"""), "").replace(Regex(""",(?=[)\]}>])"""), "")

    /**
     * 在 [compact] 之上再抹掉**具名实参的名字**——`f(a, b = x)` 与 `f(a, x)` 等价。
     *
     * IDEA 的「Add name to argument」意图是一次按键的纯重构，语义零变化。
     * 不抹的话 `label(statusText(finding, agentType = agentType))` 会让
     * 「第三列必须来自 statusText()」变红，而报错说的是「接一句 .let { "" } 就是整列空白」
     *——又一次「敏感面比不变量大一圈」，与尾逗号是同一类装饰。
     *
     * 只抹 `(` 或 `,` 紧跟着的 `名字=`，且用 `(?![=])` 排除 `==` / `!=` / `>=` / `<=`。
     * **实参的先后顺序仍然被检查**：具名重排（`f(b = b, a = a)`）抹完是 `f(b,a)`，
     * 与 `f(a,b)` 仍然不等——那本来也不是纯格式改动。
     *
     * 刻意不并进 [compact]：[compactIndex] 为了做下标映射复刻了 [compact] 的规则，
     * 两处必须逐条对齐，多一条就多一处走样的机会。而锚点是函数**签名**
     *（`(finding: LanguageFinding, …)`），里面根本没有具名实参，用不到这一条。
     */
    private fun compactArgs(code: String): String =
        compact(code).replace(Regex("""(?<=[(,])\w+=(?![=])"""), "")

    /** 忽略空白差异地比对整段代码。 */
    private fun assertSameCode(message: String, expected: String, actual: String) {
        assertTrue(
            "$message\n期望（忽略空白）：${expected.replace(Regex("""\s+"""), " ").trim()}\n实际：$actual",
            compactArgs(expected) == compactArgs(actual),
        )
    }

    /**
     * [normalized] 的**压缩视图**，外加「压缩串的第 i 个字符原本落在 normalized 的哪个
     * 下标」这张映射表。规则与 [compact] 完全一致（去全部空白、抹尾逗号），两处必须同步。
     *
     * [bodyAfter] 用它做**格式无关**的锚点定位：锚点写的是人能读的函数签名，
     * 而 IDEA 一把参数表拆行就会写成 `statusText(\n finding: …,\n agentType: …,\n)`,
     * 逐字节匹配的锚点当场找不到——那是纯格式改动，不该红。
     * 定位在压缩视图上做、切片仍然回到 [normalized] 上切，失败信息因此还是人能读的原文。
     */
    private val compactIndex: Pair<String, IntArray> by lazy {
        val dense = StringBuilder()
        val origin = ArrayList<Int>()
        normalized.forEachIndexed { i, c ->
            if (!c.isWhitespace()) {
                dense.append(c)
                origin.add(i)
            }
        }
        val packed = StringBuilder()
        val map = ArrayList<Int>()
        dense.indices.forEach { i ->
            if (dense[i] == ',' && i + 1 < dense.length && dense[i + 1] in ")]}>") return@forEach
            packed.append(dense[i])
            map.add(origin[i])
        }
        packed.toString() to map.toIntArray()
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
     *
     * **锚点必须唯一**。用 `indexOf` 取第一个匹配，意味着「把原函数原封不动留成死代码、
     * 另写一个真正被调用的同形函数」能让整组断言钉在一段没人执行的代码上——Kotlin 对
     * 没人调用的 private 函数只报 warning，拦不住。这里直接把「出现两次」判为失败。
     */
    private fun bodyAfter(anchor: String, open: Char): String {
        val (packed, map) = compactIndex
        val needle = compact(anchor)
        val at = packed.indexOf(needle)
        assertTrue("源码里找不到锚点：$anchor", at >= 0)
        assertEquals(
            "锚点出现了不止一次，断言可能钉在一段没人调用的死代码上：$anchor",
            at,
            packed.lastIndexOf(needle),
        )
        val start = map[at + needle.length - 1] + 1
        val from = normalized.indexOf(open, start)
        assertTrue("锚点 $anchor 之后找不到 $open", from >= 0)
        val close = if (open == '{') '}' else ')'
        var depth = 0
        for (i in from until normalized.length) {
            when (normalized[i]) {
                open -> depth++
                close -> if (--depth == 0) return normalized.substring(start, i + 1).trim()
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
        assertTrue("探测必须放到后台线程", normalized.contains("executeOnPooledThread"))
        assertTrue("刷新 UI 必须回到 EDT", normalized.contains("invokeLater"))
    }

    /**
     * CLI 是否安装必须并进那一次批量探测。页面里凡是另起 ProcessBuilder 的，
     * 都是又一个登录 shell——读一遍 profile 的钱要重付一次。
     */
    @Test
    fun `设置页自己不起 shell 进程`() {
        assertFalse("CLI 探测应并入 BinaryProbe，不在页面里另起进程", normalized.contains("ProcessBuilder"))
    }

    @Test
    fun `页面是只读的`() {
        assertTrue("体检页没有可保存状态", normalized.contains("override fun isModified(): Boolean = false"))
    }

    @Test
    fun `按 CLI 分组并给出重新检测入口`() {
        assertTrue(normalized.contains("settings.lsp.refresh"))
        assertTrue(normalized.contains("settings.lsp.checking"))
        assertTrue("必须说明只检查全局配置", normalized.contains("settings.lsp.scope.note"))
    }

    @Test
    fun `复制按钮走平台剪贴板`() {
        assertTrue(normalized.contains("CopyPasteManager"))
        assertTrue(normalized.contains("settings.lsp.copy"))
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
            normalized.contains("ModalityState.any()"),
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
            normalized.contains("""else -> ImuxBundle.message("settings.lsp.pi.missing")"""),
        )
        // settings.lsp.pi.auto 那句补丁式说明随全量列表一起删了（10 个语言文件都删了）：
        // 它写在「只列缺口」的前面，只有存在缺口时才显示，而且措辞只说「下列语言需要自行
        // 安装」，从没说过「没列出来的都自动装好了」。现在每门语言各自说明状态，不再需要它。
        // 键已不存在，再引用就是取一条空消息——断言挡住这条回头路。
        assertFalse(
            "settings.lsp.pi.auto 已删除，不得再被引用",
            normalized.contains("settings.lsp.pi.auto"),
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
        // 钉的是「runCatching 的失败分支里确实记了一笔」，不是 `LOG` 这个属性名：
        // 只写 contains("LOG.warn") 的话，把伴生里的 `val LOG` 改名成 `val logger`
        //（IDEA 插件里很常见的写法）就会红，而那是纯重构、日志一条没少。
        assertTrue(
            "异常必须落到 idea.log——这一页唯一的诊断入口就是它",
            Regex("""\.onFailure\s*\{\s*\w+\.warn\(""").containsMatchIn(normalized),
        )
        assertTrue(
            "失败必须分派到独立的错误态，不能复用「正在检测」",
            normalized.contains("if (report == null) showFailed() else showReport(report)"),
        )
    }

    /**
     * 每次探测都是一个 `zsh -l -i`。按钮不设防的话连点五次就是五个登录 shell 同时读
     * profile，而且**先发起的可能后返回**，最终显示的会是更旧的结果。
     */
    @Test
    fun `连点重新检测不会叠起多个登录 shell`() {
        assertTrue("探测期间必须禁用按钮", normalized.contains("refreshButton?.isEnabled = false"))
        // 钉回调里的比对本身：只断言 AtomicInteger 字段声明的话，
        // 把这行守卫删掉、字段留着，竞态就复活了而断言照样绿。
        assertTrue(
            "过期结果不得覆盖最新结果",
            normalized.contains("if (token == generation.get())"),
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
            normalized.contains("if (cliReport.findings.isEmpty())"),
        )
        assertTrue(
            "兜底行必须用 comment：label 不折行会撑宽整个设置对话框",
            normalized.contains("""comment(ImuxBundle.message("settings.lsp.no.findings"))"""),
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
            normalized.contains("comment(groupMessage(cliReport))"),
        )
        assertFalse(
            "组级提示不得退回 label",
            normalized.contains("label(groupMessage(cliReport))"),
        )
    }

    /**
     * 每个 CLI 都必须**原样**得到一个分组，标题是它的显示名。
     *
     * 反向引用把循环变量名一起捕获，所以 IDE 改名不会误报；它挡的是在分派途中
     * 换掉数据的写法——`renderCli(it.copy(findings = it.gaps))` 会让三个分组同时
     * 退回「只列缺口」，而下面那条调用点断言一字未改仍然全绿。
     */
    @Test
    fun `每个 CLI 各成一组且原样传递`() {
        assertTrue(
            "分组必须逐个 cliReport 渲染、标题用 agentType.displayName，且中途不得换掉数据",
            Regex("""cliReports\.forEach\{(\w+)->group\(\1\.agentType\.displayName\)\{renderCli\(\1\)\}\}""")
                .containsMatchIn(compactArgs(normalized)),
        )
    }

    /**
     * 体检结果是只读的输入，设置页只负责显示。
     *
     * `val cliReport = cliReport.copy(findings = cliReport.gaps)` 插在调用点**之前**，
     * 下面那行 `findingsPanel(cliReport.findings, …)` 一个字都不用改，pi 组里的
     * TypeScript 就又消失了。形参遮蔽在 Kotlin 里连 warning 都没有。
     */
    @Test
    fun `壳里不得改写体检结果`() {
        assertFalse(
            "形参被同名局部变量遮蔽后，调用点一字未改却换了数据源",
            Regex("""\b(?:val|var)\s+cliReport\b""").containsMatchIn(normalized),
        )
        assertFalse(
            "设置页只显示、不重建体检结果；copy 出来的报告能在不碰调用点的前提下换掉整张表",
            normalized.contains(".copy("),
        )
    }

    /**
     * 壳里用到的跨文件符号必须**真的是**那个跨文件符号。
     *
     * 这条挡的是一整类「函数体一字节不改、语义整个换掉」的改法：删掉
     * `import com.github.izerui.imux.lsp.serverBinaryFor`，在同文件补一句
     * `private fun serverBinaryFor(language: LspLanguage, agentType: AgentType): String? = null`，
     * 被钉死的 `statusText` 逐字节不变，就绪那一列 18 行全空——与 `?: return ""` 完全
     * 同一个用户可见后果，而整段函数体的比对全绿。
     *
     * 所以必须两头都堵：这条要求 import 存在（挡「删 import + 补本地定义」），
     * [壳里不得用同名声明遮蔽 import 进来的符号] 要求没有同名声明（挡「留着 import
     * 再补本地定义」——后者 Kotlin 只报「import 未使用」这一条 warning）。
     */
    @Test
    fun `状态映射与文案必须来自壳外`() {
        listOf(
            "com.github.izerui.imux.ImuxBundle",
            "com.github.izerui.imux.lsp.StatusIconKind",
            // canRun 是这一页唯一「点错了就在用户机器上跑错东西」的守卫：本文件里补一句
            // `private fun canRun(remedy: Remedy, isMac: Boolean) = true`，被钉死的调用点
            // 一字不用改，Windows 用户的体检页上就长出 18 个「安装」按钮。
            "com.github.izerui.imux.lsp.canRun",
            "com.github.izerui.imux.lsp.readyServerText",
            // 同理：补一句恒返回 activate 键的同名声明，「安装」按钮全部写成「激活」，
            // 用户点一个说一两秒就完的按钮，等来的是几百兆下载。
            "com.github.izerui.imux.lsp.runActionKey",
            // 补一句 `runCommandLine(shell, command) = listOf(shell, "-c", command)`，
            // 从 Dock 启动的 IDE 上每一条安装命令都变成 command not found。
            "com.github.izerui.imux.lsp.runCommandLine",
            // 补一句恒返回空串的同名声明，标签名全部变空，用户在几个安装标签之间认不出
            // 哪个是哪个；而整条 builder 链的比对一字不变。
            "com.github.izerui.imux.lsp.runTabName",
            "com.github.izerui.imux.lsp.statusIconKind",
            "com.github.izerui.imux.lsp.statusMessageKey",
            "com.github.izerui.imux.terminal.resolveShell",
            "com.intellij.icons.AllIcons",
            "com.intellij.openapi.ide.CopyPasteManager",
            "com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager",
        ).forEach { fqn ->
            val name = fqn.substringAfterLast('.')
            assertTrue(
                "必须 import $fqn；删掉 import 再在本文件补一个同名声明，被钉死的函数体一字不用改就能换掉语义",
                normalized.contains("import $fqn "),
            )
            // 留着 import、同时在本文件补一个同名声明也一样能赢，代价只有一条
            // 「import 未使用」的 warning。这 7 个符号连 val 一起禁——
            // `private val readyServerText: (LspLanguage, AgentType) -> String = { _, _ -> "" }`
            // 是能通过编译的写法，而属性优先于 import 进来的顶层函数。
            //
            // 与 [壳里不得用同名声明遮蔽 import 进来的符号] 并存而不是二选一：那条覆盖
            // **全部** import 但为了避开「自初始化绑定」留了一个 carve-out，这条对这 7 个
            // 语义受保护的符号连 carve-out 都不给，且失败信息直接点名是哪一个符号。
            assertFalse(
                "本文件里不得再声明一个叫 $name 的东西——本地声明会赢，被钉死的函数体一字不用改，语义已经换了",
                Regex(
                    """\b(?:fun|val|var|class|object|interface|typealias)\s+""" +
                        """(?:<[^>]*>\s*)?(?:[\w.?<>, ]+?\s*\.\s*)?\Q$name\E\b""",
                ).containsMatchIn(normalized),
            )
        }
    }

    /**
     * 同文件里的同名声明会**静默**遮蔽 import 进来的符号，**任何**一个 import 都算。
     *
     * 上一条只盯着 7 个符号，漏掉的都是能直接把页面打空的：
     *
     * ```
     * private val panel: (Panel.() -> Unit) -> DialogPanel = { … }   // 整张体检页渲染成空白
     * private object JBUI { val scale: (Int) -> Int = { it * 1000 } } // 设置页撑到上千像素
     * private typealias RowLayout = …                                 // 三列不再对齐
     * ```
     *
     * 三条都是 2～3 行、不删任何 import、不碰任何被钉死的函数体，`@Deprecated(ERROR)`
     * 探针确认调用点全部选中本地那一份。所以覆盖面必须是**全部 import**。
     *
     * 唯一的 carve-out 是**自初始化绑定**——`val logger = logger<…>()`、
     * `val panel = panel { … }`：右边调用的正是左边那个名字，它在自己的初始化式里
     * 什么都没遮蔽，是 IDEA 插件里极常见的写法。曾经因为分不清「哪些绑定无害」与
     * 「哪些符号值得保护」，把整条断言按符号收窄，结果连上面三条一起放走了——
     * 正确的切法是按**绑定形状**开一个口子，而不是按符号缩小范围。
     */
    @Test
    fun `壳里不得用同名声明遮蔽 import 进来的符号`() {
        val imported = Regex("""^import\s+([\w.]+)""", RegexOption.MULTILINE)
            .findAll(source)
            .map { it.groupValues[1].substringAfterLast('.') }
            .toSet()
        val declared = Regex(
            """\b(?:fun|val|var|class|object|interface|typealias)\s+""" +
                """(?:<[^>]*>\s*)?(?:[\w.?<>, ]+?\s*\.\s*)?(\w+)\b""",
        ).findAll(normalized).map { it.groupValues[1] }.toSet()
        val selfInitialised = Regex("""\b(?:val|var)\s+(\w+)\s*=\s*\1\s*[({<]""")
            .findAll(normalized).map { it.groupValues[1] }.toSet()

        assertEquals(
            "这些名字既 import 进来、又在本文件里声明了一遍。本地声明会赢（代价只有一条" +
                "「import 未使用」warning），于是被钉死的函数体一字不用改，语义已经换了。" +
                "确实需要一个同名的局部绑定时，写成 `val x = x(…)` 这种自初始化形式即可",
            emptySet<String>(),
            (imported intersect declared) - selfInitialised,
        )
    }

    /**
     * 本文件只允许声明这些函数。**任何**多出来的 `fun` 都会让这条红。
     *
     * 它一次盖住三类「函数体一字节不改、语义整个换掉」的写法，实测（`@Deprecated(ERROR)`
     * 探针确认编译器确实选中了本地那一份）：
     *
     * 1. **同名遮蔽显式 import**：补一句 `private fun readyServerText(…): String = ""`，
     *    就绪那一列 18 行全空；
     * 2. **成员扩展遮蔽默认导入**：`private fun String?.orEmpty(): String = ""` 同样后果，
     *    而默认导入（`kotlin.text` / `kotlin.collections`）的名字**不在 import 列表里**，
     *    任何基于 import 的检查都看不见它；
     * 3. **非扩展成员遮蔽顶层函数**：在 `CappedHeightView` 里补一句
     *    `private fun minOf(a: Int, b: Int): Int = a`，高度封顶当场失效、设置页撑到上千像素，
     *    而钉住那行文本的断言原样全绿。这一类既不是扩展、名字也不在 import 列表，
     *    是前两道网**都**看不见的平行类。
     *
     * 受体写成 `([\w.?<>, ]+?)\s*\.\s*` 并把结果去空白：受体与点之间只要有一个空格
     *（`fun <T> T .also(…)`）或受体是 `Map<K, V>` 这种带逗号加空格的泛型，
     * 收紧的写法就整条匹配不上——**一个都不进集合、白名单静默失效**，实测过。
     *
     * 一条已验证的反例，记在这里免得下一轮又被推断成事实：**`List<T>.forEach` 遮蔽不了
     * stdlib 的那一份**。本文件里 `findings.forEach` 的调用点嵌在 `panel { }` 这个
     * DSL lambda 里，`@Deprecated(DeprecationLevel.ERROR)` 探针证实编译器仍然选中
     * `kotlin.collections` 的版本。同样的探针下 `String?.orEmpty`、`T.also`、
     * 以及非扩展的顶层 `minOf` **都会**被本地那一份接管。所以那三类是真攻击，
     * `forEach` 那条只是白名单尽职，不是攻击奏效。
     *
     * 白名单而不是黑名单：新增或改名一个函数就要来这里点头一次。这是刻意的成本——
     * 这份名单是本层唯一挡得住「默认导入 / 顶层函数被遮蔽」的东西。
     */
    @Test
    fun `本文件只允许声明这些函数`() {
        val declared = Regex("""\bfun\b\s*(?:<[^>]*>\s*)?(?:([\w.?<>, ]+?)\s*\.\s*)?(\w+)\s*\(""")
            .findAll(normalized)
            .map { match ->
                val receiver = match.groupValues[1].filterNot(Char::isWhitespace)
                if (receiver.isEmpty()) match.groupValues[2] else "$receiver.${match.groupValues[2]}"
            }
            .toSet()

        val allowed = setOf(
            "createPanel", "isModified", "apply", "refresh", "diagnostics",
            "showChecking", "showFailed", "showReport", "replaceContent",
            "Panel.renderCli", "summaryText", "findingsPanel", "Panel.renderRemedy",
            "Row.runRemedyButton", "hasProjectWindow", "runInTerminal", "targetProject",
            "groupMessage", "statusText", "statusIcon",
            "getPreferredScrollableViewportSize", "getScrollableUnitIncrement",
            "getScrollableBlockIncrement", "getScrollableTracksViewportWidth",
            "getScrollableTracksViewportHeight",
        )

        // 两个方向分开断言：JUnit 的集合比对会把两份名单整个打印出来，
        // 而维护者只想知道**多了哪一个**。分开之后失败信息第一行就点名。
        assertEquals(
            "本文件多声明了这些函数。它们可能正在遮蔽 import 进来的符号、默认导入的扩展" +
                "（String?.orEmpty 等）或顶层函数（minOf 等），让被钉死的函数体在一字未改的" +
                "情况下换掉语义。确认无害的话把它加进本用例的 allowed 名单",
            emptySet<String>(),
            declared - allowed,
        )
        assertEquals(
            "白名单里有本文件已经不存在的函数——多半是删掉或改名了，请同步本用例的 allowed 名单",
            emptySet<String>(),
            allowed - declared,
        )
    }

    /**
     * 全量列表是这次改动的**全部意义**：每个分组列出目录表里的 18 门语言，一门不少。
     *
     * 从前每组只显示「有问题的」语言，其余静默省略：pi 组里没有 TypeScript，
     * 真实用户据此得出「pi 不支持 TypeScript LSP」——而 pi-lens 恰恰会自动装它。
     *
     * 四道网并存，各挡一类入口，且**每一条失败都对应一句具体的「用户看到了什么」**：
     *
     * 1. 调用点必须拿 `findings`（跑在 [normalized] 上，注释满足不了它）；
     * 2. 否定断言挡最常见的跳过写法，失败信息直接点名是哪一种；
     * 3. 前缀链从函数签名一路连到 `panel {` 与 `forEach`，挡「过滤挪到循环之前」
     *    与「形参遮蔽」——这两种写法循环体一字未动，否定断言看不见；
     * 4. 循环体内逐条钉住四件用户直接看得见的事：不许有分支、图标来自 statusIcon、
     *    语言名和状态各占一列、缺口下面挂着修复命令。
     *
     * 刻意**不**整段比对循环体。整段比对的敏感面是「去掉空白后的 token 序列」，
     * 比真正的不变量大出一圈：删一个尾逗号、IDEA 把 `label(...)` 拆行并补尾逗号、
     * `import RowLayout.PARENT_GRID` 之后写短名、把循环变量 `finding` 改名 `item`——
     * 四种纯格式/纯重构改动全都误红，而且和真缺陷共用同一条失败消息，
     * 维护者删个尾逗号会被告知「你改坏了逻辑」。下面这组对四种改写全不敏感。
     * 代价是不再钉住三个 label 的先后顺序，但那本来就不是不变量。
     */
    @Test
    fun `逐语言列表必须无条件渲染每一条 finding`() {
        assertTrue(
            "列表必须拿到完整的 findings，不能退回 gaps 或 ready",
            normalized.contains("scrollCell(findingsPanel(cliReport.findings, cliReport.agentType))"),
        )

        val body = findingsPanelBody()

        // `.visible` / `.enabled` 覆盖 UI DSL 的 visible / visibleIf / enabled / enabledIf：
        // `}.layout(RowLayout.PARENT_GRID).visible(finding.status != LspStatus.READY)`
        // 里没有一个 if、没有一个 filter，整组语言照样重新消失——正是本 epic 的起因缺陷。
        //
        // 必须跑在 **compactArgs** 上而不是 body 上。body 来自 normalized，空白只被压成
        // 单空格而不是抹掉，于是**点后面加一个空格**就整条绕过：
        // `.layout(RowLayout.PARENT_GRID). visible(…)` 让整组语言消失而全部断言照绿。
        // 同一个空格对 .filter / .take / .drop / .enabled 一样有效。
        // renderRemedy 那边的姊妹断言一开始就跑在压缩串上，只有这边漏了。
        val dense = compactArgs(body)
        listOf("return@forEach", "continue", ".filter", ".take", ".drop", ".visible", ".enabled")
            .forEach { escape ->
                assertFalse("逐语言渲染里不得出现跳过逻辑：$escape", dense.contains(escape))
            }

        val item = Regex("""^CappedHeightView\(\s*panel\s*\{\s*findings\s*\.\s*forEach\s*\{\s*(\w+)\s*->""")
            .find(body)
            ?.groupValues
            ?.get(1)
            ?: throw AssertionError("形参 findings 必须直接进 panel 的 forEach，中间不得有任何加工或遮蔽：$body")

        val loop = compactArgs(bodyAfter("findings.forEach", '{'))

        assertFalse(
            "循环体内套一层条件分支，就能让整组语言重新消失，而调用点与前缀链一字未改：$loop",
            Regex("""\bif[({]|\bwhen[({]""").containsMatchIn(loop),
        )
        assertTrue(
            "图标必须来自 statusIcon()：写死一个 AllIcons 常量会绕开整套状态映射，18 行挂同一个牌子：$loop",
            loop.contains("icon(statusIcon($item.status))"),
        )
        assertTrue(
            "第二列必须是语言显示名，否则用户认不出这一行说的是哪门语言：$loop",
            loop.contains("label($item.language.displayName)"),
        )
        // 这两条必须**封口**。写成前缀（`label(statusText($item,` / `renderRemedy(`）时，
        // `label(statusText(finding, agentType).let { "" })` 与
        // `renderRemedy(it.copy(command = null))` 都能满足断言，而后果分别是
        // 「第三列 18 行全空」和「全部安装命令消失」——与必红清单里的 S9 / S10 同一后果。
        // 封口不会重新引入格式误报：`compact()` 已经抹掉了尾逗号，IDEA 拆行后正是这个串。
        assertTrue(
            "第三列必须来自 statusText() 且不得再加工：接一句 .let { \"\" } 就是整列空白：$loop",
            loop.contains("label(statusText($item,agentType))"),
        )
        assertTrue(
            "每条缺口下面必须原样挂上它的修复命令：删掉这一句、或给 renderRemedy 喂一个" +
                "加工过的 remedy，所有安装命令都会消失：$loop",
            Regex("""\Q$item\E\.remedy\?\.let\{(\w+->)?renderRemedy\(\w+\)\}""").containsMatchIn(loop),
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
     *（`val findings = findings.filter { … }`）——那种写法下前缀链的匹配也会断，
     * 但两道网各自还能挡住对方漏掉的变体，成本又只有一行。
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
            renderRemedyBody().contains("PARENT_GRID"),
        )
    }

    /**
     * 缺口下面那条安装命令是这一页**唯一可执行的产出**——「该敲哪条命令」就是它。
     *
     * 之前这个函数体上一个钉子都没有。实测把
     * `remedy.command?.let { command -> … }` 改成
     * `remedy.command?.takeIf { … }?.let { command -> … }`：`CopyPasteManager`、
     * `settings.lsp.copy`、整段命令行结构全部原样留在源码里，全部安装命令消失，
     * 620 条测试全绿。所以钉的是**从 command 直接进 let 的那一串**，
     * 中间插任何东西都会断。
     *
     * 分支只准出现在 `remedy.command == null` 那一条兜底上，所以「不得有条件」
     * 只在命令块**内部**说——在整个函数体上说会把那条合法兜底一起判死。
     */
    @Test
    fun `有安装命令时必须无条件给出命令与复制按钮`() {
        val body = compactArgs(renderRemedyBody())

        // lambda 形参名一并捕获，IDE 把 `command` 改名 `cmd` 是纯重构，不该红。
        val cmd = Regex("""remedy\.command\?\.let\{(\w+)->""").find(body)?.groupValues?.get(1)
            ?: throw AssertionError(
                "command 与 let 之间插一句 takeIf，全部安装命令就消失了，而所有字面量原样还在：$body",
            )

        val commandBlock = compactArgs(bodyAfter("remedy.command?.let", '{'))

        assertFalse(
            "命令行不得再被条件挡住：$commandBlock",
            Regex("""\bif[({]|\bwhen[({]|\.visible|\.enabled""").containsMatchIn(commandBlock),
        )
        assertTrue(
            "命令本身必须原样显示出来；接一句 .let { \"\" } 就只剩一个复制按钮：$commandBlock",
            commandBlock.contains("label($cmd)"),
        )
        assertTrue(
            "命令旁必须有复制按钮，且把原样的命令送进平台剪贴板：$commandBlock",
            commandBlock.contains("CopyPasteManager.copyTextToClipboard($cmd)"),
        )
        // 这一行是「激活 / 安装」按钮**唯一**的调用点。删掉它，界面上所有执行按钮当场
        // 消失，而 runRemedyButton 自己那条整段断言读的是一段没人调用的死代码，照样全绿
        //（bodyAfter 的「锚点不得出现两次」只抓重复锚点，抓不到断开的调用链）。
        // 与上面 CopyPasteManager 那条同一把尺子：调用点与被调用者两头都要钉。
        assertTrue(
            "执行按钮必须挂在命令行上；删掉这一句，所有「激活 / 安装」按钮从界面消失，" +
                "而 runRemedyButton 变成没人调用的死代码、它自己的断言仍然全绿：$commandBlock",
            commandBlock.contains("runRemedyButton(remedy,$cmd)"),
        )

        assertTrue(
            "没有已知安装命令时至少要给出上游文档，不能让用户卡在「不可用」三个字上：$body",
            Regex("""remedy\.docsUrl\?\.let\{(\w+)->row\{browserLink\(\1,\1\)\}\}""").containsMatchIn(body),
        )
    }

    private fun renderRemedyBody(): String = bodyAfter("private fun Panel.renderRemedy(remedy: Remedy)", '{')

    /**
     * 执行按钮那四行**整段钉死**——这是本文件里唯一一个职责就是「可见性」的函数。
     *
     * 前一版用的是逐条列举被禁 token 的黑名单（`contains("if(!canRun(…))return")` 加
     * 一条 `SystemInfo\.(?!isMac)` 的否定），实测三种「加法」全部漏过去：
     *
     * 1. 守卫**后面**补一句 `if (!SystemInfo.isMac) return`——那条否定断言只禁
     *    `isMac` **以外**的成员，而这句用的正是 `isMac`。后果：Linux 用户的「激活」
     *    按钮整体消失，那恰恰是这一轮才刚为他们放开的。
     * 2. `if (remedy.kind.ordinal == 1) return`——不含 `RemedyKind.` 这个字符串。
     *    后果：所有「安装」按钮消失。
     * 3. `button(…) { … }.visible(false)`——同文件另外两处渲染函数都明令禁了
     *    `.visible` / `.enabled`，偏偏漏在这个函数上。后果：按钮全部消失。
     *
     * 三种都是**加法**：被钉住的那一句一字未改。逐条列举的黑名单永远漏得掉下一种写法，
     * 整段比对漏不掉。先例是 [statusText] 与 [statusIcon] 那两条。
     *
     * 守卫刻意写成**带大括号**的形式并按这个形式钉住：`if (…) return` 加大括号是一次
     * IDEA intention、零语义变化，若按不带括号的形式钉，那次纯格式操作会让这条变红，
     * 而失败信息说的是「Windows 用户就会看到安装按钮」——正是本文件警告过的那种误导。
     * 写成已经带括号的形式之后，那个 intention 变成 no-op。
     *
     * 代价是改动这四行要来这里点头一次。这是刻意的：这四行每一次改动都直接决定
     * 「谁能看到这个按钮、点下去跑什么」。
     */
    @Test
    fun `执行按钮的可见性只能来自 canRun`() {
        assertSameCode(
            "这四行决定「谁看得到这个按钮、点下去跑什么」。守卫后面补一句 return、" +
                "按 kind.ordinal 分支、给 button 链一个 .visible(false)，" +
                "三种都是加法且都能让按钮整体消失——所以整段比对，不列举被禁写法。" +
                "\n（整段比对对空白、换行、尾逗号、具名实参都免疫，但**对大括号敏感**：" +
                "守卫刻意写成带括号的形式，「加大括号」那次 intention 因此是 no-op，" +
                "而反方向去掉括号会红。若你只是动了排版，照下面的「期望」抄回去即可。）",
            """
            {
                if (!canRun(remedy, SystemInfo.isMac, !SystemInfo.isWindows)) {
                    return
                }
                if (!hasProjectWindow()) {
                    return
                }
                button(ImuxBundle.message(runActionKey(remedy.kind))) { event ->
                    runInTerminal(remedy, command, event)
                }
            }
            """,
            runRemedyButtonBody(),
        )
    }

    private fun runRemedyButtonBody(): String =
        bodyAfter("private fun Row.runRemedyButton(remedy: Remedy, command: String)", '{')

    /**
     * 整份源码里不得有第二处平台判断，也不得自己按
     * [com.github.izerui.imux.lsp.RemedyKind] 分支。
     *
     * 上一条整段钉住了 `runRemedyButton`，但闸门还能在**别的函数**里被架空：
     * 在 `renderRemedy` 或 `findingsPanel` 里补一句平台判断，上一条一字不改仍然全绿。
     * 这一条覆盖全文件，两条各管一层。
     *
     * 放行 `isMac` 与 `isWindows` 两个成员，因为 `canRun` 的两个实参正是它们；
     * 别的成员（`isLinux`、`isUnix`、`isWin10OrNewer`…）一律禁——出现即意味着壳里
     * 长出了第二套平台逻辑，而闸门语义只该住在被真调用测试钉住的那个纯函数里。
     */
    @Test
    fun `壳里不得有第二处平台或性质判断`() {
        assertFalse(
            "平台判断只能是喂给 canRun 的那两个实参（SystemInfo.isMac / isWindows）；" +
                "壳里长出第三个平台成员，闸门就不在被真调用测试钉住的纯函数里了",
            Regex("""SystemInfo\.(?!isMac\b|isWindows\b)\w+""").containsMatchIn(normalized),
        )
        assertFalse(
            "壳里不得出现 RemedyKind 常量：按性质分支就能在两个键都还在源码里的前提下" +
                "把「激活」和「安装」对调，或者干脆藏掉一类按钮",
            normalized.contains("RemedyKind."),
        )
    }

    /**
     * 开标签那条 builder 链**整条钉死，顺序一起钉**。
     *
     * 逐项 `contains` 在这里不够，builder 的 setter 是 last-wins（纯 `putfield`）：
     * `.closeOnProcessTermination(false).closeOnProcessTermination(true)` 里两个
     * `contains` 都能满足，而行为整个翻转。整条比对之后，链里插一项、改一项、
     * 重排一项都会断。
     *
     * 链上每一项失败时用户看到的是什么：
     *
     * - `closeOnProcessTermination(false)`：命令跑完标签页就关，输出闪一下没了。
     *   它的默认值不是常量而是用户设置 `TerminalOptionsProvider.closeSessionOnLogout`，
     *   所以这一句消除的是对一项用户设置的依赖，不只是覆盖一个默认值。
     * - `shellCommand(runCommandLine(resolveShell(…)))`：这一层给的是 `-l -i -c`。
     *   从 Dock 启动的 IDE 只有系统默认 PATH，`brew`/`go`/`npm`/`rustup`/`gem` 一个都
     *   不在里面——壳里自己拼一个 `listOf(shell, "-c", command)`，`LspRemedyRunTest`
     *   那组行为测试全绿而每一条安装命令都变成 `command not found`。
     *   这个坑项目里踩过两次，且从终端 `runIde` 起的沙箱继承了终端 PATH，
     *   **在沙箱里永远复现不出来**。
     * - `tabName(runTabName(…))`：改成空串，用户同时开着几个安装标签时认不出哪个是哪个。
     * - `requestFocus(true)`：改成 false，命令在一个没人看的后台标签里跑，等于静默执行。
     * - `workingDirectory(…)`：`project.basePath` 为 null（默认项目、轻量编辑）时
     *   传 null 会让标签起不来。
     */
    @Test
    fun `开标签那条链必须原样`() {
        assertTrue(
            "builder 的 setter 是 last-wins，逐项 contains 拦不住「后面再覆盖一次」；" +
                "所以整条链连顺序一起钉。每一项的用户可见后果见本用例的 KDoc：" +
                runInTerminalBody(),
            compactArgs(runInTerminalBody()).contains(
                compactArgs(
                    """
                    TerminalToolWindowTabsManager.getInstance(project)
                        .createTabBuilder()
                        .workingDirectory(project.basePath ?: System.getProperty("user.home"))
                        .shellCommand(runCommandLine(resolveShell(System.getenv("SHELL")), command))
                        .tabName(runTabName(ImuxBundle.message(runActionKey(remedy.kind)), command))
                        .requestFocus(true)
                        .closeOnProcessTermination(false)
                        .createTab()
                    """,
                ),
            ),
        )
    }

    private fun runInTerminalBody(): String =
        bodyAfter("private fun runInTerminal(remedy: Remedy, command: String, event: ActionEvent)", '{')

    /**
     * 跑完**不自动刷新**报告。
     *
     * 命令在终端里异步跑，插件不知道它什么时候结束——猜一个时机（延时几秒、或者干脆
     * 点完就刷）只会给出更假的信息：用户看到的是一份在安装完成之前采集的报告，
     * 上面还写着「未安装」，而终端里正跑得好好的。页面顶部就有「重新检测」。
     *
     * 断言写成「执行路径上不得调用 refresh()」：`refresh()` 在 createPanel 与按钮回调里
     * 都有合法用法，在整份源码上数没有意义。
     */
    @Test
    fun `执行完不自动刷新报告`() {
        assertFalse(
            "命令在终端里异步跑，我们不知道它什么时候结束；猜一个时机刷新只会显示一份" +
                "比现在更假的报告。让用户自己点「重新检测」",
            compactArgs(runInTerminalBody()).contains("refresh()"),
        )
        assertFalse(
            "同上：按钮回调里也不许顺手刷一下",
            compactArgs(runRemedyButtonBody()).contains("refresh()"),
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
     * 把 `?: return readyServerText(…)` 改成 `?: return ""`，就绪那一列当场变成一整列
     * 空白（那正是 D2 决策要避免的形态）；在 `ImuxBundle.message(key)` 后面接一句
     * `.replace(…)`，文案照样可以被改写——两种都不含 `LspStatus.`、也不含写死的键。
     *
     * 这里保留整段比对，是因为这四行里没有一个尾逗号、没有一个可拆行的长参数表，
     * 也没有可改名的循环变量——「敏感面比不变量大出一圈」那个问题在这里不存在。
     */
    @Test
    fun `状态文案必须全部来自纯映射，壳里不得自己判断`() {
        val text = bodyAfter(
            "private fun statusText(finding: LanguageFinding, agentType: AgentType): String",
            '{',
        )
        assertFalse("壳里不得再按状态分支，那正是绕过行为测试的路：$text", text.contains("LspStatus."))
        assertFalse("文案键只能来自 statusMessageKey，壳里不得写死：$text", text.contains("\"settings.lsp.status."))

        // 两条各说一种用户可见的坏结果，再由整体比对兜住剩下的——
        // 否则「就绪列整列空白」和「文案被改写」会共用同一条失败消息。
        assertTrue(
            "没有文案键的（只有 READY）必须原样显示 server 二进制名；改成空串、或者接一句" +
                " .let { \"\" }，都是就绪那一列 18 行全空：$text",
            compactArgs(text).contains("?:returnreadyServerText(finding.language,agentType)return"),
        )
        assertTrue(
            "取到键之后必须原样交给 ImuxBundle；后面接一句 .replace(…) 就能把任何一条状态说反：$text",
            compactArgs(text).contains("returnImuxBundle.message(key)}"),
        )

        assertSameCode(
            "statusText 必须是取键 → 查 bundle 的薄壳；没有键的（只有 READY）显示 server 二进制名",
            """
            {
                val key = statusMessageKey(finding.status)
                    ?: return readyServerText(finding.language, agentType)
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
        assertTrue("列表必须放进滚动区", normalized.contains("scrollCell("))
        assertTrue(
            "视图必须实现 Scrollable，否则滚动面板会跟着内容一起长，等于没滚动区",
            normalized.contains("Scrollable") &&
                normalized.contains("override fun getPreferredScrollableViewportSize()"),
        )
        assertTrue(
            "可视高度必须封顶，否则 Scrollable 也拦不住",
            normalized.contains("minOf(preferredSize.height, JBUI.scale(MAX_VISIBLE_HEIGHT))"),
        )
        // 封顶只是一半。tracksViewportHeight 为 true 时 JViewport 会把视图高度直接压成
        // 视口高度：内容被挤扁、纵向再也滚不动——与「去掉封顶」是同一个终局，
        // 而上面三条断言一条都拦不住它。
        assertTrue(
            "视图高度不得跟随视口，否则 18 行被压扁且滚不动",
            normalized.contains("override fun getScrollableTracksViewportHeight(): Boolean = false"),
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
            normalized.contains("ImuxBundle.message(\"settings.lsp.ready\", cliReport.ready.size)") &&
                normalized.contains("ImuxBundle.message(\"settings.lsp.gaps\", cliReport.gaps.size)"),
        )
        assertFalse(
            "再把语言名拼成一行就会变回那条撑宽对话框的 116 字符 label",
            normalized.contains("joinToString"),
        )
    }

    /**
     * `CellImpl.align(Align)` 在 `maxLineLength == -1` 时把 `limitPreferredSize` 覆写成
     * `horizontalAlign == FILL`，而 `text()` / `comment()` 的折行**正依赖**
     * `limitPreferredSize == true`。于是 `text(x).align(AlignX.LEFT)` 会静默关掉折行：
     * 编译通过、界面看着也正常，直到某个语种的长句把设置对话框撑宽。
     *
     * 本页所有需要折行的文字都不得链 `.align()`——这条路径三道现有防线全都发现不了。
     *
     * 这一条是本文件里**唯一**刻意跑在原始 [source] 上的：正则里的 `[^\n]*` 靠换行
     * 把搜索范围限制在同一行内，而 [normalized] 里一个换行都没有——跑在归一版上，
     * 这个 `[^\n]*` 会横跨整份源码，把 `comment(...)` 与几十行之外某个合法的
     * `cell(content).align(AlignX.FILL)` 连成一条匹配，无条件误红。
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
        assertTrue(normalized.contains("AllIcons."))
        assertFalse("不得引用自定义 svg", normalized.contains(".svg"))
    }

}
