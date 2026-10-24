// ClarificationPrompt.kt
package com.github.izerui.imux.clarification

internal const val JSON_ONLY_ZH = "只输出一个 JSON 对象，不要输出其他文字。"
internal const val JSON_ONLY_EN = "Output exactly one JSON object and nothing else."

private object GuidanceAnchor

/** 内嵌指南正文（随插件 jar 分发）；拼进提示词前去掉 front matter。 */
internal fun loadGuidance(isChinese: Boolean): String {
    val name = if (isChinese) "requirement-clarifier.zh.md" else "requirement-clarifier.en.md"
    val stream = GuidanceAnchor::class.java.getResourceAsStream("/clarification/$name")
        ?: error("Missing bundled guidance /clarification/$name")
    return stripFrontMatter(stream.bufferedReader().use { it.readText() }).trim()
}

internal fun stripFrontMatter(text: String): String {
    val normalized = text.replace("\r\n", "\n")
    if (!normalized.startsWith("---\n")) return normalized
    val end = normalized.indexOf("\n---\n", startIndex = 4)
    return if (end < 0) normalized else normalized.substring(end + "\n---\n".length)
}

/**
 * 一段对话的第一条消息：核心指南 + 运行边界 + 用户内容 + 输出协议。
 * 之前的对话中断过（取消、失败）时，[record] 里已有的问答一并带上，让新对话接着做。
 */
internal fun openingMessage(
    record: ClarificationRecord,
    isChinese: Boolean,
    guidance: String,
    plan: String? = null,
    planHistorySize: Int = 0,
): String =
    buildString {
        val answers = if (plan == null) record.history else record.history.drop(planHistorySize)
        append(guidance.trim()).append("\n\n")
        append(if (isChinese) "## 运行边界\n\n" else "## Operating Boundaries\n\n")
        append(if (isChinese) OPERATING_RULES_ZH else OPERATING_RULES_EN).append("\n\n")
        append(if (isChinese) "## 用户原始输入\n\n" else "## Original User Input\n\n")
        append(record.requirement.trim()).append("\n\n")
        if (plan != null) {
            append(if (isChinese) "## 用户修订的讨论成果\n\n" else "## User-Revised Outcome\n\n")
            append(plan).append("\n\n")
            append(
                if (isChinese) "这份成果取代修订前的决定。下方回答是在修订之后提交的；若与成果冲突，以后续回答为准。\n\n"
                else "This outcome replaces decisions made before the revision. The answers below were submitted after the revision; if they conflict with the outcome, the later answers take precedence.\n\n",
            )
        }
        if (answers.isNotEmpty()) {
            append(if (isChinese) "## 用户已经给出的回答\n\n" else "## Answers the User Has Already Given\n\n")
            append(
                if (isChinese) "这是中断前保留的问答，按发生顺序排列；未保留的调查信息不代表已经查证。\n\n"
                else "These are the retained exchanges from the interrupted conversation, in chronological order. Missing investigation findings are not established facts.\n\n",
            )
            append(answerLines(answers, isChinese)).append("\n\n")
        }
        append(outputProtocol(isChinese))
    }

/** 用户提交了一批：只发这一批的回答（短标签 → 回答；不回答的写明按默认处理）和一句格式提醒。 */
internal fun answersMessage(records: List<AnswerRecord>, isChinese: Boolean): String =
    buildString {
        append(if (isChinese) "用户对这一批问题的回答：\n\n" else "The user's answers to this batch:\n\n")
        append(answerLines(records, isChinese)).append("\n\n")
        append(formatReminder(isChinese))
    }

/** 看过结果后要求继续聊。 */
internal fun continueMessage(isChinese: Boolean, plan: String): String =
    (
        if (isChinese) "用户希望在以下当前成果的基础上继续探讨。这份文本取代修订前的决定。"
        else "The user wants to continue the discussion from the current outcome below. This text replaces decisions made before the revision."
        ) + "\n\n" +
        plan + "\n\n" + formatReminder(isChinese)

/** 回复不合协议：在同一段对话里把错误发回，要求按格式重答。 */
internal fun correctionMessage(errors: List<String>, isChinese: Boolean): String =
    buildString {
        append(if (isChinese) "你的上一条回复不符合约定的格式：\n\n" else "Your last reply does not follow the agreed format:\n\n")
        errors.forEach { append("- ").append(it).append('\n') }
        append('\n')
        append(if (isChinese) "请修正后重新输出，内容不变的部分照旧。\n\n" else "Fix these and answer again; keep whatever was right.\n\n")
        append(outputProtocol(isChinese))
    }

