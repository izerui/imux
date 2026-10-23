package com.github.izerui.imux.clarification

internal data class ChoiceOption(
    val id: String,
    /** 短标签，选项旁显示。 */
    val label: String,
    /** 一行说明，显示在标签下；选中后原样记下。 */
    val description: String,
    val recommended: Boolean,
)

internal enum class QuestionKind { CHOICE, OPEN }

internal data class Question(
    /** 插件按这一批里的顺序编号（q1～q4），只用来对应草稿与回答。 */
    val id: String,
    /** 短标签，显示在标签条上。 */
    val header: String,
    val text: String,
    val multiSelect: Boolean,
    val options: List<ChoiceOption>,
    /** 不回答时按什么处理，事先写明；用户没见过的默认，他也就没法拒绝。 */
    val fallback: String = "",
    val kind: QuestionKind = QuestionKind.CHOICE,
    /** 对话者的看法、发现或必要背景，与邀请用户回应的问题分开呈现。 */
    val context: String = "",
)

internal sealed interface Answer {
    /** 选中的选项（单选恰好一个）；多选题还可以同时写下"其他"的原话 [other]。 */
    data class Picked(val optionIds: List<String>, val other: String? = null) : Answer

    /** 开放题的回答，或选择题中"其他"的原话。 */
    data class OwnWords(val text: String) : Answer

    /** 没有回答：按这一题事先写明的默认处理。 */
    data object Skipped : Answer
}

/** 一次问答；[recorded] 是记下的内容：选中选项的说明（及原话）、用户原话或不回答时的默认处理。 */
internal data class AnswerRecord(
    val question: Question,
    val answer: Answer,
    val recorded: String,
)

/**
 * 插件这边记下的澄清：原需求与用户提交过的全部回答。
 * 对话的上下文由 Agent 自己记着；这里只用于界面上的已确认列表、失败时的兜底结果，
 * 以及对话中断（取消、失败）后新开对话时的开场。
 */
internal data class ClarificationRecord(
    val requirement: String,
    val history: List<AnswerRecord> = emptyList(),
) {
    /** 已确认 = 用户提交过的回答（含不回答时的默认处理），按提交顺序、去重。 */
    fun confirmed(): List<String> = history.map { it.recorded.trim() }.filter(String::isNotEmpty).distinct()
}

/**
 * 记下这一题的回答：选中选项的说明（多选按选项顺序，以"；"连接，再接上"其他"的原话）、
 * 用户原话，或不回答时的默认处理。回答与题目对不上（选项不存在、单选选了多个、原话为空）时按不回答处理。
 */
internal fun answerRecord(question: Question, answer: Answer): AnswerRecord {
    val text =
        when (answer) {
            is Answer.Picked -> {
                val picked = question.options.filter { it.id in answer.optionIds }
                val other = answer.other?.trim()?.takeIf(String::isNotEmpty)
                val valid =
                    question.kind == QuestionKind.CHOICE && picked.size == answer.optionIds.toSet().size && picked.isNotEmpty() &&
                        (question.multiSelect || (picked.size == 1 && other == null))
                if (valid) (picked.map { it.description } + listOfNotNull(other)).joinToString("；") else null
            }
            is Answer.OwnWords -> answer.text.trim().takeIf(String::isNotEmpty)
            Answer.Skipped -> null
        }
    return if (text == null) AnswerRecord(question, Answer.Skipped, question.fallback) else AnswerRecord(question, answer, text)
}

/** 失败页「生成结果」不调用 Agent：原需求后面按顺序列出已确认的回答。 */
internal fun composeFallback(record: ClarificationRecord): String =
    buildString {
        append(record.requirement.trim())
        // 开放回答可能只有“上周那次”或“不认同”：离开问题与对方看法就无法独立理解。
        val confirmed = record.history.filter { it.recorded.isNotBlank() }.map { r ->
            if (r.question.kind == QuestionKind.OPEN || r.question.context.isNotBlank()) {
                listOf(r.question.context, r.question.text, r.recorded).filter(String::isNotBlank).joinToString("\n")
            } else r.recorded.trim()
        }.distinct()
        if (confirmed.isNotEmpty()) {
            append("\n\n")
            confirmed.forEach { append("- ").append(it.replace("\n", "\n  ")).append('\n') }
        }
    }.trimEnd()
