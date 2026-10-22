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

private fun phaseInstruction(phase: Phase, isChinese: Boolean): String =
    if (isChinese) {
        when (phase) {
            Phase.FIRST -> "这是首轮。先调查项目，给出 understanding（你的理解、代码现状与这次会碰到哪里），在 directions.add 里列出所有需要用户确认的方向，并给出第一个问题；没有需要确认的方向时直接返回 done。"
            Phase.ROUND -> "用户已回答上一轮的问题（见 history 最后一条）。带着全部已确认内容继续调查：用 previous 交代 reportPrevious 指向的方向是否已定，重新审视方向列表，给出下一个问题；没有需要用户拍板的方向了就返回 done。history 中 voided 为 true 的回答已被用户撤回（方向被重新打开），不得视为已确认。"
            Phase.CORRECTED -> "用户纠正了你的理解（见 corrections）。按新理解重新调查，重新给出 understanding，并重新审视方向列表；其余同逐轮要求。history 中 voided 为 true 的回答已被用户撤回（方向被重新打开），不得视为已确认。"
            Phase.RESULT -> "用户要求现在生成结果。把原需求与 confirmed 数组中的每一条融合成调整后的需求：精炼，只到方向层，不附带任何说明、依据、未决事项或标记；没定的方向不要写成已定。history 中 voided 为 true 的回答已被用户撤回（方向被重新打开），不得视为已确认。"
        }
    } else {
        when (phase) {
            Phase.FIRST -> "This is the first round. Investigate the project, give `understanding` (your understanding, how the code works today and what the change touches), list every direction that needs the user's confirmation in `directions.add`, and ask the first question; if nothing needs confirming, return `done`."
            Phase.ROUND -> "The user answered the last question (see the last entry of `history`). Continue investigating with everything confirmed so far: use `previous` to report whether the direction in `reportPrevious` is settled, re-examine the list, and ask the next question; when no direction needs the user's call, return `done`. History entries with `voided: true` were withdrawn by the user (the direction was reopened) and must not be treated as confirmed."
            Phase.CORRECTED -> "The user corrected your understanding (see `corrections`). Investigate again with the new understanding, give `understanding` again and re-examine the list; otherwise as in a normal round. History entries with `voided: true` were withdrawn by the user (the direction was reopened) and must not be treated as confirmed."
            Phase.RESULT -> "The user asked for the result now. Merge the original requirement with every entry in the `confirmed` array into the adjusted requirement: concise, at the level of direction, with no explanations, evidence, open items or markers; never write an unsettled direction as settled. History entries with `voided: true` were withdrawn by the user (the direction was reopened) and must not be treated as confirmed."
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
      "previous": {"id": "d1", "status": "DECIDED | OPEN", "conclusion": "...", "openPoints": ["..."]},
      "notes": "...",
      "next": {
        "type": "question",
        "directionId": "new-1",
        "question": "...", "reason": "...", "basis": "...",
        "options": [{"id": "a", "label": "...", "recommended": true, "conclusion": "..."}]
      }
    }
    next 也可以是 / next may also be: {"type": "done", "requirement": "..."}
    tempId 以 new- 开头且本次内不重复；options 2～4 个，每个都有 conclusion；reportPrevious 不为 null 时 previous 必填；notes 不超过 $MAX_NOTES_CHARS 字符。
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
                "reportPrevious" to ledger.lastAsked,
            ),
        )
    return GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create().toJson(state)
}
