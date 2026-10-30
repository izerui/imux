package com.github.izerui.imux.bridge.transcript

import com.github.izerui.imux.bridge.core.SessionEvent
import com.github.izerui.imux.bridge.json.flag
import com.github.izerui.imux.bridge.json.obj
import com.github.izerui.imux.bridge.json.objects
import com.github.izerui.imux.bridge.json.string
import com.github.izerui.imux.model.AgentType
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 把会话记录的一行解成桥接事件。全文保留，不截断、不压缩换行——
 * 这正是不能复用导航索引解析的原因（它为悬停卡片截断并压平了正文）。
 */
fun interface TranscriptDecoder {
    fun decode(line: String): List<SessionEvent>
}

object TranscriptDecoders {
    fun forAgent(agentType: AgentType): TranscriptDecoder =
        when (agentType) {
            AgentType.CLAUDE -> ClaudeTranscriptDecoder
            AgentType.CODEX -> CodexTranscriptDecoder
            AgentType.PI -> PiTranscriptDecoder
        }
}

object ClaudeTranscriptDecoder : TranscriptDecoder {
    override fun decode(line: String): List<SessionEvent> {
        val root = parseObject(line) ?: return emptyList()
        // 后台任务在主会话忙时结束：通知先排进队列，这一刻任务已经结束
        if (root.string("type") == "queue-operation") return listOfNotNull(root.string("content")?.let(::backgroundEnded))
        // 子代理、注入的元数据与压缩摘要都不是这条会话里人和助手说的话
        if (root.flag("isSidechain") || root.flag("isMeta") || root.flag("isCompactSummary")) return emptyList()
        val message = root.obj("message") ?: return emptyList()
        // Claude Code 恢复会话时给没回答的那一轮补的占位 “No response requested.”（model 为 <synthetic>）不是回复；
        // 同为合成消息的 API 报错（isApiErrorMessage）照常转发，手机上才知道这一轮失败了
        if (message.string("model") == "<synthetic>" && !root.flag("isApiErrorMessage")) return emptyList()
        val content = message.get("content") ?: return emptyList()
        return when (message.string("role")) {
            "user" -> backgroundStarted(root, content) + userEvents(content, humanOrigin(root))
            // 一条消息的各个块分行写入且都带 stop_reason；只在含正文的那一行标记结束，结论才在结束之前。
            // 除了还要调工具（tool_use、pause_turn），其余收尾都算本轮结束：end_turn 之外，
            // 输出超长截断、API 报错也会带着正文停下，漏标的话这一轮就没有结论发出
            "assistant" -> assistantEvents(content).let { events ->
                if (claudeTurnEnds(message.string("stop_reason")) && events.any { it is SessionEvent.AssistantText }) {
                    events + SessionEvent.TurnEnded
                } else {
                    events
                }
            }
            else -> emptyList()
        }
    }

    /**
     * 这条用户消息是不是从输入框提交的（人、副驾驶、手机）。Claude Code 在 `origin.kind` 里写明来源：
     * 输入是 `human`；后台任务通知、命令输出等是别的值或没有。不兼容 2.1.28 以前还没有这个字段的版本。
     */
    private fun humanOrigin(root: JsonObject): Boolean = root.obj("origin")?.string("kind") == "human"

    private fun userEvents(
        content: JsonElement,
        human: Boolean,
    ): List<SessionEvent> {
        if (content.isJsonPrimitive) return userText(content.asString, human)
        return blocks(content).flatMap { block ->
            when (block.string("type")) {
                "text" -> block.string("text")?.let { userText(it, human) }.orEmpty()
                else -> emptyList()
            }
        }
    }

    /**
     * 后台调用的结果：后台命令给出 `backgroundTaskId`，后台子代理给出 `agentId`（状态 `async_launched`），
     * 用 SendMessage 续跑已结束的子代理给出 `resumedAgentId`。
     */
    private fun backgroundStarted(
        root: JsonObject,
        content: JsonElement,
    ): List<SessionEvent> {
        val result = root.obj("toolUseResult") ?: return emptyList()
        val toolUseId = blocks(content).firstOrNull { it.string("type") == "tool_result" }?.string("tool_use_id")
        val taskId =
            result.string("backgroundTaskId")
                ?: result.string("agentId")?.takeIf { result.string("status") == "async_launched" }
                ?: return listOfNotNull(result.string("resumedAgentId")?.let { SessionEvent.BackgroundStarted(it, null) })
        return listOf(SessionEvent.BackgroundStarted(taskId, toolUseId, result.string("description")))
    }

    /**
     * 后台任务的结束通知。子代理停下时自己的后台工作还在跑，通知里写明在等它们，之后还会再通知一次，这次不算结束。
     */
    private fun backgroundEnded(text: String): SessionEvent? {
        if (!text.contains(TASK_NOTIFICATION)) return null
        if (text.contains(WAITING_ON_CHILDREN)) return null
        val taskId = TASK_ID.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return SessionEvent.BackgroundEnded(taskId)
    }

