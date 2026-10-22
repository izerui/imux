package com.github.izerui.imux.clarification

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Agent 的返回不符合协议；[errors] 会附进下一次提示词，所以用英文写给模型看。 */
internal class ProtocolException(val errors: List<String>) : RuntimeException(errors.joinToString("; "))

internal data class NewDirection(
    val tempId: String,
    val title: String,
    val why: String,
    val source: String,
    val fallback: String,
)

/** 模型对上一批里用户回答过的某个方向的交代：已定，或仍待确认（附已有结论与剩余疑点）。 */
internal data class AnsweredReport(
    val id: String,
    val decided: Boolean,
    val conclusion: String,
    val openPoints: List<String>,
)

/** 每轮问题数的上限，与 Claude Code 的提问相同；数量由模型判断，见设计文档「照 Claude Code 的提问过程」。 */
internal const val MAX_QUESTIONS_PER_ROUND = 4

/**
 * 标签条上的短标签：提示词要求不超过 [HEADER_CHARS] 个字，超过 [MAX_HEADER_CHARS] 才算错（重调一次代价是一整轮调查）；
 * 界面也按 [MAX_HEADER_CHARS] 截断，所以通过校验的标签总是完整显示。都按码点计数。
 */
internal const val HEADER_CHARS = 12
internal const val MAX_HEADER_CHARS = 16

internal fun codePoints(text: String): Int = text.codePointCount(0, text.length)

/** 按码点截断到 [max] 个字，超出用"…"结尾。 */
internal fun truncateCodePoints(text: String, max: Int): String =
    if (codePoints(text) <= max) text else text.substring(0, text.offsetByCodePoints(0, max - 1)) + "…"

/** 选项短标签的建议长度；界面上超出的部分省略显示。 */
internal const val LABEL_CHARS = 20

internal sealed interface Next {
    /** 1～[MAX_QUESTIONS_PER_ROUND] 个问题；用户整批提交后才调用下一轮。 */
    data class Ask(val questions: List<Question>) : Next

    data class Done(val requirement: String) : Next
}

internal data class RoundResponse(
    val understanding: String?,
    val added: List<NewDirection>,
    /** 模型提议去掉的方向 ID。 */
    val dropped: List<String>,
    val answered: List<AnsweredReport>,
    val notes: String,
    val next: Next,
)

/** 取第一个 `{` 到最后一个 `}`：模型常在 JSON 外包一层说明文字或代码围栏。 */
private fun jsonObjectIn(raw: String): JsonObject {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start < 0 || end <= start) throw ProtocolException(listOf("no JSON object found in the output"))
    return runCatching { JsonParser.parseString(raw.substring(start, end + 1)).asJsonObject }
        .getOrElse { throw ProtocolException(listOf("invalid JSON: ${it.message}")) }
}

private fun JsonObject.str(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive }?.asString?.trim()

private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.arr(name: String): JsonArray = get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()

private fun JsonElement.asObjOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject

