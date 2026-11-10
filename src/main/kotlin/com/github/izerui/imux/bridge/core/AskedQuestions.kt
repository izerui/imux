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
 * 题目一次全部列出，手机一条回复答完。作答不在提问框里按键：电脑上可能已经把光标移进自定义输入项、打了草稿，
 * 或者多题里已经答了几题，按键就会错位，而不读屏无从得知。按 Claude Code 2.1.308 实测，不论框里是什么状态，
 * Esc 一次都会关掉整个提问框、回到输入框；所以调用方先按 Esc，再把 [answer] 给出的文字作为一条消息发给 Claude。
 * 回复写得规整（只有一题时回 `B`、`2`、`A C`；多题时每行一题）就译成「题目 → 选项原文」，
 * 其余任何说法（「第一题选第二个」「都要推荐的」）原样发出，由 Claude 结合刚才的提问理解。
 */
object AskedQuestions {
    /** 题目与选项排成列表；选项用字母编号，[multiSelectMark] 跟在多选题题目后面。 */
    fun render(
        questions: List<AskedQuestion>,
        markdown: Boolean,
        multiSelectMark: String,
    ): String {
        val lineBreak = if (markdown) "  \n" else "\n"
        return questions.withIndex().joinToString("\n\n") { (index, question) ->
            val title = PhoneMarkdown.inline(question.question.trim(), markdown) + if (question.multiSelect) multiSelectMark else ""
            val head =
                when {
                    questions.size > 1 -> (if (markdown) "**${index + 1}.**" else "${index + 1}.") + " " + title
                    markdown -> PhoneMarkdown.escapeLineStart(title)
                    else -> title
                }
            val options =
                question.options.withIndex().map { (i, option) ->
                    val letter = "${'A' + i}."
                    val description = option.description?.trim()?.takeIf(String::isNotEmpty)?.let { " · " + PhoneMarkdown.inline(it, markdown) }.orEmpty()
                    (if (markdown) "**$letter**" else letter) + " " + PhoneMarkdown.inline(option.label.trim(), markdown) + description
                }
            (listOf(head) + options).joinToString(lineBreak)
        }
    }

    /** 回复 [reply] 交给 Claude 的文字；空白回复返回 null。 */
    fun answer(
        questions: List<AskedQuestion>,
        reply: String,
    ): String? {
        if (reply.isBlank() || questions.isEmpty()) return null
        val lines = reply.lines().map(String::trim).filter(String::isNotEmpty)
        if (questions.size == 1) return chosen(questions.single(), reply)?.let { "${questions.single().question.trim()} → $it" } ?: reply.trim()
        if (lines.size != questions.size) return reply.trim()
        return questions.zip(lines).joinToString("\n") { (question, line) -> "${question.question.trim()} → ${chosen(question, line) ?: line}" }
    }

    /** 对得上选项时给出选项原文（多选用顿号连起来），否则 null。 */
    private fun chosen(
        question: AskedQuestion,
        answer: String,
    ): String? {
        val picks = picks(answer, question.options.size) ?: return null
        if (!question.multiSelect && picks.size != 1) return null
        return picks.joinToString("、") { question.options[it - 1].label.trim() }
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
