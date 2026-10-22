package com.github.izerui.imux.clarification

import com.github.izerui.imux.clarification.DirectionStatus.ACTIVE
import com.github.izerui.imux.clarification.DirectionStatus.DECIDED
import com.github.izerui.imux.clarification.DirectionStatus.DEFAULTED
import com.github.izerui.imux.clarification.DirectionStatus.DROPPED
import com.github.izerui.imux.clarification.DirectionStatus.OPEN

// ---- 用户操作：只由用户触发，Agent 的返回永远不能撤销它们 ----

internal fun Ledger.recordAnswer(question: Question, answer: Answer, correction: String?): Ledger {
    val direction = direction(question.directionId) ?: return this
    val recorded =
        when (answer) {
            is Answer.Picked -> question.options.firstOrNull { it.id == answer.optionId }?.conclusion ?: return this
            is Answer.OwnWords -> answer.text.trim()
            Answer.Skipped -> direction.fallback
        }
    val skipped = answer == Answer.Skipped
    val updated = if (skipped) direction.copy(status = DEFAULTED, conclusion = recorded) else direction.copy(status = ACTIVE)
    return replace(updated).copy(
        history = history + AnswerRecord(question, answer, recorded),
        corrections = corrections + listOfNotNull(correction?.trim()?.takeIf(String::isNotEmpty)),
        lastAsked = if (skipped) null else question.directionId,
    )
}

internal fun Ledger.skip(id: String): Ledger {
    val d = direction(id) ?: return this
    if (d.status == DEFAULTED) return this
    return replace(d.copy(status = DEFAULTED, conclusion = d.fallback.ifBlank { null }))
        .copy(pinned = pinned.takeUnless { it == id }, lastAsked = lastAsked.takeUnless { it == id })
}

internal fun Ledger.pin(id: String): Ledger =
    if (direction(id)?.status == OPEN) copy(pinned = id) else this

internal fun Ledger.reopen(id: String): Ledger {
    val d = direction(id) ?: return this
    if (d.status == OPEN || d.status == ACTIVE) return this
    return replace(d.copy(status = OPEN, userOwned = true, conclusion = null, openPoints = emptyList()))
}

/**
 * 离开提问页却没有回答（直接生成结果、兜底结果、继续澄清）时调用：被问的方向从 ACTIVE 回到 OPEN，
 * 否则它既不能再被模型提问（只能问 OPEN 的方向），也不能被「先问这个」或「重新打开」。
 * 用户刚回答、等模型用 previous 交代的方向（[Ledger.lastAsked]）不动。
 */
internal fun Ledger.releaseUnanswered(): Ledger =
    copy(directions = directions.map { if (it.status == ACTIVE && it.id != lastAsked) it.copy(status = OPEN) else it })

internal fun Ledger.addUserDirection(title: String): Pair<Ledger, String> {
    val id = "user-$nextUserNumber"
    val direction = Direction(id, title.trim(), why = "", source = "user", fallback = "", userOwned = true)
    return copy(directions = directions + direction, nextUserNumber = nextUserNumber + 1) to id
}

// ---- 合并 Agent 的提议 ----

internal sealed interface MergedNext {
    data class Ask(val question: Question) : MergedNext

    data class Done(val requirement: String) : MergedNext
}

/**
 * [errors] 非空时 [ledger] 就是合并前的台账，一处都不改。
 * [invalidatedByUser]：问题之所以无效，是因为调查期间用户改动了它针对的方向或「先问这个」——
 * 那不是模型的错，调用方应直接用新台账重新调用，而不是把错误附给模型。
 */
internal data class MergeOutcome(
    val ledger: Ledger,
    val next: MergedNext?,
    val errors: List<String>,
    val invalidatedByUser: Boolean,
)

