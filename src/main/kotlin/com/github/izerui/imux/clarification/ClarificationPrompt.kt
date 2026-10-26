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
): String =
    buildString {
        append(guidance.trim()).append("\n\n")
        append(if (isChinese) "## 运行边界\n\n" else "## Operating Boundaries\n\n")
        append(if (isChinese) OPERATING_RULES_ZH else OPERATING_RULES_EN).append("\n\n")
        append(if (isChinese) "## 用户原始输入\n\n" else "## Original User Input\n\n")
        append(record.requirement.trim()).append("\n\n")
        if (record.history.isNotEmpty() || record.reviewedDrafts.isNotEmpty()) {
            append(if (isChinese) "## 已保留的讨论\n\n" else "## Retained Discussion\n\n")
            append(
                if (isChinese) "以下保留全部回答和继续讨论时的文本，按发生顺序排列并注明来源；未保留的调查信息不代表已经查证。\n\n"
                else "All answers and texts used to continue the discussion are retained below, in chronological order with their origins. Missing investigation findings are not established facts.\n\n",
            )
            append(discussionHistory(record, isChinese)).append("\n\n")
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
internal fun continueMessage(isChinese: Boolean, draft: ReviewedDraft): String =
    (
        if (isChinese) "用户希望继续探讨。继续聊不代表确认候选任务；请保留原始目标与此前明确要求。"
        else "The user wants to continue the discussion. Continuing does not approve a candidate task; retain the original goal and earlier explicit requirements."
        ) + "\n\n" +
        draftContent(draft, isChinese) + "\n\n" + formatReminder(isChinese)

/** 原始文本和用户编辑分开保存；省略某项要求不自动成为取消该要求的指令。 */
private fun draftContent(draft: ReviewedDraft, isChinese: Boolean): String =
    buildString {
        if (draft.origin == ContentOrigin.NOTES) {
            append(if (isChinese) "用户对整理材料的修改（仅记录差异）：\n" else "User edits to discussion notes (changes only):\n")
            if (draft.notesChanges.isEmpty()) append(if (isChinese) "没有修改。\n" else "No edits.\n")
            draft.notesChanges.forEachIndexed { index, edit ->
                append(if (isChinese) "修改 " else "Edit ").append(index + 1).append('\n')
                append(if (isChinese) "前文片段：" else "Preceding context: ").append(edit.beforeContext).append('\n')
                append(if (isChinese) "删除内容：" else "Removed text: ").append(edit.removedPreview)
                if (edit.removalTruncated) append(if (isChinese) "（仅预览，完整内容在此前记录中）" else " (preview only; full content is in the earlier record)")
                append('\n')
                append(if (isChinese) "新增内容：" else "Inserted text: ").append(edit.insertedText).append('\n')
                append(if (isChinese) "后文片段：" else "Following context: ").append(edit.afterContext).append('\n')
            }
            append(
                if (isChinese) "\n\n这些是保留材料，需结合原始目标与完整讨论整理为任务，不能直接当作已确认方案。"
                else "\n\nThese are retained materials to refine into a task using the original goal and full discussion, not an approved plan.",
            )
        } else {
            append(if (isChinese) "Agent 候选任务（不代表用户确认）：\n" else "Agent candidate task (not user approval):\n")
            append(draft.originalText)
            if (draft.edited) {
                append(if (isChinese) "\n\n用户编辑后的文本：\n" else "\n\nUser-edited text:\n")
                append(draft.text)
            }
        }
    }

/** 按问答发生位置插入草稿与编辑，恢复时不丢弃草稿出现之前的用户要求。 */
internal fun discussionHistory(record: ClarificationRecord, isChinese: Boolean): String =
    buildString {
        val draftsByPosition = record.reviewedDrafts.groupBy { it.answerCount }
        for (position in 0..record.history.size) {
            if (position > 0) {
                val answer = record.history[position - 1]
                append(if (isChinese) "模型提问与用户回答：\n" else "Agent question and user response:\n")
                append(answerLines(listOf(answer), isChinese)).append("\n\n")
            }
            draftsByPosition[position].orEmpty().forEach {
                append(draftContent(it, isChinese)).append("\n\n")
            }
        }
    }.trimEnd()

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
        val context = q.context.takeIf(String::isNotBlank)?.let {
            (if (isChinese) "Agent 看法：" else "Agent view: ") + it + "\n"
        }.orEmpty()
        "- ${q.header}${paren(context + q.text)} → $answer"
    }

private fun otherWords(text: String, isChinese: Boolean) = (if (isChinese) "其他：" else "Other: ") + text

private fun formatReminder(isChinese: Boolean): String =
    if (isChinese) "回复仍只用约定的 JSON：{\"type\": \"questions\", ...} 或 {\"type\": \"done\", ...}。"
    else "Reply with the agreed JSON only: {\"type\": \"questions\", ...} or {\"type\": \"done\", ...}."

private const val OPERATING_RULES_ZH =
    "本次只讨论与调查。可用工具读取相关代码、文档和查询网络事实；不修改文件、不运行构建或测试、不启动服务或长时间运行的命令。未回答的题目采用该题事先披露的默认处理，可以据此推进并纳入任务，但不要说成用户的主动选择；它与后来的新情况冲突时再确认。探索兴趣不代表实施授权；用户最新明确修订优先，但只覆盖实际改变的要求。模型草稿不是用户决定，点击继续聊不是确认；草稿或编辑文本没提到的旧要求不自动取消，是否取消不明确时询问。遵守下方输出协议。"

private const val OPERATING_RULES_EN =
    "This session is for discussion and investigation. Use tools to read relevant code and documents or check facts online; do not modify files, run builds or tests, start services or run long-lived commands. An unanswered item takes the default disclosed for it, which you may proceed with and include in the task, but do not present it as the user's active choice; reconfirm it if later developments conflict with it. Exploratory interest is not implementation authorization; the user's latest explicit revision takes precedence only for requirements actually changed. Model drafts are not user decisions, and continuing is not approval. Requirements omitted from a draft or edit are not automatically cancelled; ask if cancellation is unclear. Follow the output protocol below."

private fun outputProtocol(isChinese: Boolean): String =
    buildString {
        append(if (isChinese) "## 输出协议\n\n" else "## Output Protocol\n\n")
        append(if (isChinese) BATCH_RULE_ZH else BATCH_RULE_EN).append("\n\n")
        append(if (isChinese) QUESTION_RULE_ZH else QUESTION_RULE_EN).append("\n\n")
        append(if (isChinese) RESULT_RULE_ZH else RESULT_RULE_EN).append("\n\n")
        append(REPLY_FORMAT).append("\n\n")
        append(if (isChinese) JSON_ONLY_ZH else JSON_ONLY_EN)
    }

private const val BATCH_RULE_ZH =
    "需要用户回应时返回 type=questions，包含 1～$MAX_QUESTIONS_PER_ROUND 题；开放、单选、多选同等可用，可混合，不要求比例或顺序。整批回答作为下一条消息返回。任务已清楚时可直接返回 type=done，不要求先提问；结果仍可编辑或继续聊。以下 JSON 是结构示例，不是固定问卷。"

private const val BATCH_RULE_EN =
    "When a response is needed, return type=questions with 1-$MAX_QUESTIONS_PER_ROUND items. Open, single-choice and multiple-choice forms are equally available and may be mixed, with no quota or order. Answers arrive as one message. Return type=done directly when the task is clear; prior questions are not required, and the result can still be edited or discussed. The JSON below illustrates structure, not a fixed questionnaire."

private const val RESULT_RULE_ZH =
    "type=done 的 requirement 是用户准备发给主 Agent 的新需求，以用户原始目标和已确认的修订为准。沿用用户原来的人称、语气和表达角度，把交互中确定的方向、取舍、功能点及必要约束自然融入，像用户自己把最初的要求重新完整说了一遍；不照抄口头重复，不机械统一成第一人称或固定模板。直接输出用户完善后的需求正文，不输出调查报告、结果评论、会议纪要或第三方指南，不加“用户希望”“经过讨论”“以下是完善后的需求”等转述或开场。只保留影响任务的必要项目事实；涉及目标、范围、关键体验或重要代价的未确认取舍不得写成决定（采用已披露默认处理的未回答题目不算未确认），探索目标不得擅自改成实施任务；这类取舍阻碍任务成立时继续询问，不返回 done。交付前核对是否遗漏用户的目标与明确要求。已查明事实、项目约定和不改变用户意图的合理细化可直接纳入，准确说明性质，不逐项要求用户批准。"

private const val RESULT_RULE_EN =
    "For type=done, requirement is the user's new request to the main agent, grounded in the user's original goal and confirmed revisions. Preserve the user's original grammatical person, tone and perspective, naturally incorporating the directions, choices, features and necessary constraints settled in the conversation, as though the user were restating the original request more completely. Do not copy verbal repetitions, force first-person phrasing or impose a template. Output the user's refined request itself, not an investigation report, commentary, meeting summary or third-party guide; omit narration or preambles such as 'The user wants', 'After discussion' or 'Here is the refined request'. Retain only project facts that affect the task; do not settle unconfirmed tradeoffs about goals, scope, key experience or significant costs (unanswered items taking their disclosed default are not unconfirmed), or turn exploratory goals into implementation tasks; when such a tradeoff prevents a coherent task, keep asking instead of returning done. Before handing off, check that none of the user's goals or explicit requirements were omitted. Verified facts, project conventions and reasonable details that preserve user intent can be included with their nature stated accurately, without asking for approval of each one."

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
