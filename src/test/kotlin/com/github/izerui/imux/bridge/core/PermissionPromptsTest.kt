package com.github.izerui.imux.bridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 按 Claude Code 2.1.308 实测：权限框第 1 项是 Yes，Esc 拒绝并回到输入框。 */
class PermissionPromptsTest {
    @Test
    fun `工具名一行，要批准的内容逐行跟在后面；Markdown 渠道工具名加粗、行首像列表的转义`() {
        val call = ToolCall("Bash", "删除临时文件\n- rm *.tmp")
        assertEquals("Bash\n删除临时文件\n- rm *.tmp", PermissionPrompts.render(call, markdown = false))
        assertEquals("**Bash**  \n删除临时文件  \n\\- rm \\*.tmp", PermissionPrompts.render(call, markdown = true))
    }

    @Test
    fun `允许只按 1，拒绝按 Esc，其他说法按 Esc 后把原话发给会话`() {
        for (reply in listOf("1", "A", "允许", "好的。", "OK", "yes")) assertEquals(reply, PermissionPrompts.Reply(listOf("1"), false), PermissionPrompts.reply(reply))
        for (reply in listOf("2", "b", "拒绝", "不行！", "No")) assertEquals(reply, PermissionPrompts.Reply(listOf(TerminalKeys.ESC), false), PermissionPrompts.reply(reply))
        assertEquals(PermissionPrompts.Reply(listOf(TerminalKeys.ESC), true), PermissionPrompts.reply("先别删，列出来给我看"))
        for (reply in listOf("允許", "許可")) assertEquals(reply, PermissionPrompts.Reply(listOf("1"), false), PermissionPrompts.reply(reply))
        for (reply in listOf("拒絕", "拒否")) assertEquals(reply, PermissionPrompts.Reply(listOf(TerminalKeys.ESC), false), PermissionPrompts.reply(reply))
        assertEquals("繁体的不執行", PermissionPrompts.Reply(listOf(TerminalKeys.ESC), false), PermissionPrompts.planReply("不執行"))
        assertEquals("英文要求照样拒绝后发给会话", PermissionPrompts.Reply(listOf(TerminalKeys.ESC), true), PermissionPrompts.reply("use pnpm instead"))
    }

    @Test
    fun `计划确认：1、2 照按，不执行按 Esc，其他说法按 Esc 后把意见发给会话`() {
        assertEquals("光标在第 1 项时 ↑ 会绕进第 3 项的输入，所以不先按 ↑", PermissionPrompts.Reply(listOf("1"), false), PermissionPrompts.planReply("1"))
        assertEquals(PermissionPrompts.Reply(listOf("2"), false), PermissionPrompts.planReply("B"))
        for (reply in listOf("不执行", "拒绝", "no")) assertEquals(reply, PermissionPrompts.Reply(listOf(TerminalKeys.ESC), false), PermissionPrompts.planReply(reply))
        assertEquals(PermissionPrompts.Reply(listOf(TerminalKeys.ESC), true), PermissionPrompts.planReply("先别写文件"))
    }


    /**
     * 实测的权限框：1. Yes / 2. Yes, and always allow access… / 3. Yes, and switch to auto mode / 4. No。
     * 手机上只能允许这一次或拒绝：除了「1」对应的 Yes，任何回复都不按编号，不会永久授权或切换模式。
     */
    @Test
    fun `手机回 1 到 4：只有 1 按编号（允许这一次），2、3、4 只按 Esc 拒绝，不把编号当成消息发出`() {
        assertEquals(PermissionPrompts.Reply(listOf("1"), false), PermissionPrompts.reply("1"))
        for (reply in listOf("2", "3", "4", "B", "c", "d", "5", "9")) {
            assertEquals(reply, PermissionPrompts.Reply(listOf(TerminalKeys.ESC), false), PermissionPrompts.reply(reply))
        }
    }

    @Test
    fun `任何回复按下的键里，除了允许这一次的 1，没有别的编号`() {
        for (reply in listOf("1", "2", "3", "4", "允许", "拒绝", "永久允许", "always", "切到自动模式", "2 3", "随便说一句")) {
            val digits = PermissionPrompts.reply(reply).keys.filter { key -> key.all(Char::isDigit) }
            assertTrue(reply, digits.isEmpty() || digits == listOf("1"))
        }
    }

}
