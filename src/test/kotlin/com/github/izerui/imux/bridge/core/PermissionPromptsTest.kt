package com.github.izerui.imux.bridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 权限确认在手机上的排版，以及手机回复的词表（经 [HookReplies] 译成 hook 决定）。 */
class PermissionPromptsTest {
    @Test
    fun `工具名一行，要批准的内容逐行跟在后面；工具名加粗、行首像列表的转义`() {
        assertEquals("**Bash**  \n删除临时文件  \n\\- rm \\*.tmp", PermissionPrompts.render("Bash", "删除临时文件\n- rm *.tmp"))
    }

    @Test
    fun `允许词（含繁体、日文、全角与结尾标点）只允许这一次，拒绝词拒绝，其他说法拒绝并转告原话`() {
        for (reply in listOf("1", "A", "允许", "好的。", "OK", "yes", "允許", "許可", "１")) {
            assertEquals(reply, HookDecision.Allow(), HookReplies.permission(reply))
        }
        val denied = HookDecision.Deny("The user denied this tool call from their phone.")
        for (reply in listOf("2", "b", "拒绝", "不行！", "No", "拒絕", "拒否")) assertEquals(reply, denied, HookReplies.permission(reply))
        assertEquals(
            "英文要求照样拒绝并转告",
            HookDecision.Deny("The user denied this tool call from their phone and said: use pnpm instead"),
            HookReplies.permission("use pnpm instead"),
        )
    }

    @Test
    fun `计划确认：繁体的不執行也算不执行`() {
        assertEquals(HookDecision.Deny("The user does not want to proceed with this plan yet."), HookReplies.plan("不執行"))
    }

    /**
     * 实测的权限框：1. Yes / 2. Yes, and always allow access… / 3. Yes, and switch to auto mode / 4. No。
     * 手机上只能允许这一次或拒绝：除了「1」对应的 Yes，其余编号都只拒绝，不当成要求转告。
     */
    @Test
    fun `手机回 1 到 4：只有 1 允许这一次，2、3、4 等编号只拒绝，不把编号当成要求转告`() {
        assertEquals(HookDecision.Allow(), HookReplies.permission("1"))
        val denied = HookDecision.Deny("The user denied this tool call from their phone.")
        for (reply in listOf("2", "3", "4", "B", "c", "d", "5", "9")) assertEquals(reply, denied, HookReplies.permission(reply))
    }

    @Test
    fun `任何回复的允许都只允许这一次，不带模式或答案`() {
        for (reply in listOf("1", "2", "3", "4", "允许", "拒绝", "永久允许", "always", "切到自动模式", "2 3", "随便说一句")) {
            val decision = HookReplies.permission(reply)
            assertTrue(reply, decision is HookDecision.Deny || decision == HookDecision.Allow())
        }
    }
}
