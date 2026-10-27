package com.github.izerui.imux.clarification

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Agent 的回复不符合协议；[errors] 会在同一段对话里发回给它，所以用英文写给模型看。 */
internal class ProtocolException(val errors: List<String>) : RuntimeException(errors.joinToString("; "))

/** 每轮问题数的上限，与 Claude Code 的提问相同；数量由模型判断，见设计文档「照 Claude Code 的提问过程」。 */
internal const val MAX_QUESTIONS_PER_ROUND = 4

/**
 * 标签条上的短标签：提示词要求不超过 [HEADER_CHARS] 个字，超过 [MAX_HEADER_CHARS] 才算错（发回重答也要多等一轮）；
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

/** Agent 一轮的回复：一批问题，或调整后的需求。 */
internal sealed interface AgentReply {
    /** 1～[MAX_QUESTIONS_PER_ROUND] 个问题；用户整批提交后才发下一条消息。 */
    data class Ask(val questions: List<Question>) : AgentReply

    data class Done(val requirement: String) : AgentReply
}

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

private fun JsonObject.arr(name: String): JsonArray = get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()

private fun JsonElement.asObjOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject

/**
 * 解析一轮的回复：`{"type":"questions","questions":[…]}` 或 `{"type":"done","requirement":"…"}`。
 * 题目 ID 由插件按顺序编为 q1～q4，Agent 不需要给。
 */
internal fun parseReply(raw: String): AgentReply {
    val root = jsonObjectIn(raw)
    val errors = mutableListOf<String>()
    val reply: AgentReply? =
        when (root.str("type")) {
            "done" -> root.str("requirement")?.takeIf(String::isNotEmpty)?.let(AgentReply::Done)
                ?: run { errors += "requirement must not be empty"; null }
            "questions" -> {
                val items = root.arr("questions")
                if (items.size() !in 1..MAX_QUESTIONS_PER_ROUND) errors += "questions must have 1-$MAX_QUESTIONS_PER_ROUND items"
                AgentReply.Ask(
                    items.mapIndexedNotNull { i, element ->
                        element.asObjOrNull()?.let { parseQuestion(it, i, errors) }
                            ?: run { errors += "questions[$i] must be an object"; null }
                    },
                )
            }
            else -> { errors += "type must be questions or done"; null }
        }
    if (errors.isNotEmpty() || reply == null) throw ProtocolException(errors)
    return reply
}

private fun parseQuestion(o: JsonObject, index: Int, errors: MutableList<String>): Question {
    val path = "questions[$index]"
    val header = o.str("header").orEmpty()
    val text = o.str("question").orEmpty()
    val fallback = o.str("fallback").orEmpty()
    for (field in listOf("kind", "context")) {
        o.get(field)?.let {
            if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) errors += "$path.$field must be a string"
        }
    }
    val kind = when (o.str("kind")) {
        null, "choice" -> QuestionKind.CHOICE
        "open" -> QuestionKind.OPEN
        else -> {
            errors += "$path.kind must be choice or open"
            QuestionKind.CHOICE
        }
    }
    val context = o.str("context").orEmpty()
    if (header.isEmpty()) errors += "$path.header must not be empty"
    if (codePoints(header) > MAX_HEADER_CHARS) errors += "$path.header must be at most $HEADER_CHARS characters"
    if (text.isEmpty()) errors += "$path.question must not be empty"
    if (fallback.isEmpty()) errors += "$path.fallback must not be empty"
    if (o.has("options") && !o.get("options").isJsonArray) errors += "$path.options must be an array"
    val optionItems = o.arr("options")
    val options =
        optionItems.mapIndexedNotNull { i, item ->
            val opt = item.asObjOrNull()
            if (opt == null) {
                errors += "$path.options[$i] must be an object"
                return@mapIndexedNotNull null
            }
            ChoiceOption(
                id = opt.str("id").orEmpty(),
                label = opt.str("label").orEmpty(),
                description = opt.str("description").orEmpty(),
                recommended = opt.get("recommended")?.takeIf { it.isJsonPrimitive }?.asBoolean == true,
            )
        }
    val multiSelect = o.get("multiSelect")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
    if (kind == QuestionKind.OPEN) {
        if (optionItems.size() != 0) errors += "$path: open questions must not have options"
        if (multiSelect) errors += "$path: open questions must not enable multiSelect"
    } else if (options.size !in 2..4) {
        errors += "$path.options must have 2-4 items"
    }
    if (options.any { it.id.isEmpty() || it.label.isEmpty() }) errors += "$path: every option needs id and label"
    if (options.any { it.description.isEmpty() }) errors += "$path: every option needs a non-empty description"
    if (options.map { it.id }.toSet().size != options.size) errors += "$path: option ids must be unique"
    if (options.any { isOtherLabel(it.label) }) errors += "$path: do not include an \"Other\" option; the plugin adds it"
    return Question("q${index + 1}", header, text, multiSelect, options, fallback, kind, context)
}

/** 模型自己加的"其他"选项：插件会追加带输入框的"其他"，模型再加一个就重复了。 */
internal fun isOtherLabel(label: String): Boolean {
    val l = label.trim().lowercase().trimEnd(':', '：', '.', '。', '…')
    return l in OTHER_LABELS || OTHER_PREFIXES.any { l.startsWith(it) }
}

private val OTHER_LABELS = setOf("other", "others", "something else", "其他", "其它", "其他想法", "其他方案", "其他选项", "自定义")
private val OTHER_PREFIXES = listOf("other (", "other:", "其他（", "其他(", "其他：", "其它（", "其它(")