    private fun userText(
        text: String,
        human: Boolean,
    ): List<SessionEvent> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        // 用户按 Esc 中断：Claude 记一条“[Request interrupted by user…]”
        if (trimmed.startsWith(CLAUDE_INTERRUPTED_PREFIX)) return listOf(SessionEvent.TurnInterrupted)
        // 不是输入的（后台任务通知等）：不转发，但它们会开启新一轮；后台任务通知同时说明那个任务结束了
        if (!human) return listOfNotNull(backgroundEnded(trimmed), SessionEvent.TurnStarted)
        // 输入的斜杠命令记成 <command-name> 标记，转成原样的“/命令 参数”
        return listOf(SessionEvent.UserText(slashCommand(trimmed) ?: trimmed))
    }

    private fun slashCommand(text: String): String? {
        val name = COMMAND_NAME.find(text)?.groupValues?.get(1)?.trim() ?: return null
        val args = COMMAND_ARGS.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        return if (args.isEmpty()) name else "$name $args"
    }

    private fun assistantEvents(content: JsonElement): List<SessionEvent> {
        if (content.isJsonPrimitive) return listOfNotNull(assistantText(content.asString))
        return blocks(content).flatMap { block ->
            when (block.string("type")) {
                "text" -> listOfNotNull(block.string("text")?.let(::assistantText))
                "tool_use" -> listOfNotNull(backgroundLaunched(block), SessionEvent.ToolStarted)
                else -> emptyList()
            }
        }
    }

    private fun backgroundLaunched(toolUse: JsonObject): SessionEvent? {
        val input = toolUse.obj("input") ?: return null
        if (!input.flag("run_in_background")) return null
        val id = toolUse.string("id") ?: return null
        return SessionEvent.BackgroundLaunched(id, input.string("description") ?: input.string("command")?.lineSequence()?.firstOrNull())
    }

    // 与 SessionTitleRegenerator 中的私有常量同值；那边是标题逻辑的私有细节，不跨包引用
    private const val CLAUDE_INTERRUPTED_PREFIX = "[Request interrupted by user"
    private const val TASK_NOTIFICATION = "<task-notification>"
    private const val WAITING_ON_CHILDREN = "is waiting on its own background work"
    private val TASK_ID = Regex("<task-id>([^<]*)</task-id>")
}

object CodexTranscriptDecoder : TranscriptDecoder {
    override fun decode(line: String): List<SessionEvent> {
        val root = parseObject(line) ?: return emptyList()
        if (root.string("type") == "event_msg") return eventMsg(root.obj("payload") ?: return emptyList())
        if (root.string("type") != "response_item") return emptyList()
        val payload = root.obj("payload") ?: return emptyList()
        val type = payload.string("type") ?: return emptyList()
        // 工具种类会增加（function_call、custom_tool_call、web_search_call、tool_search_call…），按后缀认，
        // 漏认一种，它前面的过程说明就会混进最终回复
        return when {
            type == "message" -> messageEvents(payload)
            type == "function_call" -> listOfNotNull(closedAgent(payload), SessionEvent.ToolStarted)
            type.endsWith("_call") -> listOf(SessionEvent.ToolStarted)
            type == "function_call_output" -> agentOutput(payload)
            else -> emptyList()
        }
    }

    /** 主会话用 close_agent 关掉了一个子代理。 */
    private fun closedAgent(call: JsonObject): SessionEvent? {
        if (call.string("name") != "close_agent") return null
        val target = call.string("arguments")?.let(::parseObject)?.string("target") ?: return null
        return SessionEvent.BackgroundEnded(target)
    }

    /**
     * 子代理相关的工具结果：spawn_agent 返回 `{agent_id, nickname}`，子代理开始在后台运行；
     * wait_agent 返回 `{status: {子代理 id: {completed|errored: …}}}`，列出的子代理已经结束。
     */
    private fun agentOutput(output: JsonObject): List<SessionEvent> {
        val result = output.string("output")?.let(::parseObject) ?: return emptyList()
        result.string("agent_id")?.let { return listOf(SessionEvent.BackgroundStarted(it, null, result.string("nickname"))) }
        val status = result.obj("status") ?: return emptyList()
        return status.entrySet().filter { (_, value) -> value.isJsonObject && value.asJsonObject.keySet().any { it in AGENT_FINISHED } }.map { SessionEvent.BackgroundEnded(it.key) }
    }

