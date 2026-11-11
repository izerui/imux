package com.github.izerui.imux.bridge.core

/** Claude 的 AskUserQuestion 调用里的一道题，取自 hook 请求里的调用参数，不看屏幕。 */
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
 * hook 送来的提问在手机上怎么列、手机的回复怎么译成这题的答案。纯函数，无 IO。
 *
 * 和电脑上一样一题一题来：每次只推一题，选项用 1、2、3 编号；全部答完后由路由器一次性回给 hook。
 * 回复只有选项编号（字母也认，A 即 1）就译成选项原文，其余任何说法原样作为这道题的回答。
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
     * 回给 hook 的这题答案：编号对得上选项给 label（多选以 ", " 连接），否则原话；空白返回 null。
     * 单选题选多个时按原话处理。
     */
    fun hookAnswer(question: AskedQuestion, reply: String): String? {
        if (reply.isBlank()) return null
        val matched = picks(reply, question.options.size)
            ?: joinedDigits(question, reply)?.let { picks(it, question.options.size) }
        if (matched != null) {
            if (!question.multiSelect && matched.size != 1) return reply.trim()
            return matched.joinToString(", ") { question.options[it - 1].label.trim() }
        }
        return reply.trim()
    }

    /**
     * 多选题里连着写的编号（「123」即 1、2、3）拆成单个数字再认：选项不超过 9 个时编号都是一位，不会有歧义。
     * 单选题不拆，「12」可能另有所指，原样作为回答。
     */
    internal fun joinedDigits(
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
    internal fun picks(
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
