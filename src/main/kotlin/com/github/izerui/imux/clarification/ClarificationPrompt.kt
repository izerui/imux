// ClarificationPrompt.kt
package com.github.izerui.imux.clarification

import com.google.gson.GsonBuilder

internal enum class Phase { FIRST, ROUND, RESULT }

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

internal fun clarificationPrompt(
    phase: Phase,
    ledger: Ledger,
    isChinese: Boolean,
    guidance: String,
    errors: List<String> = emptyList(),
): String =
    buildString {
        append(guidance.trim()).append("\n\n")
        append(if (isChinese) "## 本阶段\n\n" else "## This Phase\n\n")
        append(phaseInstruction(phase, isChinese)).append("\n\n")
        append(if (isChinese) "## 当前状态\n\n" else "## Current State\n\n")
        append("```json\n").append(ledgerJson(ledger)).append("\n```\n\n")
        if (errors.isNotEmpty()) {
            append(if (isChinese) "## 上一次的输出有问题，请修正后重新输出\n\n" else "## Your previous output was rejected; fix these and answer again\n\n")
            errors.forEach { append("- ").append(it).append('\n') }
            append('\n')
        }
        append(if (isChinese) "## 输出格式\n\n" else "## Output Format\n\n")
        append(if (phase == Phase.RESULT) RESULT_FORMAT else ROUND_FORMAT).append("\n\n")
        append(if (isChinese) JSON_ONLY_ZH else JSON_ONLY_EN)
    }

private const val BATCH_RULE_ZH =
    "next.questions 给 1～$MAX_QUESTIONS_PER_ROUND 个问题，数量由你判断：几个问题彼此独立、此刻都已能问，就一次给出；某个回答会决定接下来查什么、问什么，就只给这一题，等回答后再调查。同一批里每题针对不同的方向，且都处于 OPEN。用户在插件里逐题作答、可以来回修改，整批提交后插件才调用下一轮；没回答的题按该方向的 fallback 处理。"

private const val BATCH_RULE_EN =
    "`next.questions` holds 1-$MAX_QUESTIONS_PER_ROUND questions; you decide how many. Give several at once when they are independent and can all be asked now; give only one when its answer decides what to investigate or ask next, and investigate again after it is answered. Each question in a batch targets a different direction, and every one of them must be OPEN. The user answers them in the plugin, can go back and change answers, and submits the whole batch; only then are you called again. A question left unanswered is settled by its direction's `fallback`."

private const val QUESTION_RULE_ZH =
    "每题：header 是不超过 $HEADER_CHARS 个字的短标签（显示在标签条上）；question 是完整的问句；2～4 个真正不同的选项，推荐的放第一个并设 recommended 为 true；label 是不超过 $LABEL_CHARS 个字的短标签；description 用一句话写明选了它方向就定为什么——它就是用户选中后记下的内容；几个选项可以同时成立时设 multiSelect 为 true。不要给\"其他\"选项，插件会自动追加一个可自由输入的\"其他\"。"

private const val QUESTION_RULE_EN =
    "For each question: `header` is a short tag of at most $HEADER_CHARS characters (shown on the tab strip); `question` is the full question; 2-4 genuinely different options with the recommended one first and `recommended: true`; `label` is a short tag of at most $LABEL_CHARS characters; `description` is one sentence saying what the direction becomes if it is chosen - it is exactly what gets recorded when the user picks it; set `multiSelect: true` when several options can hold together. Do not include an \"Other\" option; the plugin appends one with free text."

private const val ANSWERED_RULE_ZH =
    "用 answered 逐个交代 constraints.reportAnswered 里的每个方向（上一批里用户回答过的方向）：已定为 DECIDED，仍待确认为 OPEN 并附已有结论 conclusion 与剩余疑点 openPoints；不要交代不在其中的方向。"

private const val ANSWERED_RULE_EN =
    "Use `answered` to report every direction in `constraints.reportAnswered` (the directions the user answered in the last batch): DECIDED when settled, or OPEN with the partial `conclusion` and the remaining `openPoints`; do not report any direction outside it."