    /** 子代理结束时注入给主会话的通知：`<subagent_notification>{"agent_path": 子代理 id, "status": …}</subagent_notification>`。 */
    private fun subagentEnded(payload: JsonObject): List<SessionEvent> =
        blocks(payload.get("content")).mapNotNull { block ->
            val text = block.string("text")?.trim() ?: return@mapNotNull null
            if (!text.startsWith(SUBAGENT_NOTIFICATION)) return@mapNotNull null
            val body = text.removePrefix(SUBAGENT_NOTIFICATION).substringBefore("</subagent_notification>").trim()
            parseObject(body)?.string("agent_path")?.let(SessionEvent::BackgroundEnded)
        }

    private const val SUBAGENT_NOTIFICATION = "<subagent_notification>"
    private val AGENT_FINISHED = setOf("completed", "errored", "shutdown")

    /**
     * 用户输入以 Codex 自己记的用户消息事件（item_completed 里的 UserMessage，0.146 起）为准。
     * 它只含从输入框提交的内容（人、副驾驶、手机）；response_item 里的用户消息还混着环境信息、
     * AGENTS.md、子代理通知等发给模型的注入，不用来判断。task_started / task_complete 标记一轮的起止。
     */
    private fun eventMsg(payload: JsonObject): List<SessionEvent> =
        when (payload.string("type")) {
            "task_started" -> listOf(SessionEvent.TurnStarted)
            "task_complete" -> listOf(SessionEvent.TurnEnded)
            // 被新输入替换（replaced）时紧接着就是新一轮，只有用户中断才说
            "turn_aborted" -> if (payload.string("reason") == "interrupted") listOf(SessionEvent.TurnInterrupted) else emptyList()
            "item_completed" -> {
                val item = payload.obj("item")
                if (item?.string("type") != "UserMessage") {
                    emptyList()
                } else {
                    val text = blocks(item.get("content")).filter { it.string("type") == "text" }.mapNotNull { it.string("text") }.joinToString("\n").trim()
                    listOfNotNull(text.takeIf(String::isNotEmpty)?.let(SessionEvent::UserText))
                }
            }
            else -> emptyList()
        }

    private fun messageEvents(payload: JsonObject): List<SessionEvent> {
        val content = payload.get("content") ?: return emptyList()
        return when (payload.string("role")) {
            // 过程说明（commentary）不是最终回复；只有 final_answer 或没有标注的才算
            "assistant" -> if (payload.string("phase") == "commentary") {
                emptyList()
            } else {
                val text =
                    blocks(content)
                        .filter { it.string("type") == "output_text" }
                        .mapNotNull { it.string("text") }
                        .joinToString("\n")
                listOfNotNull(assistantText(text))
            }
            // 用户消息只看子代理结束通知；输入以 item_completed 为准，见 eventMsg
            "user" -> subagentEnded(payload)
            else -> emptyList()
        }
    }
}

object PiTranscriptDecoder : TranscriptDecoder {
    override fun decode(line: String): List<SessionEvent> {
        val root = parseObject(line) ?: return emptyList()
        if (root.string("type") != "message") return emptyList()
        val message = root.obj("message") ?: return emptyList()
        return when (message.string("role")) {
            "user" -> {
                val content = message.get("content") ?: return emptyList()
                val text =
                    if (content.isJsonPrimitive) {
                        content.asString
                    } else {
                        blocks(content).filter { it.string("type") == "text" }.mapNotNull { it.string("text") }.joinToString("\n")
                    }
                listOfNotNull(text.trim().takeIf(String::isNotEmpty)?.let(SessionEvent::UserText))
            }
            "assistant" -> {
                val events =
                    blocks(message.get("content")).mapNotNull { block ->
                        when (block.string("type")) {
                            "text" -> block.string("text")?.let(::assistantText)
                            "toolCall" -> SessionEvent.ToolStarted
                            else -> null
                        }
                    }
                // stop 正常结束，length 截断、error 出错也已停下；toolUse 还要继续，aborted 是被用户中断
                when (message.string("stopReason")) {
                    in PI_TURN_ENDS -> events + SessionEvent.TurnEnded
                    "aborted" -> listOf(SessionEvent.TurnInterrupted)
                    else -> events
                }
            }
            else -> emptyList()
        }
    }
}

private val PI_TURN_ENDS = setOf("stop", "length", "error")

private fun claudeTurnEnds(stopReason: String?): Boolean = stopReason != null && stopReason != "tool_use" && stopReason != "pause_turn"

private fun assistantText(text: String): SessionEvent? =
    text.takeIf(String::isNotBlank)?.let(SessionEvent::AssistantText)

private val COMMAND_NAME = Regex("""<command-name>(.*?)</command-name>""", RegexOption.DOT_MATCHES_ALL)
private val COMMAND_ARGS = Regex("""<command-args>(.*?)</command-args>""", RegexOption.DOT_MATCHES_ALL)

private fun parseObject(line: String): JsonObject? =
    runCatching { JsonParser.parseString(line) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject

private fun blocks(content: JsonElement?): List<JsonObject> = content.objects()

