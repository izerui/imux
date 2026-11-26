package com.github.izerui.imux.code

import com.github.izerui.imux.terminal.IDEA_MCP_PROJECT_HEADER
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 结束前自动检查：Claude 一轮要结束时，让 IDE 检查这一轮改过的所有文件，有错误就拦下、让它接着修。
 *
 * 为什么要做：引导词只能提醒「改完要检查」，智能体不一定照做（实测 Claude 改签名四次都没检查）。
 * 这里靠 Claude Code 的事件机制把检查变成自动发生的事：你发话时（`UserPromptSubmit`）imux 开始记这个项目里变过的文件，
 * Claude 要结束时（`Stop`）imux 检查这些文件，有错误就回 `decision: block` 把错误作为理由交给模型。
 *
 * 为什么在结束时查、不在每改一个文件后查：对比实验里 Claude 改代码多半用命令行脚本或 IDE 重命名一步改完多个文件，
 * 只订阅「改文件」工具一次都触发不了；按文件变化在结束时统一查，不管用什么方式改都查得到，也不会被改到一半的
 * 中间状态误报打断，一轮只查一次。拦过一次（`stop_hook_active`）就放行，不会死循环。
 *
 * 为什么经 IDEA MCP 而不自己检查：IDE 后台跑检查的入口 `DaemonCodeAnalyzerImpl.runMainPasses` 是内部接口，
 * IDEA MCP 的 `lint_files` 是公开工具、内部就是这样调的。代价是只有开着 IDEA MCP 的会话才有。
 *
 * Claude 用 HTTP hook，Codex 用命令 hook，Pi 用扩展结束事件；三者都把本轮事件交给同一个检查入口。
 */
const val CODE_EDIT_CHECK_PATH = "/imux/code-edit-check"

/** Claude 等「结束前」回应的上限：要先同步磁盘改动、再检查，留出余量。 */
internal const val EDIT_CHECK_HOOK_TIMEOUT_SECONDS = 40

/** 交给 IDEA MCP `lint_files` 的检查时间；超时就当没查出问题，放它结束。 */
internal const val EDIT_CHECK_LINT_TIMEOUT_MILLIS = 20_000

/** 一次最多检查几个文件：改动面再大也只查前这些，免得检查本身拖到超时。 */
internal const val EDIT_CHECK_MAX_FILES = 40

/** 一次最多交回几条错误：多了模型也看不完，先修前面的。 */
internal const val EDIT_CHECK_MAX_PROBLEMS = 15

/** 给 Claude 的 `--settings`：发话时和要结束时通知 [url]，令牌从 [tokenEnvironment] 带进请求头。 */
internal fun editCheckHookSettings(url: String, tokenEnvironment: String): String {
    fun rule(timeout: Int) = JsonArray().apply {
        add(JsonObject().apply {
            add("hooks", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "http")
                    addProperty("url", url)
                    add("headers", JsonObject().apply { addProperty("Authorization", "Bearer \$$tokenEnvironment") })
                    add("allowedEnvVars", JsonArray().apply { add(tokenEnvironment) })
                    addProperty("timeout", timeout)
                })
            })
        })
    }
    return JsonObject().apply {
        add("hooks", JsonObject().apply {
            add("UserPromptSubmit", rule(5))
            add("Stop", rule(EDIT_CHECK_HOOK_TIMEOUT_SECONDS))
        })
    }.toString()
}

internal object EditCheck {
    /** hook 通知的种类。 */
    sealed interface Event {
        /** 你发了话：这一轮开始，从现在起记变过的文件。 */
        data object TurnStart : Event

        /** Claude 要结束这一轮；[alreadyBlocked] 表示这一轮已经被拦过一次。 */
        data class TurnEnd(val alreadyBlocked: Boolean) : Event

        data object Other : Event
    }

    fun event(body: JsonObject?): Event = when (body?.get("hook_event_name")?.takeIf { it.isJsonPrimitive }?.asString) {
        "UserPromptSubmit" -> Event.TurnStart
        "Stop" -> Event.TurnEnd(body.get("stop_hook_active")?.takeIf { it.isJsonPrimitive }?.asBoolean == true)
        else -> Event.Other
    }

