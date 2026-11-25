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

    /** 只属于 IDEA MCP 的工具名：没挂 IDEA MCP 时一个都不能出现。 */
    private val ideaOnlyTools = listOf(
        "rename_refactoring", "build_project", "lint_files", "xdebug_set_breakpoint", "execute_sql_query",
        "search_symbol", "get_symbol_info", "analyze_calls", "reformat_file",
    )

    // region 工具组合

    @Test
    fun `两组工具都没挂时中文和英文都不注入引导词`() {
        assertNull(agentGuidance(chinese = false, ideaMcp = false, codeTools = false))
        assertNull(agentGuidance(chinese = true, ideaMcp = false, codeTools = false))
    }

    /** IDEA MCP 部分提到的全部工具名：从「只挂 IDEA MCP」的引导词里提取，新增工具会自动纳入检查。 */
    private fun toolNames(text: String): Set<String> =
        Regex("""`([a-z]+(?:_[a-z]+)+)`""").findAll(text).map { it.groupValues[1] }.toSet()

    @Test
    fun `IDEA MCP 部分的工具名能被提取到，下面两条全称断言不会恒真`() {
        assertTrue(toolNames(enIdeaOnly).containsAll(ideaOnlyTools))
        assertTrue(toolNames(zhIdeaOnly).containsAll(ideaOnlyTools))
    }

    @Test
    fun `只挂 imux_code 时英文引导词不提任何 IDEA MCP 工具`() {
        for (tool in toolNames(enIdeaOnly)) assertFalse(tool, enCodeOnly.contains("`$tool`"))
        assertFalse(enCodeOnly.contains("`idea` (IDEA MCP)"))
        assertTrue(enCodeOnly.contains("- `imux_code`: `find_references`, `call_hierarchy`, and `type_hierarchy`"))
        assertTrue(enCodeOnly.contains("## 1. Map the chain: before editing, and when reviewing or analyzing code"))
    }

    @Test
    fun `只挂 imux_code 时中文引导词不提任何 IDEA MCP 工具`() {
        for (tool in toolNames(zhIdeaOnly)) assertFalse(tool, zhCodeOnly.contains("`$tool`"))
        assertFalse(zhCodeOnly.contains("`idea`（IDEA MCP）"))
        assertTrue(zhCodeOnly.contains("- `imux_code`：`find_references`、`call_hierarchy` 和 `type_hierarchy`"))
        assertTrue(zhCodeOnly.contains("## 1. 摸清链路：改代码之前，以及审查、分析代码时"))
    }

    @Test
    fun `只挂 imux_code 时找符号这一步不依赖 IDEA 工具`() {
        assertTrue(enCodeOnly.contains("1. Find each symbol you will change, read its declaration, and read the full body"))
        assertTrue(zhCodeOnly.contains("1. 找到要改的每个符号，看它的声明，并把方法体完整读完"))
    }

    @Test
    fun `只挂 IDEA MCP 时英文引导词不提 imux_code 也没有改前摸排一节`() {
        assertFalse(enIdeaOnly.contains("imux_code"))
        assertFalse(enIdeaOnly.contains("find_references"))
        assertFalse(enIdeaOnly.contains("call_hierarchy"))
        assertFalse(enIdeaOnly.contains("## 1. Map the chain"))
        assertTrue(enIdeaOnly.contains("- `idea` (IDEA MCP):"))
        assertTrue(enIdeaOnly.contains("## 2. While editing"))
    }

    @Test
    fun `只挂 IDEA MCP 时中文引导词不提 imux_code 也没有改前摸排一节`() {
        assertFalse(zhIdeaOnly.contains("imux_code"))
        assertFalse(zhIdeaOnly.contains("find_references"))
        assertFalse(zhIdeaOnly.contains("call_hierarchy"))
        assertFalse(zhIdeaOnly.contains("## 1. 摸清链路"))
        assertTrue(zhIdeaOnly.contains("- `idea`（IDEA MCP）："))
        assertTrue(zhIdeaOnly.contains("## 2. 改的时候"))
    }

    @Test
    fun `两组都挂时英文按改前、改时、改后的顺序排列并带上其余各节`() {
        val order = listOf(
            "## 1. Map the chain", "## 2. While editing", "## 3. After editing", "## Debugging runtime bugs",
            "## Other IDE capabilities to prefer", "## Use your own tools for",
        ).map(enFull::indexOf)
        assertTrue(order.toString(), order.all { it >= 0 })
        assertEquals(order.sorted(), order)
    }

    @Test
    fun `两组都挂时中文按改前、改时、改后的顺序排列并带上其余各节`() {
        val order = listOf(
            "## 1. 摸清链路", "## 2. 改的时候", "## 3. 改完以后", "## 调试运行时 bug",
            "## 其它优先用 IDE 的能力", "## 这些用你自己的工具",
        ).map(zhFull::indexOf)
        assertTrue(order.toString(), order.all { it >= 0 })
        assertEquals(order.sorted(), order)
    }

    @Test
    fun `只挂 IDEA MCP 时英文引导词保留找符号、看声明和 analyze_calls 查调用`() {
        assertTrue(enIdeaOnly.contains("- Find a symbol by name: `search_symbol`; its declaration, type, and docs: `get_symbol_info`."))
        assertTrue(enIdeaOnly.contains("- Who calls a function and what it calls: `analyze_calls`."))
        assertTrue(enIdeaOnly.contains("when it answers `No callable symbol found`, do not guess names — use the LSP tool's `incomingCalls` / `findReferences`, or text search."))
    }

    @Test
    fun `只挂 IDEA MCP 时中文引导词保留找符号、看声明和 analyze_calls 查调用`() {
        assertTrue(zhIdeaOnly.contains("- 按名字找符号：`search_symbol`；看它的声明、类型和文档：`get_symbol_info`。"))
        assertTrue(zhIdeaOnly.contains("- 谁调用了某个函数、它又调用了谁：`analyze_calls`。"))
        assertTrue(zhIdeaOnly.contains("返回 `No callable symbol found` 时不要猜名字，改用 LSP 工具的 `incomingCalls` / `findReferences`，或文本搜索。"))
    }

    @Test
    fun `两组都挂时中文和英文都不再把 analyze_calls 当作查调用的首选`() {
        assertFalse(enFull.contains("- Who calls a function and what it calls: `analyze_calls`."))
        assertFalse(zhFull.contains("- 谁调用了某个函数、它又调用了谁：`analyze_calls`。"))
    }

    @Test
    fun `中文和英文都解释继承没查全的标记该怎么办`() {
        assertTrue(enFull.contains("- `overrides_incomplete: true` means the search for what a method overrides stopped early (too many declarations share its name), so calls made through an interface may be missing: check the enclosing type's interfaces and base types yourself."))
        assertTrue(zhFull.contains("- 结果里出现 `overrides_incomplete: true` 时，说明同名声明太多、往上找重写对象提前停了，经由接口的调用可能没列全：自己看一下所在类型实现的接口和父类。"))
    }

    @Test
    fun `analyze_calls 的说明只在挂了 IDEA MCP 时出现`() {
        assertTrue(enFull.contains("when it answers `No callable symbol found`, use `call_hierarchy` instead."))
        assertTrue(zhFull.contains("它返回 `No callable symbol found` 时，改用 `call_hierarchy`。"))
        assertFalse(enCodeOnly.contains("analyze_calls"))
        assertFalse(zhCodeOnly.contains("analyze_calls"))
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
    fun `设置按当前语言和工具组合取引导词`() {
        val settings = ImuxSettings()
        settings.setLanguage(PluginLanguage.SIMPLIFIED_CHINESE)
        assertEquals(zhCodeOnly, settings.agentGuidance(ideaMcp = false, capabilities = setOf(GuidanceCapability.CODE_TOOLS)))
        settings.setLanguage(PluginLanguage.ENGLISH)
        assertEquals(enIdeaOnly, settings.agentGuidance(ideaMcp = true, capabilities = emptySet()))
    }

    // endregion

    // region 改前摸排

    @Test
    fun `英文改前摸排五步`() {
        assertTrue(enFull.contains("Do not start editing after reading only a few files."))
        assertTrue(enFull.contains("1. Find each symbol you will change (`search_symbol` when you only know the name), read its declaration and docs with `get_symbol_info`, and read the full body"))
        assertTrue(enFull.contains("2. Run `call_hierarchy` on every function or method you will change, in both directions: callers (who depends on it and what arguments they pass) and callees (what it relies on). Calls made through an interface or base method are included and marked `via`. Keep following until you reach a boundary the change cannot affect"))
        assertTrue(enFull.contains("3. When a method or type you will change overrides, implements, or is overridden or implemented by others, run `type_hierarchy` in both directions (`supers` and `implementations`): a signature change has to be made on all of them together."))
        assertTrue(enFull.contains("4. For every parameter, field, return value, or shared state whose meaning, type, or nullability changes, run `find_references`"))
        assertTrue(enFull.contains("5. Then edit, and update every affected caller, override, and implementation in the same change. If the chain turns out larger than expected, say so"))
    }

    @Test
    fun `中文改前摸排五步`() {
        assertTrue(zhFull.contains("不要只读了几个文件就动手。"))
        assertTrue(zhFull.contains("1. 找到要改的每个符号（只知道名字时用 `search_symbol`），用 `get_symbol_info` 看它的声明和文档，并把方法体完整读完"))
        assertTrue(zhFull.contains("2. 对每个要改的函数或方法运行 `call_hierarchy`，两个方向都看：调用方（谁依赖它、传进来什么参数）和被调用方（它依赖什么）。通过接口或父类方法调用的地方也会列出，并标明 `via`。一路追到改动影响不到的边界为止"))
        assertTrue(zhFull.contains("3. 要改的方法或类型如果重写、实现了别的，或者被别的重写、实现，用 `type_hierarchy` 上下两个方向（`supers` 和 `implementations`）都查：改签名要一起改。"))
        assertTrue(zhFull.contains("4. 凡是含义、类型或可空性要变的参数、字段、返回值和共享状态，都用 `find_references`"))
        assertTrue(zhFull.contains("5. 摸清之后再动手，并在同一次改动里把受影响的调用方、重写和实现一并改掉。链路比预想的大时如实说明"))
    }

    @Test
    fun `英文查不全时的核对与退路`() {
        assertTrue(enFull.contains("`unanalyzed_mentions` in a result lists files in languages the IDE cannot analyze, so their uses are missing from the result: open each one and check it before treating the chain as complete."))
        assertTrue(enFull.contains("If `imux_code` answers `language_not_supported` or is unavailable, use the language server: the LSP tool's `findReferences` / `incomingCalls` / `outgoingCalls`"))
        assertTrue(enFull.contains("With no language server either, use text search, read each hit in context to confirm it is a real use, and say in your reply that the chain came from text search."))
        assertTrue(enFull.contains("Do not retry a failing call with different spellings."))
    }

    @Test
    fun `中文查不全时的核对与退路`() {
        assertTrue(zhFull.contains("结果里出现 `unanalyzed_mentions` 时，说明这些文件的语言 IDE 分析不了，其中的用法不在结果里：逐个打开核对，核对完才能当作完整链路。"))
        assertTrue(zhFull.contains("`imux_code` 返回 `language_not_supported` 或不可用时，改用语言服务器：LSP 工具的 `findReferences` / `incomingCalls` / `outgoingCalls`"))
        assertTrue(zhFull.contains("这门语言也没有语言服务器时，用文本搜索，逐个打开命中处读上下文，确认是真实用法，回复里说明这条链路是靠文本搜索得出的。"))
        assertTrue(zhFull.contains("同一个失败的调用不要换个写法反复重试。"))
    }

    @Test
    fun `英文写明审查、阅读、排查时也要先查链路，审查时漏改的就是问题`() {
        assertTrue(enCodeOnly.contains("These tools are not only for editing. Whenever a conclusion depends on how code is connected — reviewing a change, reading or explaining code, tracking down a bug"))
        assertTrue(enCodeOnly.contains("When reviewing a change, trace the same chain for each changed symbol: callers, overrides, and implementations that should have changed but did not are the problems to report."))
    }

    @Test
    fun `中文写明审查、阅读、排查时也要先查链路，审查时漏改的就是问题`() {
        assertTrue(zhCodeOnly.contains("这组工具不只在改代码时用。凡是结论取决于代码之间怎么连着的——审查改动、阅读或讲解代码、排查 bug"))
        assertTrue(zhCodeOnly.contains("审查改动时，对每个改过的符号查同样的链路：该跟着改却没改的调用方、重写和实现，就是要指出的问题。"))
    }

    // endregion

    // region IDEA MCP 各节

    @Test
    fun `英文改时用 IDE 重命名和格式化`() {
        assertTrue(enFull.contains("Rename any variable, method, class, or other symbol with `rename_refactoring`; text find-and-replace breaks same-name symbols in other scopes."))
        assertTrue(enFull.contains("Format with `reformat_file`"))
    }

    @Test
    fun `中文改时用 IDE 重命名和格式化`() {
        assertTrue(zhFull.contains("重命名变量、方法、类或任何符号都用 `rename_refactoring`；文本查找替换会误改其它作用域里的同名符号。"))
        assertTrue(zhFull.contains("用 `reformat_file` 格式化"))
    }

    @Test
    fun `英文改后验证三步，构建和测试各有退路`() {
        assertTrue(enFull.contains("run `build_project`"))
        assertTrue(enFull.contains("skip this when the project has no effective build step"))
        assertTrue(enFull.contains("2. Run `lint_files` on every file you changed"))
        assertTrue(enFull.contains("when a matching IDE run configuration exists, use `execute_run_configuration`"))
        assertTrue(enFull.contains("when no matching configuration exists, fall back to the project's native test command"))
    }

    @Test
    fun `中文改后验证三步，构建和测试各有退路`() {
        assertTrue(zhFull.contains("运行 `build_project`"))
        assertTrue(zhFull.contains("若项目没有有效构建步骤则跳过"))
        assertTrue(zhFull.contains("2. 对所有修改过的文件运行 `lint_files`"))
        assertTrue(zhFull.contains("存在匹配的 IDE 运行配置时使用 `execute_run_configuration`"))
        assertTrue(zhFull.contains("不存在匹配配置时，退回项目原生测试命令"))
    }

    @Test
    fun `英文调试优先用断点`() {
        assertTrue(enFull.contains("Prefer interactive debugging over adding print/log statements"))
    }

    @Test
    fun `中文调试优先用断点`() {
        assertTrue(zhFull.contains("优先用交互式调试，而不是加 print/log"))
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