internal fun parseRoundResponse(raw: String): RoundResponse {
    val root = jsonObjectIn(raw)
    val errors = mutableListOf<String>()

    val directions = root.obj("directions")
    val added =
        (directions?.arr("add") ?: JsonArray()).mapNotNull { element ->
            val o = element.asObjOrNull()
            val fields = listOf("tempId", "title", "why", "source", "fallback").associateWith { o?.str(it).orEmpty() }
            val missing = fields.filterValues { it.isEmpty() }.keys
            if (missing.isNotEmpty()) {
                errors += "directions.add item is missing ${missing.joinToString()}"
                null
            } else {
                NewDirection(fields.getValue("tempId"), fields.getValue("title"), fields.getValue("why"), fields.getValue("source"), fields.getValue("fallback"))
            }
        }
    val dropped = (directions?.arr("drop") ?: JsonArray()).mapNotNull { it.asObjOrNull()?.str("id")?.takeIf(String::isNotEmpty) }

    val answered =
        root.arr("answered").mapIndexedNotNull { i, element ->
            val p = element.asObjOrNull() ?: run { errors += "answered[$i] must be an object"; return@mapIndexedNotNull null }
            val id = p.str("id").orEmpty()
            val status = p.str("status")
            if (id.isEmpty()) errors += "answered[$i].id must not be empty"
            if (status != "DECIDED" && status != "OPEN") errors += "answered[$i].status must be DECIDED or OPEN"
            AnsweredReport(
                id = id,
                decided = status == "DECIDED",
                conclusion = p.str("conclusion").orEmpty(),
                openPoints = p.arr("openPoints").mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString?.trim()?.takeIf(String::isNotEmpty) },
            )
        }

    val nextObj = root.obj("next")
    val next: Next? =
        when (nextObj?.str("type")) {
            "done" -> nextObj.str("requirement")?.takeIf(String::isNotEmpty)?.let(Next::Done)
                ?: run { errors += "next.requirement must not be empty"; null }
            "questions" -> {
                val items = nextObj.arr("questions")
                if (items.size() !in 1..MAX_QUESTIONS_PER_ROUND) errors += "next.questions must have 1-$MAX_QUESTIONS_PER_ROUND items"
                val questions =
                    items.mapIndexedNotNull { i, element ->
                        element.asObjOrNull()?.let { parseQuestion(it, "next.questions[$i]", errors) }
                            ?: run { errors += "next.questions[$i] must be an object"; null }
                    }
                Next.Ask(questions)
            }
            else -> { errors += "next.type must be questions or done"; null }
        }

    if (errors.isNotEmpty() || next == null) throw ProtocolException(errors.ifEmpty { listOf("next is missing") })
    return RoundResponse(root.str("understanding")?.takeIf(String::isNotEmpty), added, dropped, answered, root.str("notes").orEmpty(), next)
}

private fun parseQuestion(o: JsonObject, path: String, errors: MutableList<String>): Question {
    val directionId = o.str("directionId").orEmpty()
    val header = o.str("header").orEmpty()
    val text = o.str("question").orEmpty()
    if (directionId.isEmpty()) errors += "$path.directionId must not be empty"
    if (header.isEmpty()) errors += "$path.header must not be empty"
    if (codePoints(header) > MAX_HEADER_CHARS) errors += "$path.header must be at most $HEADER_CHARS characters"
    if (text.isEmpty()) errors += "$path.question must not be empty"
    val options =
        o.arr("options").mapNotNull { it.asObjOrNull() }.map { opt ->
            ChoiceOption(
                id = opt.str("id").orEmpty(),
                label = opt.str("label").orEmpty(),
                description = opt.str("description").orEmpty(),
                recommended = opt.get("recommended")?.takeIf { it.isJsonPrimitive }?.asBoolean == true,
            )
        }
    if (options.size !in 2..4) errors += "$path.options must have 2-4 items"
    if (options.any { it.id.isEmpty() || it.label.isEmpty() }) errors += "$path: every option needs id and label"
    if (options.any { it.description.isEmpty() }) errors += "$path: every option needs a non-empty description"
    if (options.map { it.id }.toSet().size != options.size) errors += "$path: option ids must be unique"
    if (options.any { isOtherLabel(it.label) }) errors += "$path: do not include an \"Other\" option; the plugin adds it"
    val multiSelect = o.get("multiSelect")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
    return Question(directionId, header, text, multiSelect, options)
}

/** 模型自己加的"其他"选项：插件会追加带输入框的"其他"，模型再加一个就重复了。 */
internal fun isOtherLabel(label: String): Boolean {
    val l = label.trim().lowercase().trimEnd(':', '：', '.', '。', '…')
    return l in OTHER_LABELS || OTHER_PREFIXES.any { l.startsWith(it) }
}

private val OTHER_LABELS = setOf("other", "others", "something else", "其他", "其它", "其他想法", "其他方案", "其他选项", "自定义")
private val OTHER_PREFIXES = listOf("other (", "other:", "其他（", "其他(", "其他：", "其它（", "其它(")

internal fun parseRequirementResponse(raw: String): String =
    jsonObjectIn(raw).str("requirement")?.takeIf(String::isNotEmpty)
        ?: throw ProtocolException(listOf("requirement must not be empty"))