    /**
     * 把 `lint_files` 的结果整理成交给模型的一段话；只看错误级别（警告不拦它），没有错误返回 null。
     * [fileCount] 是这一轮检查了几个文件。
     */
    fun report(lint: JsonObject, fileCount: Int, chinese: Boolean): String? {
        val problems = lint.getAsJsonArray("items")?.flatMap { item ->
            val o = item.asJsonObject
            val file = o.get("filePath")?.asString.orEmpty()
            o.getAsJsonArray("problems")?.map { file to it.asJsonObject } ?: emptyList()
        }.orEmpty().filter { (_, p) -> p.get("severity")?.asString.equals("ERROR", ignoreCase = true) }
        if (problems.isEmpty()) return null
        val lines = problems.take(EDIT_CHECK_MAX_PROBLEMS).joinToString("\n") { (file, p) ->
            val line = p.get("line")?.asInt
            val description = p.get("description")?.asString.orEmpty()
            val code = p.get("lineText")?.asString?.trim().orEmpty().take(120)
            if (chinese) "- `$file` 第 $line 行：$description" + (if (code.isNotEmpty()) "（`$code`）" else "")
            else "- `$file` line $line: $description" + (if (code.isNotEmpty()) " (`$code`)" else "")
        }
        val more = problems.size - EDIT_CHECK_MAX_PROBLEMS
        return if (chinese) {
            "IDE 检查了这一轮改过的 $fileCount 个文件，发现 ${problems.size} 处错误：\n$lines" +
                (if (more > 0) "\n……还有 $more 处没列出。" else "") +
                "\n修好再结束。如果某处错误在你改之前就有、或是有意留着的，说明一下即可，不用为它改动无关的代码。"
        } else {
            "The IDE checked the $fileCount file(s) changed in this turn and found ${problems.size} error(s):\n$lines" +
                (if (more > 0) "\n...and $more more not listed." else "") +
                "\nFix them before finishing. If an error was there before your change, or is intended, say so instead of changing unrelated code."
        }
    }

    /** 「要结束」的回应：有错误时拦下、把报告作为理由交给模型，否则回 `{}` 放行。 */
    fun stopResponse(report: String?): String =
        if (report == null) "{}"
        else JsonObject().apply {
            addProperty("decision", "block")
            addProperty("reason", report)
        }.toString()
}

/**
 * 用 MCP 协议调 IDEA MCP 的 `lint_files`：初始化、确认初始化、调用。任何一步出错（没开、连不上、超时、格式不对）
 * 都返回 null，由调用方当作「没查出问题」。[post] 可注入，测试不用起服务。
 */
internal class IdeaMcpLintClient(
    private val url: String,
    private val projectPath: String,
    private val post: (url: String, headers: Map<String, String>, body: String) -> Pair<Map<String, String>, String>? = ::httpPost,
) {
    fun lint(relativeFiles: List<String>, timeoutMillis: Int): JsonObject? {
        val base = mapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json, text/event-stream",
            IDEA_MCP_PROJECT_HEADER to projectPath,
        )
        val (headers, _) = post(url, base, rpc(1, "initialize", JsonObject().apply {
            addProperty("protocolVersion", "2025-03-26")
            add("capabilities", JsonObject())
            add("clientInfo", JsonObject().apply { addProperty("name", "imux-edit-check"); addProperty("version", "1") })
        })) ?: return null
        val session = headers.entries.firstOrNull { it.key.equals("Mcp-Session-Id", ignoreCase = true) }?.value
        val withSession = if (session != null) base + ("Mcp-Session-Id" to session) else base
        post(url, withSession, JsonObject().apply { addProperty("jsonrpc", "2.0"); addProperty("method", "notifications/initialized") }.toString())
        val (_, text) = post(url, withSession, rpc(2, "tools/call", JsonObject().apply {
            addProperty("name", "lint_files")
            add("arguments", JsonObject().apply {
                add("files", JsonArray().apply { relativeFiles.forEach(::add) })
                addProperty("min_severity", "error")
                addProperty("timeout", timeoutMillis)
            })
        })) ?: return null
        return structuredResult(text)
    }

    private fun rpc(id: Int, method: String, params: JsonObject): String =
        JsonObject().apply {
            addProperty("jsonrpc", "2.0"); addProperty("id", id); addProperty("method", method); add("params", params)
        }.toString()

    companion object {
        /** 回应可能是普通 JSON，也可能是事件流（`data:` 行）；取出结果里的结构化内容，工具报错时返回 null。 */
        fun structuredResult(text: String): JsonObject? {
            val payload = if (text.trimStart().startsWith("{")) text
            else text.lineSequence().map { it.trim() }.filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }
                .lastOrNull { it.startsWith("{") && it.contains("\"result\"") } ?: return null
            val result = runCatching { JsonParser.parseString(payload).asJsonObject.getAsJsonObject("result") }.getOrNull() ?: return null
            if (result.get("isError")?.asBoolean == true) return null
            return result.getAsJsonObject("structuredContent")
        }

        private val client: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build() }

        private fun httpPost(url: String, headers: Map<String, String>, body: String): Pair<Map<String, String>, String>? =
            try {
                val builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(EDIT_CHECK_LINT_TIMEOUT_MILLIS + 5_000L))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                headers.forEach { (k, v) -> builder.header(k, v) }
                val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) null
                else response.headers().map().mapValues { it.value.firstOrNull().orEmpty() } to response.body()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt(); null
            } catch (_: Exception) {
                null
            }
    }
}
