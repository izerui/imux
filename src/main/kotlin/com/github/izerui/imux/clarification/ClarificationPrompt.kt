// ClarificationPrompt.kt
package com.github.izerui.imux.clarification

internal const val JSON_ONLY_ZH = "只输出一个 JSON 对象，不要输出其他文字。不修改任何文件。"
internal const val JSON_ONLY_EN = "Output exactly one JSON object and nothing else. Do not modify any file."

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
 * 一段对话的第一条消息：内嵌指南 + 提问规则 + 原需求 + 背景快照 + 输出格式。
 * 之前的对话中断过（取消、失败）时，[record] 里已有的问答一并带上，让新对话接着做；
 * [finishNow] 为 true 时要求直接给出调整后的需求（「够了，生成结果」时对话已中断）。
 */
internal fun openingMessage(record: ClarificationRecord, isChinese: Boolean, guidance: String, finishNow: Boolean): String =
    buildString {
        append(guidance.trim()).append("\n\n")
        append(if (isChinese) "## 提问规则\n\n" else "## Rules for Asking\n\n")
        append(if (isChinese) "$BATCH_RULE_ZH $QUESTION_RULE_ZH" else "$BATCH_RULE_EN $QUESTION_RULE_EN").append("\n\n")
        append(if (isChinese) "## 需求\n\n" else "## Requirement\n\n")
        append(record.requirement.trim()).append("\n\n")
        record.background?.let {
            append(if (isChinese) "## 背景：主会话最近的对话\n\n" else "## Background: Recent Conversation in the Main Session\n\n")
            append(it.trim()).append("\n\n")
        }
        if (record.history.isNotEmpty()) {
            append(if (isChinese) "## 用户已经给出的回答\n\n" else "## Answers the User Has Already Given\n\n")
            append(
                if (isChinese) "之前的澄清对话中断了，下面是用户在其中已经给出的回答，都算已确认，不要再问。\n\n"
                else "An earlier clarification conversation was interrupted; these are the answers the user gave in it. They are confirmed; do not ask them again.\n\n",
            )
            append(answerLines(record.history, isChinese)).append("\n\n")
        }
        append(if (isChinese) "## 现在\n\n" else "## Now\n\n")
        append(
            when {
                finishNow -> finishInstruction(isChinese)
                record.history.isNotEmpty() ->
                    if (isChinese) "带着这些回答继续调查，给出下一批问题；没有需要用户拍板的方向了就给出调整后的需求。"
                    else "Continue investigating with these answers and ask the next batch; when no direction needs the user's call, give the adjusted requirement."
                else ->
                    if (isChinese) "先调查项目，再给出第一批问题；没有需要用户拍板的方向时直接给出调整后的需求。"
                    else "Investigate the project first, then ask the first batch; if nothing needs the user's call, give the adjusted requirement right away."
            },
        ).append("\n\n")
        append(if (isChinese) "## 输出格式\n\n" else "## Output Format\n\n")
        append(REPLY_FORMAT).append("\n\n")
        append(if (isChinese) JSON_ONLY_ZH else JSON_ONLY_EN)
    }

/** 用户提交了一批：只发这一批的回答（短标签 → 回答；不回答的写明按默认处理）和一句格式提醒。 */
internal fun answersMessage(records: List<AnswerRecord>, isChinese: Boolean): String =
    buildString {
        append(if (isChinese) "用户对这一批问题的回答：\n\n" else "The user's answers to this batch:\n\n")
        append(answerLines(records, isChinese)).append("\n\n")
        append(
            if (isChinese) "带着这些回答继续：给出下一批问题，或在没有需要用户拍板的方向时给出调整后的需求。"
            else "Continue with these answers: ask the next batch, or give the adjusted requirement when no direction needs the user's call.",
        ).append(' ')
        append(formatReminder(isChinese))
    }

/** 「够了，生成结果」：这一批里已填写的回答（可能没有）+ 要求现在给出调整后的需求。 */
internal fun finishMessage(records: List<AnswerRecord>, isChinese: Boolean): String =
    buildString {
        if (records.isNotEmpty()) {
            append(if (isChinese) "用户对这一批问题已经填写的回答：\n\n" else "The answers the user filled in for this batch:\n\n")
            append(answerLines(records, isChinese)).append("\n\n")
        }
        append(finishInstruction(isChinese)).append(' ')
        append(formatReminder(isChinese))
    }

/** 看过结果后要求继续澄清。 */
internal fun continueMessage(isChinese: Boolean): String =
    (
        if (isChinese) "用户看过这份需求后要求继续澄清：再想想还有没有需要用户拍板的方向，有就给出下一批问题，没有就再给出调整后的需求。"
        else "The user read this requirement and wants to keep clarifying: consider whether any direction still needs their call; if so ask the next batch, otherwise give the adjusted requirement again."
        ) + " " + formatReminder(isChinese)

