package com.github.izerui.imux.bridge.transcript

import com.github.izerui.imux.bridge.core.AskedOption
import com.github.izerui.imux.bridge.core.AskedQuestion
import com.github.izerui.imux.bridge.json.flag
import com.github.izerui.imux.bridge.json.objects
import com.github.izerui.imux.bridge.json.string
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Claude 工具调用参数的解析：hook 入口从 tool_input 得出「要批准的内容」与题目；
 * hook 与会话记录都用 [fields] 把参数规范化，两边才能按参数对上同一次调用。
 */
object ClaudeToolInputs {
    /** 要批准的内容：计划取全文，命令连同说明，其余取文件路径、网址、查询或参数原文；计划按 8000 截短，其它 1500。 */
    fun detail(name: String, input: JsonObject): String {
        val detail =
            when {
                name == EXIT_PLAN_MODE && input.string("plan") != null -> input.string("plan")!!
                input.string("command") != null -> listOfNotNull(input.string("description"), input.string("command")).joinToString("\n")
                else ->
                    listOf("file_path", "notebook_path", "url", "query").firstNotNullOfOrNull(input::string)
                        ?: input.toString()
            }
        val max = if (name == EXIT_PLAN_MODE) MAX_PLAN_DETAIL else MAX_TOOL_DETAIL
        return if (detail.length > max) detail.take(max - 1) + "…" else detail
    }

    /** 参数的顶层字段 → 键名递归排序后的 JSON 字符串。 */
    fun fields(input: JsonObject): Map<String, String> = input.entrySet().associate { (key, value) -> key to sorted(value).toString() }

    private fun sorted(element: JsonElement): JsonElement = when {
        element.isJsonObject -> JsonObject().also { copy ->
            element.asJsonObject.entrySet().sortedBy { it.key }.forEach { (key, value) -> copy.add(key, sorted(value)) }
        }
        element.isJsonArray -> JsonArray().also { copy -> element.asJsonArray.forEach { copy.add(sorted(it)) } }
        else -> element
    }

    /** 题目与选项；任何一题缺题目或选项返回 null。 */
    fun questions(input: JsonObject): List<AskedQuestion>? {
        val questions =
            input.objects("questions").map { question ->
                val text = question.string("question")?.takeIf(String::isNotBlank) ?: return null
                val options =
                    question.objects("options").map { option ->
                        AskedOption(option.string("label")?.takeIf(String::isNotBlank) ?: return null, option.string("description"))
                    }
                if (options.isEmpty()) return null
                AskedQuestion(text, question.flag("multiSelect"), options)
            }
        return questions.takeIf { it.isNotEmpty() }
    }

    internal const val MAX_TOOL_DETAIL = 1500
    internal const val MAX_PLAN_DETAIL = 8000
    private const val EXIT_PLAN_MODE = "ExitPlanMode"
}
