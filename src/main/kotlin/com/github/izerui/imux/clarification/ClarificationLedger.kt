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
    val id = question.directionId
    return replace(updated).copy(
        history = history + AnswerRecord(question, answer, recorded),
        corrections = corrections + listOfNotNull(correction?.trim()?.takeIf(String::isNotEmpty)),
        awaitingReport = if (skipped) awaitingReport - id else awaitingReport.filterNot { it == id } + id,
    )
}

internal fun Ledger.skip(id: String): Ledger {
    val d = direction(id) ?: return this
    if (d.status == DEFAULTED) return this
    return replace(d.copy(status = DEFAULTED, conclusion = d.fallback.ifBlank { null }))
        .copy(pinned = pinned.takeUnless { it == id }, awaitingReport = awaitingReport - id)
}

internal fun Ledger.pin(id: String): Ledger =
    if (direction(id)?.status == OPEN) copy(pinned = id) else this

internal fun Ledger.reopen(id: String): Ledger {
    val d = direction(id) ?: return this
    if (d.status == OPEN || d.status == ACTIVE) return this
    return replace(d.copy(status = OPEN, userOwned = true, conclusion = null, openPoints = emptyList()))
        .copy(history = history.map { if (it.question.directionId == id) it.copy(voided = true) else it })
}

/**
 * 这一批里还没回答的题，其方向从 ACTIVE 回到 OPEN：离开提问页却没有回答（直接生成结果、兜底结果、
 * 继续澄清），或这一批提前结束（其他想法、纠正理解）时调用。否则这些方向既不能再被模型提问
 * （只能问 OPEN 的方向），也不能被「先问这个」或「重新打开」。
 * 用户已回答、等模型用 answered 交代的方向（[Ledger.awaitingReport]）不动。
 */
internal fun Ledger.releaseUnanswered(): Ledger =
    copy(directions = directions.map { if (it.status == ACTIVE && it.id !in awaitingReport) it.copy(status = OPEN) else it })

internal fun Ledger.addUserDirection(title: String): Pair<Ledger, String> {
    val id = "user-$nextUserNumber"
    val direction = Direction(id, title.trim(), why = "", source = "user", fallback = "", userOwned = true)
    return copy(directions = directions + direction, nextUserNumber = nextUserNumber + 1) to id
}

// ---- 合并 Agent 的提议 ----

internal sealed interface MergedNext {
    /** 方向 ID 已换成稳定 ID；第一题就是插件先展示的那一题。 */
    data class Ask(val questions: List<Question>) : MergedNext

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

    // 2. 去掉：用户的方向（跳过的、添加的、重新打开的）、已定的方向、这一批用户回答过待交代的方向、
    //    用户「先问这个」的方向一律不动，否则用户的回答或指定会被模型抹掉
    for (drop in response.dropped) {
        val d = merged.direction(drop.id)
        when {
            d == null -> errors += "dropped direction ${drop.id} does not exist"
            d.userOwned || d.status == DEFAULTED || d.status == DECIDED || d.id in awaitingReport || d.id == pinned -> Unit
            else -> merged = merged.replace(d.copy(status = DROPPED, dropReason = drop.reason.ifBlank { null }))
        }
    }

    // 3. 逐个交代这一批里用户回答过的方向；已定时结论取用户的回答，不取模型的转述。
    //    交代了不在待交代之列的方向是模型的错——除非那是调查期间用户动过的方向（例如刚被跳过，
    //    已从待交代里移除），那时模型看到的还是旧台账，它的交代直接忽略
    val reported = mutableSetOf<String>()
    for (report in response.answered) {
        when {
            !reported.add(report.id) -> errors += "answered reports direction ${report.id} more than once"
            report.id !in awaitingReport -> if (report.id !in changedByUser) errors += "answered reports direction ${report.id}, which the user did not answer in this batch"
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
                // 每题：方向存在、处于 OPEN、同一批里不重复；有「先问这个」时第一题必须针对它。
                // 只有每个毛病都由调查期间的用户操作造成，才算用户造成
                val pin = merged.pinned
                val ids = n.questions.map { resolve(it.directionId) }
                var allCausedByUser = true
                fun problem(text: String, causedByUser: Boolean) {
                    targetProblems += text
                    if (!causedByUser) allCausedByUser = false
                }
                if (n.questions.size !in 1..MAX_QUESTIONS_PER_ROUND) problem("next.questions must have 1-$MAX_QUESTIONS_PER_ROUND items", causedByUser = false)
                val seen = mutableSetOf<String>()
                n.questions.forEachIndexed { i, q ->
                    val id = ids[i]
                    val d = id?.let(merged::direction)
                    when {
                        id == null || d == null -> problem("question ${i + 1} targets unknown direction ${q.directionId}", causedByUser = false)
                        !seen.add(id) -> problem("questions ${i + 1} and an earlier one both target direction $id; each question in a batch must target a different direction", causedByUser = false)
                        d.status != OPEN -> problem("question ${i + 1} targets direction $id which is ${d.status}, not OPEN", causedByUser = id in changedByUser)
                    }
                }
                if (pin != null && ids.firstOrNull() != pin) {
                    problem("the user asked to discuss $pin next; the first question must target it", causedByUser = pin in changedByUser)
                }
                if (targetProblems.isNotEmpty()) {
                    touchedByUser = allCausedByUser
                    null
                } else {
                    val stable = ids.map { it!! }
                    for (id in stable) merged = merged.replace(merged.direction(id)!!.copy(status = ACTIVE))
                    merged = merged.copy(pinned = merged.pinned.takeUnless { it == stable.first() })
                    MergedNext.Ask(n.questions.mapIndexed { i, q -> q.copy(directionId = stable[i]) })
                }
            }
        }

    if (errors.isNotEmpty() || targetProblems.isNotEmpty()) {
        val causedByUser = errors.isEmpty() && touchedByUser
        return MergeOutcome(this, null, errors + targetProblems, causedByUser)
    }
    return MergeOutcome(merged.copy(awaitingReport = emptyList()), next, emptyList(), invalidatedByUser = false)
}

private fun Ledger.recordedFor(id: String): String =
    history.filter { it.question.directionId == id && it.answer != Answer.Skipped && !it.voided }.joinToString("；") { it.recorded }

/**
 * 「已确认」的结论：所有 DECIDED/DEFAULTED 方向的非空 conclusion（按方向顺序），
 * 加上用户已回答（非跳过、未作废）但方向尚未 DECIDED/DEFAULTED/DROPPED 的 history 记录
 * （按 history 顺序），去重。
 *
 * 第二部分覆盖了 [Ledger.awaitingReport] 里的每个方向：这一批答过、还没等到模型交代的方向
 * 保持 ACTIVE，且不能被模型去掉，所以它们的回答在批中途生成结果时一律算已确认；
 * 这一批里还没问到的方向没有 history 记录，不会混进来。
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
    // 2. Non-skipped, non-voided history records whose direction exists and is NOT DECIDED/DEFAULTED/DROPPED
    for (record in history) {
        if (record.answer == Answer.Skipped || record.voided) continue
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