internal fun Ledger.merge(response: RoundResponse, changedByUser: Set<String>): MergeOutcome {
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

    // 2. 去掉：用户的方向（跳过的、添加的、重新打开的）、已定的方向、用户刚回答的方向、
    //    用户「先问这个」的方向一律不动，否则用户的回答或指定会被模型抹掉
    for (drop in response.dropped) {
        val d = merged.direction(drop.id)
        when {
            d == null -> errors += "dropped direction ${drop.id} does not exist"
            d.userOwned || d.status == DEFAULTED || d.status == DECIDED || d.id == lastAsked || d.id == pinned -> Unit
            else -> merged = merged.replace(d.copy(status = DROPPED, dropReason = drop.reason.ifBlank { null }))
        }
    }

    // 3. 交代上一轮的方向；已定时结论取用户的回答，不取模型的转述
    lastAsked?.let { asked ->
        val p = response.previous
        val d = merged.direction(asked)
        if (p == null || p.id != asked) {
            errors += "previous must report direction $asked"
        } else if (d != null && d.status == ACTIVE) {
            merged =
                merged.replace(
                    if (p.decided) {
                        d.copy(status = DECIDED, conclusion = merged.recordedFor(asked), openPoints = emptyList())
                    } else {
                        d.copy(status = OPEN, conclusion = p.conclusion.ifBlank { null }, openPoints = p.openPoints)
                    },
                )
        }
    }

    if (response.notes.length > MAX_NOTES_CHARS) errors += "notes must not exceed $MAX_NOTES_CHARS characters"
    merged = merged.copy(notes = response.notes, understanding = response.understanding ?: merged.understanding)

    // 4. 下一步。问题本身的毛病（targetProblems）与其余结构错误分开记：
    //    只有问题之所以无效是因为调查期间用户动过它针对的方向或「先问这个」，才算用户造成
    val targetProblems = mutableListOf<String>()
    var touchedByUser = false
    val next: MergedNext? =
        when (val n = response.next) {
            is Next.Done -> {
                // 用户指定了「先问这个」，或调查期间动过方向（跳过、添加、先问这个、重新打开）时，
                // done 没有顾及这些操作：前者是模型的错，后者由用户造成，用新台账重新调用即可
                val pin = merged.pinned
                if (pin != null || changedByUser.isNotEmpty()) {
                    if (pin != null) targetProblems += "the user asked to discuss $pin next; ask about it instead of returning done"
                    if (changedByUser.isNotEmpty()) targetProblems += "the user changed directions ${changedByUser.sorted().joinToString()} during this round"
                    touchedByUser = changedByUser.isNotEmpty()
                    null
                } else {
                    MergedNext.Done(n.requirement)
                }
            }
            is Next.Ask -> {
                val id = resolve(n.question.directionId)
                val d = id?.let(merged::direction)
                val problems = mutableListOf<String>()
                if (id == null || d == null) problems += "question targets unknown direction ${n.question.directionId}"
                else if (d.status != OPEN) problems += "question targets direction $id which is ${d.status}, not OPEN"
                val pin = merged.pinned
                if (pin != null && id != pin) problems += "the user asked to discuss $pin next"
                if (problems.isNotEmpty()) {
                    touchedByUser = (id != null && id in changedByUser) || (pin != null && pin in changedByUser)
                    targetProblems += problems
                    null
                } else {
                    merged = merged.replace(d!!.copy(status = ACTIVE)).copy(pinned = merged.pinned.takeUnless { it == id })
                    MergedNext.Ask(n.question.copy(directionId = id!!))
                }
            }
        }

    if (errors.isNotEmpty() || targetProblems.isNotEmpty()) {
        val causedByUser = errors.isEmpty() && touchedByUser
        return MergeOutcome(this, null, errors + targetProblems, causedByUser)
    }
    return MergeOutcome(merged.copy(lastAsked = null), next, emptyList(), invalidatedByUser = false)
}

private fun Ledger.recordedFor(id: String): String =
    history.filter { it.question.directionId == id && it.answer != Answer.Skipped }.joinToString("；") { it.recorded }

/**
 * 「已确认」的结论：所有 DECIDED/DEFAULTED 方向的非空 conclusion（按方向顺序），
 * 加上用户已回答（非跳过）但方向尚未 DECIDED/DEFAULTED/DROPPED 的 history 记录
 * （按 history 顺序），去重。
 */
internal fun Ledger.confirmedConclusions(): List<String> {
    val settledIds = directions.filter { it.status == DECIDED || it.status == DEFAULTED }.mapTo(mutableSetOf()) { it.id }
    val seen = mutableSetOf<String>()
    val result = mutableListOf<String>()
    // 1. DECIDED / DEFAULTED directions' conclusion, in direction order
    for (d in directions) {
        if (d.status == DECIDED || d.status == DEFAULTED) {
            val c = d.conclusion?.takeIf(String::isNotBlank) ?: continue
            if (seen.add(c)) result += c
        }
    }
    // 2. Non-skipped history records whose direction exists and is NOT DECIDED/DEFAULTED/DROPPED
    for (record in history) {
        if (record.answer == Answer.Skipped) continue
        val d = direction(record.question.directionId) ?: continue
        if (d.id in settledIds || d.status == DROPPED) continue
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
