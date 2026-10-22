package com.github.izerui.imux.clarification

import com.github.izerui.imux.clarification.DirectionStatus.ACTIVE
import com.github.izerui.imux.clarification.DirectionStatus.DECIDED
import com.github.izerui.imux.clarification.DirectionStatus.DEFAULTED
import com.github.izerui.imux.clarification.DirectionStatus.DROPPED
import com.github.izerui.imux.clarification.DirectionStatus.OPEN

// ---- 用户的回答：只由用户提交，Agent 的返回永远不能改写它们 ----

/**
 * 记下这一题的回答所定下的结论：选中选项的说明（多选按选项顺序，以"；"连接，再接上"其他"的原话）、
 * 用户原话，或不回答时的默认处理。回答与题目对不上（选项不存在、单选选了多个、原话为空）时返回 null。
 */
internal fun recordedText(question: Question, direction: Direction, answer: Answer): String? =
    when (answer) {
        is Answer.Picked -> {
            val picked = question.options.filter { it.id in answer.optionIds }
            val other = answer.other?.trim()?.takeIf(String::isNotEmpty)
            val valid =
                picked.size == answer.optionIds.toSet().size && picked.isNotEmpty() &&
                    (question.multiSelect || (picked.size == 1 && other == null))
            if (valid) (picked.map { it.description } + listOfNotNull(other)).joinToString("；") else null
        }
        is Answer.OwnWords -> answer.text.trim().takeIf(String::isNotEmpty)
        Answer.Skipped -> direction.fallback
    }

/** 记下一题的回答。对不上题目的回答按不回答处理。 */
internal fun Ledger.recordAnswer(question: Question, answer: Answer): Ledger {
    val direction = direction(question.directionId) ?: return this
    val text = recordedText(question, direction, answer)
    val effective = if (text == null) Answer.Skipped else answer
    val recorded = text ?: direction.fallback
    val id = question.directionId
    return if (effective == Answer.Skipped) {
        replace(direction.copy(status = DEFAULTED, conclusion = recorded.ifBlank { null }))
            .copy(history = history + AnswerRecord(question, Answer.Skipped, recorded), awaitingReport = awaitingReport - id)
    } else {
        replace(direction.copy(status = ACTIVE))
            .copy(history = history + AnswerRecord(question, effective, recorded), awaitingReport = awaitingReport.filterNot { it == id } + id)
    }
}

/**
 * 这一批里没有回答的题，其方向从 ACTIVE 回到 OPEN：在提问页直接生成结果（或兜底结果）时调用。
 * 否则这些方向再也不能被模型提问（只能问 OPEN 的方向）。
 * 用户已回答、等模型用 answered 交代的方向（[Ledger.awaitingReport]）不动。
 */
internal fun Ledger.releaseUnanswered(): Ledger =
    copy(directions = directions.map { if (it.status == ACTIVE && it.id !in awaitingReport) it.copy(status = OPEN) else it })

// ---- 合并 Agent 的提议 ----

internal sealed interface MergedNext {
    /** 方向 ID 已换成稳定 ID。 */
    data class Ask(val questions: List<Question>) : MergedNext

    data class Done(val requirement: String) : MergedNext
}

/** [errors] 非空时 [ledger] 就是合并前的台账，一处都不改。 */
internal data class MergeOutcome(
    val ledger: Ledger,
    val next: MergedNext?,
    val errors: List<String>,
)

