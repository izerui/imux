// ClarificationModels.kt
package com.github.izerui.imux.clarification

/** 方向的状态，见设计文档「方向状态」。 */
internal enum class DirectionStatus { OPEN, ACTIVE, DECIDED, DEFAULTED, DROPPED }

internal data class Direction(
    val id: String,
    val title: String,
    val why: String,
    val source: String,
    /** 用户跳过时的默认处理，事先写明；用户没见过的默认，他也就没法拒绝。 */
    val fallback: String,
    val status: DirectionStatus = DirectionStatus.OPEN,
    /** 用户添加或重新打开的方向：Agent 不能去掉，只能由用户的回答来定。 */
    val userOwned: Boolean = false,
    val conclusion: String? = null,
    val openPoints: List<String> = emptyList(),
    /** 模型去掉该方向时给的理由；界面在去掉的方向旁原样显示。 */
    val dropReason: String? = null,
)

internal data class ChoiceOption(
    val id: String,
    val label: String,
    val recommended: Boolean,
    /** 选了它，方向就定为什么；界面原样显示，选中后原样记下。 */
    val conclusion: String,
)

internal data class Question(
    val directionId: String,
    val text: String,
    val reason: String,
    val basis: String,
    val options: List<ChoiceOption>,
)

internal sealed interface Answer {
    data class Picked(val optionId: String) : Answer

    data class OwnWords(val text: String) : Answer

    data object Skipped : Answer
}

/** 一次问答；[recorded] 是记下的方向结论：选项自带的结论、用户原话或跳过时的默认处理。 */
internal data class AnswerRecord(
    val question: Question,
    val answer: Answer,
    val recorded: String,
)

/** 插件持有的台账，每轮完整交给 Agent。 */
internal data class Ledger(
    val requirement: String,
    val background: String?,
    val understanding: String? = null,
    val corrections: List<String> = emptyList(),
    val directions: List<Direction> = emptyList(),
    val history: List<AnswerRecord> = emptyList(),
    val notes: String = "",
    /** 用户「先问这个」指定的方向；该方向的问题展示出来后清空。 */
    val pinned: String? = null,
    /** 用户刚回答（非跳过）的方向；下一轮返回必须用 previous 交代它。 */
    val lastAsked: String? = null,
    val nextDirectionNumber: Int = 1,
    val nextUserNumber: Int = 1,
) {
    fun direction(id: String): Direction? = directions.firstOrNull { it.id == id }

    fun replace(direction: Direction): Ledger =
        copy(directions = directions.map { if (it.id == direction.id) direction else it })
}

internal const val MAX_BACKGROUND_CHARS = 8_000
internal const val MAX_NOTES_CHARS = 6_000