private fun phaseInstruction(phase: Phase, isChinese: Boolean): String =
    if (isChinese) {
        when (phase) {
            Phase.FIRST -> "这是首轮。先调查项目，给出 understanding（你的理解、代码现状与这次会碰到哪里，只作为之后各轮的背景），在 directions.add 里列出所有需要用户确认的方向，并给出第一批问题；没有需要确认的方向时直接返回 done。$BATCH_RULE_ZH $QUESTION_RULE_ZH"
            Phase.ROUND -> "继续澄清。constraints.reportAnswered 不为空时，用户刚提交了其中这些方向的回答（见 history 末尾的记录）；为空时没有新回答（例如用户看过结果后要求继续）。带着全部已确认内容继续调查：$ANSWERED_RULE_ZH 重新审视方向列表，给出下一批问题；没有需要用户拍板的方向了就返回 done。$BATCH_RULE_ZH $QUESTION_RULE_ZH"
            Phase.RESULT -> "用户要求现在生成结果。把原需求与 confirmed 数组中的每一条融合成调整后的需求：精炼，只到方向层，不附带任何说明、依据、未决事项或标记；没定的方向不要写成已定。"
        }
    } else {
        when (phase) {
            Phase.FIRST -> "This is the first round. Investigate the project, give `understanding` (your understanding, how the code works today and what the change touches; it is only context for later rounds), list every direction that needs the user's confirmation in `directions.add`, and ask the first batch of questions; if nothing needs confirming, return `done`. $BATCH_RULE_EN $QUESTION_RULE_EN"
            Phase.ROUND -> "Clarification continues. When `constraints.reportAnswered` is not empty, the user has just submitted answers for those directions (see the last entries of `history`); when it is empty there are no new answers (for example the user looked at the result and asked to continue). Continue investigating with everything confirmed so far: $ANSWERED_RULE_EN Re-examine the list and ask the next batch; when no direction needs the user's call, return `done`. $BATCH_RULE_EN $QUESTION_RULE_EN"
            Phase.RESULT -> "The user asked for the result now. Merge the original requirement with every entry in the `confirmed` array into the adjusted requirement: concise, at the level of direction, with no explanations, evidence, open items or markers; never write an unsettled direction as settled."
        }
    }

private val ROUND_FORMAT =
    """
    {
      "understanding": "...（只在首轮给出 / first round only）",
      "directions": {
        "add": [{"tempId": "new-1", "title": "...", "why": "...", "source": "...", "fallback": "..."}],
        "drop": [{"id": "d3", "reason": "..."}]
      },
      "answered": [{"id": "d1", "status": "DECIDED | OPEN", "conclusion": "...", "openPoints": ["..."]}],
      "notes": "...",
      "next": {
        "type": "questions",
        "questions": [
          {
            "directionId": "new-1",
            "header": "...",
            "question": "...",
            "multiSelect": false,
            "options": [{"id": "a", "label": "...", "description": "...", "recommended": true}]
          }
        ]
      }
    }
    next 也可以是 / next may also be: {"type": "done", "requirement": "..."}
    tempId 以 new- 开头且本次内不重复；questions 1～$MAX_QUESTIONS_PER_ROUND 个，各针对不同的方向；header 不超过 $HEADER_CHARS 个字；每题 options 2～4 个，每个都有 description，不含"其他"；answered 恰好交代 reportAnswered 里的每个方向；notes 不超过 $MAX_NOTES_CHARS 字符。
    tempId starts with new- and is unique; 1-$MAX_QUESTIONS_PER_ROUND questions, each on a different direction; header at most $HEADER_CHARS characters; 2-4 options per question, each with a description, no "Other"; answered reports exactly the directions in reportAnswered; notes at most $MAX_NOTES_CHARS characters.
    """.trimIndent()

private const val RESULT_FORMAT = "{\"requirement\": \"...\"}"

private fun ledgerJson(ledger: Ledger): String {
    val state =
        linkedMapOf(
            "requirement" to ledger.requirement,
            "background" to ledger.background,
            "understanding" to ledger.understanding,
            "directions" to ledger.directions.map {
                linkedMapOf(
                    "id" to it.id, "title" to it.title, "why" to it.why, "source" to it.source,
                    "fallback" to it.fallback, "status" to it.status.name,
                    "conclusion" to it.conclusion, "openPoints" to it.openPoints,
                )
            },
            "history" to ledger.history.map {
                linkedMapOf(
                    "directionId" to it.question.directionId,
                    "question" to it.question.text,
                    // 答过的题不再带选项：结论已记在 recorded，提示词随轮数增长得慢一些
                    "answer" to when (val a = it.answer) {
                        is Answer.Picked -> "picked:" + a.optionIds.joinToString(",") + if (a.other != null) "+own-words" else ""
                        is Answer.OwnWords -> "own-words"
                        Answer.Skipped -> "unanswered"
                    },
                    "recorded" to it.recorded,
                )
            },
            "notes" to ledger.notes,
            "confirmed" to ledger.confirmedConclusions(),
            "constraints" to linkedMapOf(
                "frozen" to ledger.directions.filter { it.status == DirectionStatus.DEFAULTED }.map { it.id },
                "reportAnswered" to ledger.awaitingReport,
            ),
        )
    return GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create().toJson(state)
}