/** 回复不合协议：在同一段对话里把错误发回，要求按格式重答。 */
internal fun correctionMessage(errors: List<String>, isChinese: Boolean): String =
    buildString {
        append(if (isChinese) "你的上一条回复不符合约定的格式：\n\n" else "Your last reply does not follow the agreed format:\n\n")
        errors.forEach { append("- ").append(it).append('\n') }
        append('\n')
        append(if (isChinese) "请修正后重新输出，内容不变的部分照旧。\n\n" else "Fix these and answer again; keep whatever was right.\n\n")
        append(REPLY_FORMAT).append("\n\n")
        append(if (isChinese) JSON_ONLY_ZH else JSON_ONLY_EN)
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
                is Answer.OwnWords -> otherWords(a.text, isChinese)
                Answer.Skipped -> (if (isChinese) "没有回答，按默认处理：" else "Not answered; use the default: ") + r.recorded
            }
        "- ${q.header}${paren(q.text)} → $answer"
    }

private fun otherWords(text: String, isChinese: Boolean) = (if (isChinese) "其他：" else "Other: ") + text

private fun finishInstruction(isChinese: Boolean): String =
    if (isChinese) "用户要求现在生成结果。把原需求与用户已确认的回答融合成调整后的需求：精炼，只到方向层，不附带任何说明、依据、未决事项或标记；没定的方向不要写成已定。只返回 {\"type\": \"done\", \"requirement\": \"...\"}。"
    else "The user asked for the result now. Merge the original requirement with the answers the user confirmed into the adjusted requirement: concise, at the level of direction, with no explanations, evidence, open items or markers; never write an unsettled direction as settled. Return only {\"type\": \"done\", \"requirement\": \"...\"}."

private fun formatReminder(isChinese: Boolean): String =
    if (isChinese) "回复仍只用约定的 JSON：{\"type\": \"questions\", ...} 或 {\"type\": \"done\", ...}。"
    else "Reply with the agreed JSON only: {\"type\": \"questions\", ...} or {\"type\": \"done\", ...}."

private const val BATCH_RULE_ZH =
    "每次给 1～$MAX_QUESTIONS_PER_ROUND 个问题，数量由你判断：几个问题彼此独立、此刻都已能问，就一次给出；某个回答会决定接下来查什么、问什么，就只给这一题，等回答后再调查。同一批里每题针对不同的方向。用户在插件里逐题作答、可以来回修改，整批提交后插件才把回答作为下一条消息发给你；没回答的题按该题的 fallback 处理。"

private const val BATCH_RULE_EN =
    "Ask 1-$MAX_QUESTIONS_PER_ROUND questions at a time; you decide how many. Give several at once when they are independent and can all be asked now; give only one when its answer decides what to investigate or ask next, and investigate again after it is answered. Each question in a batch targets a different direction. The user answers them in the plugin, can go back and change answers, and submits the whole batch; only then are the answers sent to you as the next message. A question left unanswered is settled by its `fallback`."

private const val QUESTION_RULE_ZH =
    "每题：header 是不超过 $HEADER_CHARS 个字的短标签（显示在标签条上）；question 是完整的问句；fallback 写明用户不回答时按什么处理；2～4 个真正不同的选项，推荐的放第一个并设 recommended 为 true；label 是不超过 $LABEL_CHARS 个字的短标签；description 用一句话写明选了它方向就定为什么——它就是用户选中后记下的内容；几个选项可以同时成立时设 multiSelect 为 true。不要给\"其他\"选项，插件会自动追加一个可自由输入的\"其他\"。"

private const val QUESTION_RULE_EN =
    "For each question: `header` is a short tag of at most $HEADER_CHARS characters (shown on the tab strip); `question` is the full question; `fallback` states what happens if the user leaves it unanswered; 2-4 genuinely different options with the recommended one first and `recommended: true`; `label` is a short tag of at most $LABEL_CHARS characters; `description` is one sentence saying what the direction becomes if it is chosen - it is exactly what gets recorded when the user picks it; set `multiSelect: true` when several options can hold together. Do not include an \"Other\" option; the plugin appends one with free text."

private val REPLY_FORMAT =
    """
    {
      "type": "questions",
      "questions": [
        {
          "header": "...",
          "question": "...",
          "multiSelect": false,
          "fallback": "...",
          "options": [{"id": "a", "label": "...", "description": "...", "recommended": true}]
        }
      ]
    }
    或 / or: {"type": "done", "requirement": "..."}
    questions 1～$MAX_QUESTIONS_PER_ROUND 个，各针对不同的方向；header 不超过 $HEADER_CHARS 个字；fallback 不为空；每题 options 2～4 个，每个都有 description，不含"其他"。
    1-$MAX_QUESTIONS_PER_ROUND questions, each on a different direction; header at most $HEADER_CHARS characters; fallback not empty; 2-4 options per question, each with a description, no "Other".
    """.trimIndent()
