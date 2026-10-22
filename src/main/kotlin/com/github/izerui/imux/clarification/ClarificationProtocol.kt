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

internal data class Previous(
    val id: String,
    val decided: Boolean,
    val conclusion: String,
    val openPoints: List<String>,
)

internal sealed interface Next {
    data class Ask(val question: Question) : Next

    data class Done(val requirement: String) : Next
}

internal data class RoundResponse(
    val understanding: String?,
    val added: List<NewDirection>,
    val dropped: List<String>,
    val previous: Previous?,
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

    val previous =
        root.obj("previous")?.let { p ->
            val status = p.str("status")
            if (status != "DECIDED" && status != "OPEN") errors += "previous.status must be DECIDED or OPEN"
            Previous(
                id = p.str("id").orEmpty(),
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
            "question" -> parseQuestion(nextObj, errors)
            else -> { errors += "next.type must be question or done"; null }
        }

    if (errors.isNotEmpty() || next == null) throw ProtocolException(errors.ifEmpty { listOf("next is missing") })
    return RoundResponse(root.str("understanding")?.takeIf(String::isNotEmpty), added, dropped, previous, root.str("notes").orEmpty(), next)
}

private fun parseQuestion(o: JsonObject, errors: MutableList<String>): Next.Ask? {
    val directionId = o.str("directionId").orEmpty()
    val text = o.str("question").orEmpty()
    if (directionId.isEmpty()) errors += "next.directionId must not be empty"
    if (text.isEmpty()) errors += "next.question must not be empty"
    val options =
        o.arr("options").mapNotNull { it.asObjOrNull() }.map { opt ->
            ChoiceOption(
                id = opt.str("id").orEmpty(),
                label = opt.str("label").orEmpty(),
                recommended = opt.get("recommended")?.takeIf { it.isJsonPrimitive }?.asBoolean == true,
                conclusion = opt.str("conclusion").orEmpty(),
            )
        }
    if (options.size !in 2..4) errors += "next.options must have 2-4 items"
    if (options.any { it.id.isEmpty() || it.label.isEmpty() }) errors += "every option needs id and label"
    if (options.any { it.conclusion.isEmpty() }) errors += "every option needs a non-empty conclusion"
    if (options.map { it.id }.toSet().size != options.size) errors += "option ids must be unique"
    return Next.Ask(Question(directionId, text, o.str("reason").orEmpty(), o.str("basis").orEmpty(), options))
}

internal fun parseRequirementResponse(raw: String): String =
    jsonObjectIn(raw).str("requirement")?.takeIf(String::isNotEmpty)
        ?: throw ProtocolException(listOf("requirement must not be empty"))