/** 一题一行：短标签 → 回答。 */
internal fun answerLines(records: List<AnswerRecord>, isChinese: Boolean): String =
    records.joinToString("\n") { r ->
        val q = r.question
        fun paren(text: String) = if (isChinese) "（$text）" else " ($text)"
        val answer =
            when (val a = r.answer) {
                is Answer.Picked ->
                    (q.options.filter { it.id in a.optionIds }.map { it.label + paren(it.description) } + listOfNotNull(a.other?.let { otherWords(it, isChinese) }))
                        .joinToString(if (isChinese) "；" else "; ")
                is Answer.OwnWords -> if (q.kind == QuestionKind.OPEN) a.text else otherWords(a.text, isChinese)
                Answer.Skipped -> (if (isChinese) "没有回答，按默认处理：" else "Not answered; use the default: ") + r.recorded
            }
        val context = q.context.takeIf(String::isNotBlank)?.let { "$it\n" }.orEmpty()
        "- ${q.header}${paren(context + q.text)} → $answer"
    }

private fun otherWords(text: String, isChinese: Boolean) = (if (isChinese) "其他：" else "Other: ") + text

private fun formatReminder(isChinese: Boolean): String =
    if (isChinese) "回复仍只用约定的 JSON：{\"type\": \"questions\", ...} 或 {\"type\": \"done\", ...}。"
    else "Reply with the agreed JSON only: {\"type\": \"questions\", ...} or {\"type\": \"done\", ...}."

private const val OPERATING_RULES_ZH =
    "本次只讨论与调查。可用工具读取相关代码、文档和查询网络事实；不修改文件、不运行构建或测试、不启动服务或长时间运行的命令。未回答不代表赞同，探索兴趣不代表实施授权；用户最新明确修订优先。遵守下方输出协议。"

private const val OPERATING_RULES_EN =
    "This session is for discussion and investigation. Use tools to read relevant code and documents or check facts online; do not modify files, run builds or tests, start services or run long-lived commands. Silence is not agreement and exploratory interest is not implementation authorization; the user's latest explicit revision takes precedence. Follow the output protocol below."

private fun outputProtocol(isChinese: Boolean): String =
    buildString {
        append(if (isChinese) "## 输出协议\n\n" else "## Output Protocol\n\n")
        append(if (isChinese) BATCH_RULE_ZH else BATCH_RULE_EN).append("\n\n")
        append(if (isChinese) QUESTION_RULE_ZH else QUESTION_RULE_EN).append("\n\n")
        append(REPLY_FORMAT).append("\n\n")
        append(if (isChinese) JSON_ONLY_ZH else JSON_ONLY_EN)
    }

private const val BATCH_RULE_ZH =
    "需要用户回应时返回 type=questions，包含 1～$MAX_QUESTIONS_PER_ROUND 题；开放、单选、多选同等可用，可混合，不要求比例或顺序。整批回答作为下一条消息返回。type=done 的 requirement 承载当前讨论成果，仍可继续聊；不要求先提问或产出实施方案。以下 JSON 是结构示例，不是固定问卷。"

private const val BATCH_RULE_EN =
    "When a response is needed, return type=questions with 1-$MAX_QUESTIONS_PER_ROUND items. Open, single-choice and multiple-choice forms are equally available and may be mixed, with no quota or order. Answers arrive as one message. For type=done, requirement holds the current outcome, which can still be discussed; neither prior questions nor an implementation plan are required. The JSON below illustrates structure, not a fixed questionnaire."

private const val QUESTION_RULE_ZH =
    "每题：header 不超过 $HEADER_CHARS 个字；可选 context 承载看法、解释、例子或背景；question 为完整问题；fallback 披露未回答时如何继续。kind=open 无 options，multiSelect 省略或为 false。kind=choice 含 2～4 个选项，multiSelect=false 为单选、true 为多选；选项含唯一 id、最多 $LABEL_CHARS 字的 label、原样记录选择含义的 description，以及布尔 recommended；有推荐时放前面。不要给\"其他\"选项，界面自带自由输入。"

private const val QUESTION_RULE_EN =
    "Each item has a header of at most $HEADER_CHARS characters, optional context for a view, explanation, example or background, a complete question, and fallback disclosing how to proceed without an answer. kind=open has no options; omit multiSelect or set it to false. kind=choice has 2-4 options; multiSelect=false means single-choice, true means multiple-choice. Each option has a unique id, label of at most $LABEL_CHARS characters, description recording the choice's meaning verbatim, and boolean recommended; put recommendations first when present. Do not include an \"Other\" option; the interface provides free text."

private val REPLY_FORMAT =
    """
    {
      "type": "questions",
      "questions": [
        {
          "kind": "open",
          "header": "...",
          "context": "...",
          "question": "...",
          "fallback": "..."
        }
      ]
    }
    或 / or:
    {
      "type": "questions",
      "questions": [
        {
          "kind": "choice",
          "header": "...",
          "context": "...",
          "question": "...",
          "multiSelect": false,
          "fallback": "...",
          "options": [
            {"id": "a", "label": "...", "description": "...", "recommended": true},
            {"id": "b", "label": "...", "description": "...", "recommended": false}
          ]
        }
      ]
    }
    或 / or: {"type": "done", "requirement": "..."}
    """.trimIndent()
