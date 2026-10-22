// ClarificationModels.kt
package com.github.izerui.imux.clarification

/** 方向的状态，见设计文档「方向状态」。 */
internal enum class DirectionStatus { OPEN, ACTIVE, DECIDED, DEFAULTED, DROPPED }

internal data class Direction(
    val id: String,
    val title: String,
    val why: String,
    val source: String,
    /** 用户不回答时的默认处理，事先写明；用户没见过的默认，他也就没法拒绝。 */
    val fallback: String,
    val status: DirectionStatus = DirectionStatus.OPEN,
    val conclusion: String? = null,
    val openPoints: List<String> = emptyList(),
)

internal data class ChoiceOption(
    val id: String,
    /** 短标签，选项旁显示。 */
    val label: String,
    /** 一行说明，显示在标签下；选中后原样记下。 */
    val description: String,
    val recommended: Boolean,
)

internal data class Question(
    val directionId: String,
    /** 短标签，显示在标签条上。 */
    val header: String,
    val text: String,
    val multiSelect: Boolean,
    val options: List<ChoiceOption>,
)

internal sealed interface Answer {
    /** 选中的选项（单选恰好一个）；多选题还可以同时写下"其他"的原话 [other]。 */
    data class Picked(val optionIds: List<String>, val other: String? = null) : Answer

    /** 只写了"其他"的原话。 */
    data class OwnWords(val text: String) : Answer

    /** 没有回答：按方向事先写明的默认处理。 */
    data object Skipped : Answer
}

/** 一次问答；[recorded] 是记下的方向结论：选中选项的说明（及原话）、用户原话或不回答时的默认处理。 */
internal data class AnswerRecord(
    val question: Question,
    val answer: Answer,
    val recorded: String,
)

/** 插件持有的台账，每轮完整交给 Agent。 */
internal data class Ledger(
    val requirement: String,
    val background: String?,
    /** 模型首轮的复述，只作为之后各轮的背景，不在界面上显示。 */
    val understanding: String? = null,
    val directions: List<Direction> = emptyList(),
    val history: List<AnswerRecord> = emptyList(),
    val notes: String = "",
    /**
     * 上一批里用户回答了（非不回答）、还等模型交代的方向，按回答顺序；
     * 下一轮返回必须在 answered 里逐个交代它们，交代完（合并成功）清空。
     */
    val awaitingReport: List<String> = emptyList(),
    val nextDirectionNumber: Int = 1,
) {
    fun direction(id: String): Direction? = directions.firstOrNull { it.id == id }

    fun replace(direction: Direction): Ledger =
        copy(directions = directions.map { if (it.id == direction.id) direction else it })
}

internal const val MAX_BACKGROUND_CHARS = 8_000
internal const val MAX_NOTES_CHARS = 6_000
