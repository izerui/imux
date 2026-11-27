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
        assertTrue(enCodeOnly.contains("`imux_code`"))
        assertTrue(enCodeOnly.contains("## Map the chain"))
    }

    @Test
    fun `只挂 imux_code 时中文引导词不提任何 IDEA MCP 工具`() {
        for (tool in toolNames(zhIdeaOnly)) assertFalse(tool, zhCodeOnly.contains("`$tool`"))
        assertFalse(zhCodeOnly.contains("`idea`（IDEA MCP）"))
        assertTrue(zhCodeOnly.contains("`imux_code`"))
        assertTrue(zhCodeOnly.contains("## 摸清链路"))
    }

    @Test
    fun `只挂 IDEA MCP 时英文引导词不提 imux_code 也没有摸清链路一节`() {
        assertFalse(enIdeaOnly.contains("imux_code"))
        assertFalse(enIdeaOnly.contains("find_references"))
        assertFalse(enIdeaOnly.contains("call_hierarchy"))
        assertFalse(enIdeaOnly.contains("## Map the chain"))
        assertTrue(enIdeaOnly.contains("- `idea`"))
        assertTrue(enIdeaOnly.contains("## While editing"))
    }

    @Test
    fun `只挂 IDEA MCP 时中文引导词不提 imux_code 也没有摸清链路一节`() {
        assertFalse(zhIdeaOnly.contains("imux_code"))
        assertFalse(zhIdeaOnly.contains("find_references"))
        assertFalse(zhIdeaOnly.contains("call_hierarchy"))
        assertFalse(zhIdeaOnly.contains("## 摸清链路"))
        assertTrue(zhIdeaOnly.contains("- `idea`"))
        assertTrue(zhIdeaOnly.contains("## 改的时候"))
    }

    @Test
    fun `两组都挂时英文按看清、摸清链路、改、验证、排错的顺序排列，冷门能力放最后`() {
        val order = listOf(
            "## See the code clearly", "## Map the chain", "## While editing", "## After editing", "## When it fails at runtime",
            "## Use when the task calls for it",
        ).map(enFull::indexOf)
        assertTrue(order.toString(), order.all { it >= 0 })
        assertEquals(order.sorted(), order)
    }

    @Test
    fun `两组都挂时中文按看清、摸清链路、改、验证、排错的顺序排列，冷门能力放最后`() {
        val order = listOf(
            "## 看清代码", "## 摸清链路", "## 改的时候", "## 改完以后", "## 运行时出错",
            "## 用得上再用",
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

    // endregion

    // region 只查不改的角色

    @Test
    fun `MCP初步定位和细读实现的引导按语言角色注入并随查代码开关关闭`() {
        for (zh in listOf(true, false)) for (idea in listOf(true, false)) for (role in GuidanceRole.entries) {
            val text = agentGuidance(zh, idea, true, role)!!
            val rule = if (zh) "先用这组 MCP 工具快速找到相关声明" else "start with these MCP tools to quickly locate relevant declarations"
            val editing = if (zh) "动手前先查受影响的用法" else "Before editing a signature"
            val reviewing = if (zh) "先查谁依赖涉及的共享约定" else "When reviewing an existing or proposed change"
            assertTrue(text.contains(rule))
            val intro = text.substringBefore("\n## ")
            assertTrue(intro.contains(if (zh) "比单靠文本搜索更快" else "faster than text search alone"))
            assertTrue(intro.contains(if (zh) "把相关实现仔细读完整" else "read the relevant implementations carefully and in full"))
            assertEquals(role == GuidanceRole.WORKER, text.contains(editing))
            assertEquals(role == GuidanceRole.INVESTIGATOR, text.contains(reviewing))
            assertFalse(agentGuidance(zh, idea, false, role).orEmpty().contains(rule))
        }
    }

    @Test
    fun `中英文工具引导保留空结果和实际实现的核实说明`() {
        for (zh in listOf(true, false)) for (idea in listOf(true, false)) for (role in GuidanceRole.entries) {
            val text = agentGuidance(zh, idea, true, role)!!
            assertTrue(text.contains(if (zh) "不能直接说没人用" else "not that the code is unused"))
            assertTrue(text.contains(if (zh) "具体落到哪个实现" else "inspect the receiver"))
        }
    }

    @Test
    fun `验证说明只进入开启 IDEA MCP 的工作角色且不再重复结论段`() {
        for (zh in listOf(true, false)) for (role in GuidanceRole.entries) {
            for ((idea, code) in listOf(true to true, true to false, false to true)) {
                val text = agentGuidance(zh, idea, code, role)!!
                val verify = if (zh) "## 改完以后" else "## After editing"
                assertEquals(idea && role == GuidanceRole.WORKER, text.contains(verify))
                assertFalse(text.contains(if (zh) "## 核对结论" else "## Check the conclusion"))
                if (idea && role == GuidanceRole.WORKER) {
                    val section = text.substringAfter(verify).substringBefore("\n## ")
                    assertTrue(section.contains(if (zh) "从实际入口核对最终结果" else "verify the final result through the entry point"))
                    assertTrue(section.contains(if (zh) "测试断言实际覆盖了哪些路径" else "which paths the test assertions actually cover"))
                }
                if (role == GuidanceRole.INVESTIGATOR) {
                    assertFalse(text.contains(if (zh) "留下能重复验证的回归案例" else "Keep the regression case reproducible"))
                    assertFalse(text.contains(if (zh) "当前运行环境是否已经用上改动" else "whether the running environment has the change"))
                }
            }
        }
    }

    @Test
    fun `只查不改的英文引导词在三种工具组合下都写明不改和判断标准，不提任何会改动状态的工具，也不说可以写文件`() {
        for ((ideaMcp, codeTools) in listOf(true to true, true to false, false to true)) {
            val text = investigator(chinese = false, ideaMcp, codeTools)
            assertTrue(text.contains("This time you only investigate."))
            assertTrue(text.contains("read-only scope"))
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
            assertTrue(text.contains("你这次只查不改。"))
            assertTrue(text.contains("只读范围"))
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
    fun `会改动状态的工具名在干活的引导词里确实出现，上面两条全称断言不会恒真`() {
        assertTrue(toolNames(enFull).containsAll(changingTools))
        assertTrue(toolNames(zhFull).containsAll(changingTools))
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

    // region IDEA MCP 各节

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

    @Test
    fun `大纲之后提示细读相关实现，局部仍不清楚时通读文件`() {
        for (role in GuidanceRole.entries) for (idea in listOf(false, true)) {
            assertTrue(agentGuidance(true, idea, true, role)!!.contains("读完局部仍拿不准它们怎样配合，就通读这个文件"))
            assertTrue(agentGuidance(false, idea, true, role)!!.contains("their interaction is still unclear, read the whole file"))
        }
    }

    @Test
    fun `只读职责和证据缺口说明按角色注入开头`() {
        for (zh in listOf(false, true)) for (role in GuidanceRole.entries) {
            for ((idea, code) in listOf(true to true, true to false, false to true)) {
                val intro = agentGuidance(zh, idea, code, role)!!.substringBefore("\n## ")
                assertEquals(role == GuidanceRole.INVESTIGATOR, intro.contains(if (zh) "只读范围" else "read-only scope"))
                assertEquals(role == GuidanceRole.INVESTIGATOR, intro.contains(if (zh) "证据不足的地方要说明" else "Explain where the evidence is insufficient"))
            }
        }
    }
}
