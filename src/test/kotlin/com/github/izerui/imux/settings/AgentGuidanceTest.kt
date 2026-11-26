package com.github.izerui.imux.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentGuidanceTest {
    private val enFull = agentGuidance(chinese = false, ideaMcp = true, codeTools = true)!!
    private val zhFull = agentGuidance(chinese = true, ideaMcp = true, codeTools = true)!!
    private val enCodeOnly = agentGuidance(chinese = false, ideaMcp = false, codeTools = true)!!
    private val zhCodeOnly = agentGuidance(chinese = true, ideaMcp = false, codeTools = true)!!
    private val enIdeaOnly = agentGuidance(chinese = false, ideaMcp = true, codeTools = false)!!
    private val zhIdeaOnly = agentGuidance(chinese = true, ideaMcp = true, codeTools = false)!!

    private fun investigator(chinese: Boolean, ideaMcp: Boolean, codeTools: Boolean) =
        agentGuidance(chinese, ideaMcp, codeTools, GuidanceRole.INVESTIGATOR)!!

    /** 只属于 IDEA MCP 的工具名：没挂 IDEA MCP 时一个都不能出现。 */
    private val ideaOnlyTools = listOf(
        "rename_refactoring", "build_project", "lint_files", "xdebug_set_breakpoint", "execute_sql_query",
        "search_symbol", "get_symbol_info", "analyze_calls", "reformat_file",
    )

    /** 会改动项目或外部状态的 IDEA 工具：只查不改的角色一个都不能听到。 */
    private val changingTools = listOf(
        "rename_refactoring", "reformat_file", "build_project", "execute_run_configuration", "execute_sql_query",
        "xdebug_set_breakpoint", "xdebug_start_debugger_session", "xdebug_control_session",
        "configure_python_interpreter", "run_inspection_kts",
    )

    /** 引导词里提到的全部工具名：新增工具会自动纳入检查。 */
    private fun toolNames(text: String): Set<String> =
        Regex("""`([a-z]+(?:_[a-z]+)+)`""").findAll(text).map { it.groupValues[1] }.toSet()

    // region 工具组合

    @Test
    fun `两组工具都没挂时，干活的和只查不改的、中文和英文四种情形都不注入引导词`() {
        assertNull(agentGuidance(chinese = false, ideaMcp = false, codeTools = false))
        assertNull(agentGuidance(chinese = true, ideaMcp = false, codeTools = false))
        assertNull(agentGuidance(chinese = false, ideaMcp = false, codeTools = false, role = GuidanceRole.INVESTIGATOR))
        assertNull(agentGuidance(chinese = true, ideaMcp = false, codeTools = false, role = GuidanceRole.INVESTIGATOR))
    }

    @Test
    fun `IDEA MCP 部分的工具名能被提取到，下面两条全称断言不会恒真`() {
        assertTrue(toolNames(enIdeaOnly).containsAll(ideaOnlyTools))
        assertTrue(toolNames(zhIdeaOnly).containsAll(ideaOnlyTools))
    }

    @Test
    fun `只挂 imux_code 时英文引导词不提任何 IDEA MCP 工具`() {
        for (tool in toolNames(enIdeaOnly)) assertFalse(tool, enCodeOnly.contains("`$tool`"))
        assertFalse(enCodeOnly.contains("`idea` (IDEA MCP)"))
        assertTrue(enCodeOnly.contains("- `imux_code`: `file_outline` (a file's structure, from the IDE's structure view) and `find_references`, `call_hierarchy`, `type_hierarchy` (from the IDE's own index)."))
        assertTrue(enCodeOnly.contains("## Map the chain"))
    }

    @Test
    fun `只挂 imux_code 时中文引导词不提任何 IDEA MCP 工具`() {
        for (tool in toolNames(zhIdeaOnly)) assertFalse(tool, zhCodeOnly.contains("`$tool`"))
        assertFalse(zhCodeOnly.contains("`idea`（IDEA MCP）"))
        assertTrue(zhCodeOnly.contains("- `imux_code`：`file_outline`（文件结构，来自 IDE 的结构视图）和 `find_references`、`call_hierarchy`、`type_hierarchy`（走 IDE 自己的索引）"))
        assertTrue(zhCodeOnly.contains("## 摸清链路"))
    }

    @Test
    fun `只挂 imux_code 时找符号这一步不依赖 IDEA 工具`() {
        assertTrue(enCodeOnly.contains("1. Find each symbol you will change, read its declaration, and read the full body"))
        assertTrue(zhCodeOnly.contains("1. 找到要改的每个符号，看它的声明，并把方法体完整读完"))
    }

    @Test
    fun `只挂 IDEA MCP 时英文引导词不提 imux_code 也没有摸清链路一节`() {
        assertFalse(enIdeaOnly.contains("imux_code"))
        assertFalse(enIdeaOnly.contains("find_references"))
        assertFalse(enIdeaOnly.contains("call_hierarchy"))
        assertFalse(enIdeaOnly.contains("## Map the chain"))
        assertTrue(enIdeaOnly.contains("- `idea` (IDEA MCP):"))
        assertTrue(enIdeaOnly.contains("## While editing"))
    }

    @Test
    fun `只挂 IDEA MCP 时中文引导词不提 imux_code 也没有摸清链路一节`() {
        assertFalse(zhIdeaOnly.contains("imux_code"))
        assertFalse(zhIdeaOnly.contains("find_references"))
        assertFalse(zhIdeaOnly.contains("call_hierarchy"))
        assertFalse(zhIdeaOnly.contains("## 摸清链路"))
        assertTrue(zhIdeaOnly.contains("- `idea`（IDEA MCP）："))
        assertTrue(zhIdeaOnly.contains("## 改的时候"))
    }

    @Test
    fun `两组都挂时英文按看清、摸清链路、改、验证、排错的顺序排列，冷门能力放最后`() {
        val order = listOf(
            "## See the code clearly", "## Map the chain", "## While editing", "## After editing", "## When it fails at runtime",
            "## Use when the task calls for it", "## Use your own tools for",
        ).map(enFull::indexOf)
        assertTrue(order.toString(), order.all { it >= 0 })
        assertEquals(order.sorted(), order)
    }

    @Test
    fun `两组都挂时中文按看清、摸清链路、改、验证、排错的顺序排列，冷门能力放最后`() {
        val order = listOf(
            "## 看清代码", "## 摸清链路", "## 改的时候", "## 改完以后", "## 运行时出错",
            "## 用得上再用", "## 这些用你自己的工具",
        ).map(zhFull::indexOf)
        assertTrue(order.toString(), order.all { it >= 0 })
        assertEquals(order.sorted(), order)
    }

    /** 节是按工具组合和角色取舍的，带编号就会断（只挂 IDEA MCP 时曾经从「2.」开始）。 */
    @Test
    fun `三种工具组合乘两种角色乘两种语言，十二份引导词的节标题都不带编号`() {
        val numbered = Regex("""(?m)^## \d""")
        val all = buildList {
            for (chinese in listOf(false, true)) for (role in GuidanceRole.entries) {
                add(agentGuidance(chinese, ideaMcp = true, codeTools = true, role = role)!!)
                add(agentGuidance(chinese, ideaMcp = true, codeTools = false, role = role)!!)
                add(agentGuidance(chinese, ideaMcp = false, codeTools = true, role = role)!!)
            }
        }
        assertEquals(12, all.size)
        for (text in all) assertFalse(text.lineSequence().first { it.startsWith("## ") }, numbered.containsMatchIn(text))
    }

    @Test
    fun `只挂 IDEA MCP 时英文引导词保留找符号、看声明和 analyze_calls 查调用`() {
        assertTrue(enIdeaOnly.contains("- What a name refers to: `search_symbol` finds it by name; `get_symbol_info` shows its declaration, type, and docs."))
        assertTrue(enIdeaOnly.contains("- Who calls a function and what it calls: `analyze_calls`."))
        assertTrue(enIdeaOnly.contains("when it answers `No callable symbol found`, do not guess names — use the LSP tool's `incomingCalls` / `findReferences`, or text search."))
    }

    @Test
    fun `只挂 IDEA MCP 时中文引导词保留找符号、看声明和 analyze_calls 查调用`() {
        assertTrue(zhIdeaOnly.contains("- 一个名字指的是什么：`search_symbol` 按名字找到它，`get_symbol_info` 看它的声明、类型和文档。"))
        assertTrue(zhIdeaOnly.contains("- 谁调用了某个函数、它又调用了谁：`analyze_calls`。"))
        assertTrue(zhIdeaOnly.contains("返回 `No callable symbol found` 时不要猜名字，改用 LSP 工具的 `incomingCalls` / `findReferences`，或文本搜索。"))
    }

    @Test
    fun `两组都挂时中文和英文都不再把 analyze_calls 当作查调用的首选`() {
        assertFalse(enFull.contains("- Who calls a function and what it calls: `analyze_calls`."))
        assertFalse(zhFull.contains("- 谁调用了某个函数、它又调用了谁：`analyze_calls`。"))
    }

    @Test
    fun `analyze_calls 的说明只在挂了 IDEA MCP 时出现`() {
        assertTrue(enFull.contains("For call relations use `call_hierarchy` rather than IDEA MCP's `analyze_calls`: the latter resolves symbols by fully qualified name, which some language plugins (e.g. Go) do not provide, and then answers `No callable symbol found`."))
        assertTrue(zhFull.contains("查调用关系用 `call_hierarchy`，不用 IDEA MCP 的 `analyze_calls`：后者按完整限定名解析符号，有些语言插件（如 Go）不提供这项换算，这时它只会返回 `No callable symbol found`。"))
        assertFalse(enCodeOnly.contains("analyze_calls"))
        assertFalse(zhCodeOnly.contains("analyze_calls"))
    }

    // endregion

    // region 目的

    @Test
    fun `英文三种工具组合的开头都先讲为什么要问 IDE，再给出先取证这条原则`() {
        val principle = "Reading text only shows what the code says; the IDE has resolved the whole project and knows what each name refers to and where it is used. " +
            "So one principle covers everything below: when the IDE can answer a question of fact, ask it instead of inferring from text — gather evidence before you conclude or change anything."
        assertTrue(enFull.contains(principle))
        assertTrue(enCodeOnly.contains(principle))
        assertTrue(enIdeaOnly.contains(principle))
    }

    @Test
    fun `中文三种工具组合的开头都先讲为什么要问 IDE，再给出先取证这条原则`() {
        val principle = "读文本只能看到代码写了什么；IDE 解析过整个项目，知道每个名字指的是谁、哪里用到了它。所以下面的内容都出自一条原则：IDE 能回答的事实就问它，不要从文字里猜——下结论、动手改之前先取证。"
        assertTrue(zhFull.contains(principle))
        assertTrue(zhCodeOnly.contains(principle))
        assertTrue(zhIdeaOnly.contains(principle))
    }

    @Test
    fun `中文和英文都不夸口每种语言都能用，而是说明解析不了时会返回什么`() {
        assertTrue(enCodeOnly.contains("They work for the languages the IDE understands and answer `language_not_supported` for the ones it does not."))
        assertTrue(zhCodeOnly.contains("IDE 能解析的语言都能用，解析不了会明确返回 `language_not_supported`。"))
        assertFalse(enCodeOnly.contains("every language"))
        assertFalse(zhCodeOnly.contains("每一种语言"))
    }

    // endregion

    // region 只查不改的角色

    @Test
    fun `只查不改的英文引导词在三种工具组合下都写明不改和判断标准，不提任何会改动状态的工具，也不说可以写文件`() {
        for ((ideaMcp, codeTools) in listOf(true to true, true to false, false to true)) {
            val text = investigator(chinese = false, ideaMcp, codeTools)
            assertTrue(text.contains("This time you only investigate. Deciding what to change and making the change belong to the user and the agent doing the work; what you contribute is facts that have been checked."))
            assertTrue(text.contains("The test for any call: would the project, the repository, or an outside system be different afterwards? If so, do not make it"))
            assertTrue(text.contains("reading files, directory listings, and read-only git and shell commands"))
            assertFalse("只查不改的不能被告知可以写文件", text.contains("writing and editing files"))
            for (tool in changingTools) assertFalse("$tool ($ideaMcp, $codeTools)", text.contains("`$tool`"))
            assertFalse(text.contains("## While editing"))
            assertFalse(text.contains("## After editing"))
            assertFalse(text.contains("## When it fails at runtime"))
            assertFalse(text.contains("## Use when the task calls for it"))
            assertFalse(text.contains("Before editing, spend effort"))
        }
    }

    @Test
    fun `只查不改的中文引导词在三种工具组合下都写明不改和判断标准，不提任何会改动状态的工具，也不说可以写文件`() {
        for ((ideaMcp, codeTools) in listOf(true to true, true to false, false to true)) {
            val text = investigator(chinese = true, ideaMcp, codeTools)
            assertTrue(text.contains("你这次只查不改。改不改、怎么改，是用户和负责干活的智能体的事；你的用处是把事实查实。"))
            assertTrue(text.contains("判断一次调用该不该做，只看一条：做完之后，项目、仓库或外部系统会不会和之前不一样。会，就不做"))
            assertTrue(text.contains("读文件、列目录，以及只读的 git 和 shell 命令"))
            assertFalse("只查不改的不能被告知可以写文件", text.contains("读写和编辑文件"))
            for (tool in changingTools) assertFalse("$tool ($ideaMcp, $codeTools)", text.contains("`$tool`"))
            assertFalse(text.contains("## 改的时候"))
            assertFalse(text.contains("## 改完以后"))
            assertFalse(text.contains("## 运行时出错"))
            assertFalse(text.contains("## 用得上再用"))
            assertFalse(text.contains("改代码之前，力气花在"))
        }
    }

    @Test
    fun `只查不改时中文和英文的原则都只说下结论之前先取证，不提动手改`() {
        val en = investigator(chinese = false, ideaMcp = true, codeTools = true)
        val zh = investigator(chinese = true, ideaMcp = true, codeTools = true)
        assertTrue(en.contains("ask it instead of inferring from text — gather evidence before you conclude.\n"))
        assertFalse(en.contains("or change anything"))
        assertTrue(zh.contains("IDE 能回答的事实就问它，不要从文字里猜——下结论之前先取证。"))
        assertFalse(zh.contains("动手改之前"))
    }

    @Test
    fun `会改动状态的工具名在干活的引导词里确实出现，上面两条全称断言不会恒真`() {
        assertTrue(toolNames(enFull).containsAll(changingTools))
        assertTrue(toolNames(zhFull).containsAll(changingTools))
    }

    @Test
    fun `只查不改时中文和英文仍讲怎么查链路，审查和评估改动各有用法，以及用 IDE 查证事实`() {
        val en = investigator(chinese = false, ideaMcp = true, codeTools = true)
        val zh = investigator(chinese = true, ideaMcp = true, codeTools = true)
        assertTrue(en.contains("- Who relies on it and what it relies on: `call_hierarchy`, in both directions"))
        assertTrue(en.contains("When reviewing a change, or weighing one that is only being discussed, trace this chain for each symbol involved: callers, overrides, and implementations that should have changed but did not are the problems to report, and the ones that would have to change are the cost to state up front."))
        assertTrue(en.contains("## Checking facts with the IDE"))
        assertTrue(en.contains("- Errors and warnings in a file, as the IDE sees them: `lint_files`."))
        assertTrue(zh.contains("- 谁在依赖它、它又依赖谁：`call_hierarchy`，两个方向都看"))
        assertTrue(zh.contains("审查一处改动、或者评估一个还在讨论的改动时，对涉及的每个符号查这条链路：该跟着改却没改的调用方、重写和实现，就是要指出的问题；将来得跟着改的，就是要提前说清的代价。"))
        assertTrue(zh.contains("## 用 IDE 查证事实"))
        assertTrue(zh.contains("- IDE 眼里某个文件有哪些错误和警告：`lint_files`。"))
    }

    // endregion

    // region 语言

    @Test
    fun `简中和繁中用中文，其余各语言用英文`() {
        val zh = agentGuidance(chinese = true, ideaMcp = true, codeTools = true)
        val en = agentGuidance(chinese = false, ideaMcp = true, codeTools = true)
        assertEquals(zh, agentGuidanceForLanguage(PluginLanguage.SIMPLIFIED_CHINESE, true, true))
        assertEquals(zh, agentGuidanceForLanguage(PluginLanguage.TRADITIONAL_CHINESE, true, true))
        for (language in PluginLanguage.entries - setOf(PluginLanguage.SIMPLIFIED_CHINESE, PluginLanguage.TRADITIONAL_CHINESE)) {
            assertEquals(language.name, en, agentGuidanceForLanguage(language, true, true))
        }
    }

    @Test
    fun `设置按当前语言、工具组合和角色取引导词`() {
        val settings = ImuxSettings()
        settings.setLanguage(PluginLanguage.SIMPLIFIED_CHINESE)
        assertEquals(zhCodeOnly, settings.agentGuidance(ideaMcp = false, capabilities = setOf(GuidanceCapability.CODE_TOOLS)))
        assertEquals(
            investigator(chinese = true, ideaMcp = true, codeTools = true),
            settings.agentGuidance(ideaMcp = true, capabilities = setOf(GuidanceCapability.CODE_TOOLS), role = GuidanceRole.INVESTIGATOR),
        )
        settings.setLanguage(PluginLanguage.ENGLISH)
        assertEquals(enIdeaOnly, settings.agentGuidance(ideaMcp = true, capabilities = emptySet()))
    }

    // endregion

    // region 摸清链路

    @Test
    fun `英文先讲为什么要查关系，再写明改代码、审查、阅读、排查时都先查，并给出三种查法`() {
        assertTrue(enCodeOnly.contains("What a piece of code is depends not only on what it says but on who relies on it and whose contract it has to keep."))
        assertTrue(enCodeOnly.contains("text search only recognizes names that look alike: unrelated functions with the same name get mixed in, and a call made through an interface or base type cannot be told apart from one that never reaches it."))
        assertTrue(enCodeOnly.contains("So whenever a conclusion depends on how code is connected — changing code, reviewing a change, reading or explaining code, tracking down a bug, or answering \"who uses this, and what breaks if it changes\" — get the relations from the IDE first:"))
        assertTrue(enCodeOnly.contains("- Who relies on it and what it relies on: `call_hierarchy`, in both directions; calls made through an interface or base method are included and marked `via`."))
        assertTrue(enCodeOnly.contains("- What it overrides or implements (whose contract it must keep) and what overrides or implements it (who must change with it): `type_hierarchy`, in both directions (`supers` and `implementations`)."))
        assertTrue(enCodeOnly.contains("- Where a value comes from and where it goes: `find_references` on the parameter, field, return value, or shared state, including other modules."))
    }

    @Test
    fun `中文先讲为什么要查关系，再写明改代码、审查、阅读、排查时都先查，并给出三种查法`() {
        assertTrue(zhCodeOnly.contains("一段代码是什么，不只看它自己写了什么，还看谁在依赖它、它得遵守谁的约定。"))
        assertTrue(zhCodeOnly.contains("文本搜索又只认得长得一样的名字：同名的无关函数会混进来，经由接口、父类发生的调用也分不出是不是落到它头上。"))
        assertTrue(zhCodeOnly.contains("所以凡是结论取决于代码之间怎么连着的——改代码、审查改动、阅读或讲解代码、排查 bug、回答「谁在用它、改了会影响哪里」——都先向 IDE 把关系查出来："))
        assertTrue(zhCodeOnly.contains("- 谁在依赖它、它又依赖谁：`call_hierarchy`，两个方向都看；经由接口或父类方法的调用也会列出，并标明 `via`。"))
        assertTrue(zhCodeOnly.contains("- 它重写、实现了谁（它得遵守谁的约定），又被谁重写、实现（谁得跟着它变）：`type_hierarchy`，上下两个方向（`supers` 和 `implementations`）。"))
        assertTrue(zhCodeOnly.contains("- 一个值从哪来、流到哪去：对参数、字段、返回值、共享状态用 `find_references`，包括其它模块。"))
    }

    /**
     * 大纲是先纵观、再决定从哪下手的第一层，不替代细读（用户定的）：干活的和只查不改的都要读文件，
     * 两种角色、两种语言都要有这句，而且都要写明不替代阅读。
     */
    @Test
    fun `先看大纲再细读这句在干活和只查不改两种角色、中英两种语言里都有，并写明不替代阅读`() {
        val en = "To get an overview of a file and decide where to start, use `file_outline`: it lists the file's declarations with the line each one is on, and those lines can be passed straight to the three tools above. For a class it also gives the parents written in its declaration and where they are, so you know which file to outline next. It is an overview, not a substitute for reading: whatever you will change or draw a conclusion about, still read in full, and when you are unsure how much is involved, read the whole file."
        val zh = "想先纵观一个文件、决定从哪下手，用 `file_outline`：它列出文件里的声明和各自所在的行，行号可以直接交给上面三个工具；类还会标出声明里写明的父类、接口及其位置，接下来该去看哪个文件就清楚了。它只是总览，不替代阅读：要改的、要下结论的地方，照样把相关代码完整读完；拿不准牵涉多大时，就读全文件。"
        assertTrue(enCodeOnly.contains(en))
        assertTrue(investigator(chinese = false, ideaMcp = false, codeTools = true).contains(en))
        assertTrue(zhCodeOnly.contains(zh))
        assertTrue(investigator(chinese = true, ideaMcp = false, codeTools = true).contains(zh))
        for (text in listOf(enCodeOnly, zhCodeOnly)) {
            assertFalse("不能写成替代阅读", text.contains("without reading all of it"))
            assertFalse("不能写成替代阅读", text.contains("only the parts you need"))
            assertFalse("不能写成替代阅读", text.contains("不必整个读进来"))
            assertFalse("不能写成替代阅读", text.contains("只读需要的部分"))
        }
        assertFalse("没挂查代码工具时不提", enIdeaOnly.contains("file_outline"))
        assertFalse("没挂查代码工具时不提", zhIdeaOnly.contains("file_outline"))
    }

    @Test
    fun `英文改代码之前按波及面花力气：局部改动读完函数，涉及别处的走三步；审查时漏改的就是问题`() {
        assertTrue(enFull.contains("Before editing, spend effort in proportion to how far the change reaches:"))
        assertTrue(enFull.contains("- A purely local change (the signature and the behavior others rely on stay the same): reading the function itself in full is enough."))
        assertTrue(enFull.contains("- A change that touches a signature, shared behavior, or anything used outside the current function:"))
        assertTrue(enFull.contains("  1. Find each symbol you will change, read its declaration, and read the full body — not just the lines around a search hit."))
        assertTrue(enFull.contains("  2. Trace the chain with the three checks above until you reach a boundary the change cannot affect: an entry point, a public API, or code whose behavior stays the same."))
        assertTrue(enFull.contains("  3. Then edit, and update every affected caller, override, and implementation in the same change. If the chain turns out larger than expected, say so instead of quietly changing only part of it."))
        assertTrue(enFull.contains("When reviewing a change, trace the same chain for each changed symbol: callers, overrides, and implementations that should have changed but did not are the problems to report."))
    }

    @Test
    fun `中文改代码之前按波及面花力气：局部改动读完函数，涉及别处的走三步；审查时漏改的就是问题`() {
        assertTrue(zhFull.contains("改代码之前，力气花在和改动的波及面相称的地方："))
        assertTrue(zhFull.contains("- 纯局部的改动（签名和别处依赖的行为都不变）：把这个函数完整读完就够。"))
        assertTrue(zhFull.contains("- 改动涉及签名、共享行为或当前函数之外用到的东西："))
        assertTrue(zhFull.contains("  1. 找到要改的每个符号，看它的声明，并把方法体完整读完——不要只看搜索命中的那几行。"))
        assertTrue(zhFull.contains("  2. 按上面三项把链路查清，一路追到改动影响不到的边界为止：入口、公开 API，或行为保持不变的代码。"))
        assertTrue(zhFull.contains("  3. 摸清之后再动手，并在同一次改动里把受影响的调用方、重写和实现一并改掉。链路比预想的大时如实说明，不要悄悄只改其中一部分。"))
        assertTrue(zhFull.contains("审查改动时，对每个改过的符号查同样的链路：该跟着改却没改的调用方、重写和实现，就是要指出的问题。"))
    }

    @Test
    fun `英文写明没查到不等于没有：没查全要补查，换路时把握跟着来源走，失败先读原因`() {
        assertTrue(enFull.contains("Claim only as much as you found — not finding something is not the same as it not being there:"))
        assertTrue(enFull.contains("A result that carries a not-complete marker (`unanalyzed_mentions`, `overrides_incomplete`, `truncated`, `not_expanded`, `unanalyzed_not_checked`) is not the whole chain yet: follow up as the tool description says before treating it as complete."))
        assertTrue(enFull.contains("If `imux_code` answers `language_not_supported` or is unavailable, take another route and let your confidence follow the source: first the language server (the LSP tool's `findReferences` / `incomingCalls` / `outgoingCalls`, or its command line run from the project root)"))
        assertTrue(enFull.contains("only with no language server either, text search — read each hit in context to confirm it is a real use, and say in your reply that the chain came from text search."))
        assertTrue(enFull.contains("A failed call is telling you the question is wrong or the route is closed: read the reason it returns before deciding what to do next, and do not retry it with different spellings."))
    }

    @Test
    fun `中文写明没查到不等于没有：没查全要补查，换路时把握跟着来源走，失败先读原因`() {
        assertTrue(zhFull.contains("查到多少，就说到多少——没查到不等于没有："))
        assertTrue(zhFull.contains("结果里带「没查全」的标记（`unanalyzed_mentions`、`overrides_incomplete`、`truncated`、`not_expanded`、`unanalyzed_not_checked`）时，这条链路还不完整：按工具说明把缺的部分补查完，才能当作完整链路。"))
        assertTrue(zhFull.contains("`imux_code` 返回 `language_not_supported` 或不可用时，换一条路查，结论的把握跟着来源走：先用语言服务器（LSP 工具的 `findReferences` / `incomingCalls` / `outgoingCalls`，或在项目根目录运行它的命令行）"))
        assertTrue(zhFull.contains("这门语言也没有语言服务器，才用文本搜索——逐个打开命中处读上下文，确认是真实用法，并在回复里说明这条链路是靠文本搜索得出的。"))
        assertTrue(zhFull.contains("一次调用失败，是在告诉你这个问法不对或这条路不通：先读它返回的原因再决定下一步，不要换个写法反复重试。"))
    }

    // endregion

    // region IDEA MCP 各节

    @Test
    fun `英文改时用 IDE 重命名和格式化，并各自说明理由`() {
        assertTrue(enFull.contains("Rename any symbol (variable, method, class, and so on) with `rename_refactoring`: it changes what the name refers to, while text find-and-replace changes what looks alike and breaks same-name symbols in other scopes."))
        assertTrue(enFull.contains("Format with `reformat_file`: it applies this project's own code style, not an external formatter's."))
    }

    @Test
    fun `中文改时用 IDE 重命名和格式化，并各自说明理由`() {
        assertTrue(zhFull.contains("重命名任何符号（变量、方法、类……）用 `rename_refactoring`：它按「指的是谁」来改，文本查找替换按「长得像」来改，会误伤别的作用域里的同名符号。"))
        assertTrue(zhFull.contains("格式化用 `reformat_file`：用的是这个项目自己的代码风格，不是外部格式化工具的。"))
    }

    @Test
    fun `英文改后把做完了当成要证据的判断：编译、检查、测试三步各有工具和退路，没做的如实说`() {
        assertTrue(enFull.contains("\"Done\" is a claim that needs evidence. Gather it in this order, and say plainly which step you did not run:"))
        assertTrue(enFull.contains("1. It compiles: when the change involves compiled code, cross-file references, or public signatures, run `build_project`, which reports compilation errors with file and line. Skip it when the project has no effective IDE build step."))
        assertTrue(enFull.contains("2. It introduced no new problems: run `lint_files` on every file you changed"))
        assertTrue("改了签名还要查受影响的调用方文件", enFull.contains("When the change touched a signature or shared behavior, also lint the files of the callers it affects: in languages without a compile step (Python, JavaScript), a caller left unchanged shows up only there."))
        assertTrue(enFull.contains("3. Behavior still holds: before running tests, check `get_run_configurations`; when one matches, use `execute_run_configuration` so the environment is the one the developer gets from Run. With no match, use the project's own test command."))
    }

    @Test
    fun `中文改后把做完了当成要证据的判断：编译、检查、测试三步各有工具和退路，没做的如实说`() {
        assertTrue(zhFull.contains("「做完了」是一个需要证据的判断。按下面的顺序取证，哪一步没做就如实说没做："))
        assertTrue(zhFull.contains("1. 能编译：改动涉及编译型代码、跨文件引用或公共签名时运行 `build_project`，它给出带文件和行号的编译错误。项目没有有效的 IDE 构建步骤就跳过。"))
        assertTrue(zhFull.contains("2. 没引入新问题：对每个改过的文件运行 `lint_files`"))
        assertTrue("改了签名还要查受影响的调用方文件", zhFull.contains("改动涉及签名或共享行为时，受它影响的调用方所在文件也一并检查：没有编译步骤的语言（Python、JavaScript）里，漏改的调用方只有这样才露得出来。"))
        assertTrue(zhFull.contains("3. 行为没坏：跑测试前先看 `get_run_configurations`，有匹配的就用 `execute_run_configuration`，环境和开发者点 Run 时一致；没有匹配的就用项目自己的测试命令。"))
    }

    @Test
    fun `英文运行时出错先看现场，并说明为什么不靠加 print 猜`() {
        assertTrue(enFull.contains("## When it fails at runtime: look at the scene instead of guessing"))
        assertTrue(enFull.contains("Adding print statements means guessing where the problem is and then editing code to test the guess; a breakpoint shows you the values at the moment it goes wrong."))
        assertTrue(enFull.contains("set breakpoints with `xdebug_set_breakpoint`"))
    }

    @Test
    fun `中文运行时出错先看现场，并说明为什么不靠加 print 猜`() {
        assertTrue(zhFull.contains("## 运行时出错：看现场，不要猜"))
        assertTrue(zhFull.contains("加 print 是先猜问题在哪、再改代码去验证这个猜测；断点让你直接看到出错那一刻的值。"))
        assertTrue(zhFull.contains("`xdebug_set_breakpoint` 设断点"))
    }

    /** 冷门能力和每次都该用的排在一起，会冲淡常用的；看代码用的工具则要排在改之前。 */
    @Test
    fun `中文和英文都把看代码的工具放在看清一节，数据库、解释器、自定义检查单独放在用得上再用`() {
        for ((text, look, onDemand) in listOf(
            Triple(enIdeaOnly, "## See the code clearly", "## Use when the task calls for it"),
            Triple(zhIdeaOnly, "## 看清代码", "## 用得上再用"),
        )) {
            val lookAt = text.indexOf(look)
            val onDemandAt = text.indexOf(onDemand)
            assertTrue(lookAt >= 0 && onDemandAt > lookAt)
            for (tool in listOf("search_symbol", "get_symbol_info", "analyze_calls", "read_file", "get_project_modules", "get_all_open_file_paths")) {
                assertTrue(tool, text.indexOf("`$tool`") in lookAt until onDemandAt)
            }
            for (tool in listOf("execute_sql_query", "configure_python_interpreter", "run_inspection_kts")) {
                assertTrue(tool, text.indexOf("`$tool`") > onDemandAt)
            }
        }
    }

    @Test
    fun `中文和英文都只在最后一节说一次读写文件用自己的工具`() {
        assertEquals(1, Regex("reading, writing and editing files").findAll(enFull).count())
        assertFalse(enFull.contains("Write and edit files with your own tools."))
        assertEquals(1, Regex("读写和编辑文件").findAll(zhFull).count())
        assertFalse(zhFull.contains("读写、编辑文件用你自己的工具。"))
    }

    @Test
    fun `英文 IDEA MCP 提示词省略安全规则段落`() {
        assertFalse(enIdeaOnly.contains("## Safety"))
        assertFalse(enFull.contains("## Safety"))
    }

    @Test
    fun `中文 IDEA MCP 提示词省略安全规则段落`() {
        assertFalse(zhIdeaOnly.contains("## 安全规则"))
        assertFalse(zhFull.contains("## 安全规则"))
    }

    // endregion
}
