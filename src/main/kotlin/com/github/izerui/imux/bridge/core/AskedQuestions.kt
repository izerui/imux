package com.github.izerui.imux.bridge.core

/** Claude 的 AskUserQuestion 调用里的一道题，取自会话记录里的调用参数，不看屏幕。 */
data class AskedQuestion(
    val question: String,
    val multiSelect: Boolean,
    val options: List<AskedOption>,
)

data class AskedOption(
    val label: String,
    val description: String?,
)

/**
 * 记录里读出的提问在手机上怎么列、手机的回复怎么交给 Claude。纯函数，无 IO。
 *
 * 和电脑上一样一题一题来：每次只推一题，选项用 1、2、3 编号；手机答一题由插件记下，电脑上同时翻到下一题
 * （见 [ADVANCE_KEYS]），再推下一题。全部答完后调用方先按 Esc 关掉提问框，再把各题的答案作为一条消息发给 Claude。
 * 答案本身不在提问框里填：
 * 电脑上可能已经把光标移进自定义输入项、打了草稿，或已答了几题，按键就会错位，而不读屏无从得知；
 * 按 Claude Code 2.1.308 实测，不论框里是什么状态，Esc 一次都会关掉整个提问框、回到输入框。
 * 回复只有选项编号（字母也认，A 即 1）就译成「题目 → 选项原文」，其余任何说法原样作为这道题的回答，
 * 由 Claude 结合刚才的提问理解。
 */
object AskedQuestions {
    /** 一道题排成列表：题目一行，选项用编号；[multiSelectMark] 跟在多选题题目后面。 */
    fun render(
        question: AskedQuestion,
        multiSelectMark: String,
    ): String {
        val title = PhoneMarkdown.inline(question.question.trim()) + if (question.multiSelect) multiSelectMark else ""
        val head = PhoneMarkdown.escapeLineStart(title)
        val options =
            question.options.withIndex().map { (i, option) ->
                val number = "${i + 1}."
                val description = option.description?.trim()?.takeIf(String::isNotEmpty)?.let { " · " + PhoneMarkdown.inline(it) }.orEmpty()
                "**$number** " + PhoneMarkdown.inline(option.label.trim()) + description
            }
        return (listOf(head) + options).joinToString(PhoneMarkdown.LINE_BREAK)
    }

    /**
     * 一道题的回答：对得上选项就是「题目 → 选项原文」，否则「题目 → 原话」；空白回复返回 null。
     * 原话有多行时第二行起缩进，几道题的回答拼成一条消息后仍分得清每行属于哪题。
     */
    fun answer(
        question: AskedQuestion,
        reply: String,
    ): String? {
        if (reply.isBlank()) return null
        val text = chosen(question, reply) ?: reply.trim().lines().joinToString("\n  ")
        return "${question.question.trim()} → $text"
    }

    /**
     * 答完一题（不是最后一题）后让电脑上也翻到下一题：只按 →，不按编号。编号依赖框此刻的状态（光标在自定义输入里时
     * 会写成文字，电脑上已答到确认页时会按下提交），→ 只翻页、不选中不提交，光标在输入里时也只是移动光标，按错无害。
     * 答案以插件记下的为准，最后一起发给 Claude。
     */
    val ADVANCE_KEYS: List<String> = listOf(TerminalKeys.RIGHT)

    /** 对得上选项时给出选项原文（多选用顿号连起来），否则 null。 */
    private fun chosen(
        question: AskedQuestion,
        answer: String,
    ): String? {
        val picks = picks(answer, question.options.size) ?: joinedDigits(question, answer)?.let { picks(it, question.options.size) } ?: return null
        if (!question.multiSelect && picks.size != 1) return null
        return picks.joinToString("、") { question.options[it - 1].label.trim() }
    }

    /**
     * 多选题里连着写的编号（「123」即 1、2、3）拆成单个数字再认：选项不超过 9 个时编号都是一位，不会有歧义。
     * 单选题不拆，「12」可能另有所指，原样作为回答。
     */
    private fun joinedDigits(
        question: AskedQuestion,
        answer: String,
    ): String? {
        if (!question.multiSelect || question.options.size > 9) return null
        val body = TerminalKeys.normalizeReply(answer).removePrefix("选").removePrefix("選").trim()
        return body.takeIf { it.length > 1 && it.all(Char::isDigit) }?.toList()?.joinToString(" ")
    }

    /**
     * 只有选项字母或编号（可带「选」前缀，空格、逗号、顿号、「和」隔开，全角也认，结尾的句号等标点忽略）时给出选中的编号；
     * 混着别的字、对不上选项、重复时为 null。
     */
    private fun picks(
        answer: String,
        optionCount: Int,
    ): List<Int>? {
        val body = TerminalKeys.normalizeReply(answer).removePrefix("选").removePrefix("選").trim()
        val tokens = body.split(SEPARATORS).filter(String::isNotEmpty)
        if (tokens.isEmpty()) return null
        val picks =
            tokens.map { token ->
                val letter = token.singleOrNull()?.lowercaseChar()?.takeIf { it in 'a'..'z' }
                letter?.let { it - 'a' + 1 } ?: token.toIntOrNull() ?: return null
            }
        if (picks.toSet().size != picks.size || picks.any { it !in 1..optionCount }) return null
        return picks
    }

    private val SEPARATORS = Regex("""[\s,，、;；/&]+|和|及|and""")
}
