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
     * 用它把断言限定在**某个函数体内**：「rowAction 里不得出现 X」这种话，
     * 在整份源码上说会被别处的合法用法搅黄，在函数体内说才是准的。
     *
     * **锚点必须唯一**。用 `indexOf` 取第一个匹配，意味着「把原函数原封不动留成死代码、
     * 另写一个真正被调用的同形函数」能让整组断言钉在一段没人执行的代码上——Kotlin 对
     * 没人调用的 private 函数只报 warning，拦不住。这里直接把「出现两次」判为失败。
     *
     * 锚点写**完整签名**（含形参名），而返回的切片正是从锚点末尾算起——把锚点截短到
     * 左括号可以让它对形参改名免疫，但那样切片会连形参表一起带进来，下游的整段比对
     * 全部要跟着改写，得不偿失。代价是 Rename 一个形参会走到「找不到锚点」这条路上，
     * 所以那句失败信息必须**自己把这种可能说出来**，别让维护者以为函数被删了。
     */
    private fun bodyAfter(anchor: String, open: Char): String {
        val (packed, map) = compactIndex
        val needle = compact(anchor)
        val at = packed.indexOf(needle)
        assertTrue(
            "源码里找不到锚点：$anchor\n" +
                "锚点写的是完整签名。如果你只是给形参改了个名（纯重构），把这里的签名同步过去即可；" +
                "只有在函数确实被删掉或拆开时，这条失败才意味着逻辑变了。",
            at >= 0,
        )
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

    /**
     * 一个按钮都放不出来时，页面必须**自己说清楚为什么**。
     *
     * 用户原话：「没看到激活按钮啊，没有任何操作按钮是咋回事」。这一页是
     * `applicationConfigurable`，从欢迎页打开设置是完全正常的路径，而那时
     * [hasProjectWindow] 一关，`runRemedyButton` 全线返回 false，整页退化成
     * 「一列数据 + 一列短名」——连第四列那个 `kotlin-lsp` 都读不成「可操作信息」。
     * 技术原因是实的（终端工具窗口是项目级的），但那是实现细节，不说出来用户只会
     * 认为插件坏了。
     *
     * 两条各挡一头：
     *
     * 1. 说明必须存在，且必须用 `comment`——它是本页最长的几句之一（德语 190 字符），
     *    不折行的 `label` 会直接把设置对话框撑宽，本页已为此返工过两轮。
     * 2. 判据必须**复用同一个** [hasProjectWindow]。另写一句「有没有项目」（哪怕逻辑
     *    看着一样）就是第二处闸门：两处一旦漂移，用户会看到「说明说没按钮、按钮却在」
     *    或者反过来的组合，比不说更糟。
     */
    @Test
    fun `没有项目窗口时必须解释为什么一个按钮都没有`() {
        val panel = compactArgs(bodyAfter("override fun createPanel(): DialogPanel", '{'))

        assertTrue(
            "从欢迎页打开设置时一个执行按钮都不会有，页面必须说明原因，否则用户只会认为插件坏了：$panel",
            panel.contains(
                "if(!hasProjectWindow()){row{icon(AllIcons.General.Information)" +
                    "comment(ImuxBundle.message(\"settings.lsp.no.project\"))}}",
            ),
        )
        // 「没有项目窗口」在源码里只该有这两处否定用法：这句说明，以及 runRemedyButton
        // 那道闸门。另写一句「有没有项目」（哪怕逻辑看着一样）就是第三处判据。
        assertEquals(
            "「有没有项目窗口」只能有一处判据。这句说明与 runRemedyButton 那道闸门必须是" +
                "同一个 hasProjectWindow()，否则两处漂移之后，用户会看到「说明说没按钮、" +
                "按钮却在」这种自相矛盾的组合",
            2,
            Regex("""!hasProjectWindow\(\)""").findAll(compactArgs(normalized)).count(),
        )
    }

    /**
     * 重新探测期间**不许把整页换成一句「正在检测…」**。
     *
     * 这一页的探测有两个入口，只有一个该看到那句话：
     *
     * - **首次打开**：手里什么都没有，「正在检测…」是唯一能说的话。
     * - **重新探测**：手动点「重新检测」，或者一条安装命令跑完之后自动来这一趟。
     *   这时整页闪成空白再重画，等于用一秒多的空白盖掉一张用户正在读的表；
     *   命令刚跑完那次尤其刺眼——用户盯着的就是那一行。
     *
     * 断言钉的是**分派那一行**，因为这是一处「删掉之后代码看起来完全正常」的改动：
     * 写回一句无条件的 `showChecking()`，编译通过、所有别的断言全绿，闪烁原样回来。
     *
     * 「失败态必须与进行中长得不一样」那条既有约定没有被动到：[showChecking] 与
     * [showFailed] 仍是两句不同的文案、仍由 `if (report == null)` 分派（见
     * [体检失败要留日志并显示错误态]），变的只是「进行中」什么时候需要露面。
     */
    @Test
    fun `重新探测时保留上一份结果而不是闪回正在检测`() {
        val body = compactArgs(bodyAfter("private fun refresh()", '{'))

        assertTrue(
            "手里已经有一份结果时必须原样摆着，只有首次打开才显示「正在检测…」：$body",
            body.contains("valprevious=lastReportif(previous==null)showChecking()elseshowReport(previous)"),
        )
    }

    /**
     * 复制按钮已随本轮改版删除（用户原话「也不需要复制了吧」），键也从十个语言文件里
     * 一起删了。守它的那条断言随之作废——但**否定断言必须留下**：再引用一个不存在的键，
     * 界面上会显示成 `!settings.lsp.copy!`，而 `ImuxBundleTest` 只比对十个文件之间的
     * 键集合一致性，源码里引用一个谁都没有的键它看不见。
     */
    @Test
    fun `复制按钮已删除且不得复活`() {
        assertFalse(
            "settings.lsp.copy 已从十个语言文件里删除，再引用就是取一条空消息",
            normalized.contains("settings.lsp.copy"),
        )
        assertFalse(
            "命令现在收在按钮的 tooltip 里，页面上不再有复制按钮",
            normalized.contains("CopyPasteManager"),
        )
    }

    /**
     * 页面上出现的每一个 bundle 键都必须在资源包里真实存在。
     *
     * 纯函数那一侧（`runActionKey` / `statusMessageKey` / `runningStatusKey`）各自有
     * 双向对齐的用例，但壳里还有十来个直接写死的键（`settings.lsp.docs`、
     * `settings.lsp.checking`…）。它们打错一个字母，界面上就是一个 `!key!`，
     * 编译期查不出、buildSearchableOptions 查不出，只有真跑起来才看得见。
     */
    @Test
    fun `壳里写死的文案键都在资源包里`() {
        val bundle = java.util.Properties().apply {
            File("src/main/resources/messages/ImuxBundle.properties").reader(Charsets.UTF_8).use(::load)
        }
        val missing = Regex(""""(settings\.[\w.]+)"""")
            .findAll(normalized)
            .map { it.groupValues[1] }
            .filterNot(bundle::containsKey)
            .toSet()

        assertEquals("这些键在资源包里不存在，界面上会显示成 !key!：$missing", emptySet<String>(), missing)
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
            // 补一句恒返回同一个键的同名声明，「正在安装…」全部写成「正在激活…」，
            // 用户以为一两秒就完的事其实是几百兆下载。
            "com.github.izerui.imux.lsp.runningStatusKey",
            // 补一句 `runRowKey(agentType, language) = language.id`，在一个分组里点激活，
            // 三个分组的同名语言会一起变成「正在激活…」，而只有一条命令真在跑。
            "com.github.izerui.imux.lsp.runRowKey",
            // 补一句恒返回空串的同名声明，标签名全部变空，用户在几个安装标签之间认不出
            // 哪个是哪个；而整条 builder 链的比对一字不变。
            "com.github.izerui.imux.lsp.runTabName",
            // 闸门挡下按钮时，退路那一格的短标签取自它；补一句恒返回空串的同名声明，
            // 那一格只剩一个看不见的空 label，ACTIVATE 类的行又变回死路一条。
            "com.github.izerui.imux.lsp.runTabTarget",
            "com.github.izerui.imux.lsp.statusIconKind",
            "com.github.izerui.imux.lsp.statusMessageKey",
            "com.github.izerui.imux.terminal.resolveShell",
            "com.intellij.icons.AllIcons",
            "com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager",
            // 「跑完了没有」这个判据只能是平台给的终端会话状态。本文件里补一个同名的
            // 密封类占位，`it is TerminalViewSessionState.Terminated` 会永远为假，
            // 那一行就永远停在「正在激活…」——正是这一轮要修的毛病。
            "com.intellij.terminal.frontend.view.TerminalViewSessionState",
        ).forEach { fqn ->
            val name = fqn.substringAfterLast('.')
            assertTrue(
                "必须 import $fqn；删掉 import 再在本文件补一个同名声明，被钉死的函数体一字不用改就能换掉语义",
                normalized.contains("import $fqn "),
            )
            // 留着 import、同时在本文件补一个同名声明也一样能赢，代价只有一条
            // 「import 未使用」的 warning。上面这批符号连 val 一起禁——
            // `private val readyServerText: (LspLanguage, AgentType) -> String = { _, _ -> "" }`
            // 是能通过编译的写法，而属性优先于 import 进来的顶层函数。
            //
            // 与 [壳里不得用同名声明遮蔽 import 进来的符号] 并存而不是二选一：那条覆盖
            // **全部** import 但为了避开「自初始化绑定」留了一个 carve-out，这条对这批
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
            "createPanel", "isModified", "apply", "disposeUIResources", "refresh", "diagnostics",
            "showChecking", "showFailed", "showReport", "replaceContent",
            "Panel.renderCli", "summaryText", "findingsPanel",
            "Row.rowAction", "Row.groupAction", "Row.fallbackCell",
            "Row.runRemedyButton", "hasProjectWindow", "runInTerminal", "refreshRow", "refreshWhenFinished",
            "targetProject", "groupMessage", "statusText", "rowIcon", "statusIcon",
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
     * 4. 循环体内逐条钉住四件用户直接看得见的事：不许有分支、图标来自 rowIcon、
     *    语言名和状态各占一列、行末挂着这一行自己的操作入口。
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
        // rowAction 那边的姊妹断言一开始就跑在压缩串上，只有这边漏了。
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
            "图标必须来自 rowIcon()：写死一个 AllIcons 常量会绕开整套状态映射，18 行挂同一个牌子：$loop",
            loop.contains("icon(rowIcon($item,agentType))"),
        )
        assertTrue(
            "第二列必须是语言显示名，否则用户认不出这一行说的是哪门语言：$loop",
            loop.contains("label($item.language.displayName)"),
        )
        // 这两条必须**封口**。写成前缀（`label(statusText($item,` / `rowAction(`）时，
        // `label(statusText(finding, agentType).let { "" })` 与 `rowAction(finding, AgentType.PI)`
        // 都能满足断言，而后果分别是「第三列 18 行全空」和「三个分组的按钮全部认错行」。
        // 封口不会重新引入格式误报：`compact()` 已经抹掉了尾逗号，IDEA 拆行后正是这个串。
        assertTrue(
            "第三列必须来自 statusText() 且不得再加工：接一句 .let { \"\" } 就是整列空白：$loop",
            loop.contains("label(statusText($item,agentType))"),
        )
        // 命令行不再单独占一行，操作按钮直接挂在语言行末尾（见 rowAction）。删掉这一句，
        // 界面上所有「激活 / 安装」按钮与文档链接一起消失，整页退回一份只能看的清单——
        // 与从前删掉 renderRemedy 调用点是同一个用户可见后果。
        assertTrue(
            "每一行末尾必须原样挂上它自己的操作入口，且行标识必须由这一行的 finding 与" +
                "所在分组的 agentType 一起决定：$loop",
            loop.contains("rowAction($item,agentType)"),
        )
        // 命令进了 tooltip，就绝不能再有第四列文本把它铺回页面上——那是这轮改版的起点：
        // 一整行原始命令把三个分组撑成密不透风的一大片。
        assertFalse(
            "命令只能出现在按钮的 tooltip 里，不得在语言行上再占一格：$loop",
            loop.contains("label(command)") || loop.contains(".command)"),
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
     * 四列必须对得齐。
     *
     * `Panel.row` 不带 label 时构造的是 `RowLayout.INDEPENDENT`，而 `PanelBuilder`
     * 对 INDEPENDENT 的处理是给每行开一个子网格——**列宽跨行不共享**。默认值下
     * `C | clangd` 与 `TypeScript/JavaScript | installed on demand by pi-lens`
     * 的第三列起点差出上百像素，18 行是一份参差的清单而不是一张表。
     *
     * 从前这里还有第二条断言，说的是「renderRemedy 的命令行不得进父网格」：那时命令
     * 单独占一行，拉进同一个网格会把第一列（图标）撑成
     * `npm install -g typescript-language-server typescript` 的宽度，整张表当场散架。
     * 现在**没有命令行了**——命令收进了按钮的 tooltip，行末只剩一个按钮、一个短目标名
     * 或一个短链接，那条断言守的东西已经不存在。取代它的是
     * [行末那一格整段钉死，闸门挡下按钮时必须仍有内容] 里的 token 否定：`rowAction`
     * 的函数体内不许出现**任何**文本单元。写在那里而不是写成全文件的
     * `label(command)` 否定，是因为后者**依赖 lambda 形参名**——实测把形参 `command`
     * 改名 `cmd`（纯重构，全套断言都接受）再补一句 `label(cmd)`，整条安装命令铺回
     * 语言行、图标列被撑爆，而全部用例全绿。
     */
    @Test
    fun `语言行必须共享列宽`() {
        assertTrue(
            "语言行必须进父网格，默认的 INDEPENDENT 每行各占一个子网格、列宽不共享",
            findingsPanelBody().contains("PARENT_GRID"),
        )
    }

    /**
     * 行末那一格是这一页**唯一可执行的产出**——「下一步该干什么」就是它。
     *
     * **整段钉死**，理由与 [执行按钮的可见性只能来自 canRun] 完全相同：这里每一种缺陷
     * 都是加法，而逐条列举被禁 token 永远漏得掉下一种。两条实测过的漏网：
     *
     * 1. 第一行前面补一句 `if (finding.status == LspStatus.MISSING_CONFIG) return`——
     *    Claude Code 组 13 门语言的「激活」按钮全部消失。
     *    [壳里不得有第二处平台或性质判断] 只禁 `SystemInfo.` 与 `RemedyKind.`，
     *    **不禁 `LspStatus.`**；调用点、前缀链、退路断言一字未改，全绿。
     * 2. 把 lambda 形参 `command` 改名 `cmd`（纯重构），再补一句 `label(cmd)`——
     *    整条安装命令铺回语言行、图标列被撑爆，而按形参名捕获的断言照样满足。
     *
     * 下面几条针对性断言与整段比对并存：整段比对给的是「照期望抄回去」，
     * 这几条给的是「你砸掉的是用户看得见的哪一件事」。
     *
     * 退路（[Row.fallbackCell]）是这轮改版**新长出来**的风险，单独一条用例钉住，
     * 见 [闸门挡下按钮时那一格不得为空]。
     */
    @Test
    fun `行末那一格整段钉死，闸门挡下按钮时必须仍有内容`() {
        val body = compactArgs(rowActionBody())

        assertFalse(
            "rowAction 里不得按 LspStatus 分支：补一句 `if (finding.status == …) return`，" +
                "Claude Code 组 13 门语言的「激活」按钮全部消失，而全文件那条否定只禁" +
                "SystemInfo. 与 RemedyKind.，看不见它：$body",
            body.contains("LspStatus."),
        )
        listOf("label(", "text(", "comment(").forEach { cell ->
            assertFalse(
                "行末这一格里不得出现文本单元：命令一旦回到单元格，第一列（图标）会被整条" +
                    "安装命令撑开，整张表散架——它只能进 tooltip。禁的是 token 而不是" +
                    "`label(command)`，后者依赖 lambda 形参名，改个名就绕过去了：$body",
                body.contains(cell),
            )
        }
        // 这一行是「激活 / 安装」按钮**唯一**的调用点。删掉它，界面上所有执行按钮当场
        // 消失，而 runRemedyButton 自己那条整段断言读的是一段没人调用的死代码，照样全绿
        //（bodyAfter 的「锚点不得出现两次」只抓重复锚点，抓不到断开的调用链）。
        assertTrue(
            "执行按钮必须挂在语言行末尾，且行标识由 runRowKey 现算——写死一个常量，" +
                "三个分组的同名语言会一起变成「进行中」：$body",
            body.contains("runRemedyButton(runRowKey(agentType,finding.language),remedy,command)"),
        )
        assertTrue(
            "退路必须以「有没有真的放上按钮」为准，不能在壳里再判一次平台或命令有无：$body",
            body.contains("if(!placed){fallbackCell(remedy)}"),
        )

        assertSameCode(
            "行末这一格决定「这一行还有没有下一步」。开头补一句按 LspStatus 的守卫、" +
                "command 与 let 之间插一句 takeIf、多补一个 label —— 三种都是加法且都能" +
                "让整列产出消失或让整张表散架，所以整段比对，不列举被禁写法。" +
                "\n（整段比对对空白、换行、尾逗号、具名实参都免疫，但对大括号与形参名敏感。" +
                "若你只是动了排版，照下面的「期望」抄回去即可。）",
            """
            {
                val remedy = finding.remedy ?: return
                val placed = remedy.command?.let { command ->
                    runRemedyButton(runRowKey(agentType, finding.language), remedy, command)
                } ?: false
                if (!placed) {
                    fallbackCell(remedy)
                }
            }
            """,
            rowActionBody(),
        )
    }

    /**
     * 闸门挡下按钮时，那一格**不得为空**。
     *
     * 这是删掉复制按钮之后新长出来的死路，而且比「只影响 Windows」严重得多：
     *
     * - `hasProjectWindow()` 这道闸门在**所有平台**都会关。这一页是
     *   `applicationConfigurable`，从欢迎页打开设置是完全正常的路径，macOS 用户一样撞得上。
     * - 撞上的不是零星几行：Claude Code 组 13 门带官方插件的语言只要没启用，都是
     *   `MISSING_CONFIG` → `ACTIVATE`，而 `ACTIVATE` 的 `docsUrl` **恒为 null**
     *   （`ClaudeCodeLspProbe.remedyFor`）；Codex 组的 `groupRemedy` 也是 null
     *   （`CodexLspProbe`）。只退回文档链接的话，那些行、那一整组就是
     *   「一句警告 + 什么也没有」。
     * - `canRun` 的 KDoc 与 `LspRemedyRunTest` 都写着「这一页在 Windows 上本来是完整
     *   可用的」，followups §10.1 的裁定也建立在这句话上。它必须继续为真，
     *   而让它为真的就是这个函数。
     *
     * 所以：有命令就摆一个不可点的短标签（[runTabTarget] 取出的目标名，用户缺的正是
     * 那个插件名/包名），完整命令挂 tooltip；有文档就再给一个链接。**两个都有时两个都给**
     * ——按平台二选一就是第二处闸门。
     */
    @Test
    fun `闸门挡下按钮时那一格不得为空`() {
        assertSameCode(
            "ACTIVATE 类修复的 docsUrl 恒为 null。这一格退化成只有文档链接，" +
                "Claude Code 组 13 门语言与 Codex 整组就再也读不到那条命令——" +
                "而删掉复制按钮之后，tooltip 是命令唯一的去处",
            """
            {
                remedy.command?.let { command ->
                    label(runTabTarget(command)).applyToComponent { toolTipText = command }
                }
                remedy.docsUrl?.let { url ->
                    browserLink(ImuxBundle.message("settings.lsp.docs"), url)
                }
            }
            """,
            fallbackCellBody(),
        )
        assertFalse(
            "退路里也不许按平台或性质再判一次——那就是第二处闸门；两样都有就两样都给：" +
                fallbackCellBody(),
            Regex("""\bif[({]|\bwhen[({]""").containsMatchIn(compactArgs(fallbackCellBody())),
        )
    }

    private fun rowActionBody(): String =
        bodyAfter("private fun Row.rowAction(finding: LanguageFinding, agentType: AgentType)", '{')

    private fun fallbackCellBody(): String = bodyAfter("private fun Row.fallbackCell(remedy: Remedy)", '{')

    /**
     * 组级修复（pi 没装 pi-lens、Codex 没挂 MCP）也得有按钮，而且必须**共用**同一条闸门。
     *
     * 它是这两个 CLI 的**前置条件**：没补上之前，逐语言列表整个不显示，那一行就是用户
     * 在这一页上唯一能做的事。删掉它，pi 与 Codex 两组退回一句只能看的说明。
     *
     * 行标识不能与任何语言撞车，否则组级按钮一跑，某门语言会跟着变成「正在激活…」。
     */
    @Test
    fun `组级修复也走同一条执行闸门`() {
        val body = compactArgs(bodyAfter("private fun Row.groupAction(agentType: AgentType, remedy: Remedy)", '{'))

        assertTrue(
            "组级修复必须走同一个 runRemedyButton，不能自己另起一套闸门；" +
                "行标识也必须带上 CLI 名，否则某门语言会跟着假装在跑：$body",
            body.contains("runRemedyButton(agentType.name+GROUP_ROW,remedy,command)"),
        )
        assertTrue(
            "组级修复放不上按钮时同样要走退路：Codex 的 groupRemedy 没有 docsUrl，" +
                "这一格空掉的话整组就是「一句警告 + 什么也没有」：$body",
            body.contains("if(!placed){fallbackCell(remedy)}"),
        )
        assertTrue(
            "分组里必须真的渲染出这一格，否则 pi 与 Codex 两组只剩一句说明",
            compactArgs(normalized).contains("row{groupAction(cliReport.agentType,remedy)}"),
        )
    }

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
     * 代价是改动这几行要来这里点头一次。这是刻意的：这几行每一次改动都直接决定
     * 「谁能看到这个按钮、点下去跑什么」。
     *
     * 本轮多进来两项，两项都必须**连实参一起**被这段比对盖住：
     *
     * - `.applyToComponent { toolTipText = command }`——删掉复制按钮之后，这是命令
     *   **唯一**的去处。它没了，用户点下去之前完全不知道要跑什么，而按钮照样在、
     *   照样能点，别的断言一条都不会红。
     * - `.enabled(!running.containsKey(key))`——从前 `.enabled` 是本用例明令列举的
     *   攻击写法（`.enabled(false)` 让按钮全灭）。现在它有了正当语义：命令在终端里
     *   异步跑，不禁用的话用户以为没反应，再点一次就是两个终端抢同一把 brew 锁。
     *   正因为它曾经是攻击面，这里**必须连实参一起钉死**——写成 `.enabled(false)`
     *   或 `.enabled(true)` 分别是「按钮全灭」和「连点不设防」，整段比对两个方向都拦得住。
     */
    @Test
    fun `执行按钮的可见性只能来自 canRun`() {
        assertSameCode(
            "这几行决定「谁看得到这个按钮、点下去跑什么、点下去之前知不知道要跑什么」。" +
                "守卫后面补一句 return、按 kind.ordinal 分支、给 button 链一个 .visible(false)、" +
                "把 .enabled 的实参改成常量、删掉 tooltip——全是加法或换参，" +
                "所以整段比对，不列举被禁写法。" +
                "\n（整段比对对空白、换行、尾逗号、具名实参都免疫，但**对大括号敏感**：" +
                "守卫刻意写成带括号的形式，「加大括号」那次 intention 因此是 no-op，" +
                "而反方向去掉括号会红。若你只是动了排版，照下面的「期望」抄回去即可。）",
            """
            {
                if (!canRun(remedy, SystemInfo.isMac, !SystemInfo.isWindows)) {
                    return false
                }
                if (!hasProjectWindow()) {
                    return false
                }
                button(ImuxBundle.message(runActionKey(remedy.kind))) { event ->
                    runInTerminal(key, remedy, command, event)
                }
                    .enabled(!running.containsKey(key))
                    .applyToComponent { toolTipText = command }
                return true
            }
            """,
            runRemedyButtonBody(),
        )
    }

    private fun runRemedyButtonBody(): String =
        bodyAfter("private fun Row.runRemedyButton(key: String, remedy: Remedy, command: String): Boolean", '{')

    /**
     * 整份源码里不得有第二处平台判断，也不得自己按
     * [com.github.izerui.imux.lsp.RemedyKind] 分支。
     *
     * 上一条整段钉住了 `runRemedyButton`，但闸门还能在**别的函数**里被架空：
     * 在 `rowAction` 或 `findingsPanel` 里补一句平台判断，上一条一字不改仍然全绿。
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
     * - `deferSessionStartUntilUiShown(false)`：**命令根本不跑**。这一项与上一条同类
     *   ——它对抗的是一个平台默认值，删掉之后代码看起来完全正常、编译通过、所有行为
     *   断言全绿，功能却静默失效。262 的 `TerminalToolWindowTabBuilderImpl` 构造里
     *   这个字段默认是 `true`（字节码：`iconst_1 / putfield deferSessionStartUntilUiShown`），
     *   语义是「等这个终端的 UI 真正显示出来再启动会话」。本页开标签时**设置对话框正
     *   挡在前面**，终端工具窗口没被显示，会话就一直挂着。真机实测：点「安装」开出来的
     *   标签通体空白只有一个光标，连登录 shell 的 profile 打印都没有；标签虽然被选中，
     *   「首次显示」的时机已经错过。
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
                        .deferSessionStartUntilUiShown(false)
                        .closeOnProcessTermination(false)
                        .createTab()
                    """,
                ),
            ),
        )
    }

    private fun runInTerminalBody(): String =
        bodyAfter(
            "private fun runInTerminal(key: String, remedy: Remedy, command: String, event: ActionEvent)",
            '{',
        )

    /**
     * 点下按钮那一刻，那一行必须**当场**改样子。
     *
     * 用户原话：「体验感不好，激活后，就状态应该变了啊」。命令在终端里异步跑，几秒到
     * 几分钟不等；这中间页面若纹丝不动，用户唯一能得出的结论就是「点了没用」，然后
     * 再点一次。而开标签会抢焦点，等用户切回设置页时，这一行必须已经不是点之前那样了。
     *
     * 两句缺一不可：只标记不刷新，界面上什么都不会变；只刷新不标记，刷出来的还是旧样子。
     * 顺序也钉住——标记必须在刷新**之前**。
     *
     * **刷新的对象必须是这一行，不是整页。** 这一条是本轮新长出来的约束，起因是用户
     * 那句「点击激活就会刷新设置页，体验不好」：从前这里写的是
     * `lastReport?.let(::showReport)`，为了让一行改个字，把三个分组 54 行整棵组件树
     * 重建一遍——整页闪一下、滚动位置回到顶部，而他刚点的那一行多半已经滚出可视区。
     * 断言因此从「必须调用 showReport」翻面成「必须调用 refreshRow(key, event)」：
     * 守的还是同一件用户可见的事（点下去这一行当场变样），只是把「整页重画」这条
     * 实现路径换掉了，而且顺带禁掉了它——写回 showReport 会让整段比对红。
     *
     * 加上**整段比对**，因为逐条 `contains` 挡不住加法，两条都实测过：
     *
     * 1. 在标记与开标签之间补一句 `running.remove(key)`——「点下立刻变进行中」当场失效，
     *    而上面两条 `contains` 照常命中。
     * 2. 在末尾之前补一句 `return`——自动重新探测整个失效，同样全绿。
     *
     * 开标签那一段还必须包在 `runCatching` 里并在失败时撤回标记：标记写在建标签之前
     * （那是对的，否则抢焦点之后才改就晚了），于是 `createTab()` 一抛异常，那一行会
     * 永久停在「正在激活…」、按钮永久禁用，而 `refresh()` 从不清 `running`——
     * 用户只能关掉整个设置对话框才能复位。
     */
    @Test
    fun `点下按钮那一行立刻变成进行中`() {
        val body = compactArgs(runInTerminalBody())

        assertTrue(
            "点击必须把这一行记成「进行中」，文案键由 runningStatusKey 决定（壳里不许自己按性质分支）：$body",
            body.contains("running[key]=runningStatusKey(remedy.kind)"),
        )
        assertTrue(
            "标记完必须立刻把这一行刷成新样子，否则界面上什么都不会变——正是用户抱怨的第一件事：$body",
            body.contains("running[key]=runningStatusKey(remedy.kind)refreshRow(key,event)"),
        )
        assertTrue(
            "开标签失败必须撤回标记并把这一行刷回去，否则那一行永久停在「正在激活…」、" +
                "按钮永久禁用，而「重新检测」清不掉它：$body",
            body.contains("running.remove(key)refreshRow(key,event)"),
        )
        assertFalse(
            "点一下按钮不得重画整页：showReport 会把三个分组 54 行整棵组件树重建一遍，" +
                "页面闪一下、滚动位置回到顶部，而用户刚点的那一行多半已经滚出可视区" +
                "——用户原话「点击激活就会刷新设置页，体验不好」：$body",
            body.contains("showReport"),
        )

        assertSameCode(
            "点击这条路径上每一种缺陷都是加法：标记与开标签之间插一句 running.remove、" +
                "末尾之前插一句 return、把 runCatching 的失败分支去掉——三种都让逐条 contains" +
                "照常命中。所以整段比对。若你只是动了排版或改了形参名，照下面的「期望」抄回去即可。",
            """
            {
                val project = targetProject(event)
                if (project == null) {
                    LOG.warn("没有可用的项目窗口，无法执行：${'$'}command")
                    return
                }
                running[key] = runningStatusKey(remedy.kind)
                refreshRow(key, event)
                val tab = runCatching {
                    TerminalToolWindowTabsManager.getInstance(project)
                        .createTabBuilder()
                        .workingDirectory(project.basePath ?: System.getProperty("user.home"))
                        .shellCommand(runCommandLine(resolveShell(System.getenv("SHELL")), command))
                        .tabName(runTabName(ImuxBundle.message(runActionKey(remedy.kind)), command))
                        .requestFocus(true)
                        .deferSessionStartUntilUiShown(false)
                        .closeOnProcessTermination(false)
                        .createTab()
                }.onFailure {
                    LOG.warn("开终端标签失败，撤回这一行的进行中标记：${'$'}command", it)
                    running.remove(key)
                    refreshRow(key, event)
                }.getOrNull() ?: return
                refreshWhenFinished(key, tab.view)
            }
            """,
            runInTerminalBody(),
        )
    }

    /**
     * 就地更新那一行的三个组件——**这是「点一下不重画整页」的落点**。
     *
     * 上一条只管住了「runInTerminal 必须叫 refreshRow」。这一条管的是 refreshRow 本身
     * 真的把用户看得见的三样东西都改了，且都从**同一个数据源**推出来。每一行失败时
     * 用户看到的是什么：
     *
     * 1. 按钮不禁用 → 用户以为没反应，再点一次，同一条 `brew install` 开出两个标签
     *    抢同一把锁。它取自 `event.source`，所以语言行与组级行走同一条路（组级行在
     *    [rowCells] 里查不到，正好在下一句退出，按钮已经处理完了）——**这一句必须排在
     *    查表之前**，顺序由整段比对保证；排到后面的话组级按钮永远不会被禁用。
     * 2. 状态文案不变 → 「点了没用」，这正是用户抱怨的原话。
     * 3. 图标不变 → 一列绿勾黄叹号里夹着一句「正在激活…」，看起来像显示出错。
     *
     * 值必须回头问 [rowIcon] / [statusText]，不许在这里现算。那两个函数第一件事就是查
     * `running`，于是「切进行中」与「撤回」共用同一段代码；这里若改成
     * `cells.icon.icon = AllIcons.Process.Step_4`，撤回那条路就再也改不回去——
     * 开标签失败之后那一行会顶着转圈图标却写着「未启用插件」，而上面几条 contains 全绿。
     *
     * 整段比对而不是逐条 token：这里每一种缺陷同样是加法（多一句 `return`、
     * 把两句赋值之一删掉、在中间插一个按状态的分支）。
     */
    @Test
    fun `就地更新那一行而不是重画整页`() {
        val body = compactArgs(refreshRowBody())

        assertFalse(
            "就地更新里出现 showReport 就等于绕回整页重画，本轮改动直接作废：$body",
            body.contains("showReport"),
        )
        assertTrue(
            "被点的按钮必须当场禁用，否则用户以为没反应会再点一次，" +
                "同一条 brew install 开出两个标签抢同一把锁：$body",
            body.contains("(event.sourceas?JComponent)?.isEnabled=!running.containsKey(key)"),
        )

        assertSameCode(
            "这四行就是「点一下按钮，那一行当场变样」的全部实现。图标与文案必须回头问 " +
                "rowIcon / statusText（它们会先查 running），在这里现算一个「进行中的样子」" +
                "会让撤回那条路再也改不回去。若你只是动了排版，照下面的「期望」抄回去即可。",
            """
            {
                (event.source as? JComponent)?.isEnabled = !running.containsKey(key)
                val cells = rowCells[key] ?: return
                cells.icon.icon = rowIcon(cells.finding, cells.agentType)
                cells.status.text = statusText(cells.finding, cells.agentType)
            }
            """,
            refreshRowBody(),
        )
    }

    private fun refreshRowBody(): String =
        bodyAfter("private fun refreshRow(key: String, event: ActionEvent)", '{')

    /**
     * 就地更新拿得到组件，前提是渲染时把它们**留下来了**。
     *
     * 这一条挡的是最安静的一种失效：`findingsPanel` 里不再往 [rowCells] 里写，
     * `refreshRow` 一字不改仍然编译通过、仍然照常执行，只是 `rowCells[key]` 恒为 null，
     * 于是每一次点击都在第二行悄悄 return——按钮禁用了，图标和文案纹丝不动。
     * 用户看到的与改动之前**一模一样**（「点了没用」），而上面那两条整段比对全绿。
     *
     * 行标识必须由 [runRowKey] 现算，与 `rowAction` 那一侧用的是同一把尺子：
     * 这里写死一个常量或换一把尺子，写进去的与查出来的对不上，后果同上。
     */
    @Test
    fun `语言行渲染时必须把可更新的组件留下来`() {
        val loop = compactArgs(bodyAfter("findings.forEach", '{'))

        assertTrue(
            "第一列的组件必须留下来，否则点下按钮之后图标改不动：$loop",
            loop.contains("valiconLabel=icon(rowIcon(finding,agentType)).component"),
        )
        assertTrue(
            "第三列的组件必须留下来，否则点下按钮之后状态文案改不动——「点了没用」：$loop",
            loop.contains("valstatusLabel=label(statusText(finding,agentType)).component"),
        )
        assertTrue(
            "两个组件必须以 runRowKey 现算的行标识入表，与 rowAction 那一侧同一把尺子；" +
                "不写进去的话每次点击都在 rowCells[key] 处悄悄 return，界面纹丝不动：$loop",
            loop.contains(
                "rowCells[runRowKey(agentType,finding.language)]=" +
                    "RowCells(finding,agentType,iconLabel,statusLabel)",
            ),
        )
        assertTrue(
            "整页重建之前必须清空映射，否则表里留的是一批已经从组件树上摘掉的旧 JLabel，" +
                "改它们不报错也没有任何效果——最难查的那种静默失效",
            compactArgs(bodyAfter("private fun showReport(report: LspReport)", '{'))
                .startsWith("{lastReport=reportrowCells.clear()"),
        )
    }

    /**
     * 跑完之后**自动**重新探测——这是本轮改动的核心。
     *
     * 从前这里写的是「跑完不自动刷新」，理由是「命令在终端里异步跑，我们不知道它什么
     * 时候结束，猜一个时机只会给出更假的信息」。理由没错，结论错了：262 的
     * `TerminalView.sessionState` **不用猜**，它会明确走到 `Terminated`。旧的那条断言
     * 守的是「不要瞎猜时机」，而现在没有猜——所以它被这一组取代，而不是被删掉让地方。
     *
     * 三条各自对应一种「那一行永远停在『正在激活…』」：
     *
     * 1. 判据必须是 `Terminated`。改成 `Running` 就是命令刚起就刷新，报告里还写着
     *    「未安装」——比不刷新更假，正是旧断言警告过的那种后果。
     * 2. **收集协程不得挂在 `view.coroutineScope` 上**。用户中途关掉终端标签会取消那个
     *    scope，挂上去的协程当场没了，没有任何人来把这一行从「进行中」放出来。
     * 3. 反过来，关标签这条路必须被显式接住：`sessionState` 在 scope 取消后再也不会
     *    走到 `Terminated`，只等它就是干等到天荒地老。
     *
     * 「不得挂在 `view.coroutineScope` 上」**不能写成禁 `.launch` 这一种写法**。实测：
     *
     * ```
     * val pageScope = scope ?: return
     * pageScope.launch { }                  // 满足所有前缀断言
     * val tabScope = view.coroutineScope    // 一行局部别名
     * tabScope.launch { …原样搬过来… }
     * ```
     *
     * 全绿，而用户一关终端标签那一行就永远停在「正在激活…」、按钮永久禁用。
     * 所以判据改成**出现次数**：`view.coroutineScope` 在这个函数体里只准出现一次，
     * 而那一次必须是紧跟 `.coroutineContext.job.invokeOnCompletion` 的那一次。
     * 再加整段比对兜底——它对「换个名字接着用」这类改法一律不敏感也不放过。
     */
    @Test
    fun `执行后必须等终端会话终止再重新探测`() {
        val body = compactArgs(refreshWhenFinishedBody())

        assertTrue(
            "开完标签必须挂上等待，否则「激活完状态就该变了」这件事根本不会发生",
            compactArgs(runInTerminalBody()).contains("refreshWhenFinished(key,tab.view)"),
        )
        assertTrue(
            "判据只能是 Terminated：等 Running 等于命令刚起就刷新，报告比不刷新还假：$body",
            body.contains("view.sessionState.first{itisTerminalViewSessionState.Terminated}"),
        )
        assertEquals(
            "view.coroutineScope 在这里只准出现一次。留一个局部别名（val tabScope = " +
                "view.coroutineScope）再拿它 launch，禁 `.launch` 那种写法的断言一条都看不见，" +
                "而用户一关终端标签，那一行就永远停在「正在激活…」：$body",
            1,
            Regex("""view\.coroutineScope""").findAll(body).count(),
        )
        assertTrue(
            "唯一那次 view.coroutineScope 必须用来接住「标签被关掉」——scope 一取消，" +
                "sessionState 就再也不会走到 Terminated，只等它就是干等到天荒地老：$body",
            body.contains("view.coroutineScope.coroutineContext.job.invokeOnCompletion{terminated.cancel()}"),
        )
        assertTrue(
            "收集必须挂在页面自己的作用域上：$body",
            body.contains("valpageScope=scope?:returnpageScope.launch{"),
        )
        assertTrue(
            "两条路必须汇到同一个出口：命令跑完与标签被关掉，都要把这一行从「进行中」放出来：$body",
            body.contains("running.remove(key)"),
        )

        assertSameCode(
            "这个函数就是「等命令跑完」这一件事，而它的每一种缺陷都是加法：" +
                "running.remove 之后插一句 return@launch，自动重新探测彻底失效而上面几条" +
                "contains 全部照常命中。所以整段比对。若你只是动了排版或改了形参名，照下面的「期望」抄回去。",
            """
            {
                val pageScope = scope ?: return
                pageScope.launch {
                    val terminated = launch {
                        view.sessionState.first { it is TerminalViewSessionState.Terminated }
                    }
                    val closed = view.coroutineScope.coroutineContext.job.invokeOnCompletion { terminated.cancel() }
                    try {
                        terminated.join()
                    } finally {
                        closed.dispose()
                    }
                    running.remove(key)
                    val ticket = refreshRequest.incrementAndGet()
                    delay(REFRESH_DEBOUNCE_MS)
                    if (ticket == refreshRequest.get()) {
                        ApplicationManager.getApplication().invokeLater(
                            { if (scope != null) refresh() },
                            ModalityState.any(),
                        )
                    }
                }
            }
            """,
            refreshWhenFinishedBody(),
        )
    }

    /**
     * 连点多个按钮时，重新探测必须被合并成一次。
     *
     * 18 门语言、缺口往往不止一处，用户一口气点四五个「激活」是常态。每个标签跑完都要求
     * 重新探测，而**一次探测就是一个 `zsh -l -i`**——五个登录 shell 同时读 profile，
     * 而且先发起的可能后返回，最终显示的会是更旧的结果。这和「连点重新检测」是同一类
     * 缺陷，只是触发点从一个按钮变成了十几个。
     *
     * 页面关掉之后更不该再刷：几分钟后 `brew install` 跑完，一个早已 dispose 的
     * Configurable 还会白起一个登录 shell。
     */
    @Test
    fun `多个命令同时跑完只重新探测一次`() {
        val body = compactArgs(refreshWhenFinishedBody())

        assertTrue(
            "必须取代次号；只 delay 不比对的话，五个请求会变成五次探测：$body",
            body.contains("valticket=refreshRequest.incrementAndGet()"),
        )
        assertTrue(
            "延时之后必须确认自己仍是最后一个请求，否则代次号形同虚设：$body",
            body.contains("if(ticket==refreshRequest.get())"),
        )
        assertTrue(
            "页面 dispose 之后不得再刷新——那会白起一个登录 shell 去更新一块已经没人看的面板：$body",
            body.contains("if(scope!=null)refresh()"),
        )
    }

    private fun refreshWhenFinishedBody(): String =
        bodyAfter("private fun refreshWhenFinished(key: String, view: TerminalView)", '{')

    /**
     * 页面关掉必须收干净。
     *
     * 等待协程挂在页面自己的作用域上（见上一条），那么这个作用域就必须随 dispose 取消，
     * 否则「不挂在 view 的 scope 上」只是把泄漏换了个地方：设置窗口关掉之后，
     * 一个 `brew install llvm` 还能在几分钟后唤起一次登录 shell 去刷新一块早已 dispose
     * 的面板。
     *
     * [running] 也必须清空：同一个实例被再次 createComponent 时，界面上不该凭空出现
     * 几行「正在激活…」——它们对应的终端标签是上一次会话的事了。
     */
    @Test
    fun `页面关闭时收干净后台等待`() {
        val body = compactArgs(bodyAfter("override fun disposeUIResources()", '{'))

        assertTrue("必须取消页面自己的作用域：$body", body.contains("scope?.cancel()scope=null"))
        assertTrue("「进行中」的标记必须一并清空：$body", body.contains("running.clear()"))
        // 组件映射握着整棵已经 dispose 的组件树。同一个实例被再次 createComponent 时，
        // 那些引用一条都不该活到下一次会话——它们指向的是上一个对话框里的 JLabel。
        assertTrue("行组件映射必须一并清空：$body", body.contains("rowCells.clear()"))
        assertTrue("必须调用父类的清理：$body", body.contains("super.disposeUIResources()"))
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
     * 这里保留整段比对，是因为这几行里没有一个尾逗号、没有一个可拆行的长参数表，
     * 也没有可改名的循环变量——「敏感面比不变量大出一圈」那个问题在这里不存在。
     *
     * 本轮多出来的第一行（查 [running]）**必须排在最前面**，而且必须一起被钉住：
     * 排到 `statusMessageKey` 后面的话，正在跑命令的那一行显示的还是那条尚未被推翻的
     * 旧状态（「未启用插件」），点下按钮之后一个字都不变——正是用户抱怨的那件事。
     * 这个顺序在整段比对里是被保证的。
     */
    @Test
    fun `状态文案必须全部来自纯映射，壳里不得自己判断`() {
        val text = bodyAfter(
            "private fun statusText(finding: LanguageFinding, agentType: AgentType): String",
            '{',
        )
        assertFalse("壳里不得再按状态分支，那正是绕过行为测试的路：$text", text.contains("LspStatus."))
        assertFalse("文案键只能来自 statusMessageKey，壳里不得写死：$text", text.contains("\"settings.lsp.status."))

        // 三条各说一种用户可见的坏结果，再由整体比对兜住剩下的——
        // 否则「就绪列整列空白」「文案被改写」「点了没反应」会共用同一条失败消息。
        assertTrue(
            "正在跑命令的那一行必须优先说「正在激活…」，否则点下按钮之后这一列一个字都不变：$text",
            compactArgs(text).contains("valkey=running[runRowKey(agentType,finding.language)]?:statusMessageKey"),
        )
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
            "statusText 必须是取键 → 查 bundle 的薄壳；正在跑的行优先，" +
                "没有键的（只有 READY）显示 server 二进制名",
            """
            {
                val key = running[runRowKey(agentType, finding.language)]
                    ?: statusMessageKey(finding.status)
                    ?: return readyServerText(finding.language, agentType)
                return ImuxBundle.message(key)
            }
            """,
            text,
        )
    }

    /**
     * 那一行的图标也必须跟着变。
     *
     * 只换文字不换图标的话，一列绿勾与黄叹号里夹着一句「正在激活…」，图标还停在
     * 「未启用插件」的黄叹号上——看起来更像显示出错，而不是「正在处理」。
     *
     * 顺序同样被整段比对保证：进行中排在 [statusIcon] 之前。反过来的话这一行
     * 永远拿不到进行中的图标，而所有别的断言一条都不会红。
     *
     * 图标常量刻意一起钉死：换一个 AllIcons 常量要来这里点头一次，理由与
     * [每个语义类别映到的图标必须钉在壳里] 相同——用户看见的是图标。
     */
    @Test
    fun `进行中的行必须换成进行中的图标`() {
        val icon = bodyAfter("private fun rowIcon(finding: LanguageFinding, agentType: AgentType): Icon", '{')

        assertFalse("壳里不得再按状态分支：$icon", icon.contains("LspStatus."))

        assertSameCode(
            "正在跑命令的那一行必须整体改变面貌：只换文字不换图标，看起来像显示出错",
            """
            {
                val inProgress = running.containsKey(runRowKey(agentType, finding.language))
                return if (inProgress) AllIcons.Process.Step_4 else statusIcon(finding.status)
            }
            """,
            icon,
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