internal fun Ledger.merge(response: RoundResponse): MergeOutcome {
    val errors = mutableListOf<String>()

    // 1. 临时 ID → 稳定 ID
    val idMap = linkedMapOf<String, String>()
    var number = nextDirectionNumber
    for (added in response.added) {
        when {
            !added.tempId.startsWith("new-") -> errors += "tempId ${added.tempId} must start with new-"
            added.tempId in idMap -> errors += "tempId ${added.tempId} is duplicate"
            else -> idMap[added.tempId] = "d${number++}"
        }
    }
    var merged =
        copy(
            directions = directions + response.added.mapNotNull { a -> idMap[a.tempId]?.let { Direction(it, a.title, a.why, a.source, a.fallback) } },
            nextDirectionNumber = number,
        )
    fun resolve(ref: String): String? = idMap[ref] ?: ref.takeIf { merged.direction(it) != null }

    // 2. 去掉：已定的、按默认的、上一批用户回答过待交代的方向一律不动，否则用户的回答会被模型抹掉
    for (id in response.dropped) {
        val d = merged.direction(id)
        when {
            d == null -> errors += "dropped direction $id does not exist"
            d.status == DEFAULTED || d.status == DECIDED || d.id in awaitingReport -> Unit
            else -> merged = merged.replace(d.copy(status = DROPPED))
        }
    }

    // 3. 逐个交代上一批里用户回答过的方向；已定时结论取用户的回答，不取模型的转述
    val reported = mutableSetOf<String>()
    for (report in response.answered) {
        when {
            !reported.add(report.id) -> errors += "answered reports direction ${report.id} more than once"
            report.id !in awaitingReport -> errors += "answered reports direction ${report.id}, which the user did not answer in the last batch"
            else -> {
                val d = merged.direction(report.id)
                if (d != null && d.status == ACTIVE) {
                    merged =
                        merged.replace(
                            if (report.decided) {
                                d.copy(status = DECIDED, conclusion = merged.recordedFor(report.id), openPoints = emptyList())
                            } else {
                                d.copy(status = OPEN, conclusion = report.conclusion.ifBlank { null }, openPoints = report.openPoints)
                            },
                        )
                }
            }
        }
    }
    for (asked in awaitingReport) {
        if (asked !in reported) errors += "answered must report direction $asked"
    }

    if (response.notes.length > MAX_NOTES_CHARS) errors += "notes must not exceed $MAX_NOTES_CHARS characters"
    merged = merged.copy(notes = response.notes, understanding = response.understanding ?: merged.understanding)

    // 4. 下一步：每题的方向存在、处于 OPEN、同一批里不重复
    val next: MergedNext? =
        when (val n = response.next) {
            is Next.Done -> MergedNext.Done(n.requirement)
            is Next.Ask -> {
                val ids = n.questions.map { resolve(it.directionId) }
                if (n.questions.size !in 1..MAX_QUESTIONS_PER_ROUND) errors += "next.questions must have 1-$MAX_QUESTIONS_PER_ROUND items"
                val seen = mutableSetOf<String>()
                n.questions.forEachIndexed { i, q ->
                    val id = ids[i]
                    val d = id?.let(merged::direction)
                    when {
                        id == null || d == null -> errors += "question ${i + 1} targets unknown direction ${q.directionId}"
                        !seen.add(id) -> errors += "questions ${i + 1} and an earlier one both target direction $id; each question in a batch must target a different direction"
                        d.status != OPEN -> errors += "question ${i + 1} targets direction $id which is ${d.status}, not OPEN"
                    }
                }
                if (errors.isNotEmpty()) {
                    null
                } else {
                    val stable = ids.map { it!! }
                    for (id in stable) merged = merged.replace(merged.direction(id)!!.copy(status = ACTIVE))
                    MergedNext.Ask(n.questions.mapIndexed { i, q -> q.copy(directionId = stable[i]) })
                }
            }
        }

    if (errors.isNotEmpty()) return MergeOutcome(this, null, errors)
    return MergeOutcome(merged.copy(awaitingReport = emptyList()), next, emptyList())
}

private fun Ledger.recordedFor(id: String): String =
    history.filter { it.question.directionId == id && it.answer != Answer.Skipped }.joinToString("；") { it.recorded }

/**
 * 「已确认」的结论：所有 DECIDED/DEFAULTED 方向的非空 conclusion（按方向顺序），
 * 加上用户已回答（非不回答）但方向尚未 DECIDED/DEFAULTED/DROPPED 的 history 记录（按 history 顺序），去重。
 *
 * 第二部分覆盖了 [Ledger.awaitingReport] 里的每个方向：提交了、还没等到模型交代的回答，
 * 在这时生成结果一律算已确认；没回答的题没有 history 记录，不会混进来。
 */
internal fun Ledger.confirmedConclusions(): List<String> {
    val seen = mutableSetOf<String>()
    val result = mutableListOf<String>()
    for (d in directions) {
        if (d.status == DECIDED || d.status == DEFAULTED) {
            val c = d.conclusion?.takeIf(String::isNotBlank) ?: continue
            if (seen.add(c)) result += c
        }
    }
    for (record in history) {
        if (record.answer == Answer.Skipped) continue
        val d = direction(record.question.directionId) ?: continue
        if (d.status == DECIDED || d.status == DEFAULTED || d.status == DROPPED) continue
        val c = record.recorded.takeIf(String::isNotBlank) ?: continue
        if (seen.add(c)) result += c
    }
    return result
}

/** 失败页「生成结果」不调用 Agent：原需求后面按顺序列出已确认的结论。 */
internal fun composeFallback(ledger: Ledger): String =
    buildString {
        append(ledger.requirement.trim())
        val confirmed = ledger.confirmedConclusions()
        if (confirmed.isNotEmpty()) {
            append("\n\n")
            confirmed.forEach { append("- ").append(it).append('\n') }
        }
    }.trimEnd()
