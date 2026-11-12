package com.github.izerui.imux.bridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** hook 路径的请求构建、手机回复到决定的翻译、提问回答。 */
class HookPromptsTest {
    @Test
    fun `权限：允许类回复只允许这一次`() {
        assertEquals(HookDecision.Allow(), HookReplies.permission("好"))
    }

    @Test
    fun `权限：拒绝类与其他编号都拒绝且说明来自手机`() {
        val expected = HookDecision.Deny("The user denied this tool call from their phone.")
        assertEquals(expected, HookReplies.permission("拒绝"))
        assertEquals(expected, HookReplies.permission("3"))
    }

    @Test
    fun `权限：其他说法拒绝并附上原话`() {
        assertEquals(
            HookDecision.Deny("The user denied this tool call from their phone and said: 改用 git rm"),
            HookReplies.permission("改用 git rm"),
        )
    }

    @Test
    fun `计划：1 与 2 各切到对应模式`() {
        assertEquals(HookDecision.Allow(modes = listOf("auto", "bypassPermissions")), HookReplies.plan("1"))
        assertEquals(HookDecision.Allow(modes = listOf("default")), HookReplies.plan("2"))
        assertEquals(HookDecision.Allow(modes = listOf("default")), HookReplies.plan("b"))
    }

    @Test
    fun `计划：不执行与其他说法各自拒绝`() {
        assertEquals(
            HookDecision.Deny("The user does not want to proceed with this plan yet."),
            HookReplies.plan("不执行"),
        )
        assertEquals(HookDecision.Deny("文件名换成 a.txt"), HookReplies.plan("文件名换成 a.txt"))
    }

    @Test
    fun `计划：像选项编号或字母的回复不当作修改意见`() {
        val expected = HookDecision.Deny("The user does not want to proceed with this plan yet.")
        assertEquals(expected, HookReplies.plan("3"))
        assertEquals(expected, HookReplies.plan("c"))
        assertEquals(expected, HookReplies.plan("1 2"))
    }

    @Test
    fun `计划回「好」「可以」「ok」「yes」「允许」都不作答，留给手机再选`() {
        assertNull(HookReplies.plan("好"))
        assertNull(HookReplies.plan("可以"))
        assertNull(HookReplies.plan("OK"))
        assertNull(HookReplies.plan("yes"))
        assertNull(HookReplies.plan("允许"))
    }

    @Test
    fun `计划回 1 与 a 都是电脑上的第 1 项：先 auto 再 bypassPermissions`() {
        assertEquals(listOf("auto", "bypassPermissions"), HookReplies.PLAN_FIRST_MODES)
        assertEquals(HookDecision.Allow(modes = HookReplies.PLAN_FIRST_MODES), HookReplies.plan("1"))
        assertEquals(HookDecision.Allow(modes = HookReplies.PLAN_FIRST_MODES), HookReplies.plan("a"))
    }

    private val fruitMulti = AskedQuestion(
        "选哪些？", true,
        listOf(AskedOption("苹果", null), AskedOption("香蕉", null), AskedOption("橙子", null)),
    )

    @Test
    fun `提问：多选编号以逗号空格连接选项原文`() {
        assertEquals("苹果, 橙子", AskedQuestions.hookAnswer(fruitMulti, "1 3"))
    }

    private val colorSingle = AskedQuestion(
        "什么颜色？", false,
        listOf(AskedOption("红", null), AskedOption("绿", null), AskedOption("蓝", null)),
    )

    @Test
    fun `提问：单选编号给选项原文，自定义文字原样`() {
        assertEquals("绿", AskedQuestions.hookAnswer(colorSingle, "2"))
        assertEquals("紫色", AskedQuestions.hookAnswer(colorSingle, "紫色"))
        assertNull(AskedQuestions.hookAnswer(colorSingle, "  "))
    }

    @Test
    fun `单选题回多个编号按原话`() {
        assertEquals("1 3", AskedQuestions.hookAnswer(colorSingle, "1 3"))
    }

    @Test
    fun `种类按工具名区分`() {
        fun request(toolName: String) = HookRequest(
            id = 1, toolName = toolName, agentId = null, agentType = null,
            detail = "", questions = null, input = emptyMap(),
        )
        assertEquals(PromptKind.QUESTION, request("AskUserQuestion").kind)
        assertEquals(PromptKind.PLAN, request("ExitPlanMode").kind)
        assertEquals(PromptKind.PERMISSION, request("Bash").kind)
    }

    // ---------- matches ----------

    private fun request(
        toolName: String = "Bash",
        input: Map<String, String> = emptyMap(),
    ) = HookRequest(
        id = 1, toolName = toolName, agentId = null, agentType = null,
        detail = "", questions = null, input = input,
    )

    private fun done(
        toolName: String = "Bash",
        input: Map<String, String> = emptyMap(),
    ) = HookToolDone(agentId = null, toolName = toolName, input = input)

    @Test
    fun `电脑端输入为空时只有计划能对上`() {
        val planReq = request(toolName = "ExitPlanMode", input = mapOf("plan" to "\"# 计划\""))
        val permReq = request(toolName = "Bash", input = mapOf("command" to "\"ls\""))
        val emptyDone = done(toolName = "ExitPlanMode")
        val emptyBashDone = done(toolName = "Bash")
        // PLAN 匹配
        assertTrue(planReq.matches(emptyDone))
        // PERMISSION（如 Bash）不匹配
        assertFalse(permReq.matches(emptyBashDone))
    }

    @Test
    fun `双方都有字段但没有共有字段时不匹配`() {
        val req = request(input = mapOf("command" to "\"ls\""))
        val d = done(input = mapOf("description" to "\"列出文件\""))
        assertFalse(req.matches(d))
    }

    @Test
    fun `双方都为空时能对上`() {
        val req = request(input = emptyMap())
        val d = done(input = emptyMap())
        assertTrue(req.matches(d))
    }

    @Test
    fun `子代理来源写在工具名后`() {
        val rendered = PermissionPrompts.render("Bash", "ls", "general-purpose")
        assertEquals("**Bash** · general-purpose  \nls", rendered)
    }

    @Test
    fun `子代理来源里的 Markdown 符号被转义`() {
        val rendered = PermissionPrompts.render("Bash", "ls", "my*agent`x")
        assertEquals("**Bash** · my\\*agent\\`x  \nls", rendered)
    }
}
