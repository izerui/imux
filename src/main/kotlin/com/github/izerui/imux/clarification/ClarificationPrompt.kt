// ClarificationPrompt.kt
package com.github.izerui.imux.clarification

import com.google.gson.GsonBuilder

internal enum class Phase { FIRST, ROUND, CORRECTED, RESULT }

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
    "next.questions 给 1～$MAX_QUESTIONS_PER_ROUND 个问题，数量由你判断：几个问题此刻都已能问、且彼此的回答不影响接下来要查什么，就一次给出；某个回答会决定接下来查什么，就只给这一题。同一批里每题针对不同的方向，且都处于 OPEN。插件逐题展示，整批答完才调用下一轮；用户选\"其他想法\"或纠正理解时这一批提前结束，没问到的方向回到 OPEN。constraints.mustAsk 不为 null 时，第一题必须针对它。"

private const val BATCH_RULE_EN =
    "`next.questions` holds 1-$MAX_QUESTIONS_PER_ROUND questions; you decide how many. Give several at once when they can all be asked now and their answers do not change what you need to investigate next; give only one when its answer decides what to investigate next. Each question in a batch targets a different direction, and every one of them must be OPEN. The plugin shows them one at a time and calls you again only when the whole batch is answered; the batch ends early when the user gives their own words or corrects your understanding, and the directions not yet asked go back to OPEN. When `constraints.mustAsk` is not null, the first question must target it."

private const val ANSWERED_RULE_ZH =
    "用 answered 逐个交代 constraints.reportAnswered 里的每个方向（这一批里用户回答过、非跳过的方向）：已定为 DECIDED，仍待确认为 OPEN 并附已有结论 conclusion 与剩余疑点 openPoints；不要交代不在其中的方向。"

private const val ANSWERED_RULE_EN =
    "Use `answered` to report every direction in `constraints.reportAnswered` (the directions the user answered, not skipped, in this batch): DECIDED when settled, or OPEN with the partial `conclusion` and the remaining `openPoints`; do not report any direction outside it."

private const val VOIDED_ZH = "history 中 voided 为 true 的回答已被用户撤回（方向被重新打开），不得视为已确认。"

private const val VOIDED_EN = "History entries with `voided: true` were withdrawn by the user (the direction was reopened) and must not be treated as confirmed."

private fun phaseInstruction(phase: Phase, isChinese: Boolean): String =
    if (isChinese) {
        when (phase) {
            Phase.FIRST -> "这是首轮。先调查项目，给出 understanding（你的理解、代码现状与这次会碰到哪里），在 directions.add 里列出所有需要用户确认的方向，并给出第一批问题；没有需要确认的方向时直接返回 done。$BATCH_RULE_ZH"
            Phase.ROUND -> "用户已回答上一批问题（见 history 末尾的记录）。带着全部已确认内容继续调查：$ANSWERED_RULE_ZH 重新审视方向列表，给出下一批问题；没有需要用户拍板的方向了就返回 done。$BATCH_RULE_ZH $VOIDED_ZH"
            Phase.CORRECTED -> "用户纠正了你的理解（见 corrections）。按新理解重新调查，重新给出 understanding，并重新审视方向列表；其余同逐轮要求：$ANSWERED_RULE_ZH $BATCH_RULE_ZH $VOIDED_ZH"
            Phase.RESULT -> "用户要求现在生成结果。把原需求与 confirmed 数组中的每一条融合成调整后的需求：精炼，只到方向层，不附带任何说明、依据、未决事项或标记；没定的方向不要写成已定。$VOIDED_ZH"
        }
    } else {
        when (phase) {
            Phase.FIRST -> "This is the first round. Investigate the project, give `understanding` (your understanding, how the code works today and what the change touches), list every direction that needs the user's confirmation in `directions.add`, and ask the first batch of questions; if nothing needs confirming, return `done`. $BATCH_RULE_EN"
            Phase.ROUND -> "The user answered the last batch of questions (see the last entries of `history`). Continue investigating with everything confirmed so far: $ANSWERED_RULE_EN Re-examine the list and ask the next batch; when no direction needs the user's call, return `done`. $BATCH_RULE_EN $VOIDED_EN"
            Phase.CORRECTED -> "The user corrected your understanding (see `corrections`). Investigate again with the new understanding, give `understanding` again and re-examine the list; otherwise as in a normal round: $ANSWERED_RULE_EN $BATCH_RULE_EN $VOIDED_EN"
            Phase.RESULT -> "The user asked for the result now. Merge the original requirement with every entry in the `confirmed` array into the adjusted requirement: concise, at the level of direction, with no explanations, evidence, open items or markers; never write an unsettled direction as settled. $VOIDED_EN"
        }
    }

private val ROUND_FORMAT =
    """
    {
      "understanding": "...（首轮与纠正理解后的那一轮必填，其余省略 / required in the first round and after a correction, omit otherwise）",
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
            "question": "...", "reason": "...", "basis": "...",
            "options": [{"id": "a", "label": "...", "recommended": true, "conclusion": "..."}]
          }
        ]
      }
    }
    next 也可以是 / next may also be: {"type": "done", "requirement": "..."}
    tempId 以 new- 开头且本次内不重复；questions 1～$MAX_QUESTIONS_PER_ROUND 个，各针对不同的方向；每题 options 2～4 个，每个都有 conclusion；answered 恰好交代 reportAnswered 里的每个方向；notes 不超过 $MAX_NOTES_CHARS 字符。
    tempId starts with new- and is unique; 1-$MAX_QUESTIONS_PER_ROUND questions, each on a different direction; 2-4 options per question, each with a conclusion; answered reports exactly the directions in reportAnswered; notes at most $MAX_NOTES_CHARS characters.
    """.trimIndent()

private const val RESULT_FORMAT = "{\"requirement\": \"...\"}"

private fun ledgerJson(ledger: Ledger): String {
    val state =
        linkedMapOf(
            "requirement" to ledger.requirement,
            "background" to ledger.background,
            "understanding" to ledger.understanding,
            "corrections" to ledger.corrections,
            "directions" to ledger.directions.map {
                linkedMapOf(
                    "id" to it.id, "title" to it.title, "why" to it.why, "source" to it.source,
                    "fallback" to it.fallback, "status" to it.status.name, "userOwned" to it.userOwned,
                    "conclusion" to it.conclusion, "openPoints" to it.openPoints,
                )
            },
            "history" to ledger.history.map {
                linkedMapOf(
                    "directionId" to it.question.directionId,
                    "question" to it.question.text,
                    // 答过的题不再带选项：结论已记在 recorded，提示词随轮数增长得慢一些
                    "answer" to when (val a = it.answer) {
                        is Answer.Picked -> "picked:${a.optionId}"
                        is Answer.OwnWords -> "own-words"
                        Answer.Skipped -> "skipped"
                    },
                    "recorded" to it.recorded,
                    "voided" to it.voided,
                )
            },
            "notes" to ledger.notes,
            "confirmed" to ledger.confirmedConclusions(),
            "constraints" to linkedMapOf(
                "mustAsk" to ledger.pinned,
                "frozen" to ledger.directions.filter { it.status == DirectionStatus.DEFAULTED }.map { it.id },
                "reportAnswered" to ledger.awaitingReport,
            ),
        )
    return GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create().toJson(state)
}
