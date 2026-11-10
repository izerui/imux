package com.github.izerui.imux.bridge.core

/**
 * 权限确认框在手机上怎么列、手机的回复怎么译成按键。纯函数，无 IO。
 *
 * 不看屏幕：要批准的内容取自记录里的工具调用。按 Claude Code 2.1.308 实测，权限框第 1 项总是「Yes」，
 * Esc 拒绝这次调用并结束这一轮，回到输入框。所以手机上只给三种回答：允许这一次、拒绝、或者说一段话
 * （拒绝后把这段话照常发给会话，相当于「别这样做，改成……」）。终端里第 2 项起是永久授权、切换模式等，
 * 选项随情况变化、记录里也没有，手机上一律不按。
 *
 * 允许只按编号 1，不先移动光标：电脑上恰好已按 Tab 进入修改输入时，1 会被写成文字、什么也不发生，
 * 回到电脑上处理或再回一次即可；这样按错时无害，也不依赖各种确认框最后一项是不是输入框。
 */
object PermissionPrompts {
    /** 工具名一行，要批准的内容逐行跟在后面。 */
    internal fun render(
        call: ToolCall,
        markdown: Boolean,
    ): String {
        val lineBreak = if (markdown) "  \n" else "\n"
        val name = if (markdown) "**${PhoneMarkdown.inline(call.name, true)}**" else call.name
        val detail = call.detail.lines().filter(String::isNotBlank).map { if (markdown) PhoneMarkdown.escapeLineStart(PhoneMarkdown.inline(it.trim(), true)) else it.trim() }
        return (listOf(name) + detail).joinToString(lineBreak)
    }

    /** 手机回复的按键，以及按完后是否还要把原话发给会话。 */
    data class Reply(
        val keys: List<String>,
        val thenSubmit: Boolean,
    )

    fun reply(text: String): Reply {
        val word = TerminalKeys.normalizeReply(text).lowercase()
        return when (word) {
            in ALLOW -> Reply(listOf(YES), thenSubmit = false)
            in DENY -> Reply(listOf(TerminalKeys.ESC), thenSubmit = false)
            // 其余像选项编号、字母的回复（终端里 2 起是永久授权、切换模式等，选项随情况变化，记录里也没有）只拒绝，
            // 不按编号，也不把编号当成要求发给会话
            else -> if (OPTION_LIKE.matches(word)) Reply(listOf(TerminalKeys.ESC), thenSubmit = false) else Reply(listOf(TerminalKeys.ESC), thenSubmit = true)
        }
    }

    /** 计划确认（ExitPlanMode）也走权限框，但选项不同，见 [planReply]。 */
    const val EXIT_PLAN_MODE = "ExitPlanMode"

    /**
     * 计划确认的回复。按 Claude Code 2.1.308 实测选项是「1. Yes, and use auto mode」「2. Yes, manually approve edits」
     * 「3. Tell Claude what to change」；Esc 拒绝计划、留在计划模式回到输入框。所以「1」「2」照按，
     * 不执行就按 Esc，其他说法按 Esc 后把原话发给会话，Claude 按它修改计划。
     *
     * 第 3 项本身是输入框，光标停在那里时数字会被写成文字。不能像权限框那样先按 ↑：光标在第 1 项时 ↑ 会绕到第 3 项、
     * 反而进入输入；三项循环，只用方向键做不到从任何位置都停在非输入项上。所以直接按编号：平常光标在第 1、2 项时正确，
     * 电脑上已把光标移进第 3 项的输入时，编号会写进那里、计划不会执行。
     */
    fun planReply(text: String): Reply {
        val word = TerminalKeys.normalizeReply(text).lowercase()
        return when (word) {
            "1", "a" -> Reply(listOf("1"), thenSubmit = false)
            "2", "b" -> Reply(listOf("2"), thenSubmit = false)
            in DENY - setOf("2", "b") + setOf("不执行", "不執行") -> Reply(listOf(TerminalKeys.ESC), thenSubmit = false)
            else -> Reply(listOf(TerminalKeys.ESC), thenSubmit = true)
        }
    }

    /** 权限框第 1 项「Yes」（只允许这一次）的编号；手机上不按其它编号，不会永久授权或切换模式。 */
    private const val YES = "1"
    private val OPTION_LIKE = Regex("""(?:\d{1,2}|[a-z])(?:[\s,，、]+(?:\d{1,2}|[a-z]))*""")
    private val ALLOW = setOf("1", "a", "允许", "允許", "許可", "同意", "可以", "好", "好的", "行", "yes", "y", "ok", "allow")
    private val DENY = setOf("2", "b", "拒绝", "拒絕", "拒否", "不", "不行", "不要", "no", "n", "deny")
}
