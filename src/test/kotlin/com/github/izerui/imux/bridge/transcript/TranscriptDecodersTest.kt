package com.github.izerui.imux.bridge.transcript

import com.github.izerui.imux.bridge.core.AskedOption
import com.github.izerui.imux.bridge.core.AskedQuestion
import com.github.izerui.imux.bridge.core.SessionEvent
import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptDecodersTest {
    @Test
    fun `Codex 失败结束保留原因且没有成功结束事件`() {
        val line = """{"type":"event_msg","payload":{"type":"task_complete","last_agent_message":null,"error":{"message":"unexpected status 503 Service Unavailable","codex_error_info":{"http_connection_failed":{"http_status_code":503}}}}}"""
        assertEquals(listOf(SessionEvent.TurnFailed("unexpected status 503 Service Unavailable")), CodexTranscriptDecoder.decode(line))
    }

    @Test
    fun `Codex 错误缺少详情仍按失败结束`() {
        assertEquals(
            listOf(SessionEvent.TurnFailed("")),
            CodexTranscriptDecoder.decode("""{"type":"event_msg","payload":{"type":"task_complete","error":{}}}"""),
        )
    }

    @Test
    fun `Codex 成功结束允许 error 为 null`() {
        assertEquals(
            listOf(SessionEvent.TurnEnded),
            CodexTranscriptDecoder.decode("""{"type":"event_msg","payload":{"type":"task_complete","error":null}}"""),
        )
    }

    @Test
    fun `Claude 区分用户文本、助手文本、工具调用与结果且保留换行`() {
        val user = """{"type":"user","uuid":"u1","origin":{"kind":"human"},"message":{"role":"user","content":"第一行\n第二行"}}"""
        val assistant =
            """{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":[""" +
                """{"type":"thinking","thinking":"内部推理"},""" +
                """{"type":"text","text":"```kt\nval a = 1\n```"},""" +
                """{"type":"tool_use","id":"t1","name":"Read","input":{}}]}}"""
        val result =
            """{"type":"user","uuid":"u2","message":{"role":"user","content":[""" +
                """{"type":"tool_result","tool_use_id":"t1","content":"文件内容"}]}}"""

        assertEquals(listOf(SessionEvent.UserText("第一行\n第二行")), ClaudeTranscriptDecoder.decode(user))
        assertEquals(
            listOf(SessionEvent.AssistantText("```kt\nval a = 1\n```"), SessionEvent.ToolCalled("t1", "Read", "{}"), SessionEvent.ToolStarted),
            ClaudeTranscriptDecoder.decode(assistant),
        )
        assertEquals("工具结果标明是哪次调用的", listOf(SessionEvent.ToolResult("t1", null)), ClaudeTranscriptDecoder.decode(result))
    }

    @Test
    fun `Claude 跳过元数据、压缩摘要、子代理与请求内容副本`() {
        listOf(
            """{"type":"user","isMeta":true,"message":{"role":"user","content":"meta"}}""",
            """{"type":"user","isCompactSummary":true,"message":{"role":"user","content":"摘要"}}""",
            """{"type":"assistant","isSidechain":true,"message":{"role":"assistant","content":[{"type":"text","text":"子代理"}]}}""",
            """{"type":"summary","summary":"x"}""",
            // 发给模型的请求内容会把已有消息再抄一遍，不能当成第二次中断或新一轮
            """{"type":"api-request-blob","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user for tool use]"}]}}""",
            """{"type":"api-request-blob","message":{"role":"assistant","content":[{"type":"text","text":"结论"}]}}""",
            "not json",
        ).forEach { assertTrue(it, ClaudeTranscriptDecoder.decode(it).isEmpty()) }
    }

    @Test
    fun `Claude 恢复会话补的占位回复不转发，合成的 API 报错照常算结论`() {
        val placeholder =
            """{"type":"assistant","isApiErrorMessage":false,"message":{"model":"<synthetic>","role":"assistant","stop_reason":"stop_sequence","content":[{"type":"text","text":"No response requested."}]}}"""
        val apiError =
            """{"type":"assistant","isApiErrorMessage":true,"message":{"model":"<synthetic>","role":"assistant","stop_reason":"stop_sequence","content":[{"type":"text","text":"API Error: 503"}]}}"""
        assertTrue(ClaudeTranscriptDecoder.decode(placeholder).isEmpty())
        assertEquals(listOf(SessionEvent.AssistantText("API Error: 503"), SessionEvent.TurnEnded), ClaudeTranscriptDecoder.decode(apiError))
    }

    @Test
    fun `本轮结束标记：Claude 只在含正文的 end_turn 行，Codex 为 task_complete，Pi 为 stop`() {
        val claudeText =
            """{"type":"assistant","message":{"role":"assistant","stop_reason":"end_turn","content":[{"type":"text","text":"结论"}]}}"""
        val claudeThinking =
            """{"type":"assistant","message":{"role":"assistant","stop_reason":"end_turn","content":[{"type":"thinking","thinking":"…"}]}}"""
        val claudeTool =
            """{"type":"assistant","message":{"role":"assistant","stop_reason":"tool_use","content":[{"type":"text","text":"我先看看"}]}}"""
        assertEquals(listOf(SessionEvent.AssistantText("结论"), SessionEvent.TurnEnded), ClaudeTranscriptDecoder.decode(claudeText))
        assertTrue("同一消息里先写入的思考行不能提前结束，否则结论会晚于结束标记", ClaudeTranscriptDecoder.decode(claudeThinking).isEmpty())
        assertEquals(listOf(SessionEvent.AssistantText("我先看看")), ClaudeTranscriptDecoder.decode(claudeTool))

        val codexDone = """{"type":"event_msg","payload":{"type":"task_complete","last_agent_message":"结论"}}"""
        assertEquals(listOf(SessionEvent.TurnEnded), CodexTranscriptDecoder.decode(codexDone))

        fun pi(stop: String) =
            """{"type":"message","message":{"role":"assistant","stopReason":"$stop","content":[{"type":"text","text":"结论"}]}}"""
        assertEquals(listOf(SessionEvent.AssistantText("结论"), SessionEvent.TurnEnded), PiTranscriptDecoder.decode(pi("stop")))
        assertEquals(listOf(SessionEvent.AssistantText("结论")), PiTranscriptDecoder.decode(pi("toolUse")))
        assertEquals("被中断：中断前的半句话不算结论", listOf(SessionEvent.TurnInterrupted), PiTranscriptDecoder.decode(pi("aborted")))
    }

    @Test
    fun `带正文停下的其它收尾：Claude 截断或报错算本轮结束，Pi 截断或出错不算`() {
        fun claude(stop: String) =
            """{"type":"assistant","message":{"role":"assistant","stop_reason":"$stop","content":[{"type":"text","text":"结论"}]}}"""
        for (stop in listOf("max_tokens", "stop_sequence", "refusal")) {
            assertEquals(stop, listOf(SessionEvent.AssistantText("结论"), SessionEvent.TurnEnded), ClaudeTranscriptDecoder.decode(claude(stop)))
        }
        assertEquals("还要继续调工具", listOf(SessionEvent.AssistantText("结论")), ClaudeTranscriptDecoder.decode(claude("pause_turn")))
        val streaming = """{"type":"assistant","message":{"role":"assistant","stop_reason":null,"content":[{"type":"text","text":"结论"}]}}"""
        assertEquals("还没写完的行没有 stop_reason", listOf(SessionEvent.AssistantText("结论")), ClaudeTranscriptDecoder.decode(streaming))

        fun pi(stop: String) =
            """{"type":"message","message":{"role":"assistant","stopReason":"$stop","content":[{"type":"text","text":"结论"}]}}"""
        for (stop in listOf("length", "error")) {
            assertEquals("$stop 之后 Pi 可能自动压缩或重试接着干，不算本轮结束", listOf(SessionEvent.AssistantText("结论")), PiTranscriptDecoder.decode(pi(stop)))
        }
    }

    @Test
    fun `Claude 没有标成输入的（命令输出、后台任务通知）不转发，只标记新一轮开始`() {
        val command = """{"type":"user","message":{"role":"user","content":"<local-command-stdout>已清空</local-command-stdout>"}}"""
        val notification =
            """{"type":"user","origin":{"kind":"task-notification"},"message":{"role":"user","content":[{"type":"text","text":"<task-notification>\n<status>completed</status>\n</task-notification>"}]}}"""
        assertEquals(listOf(SessionEvent.TurnStarted), ClaudeTranscriptDecoder.decode(command))
        assertEquals(listOf(SessionEvent.TurnStarted), ClaudeTranscriptDecoder.decode(notification))
    }

    @Test
    fun `Claude 按来源字段区分人输入：人输入照常转发（含尖括号代码），斜杠命令转成原样，任务通知只开启新一轮`() {
        fun line(
            kind: String,
            content: String,
        ) = """{"type":"user","origin":{"kind":"$kind"},"message":{"role":"user","content":${com.google.gson.JsonPrimitive(content)}}}"""
        assertEquals(listOf(SessionEvent.UserText("跑一下测试")), ClaudeTranscriptDecoder.decode(line("human", "跑一下测试")))
        assertEquals(
            listOf(SessionEvent.UserText("<task-notification>写在代码里</task-notification>")),
            ClaudeTranscriptDecoder.decode(line("human", "<task-notification>写在代码里</task-notification>")),
        )
        assertEquals(
            listOf(SessionEvent.UserText("/codex:review --wait")),
            ClaudeTranscriptDecoder.decode(
                line("human", "<command-message>codex:review</command-message>\n<command-name>/codex:review</command-name>\n<command-args>--wait</command-args>"),
            ),
        )
        assertEquals(
            listOf(SessionEvent.TurnStarted),
            ClaudeTranscriptDecoder.decode(line("task-notification", "<task-notification>\n<status>completed</status>\n</task-notification>")),
        )
    }

    @Test
    fun `人写的 HTML 与自定义标签照常是用户输入，不当成注入消息`() {
        for (text in listOf("<div>你好</div>", "<my-component>内容</my-component>", "<user_card>x</user_card>")) {
            val line = """{"type":"user","origin":{"kind":"human"},"message":{"role":"user","content":"$text"}}"""
            assertEquals(text, listOf(SessionEvent.UserText(text)), ClaudeTranscriptDecoder.decode(line))
        }
    }

    @Test
    fun `Codex 的各种工具调用都算工具，过程说明不算最终回复`() {
        fun item(payload: String) = """{"type":"response_item","payload":$payload}"""
        assertEquals(
            listOf(SessionEvent.ToolStarted),
            CodexTranscriptDecoder.decode(item("""{"type":"web_search_call","status":"completed","action":{"type":"search","query":"x"}}""")),
        )
        assertEquals(
            listOf(SessionEvent.ToolStarted),
            CodexTranscriptDecoder.decode(item("""{"type":"tool_search_call","call_id":"c2"}""")),
        )
        assertEquals(
            emptyList<SessionEvent>(),
            CodexTranscriptDecoder.decode(item("""{"type":"tool_search_output","call_id":"c2"}""")),
        )
        assertTrue(CodexTranscriptDecoder.decode(item("""{"type":"reasoning","summary":[]}""")).isEmpty())
        val commentary = item("""{"type":"message","role":"assistant","phase":"commentary","content":[{"type":"output_text","text":"我先搜一下"}]}""")
        val answer = item("""{"type":"message","role":"assistant","phase":"final_answer","content":[{"type":"output_text","text":"结论"}]}""")
        assertTrue(CodexTranscriptDecoder.decode(commentary).isEmpty())
        assertEquals(listOf(SessionEvent.AssistantText("结论")), CodexTranscriptDecoder.decode(answer))
    }

    @Test
    fun `Codex 用户输入以它自己记的用户消息事件为准，发给模型的注入不转发`() {
        // item_completed 里的 UserMessage，只含从输入框提交的内容（可能带图片）
        val userMessage =
            """{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"UserMessage","id":"u1","content":[""" +
                """{"type":"text","text":"<my-component>修一下</my-component>","text_elements":[]},{"type":"local_image","path":"/tmp/a.png"}]}}}"""
        // response_item 里的用户消息混着环境、AGENTS、子代理通知、贴图标签，一律不当输入
        val injected =
            """{"type":"response_item","payload":{"type":"message","role":"user","content":[""" +
                """{"type":"input_text","text":"<environment_context>x</environment_context>"},{"type":"input_text","text":"# AGENTS.md instructions for /p"},""" +
                """{"type":"input_text","text":"<image name=[Image #1]>"},{"type":"input_text","text":"</image>"},{"type":"input_text","text":"修一下"}]}}"""
        val started = """{"type":"event_msg","payload":{"type":"task_started"}}"""
        val assistant =
            """{"type":"response_item","payload":{"type":"message","role":"assistant","content":[""" +
                """{"type":"output_text","text":"好的"}]}}"""
        val call = """{"type":"response_item","payload":{"type":"function_call","name":"shell","call_id":"c1","arguments":"{}"}}"""
        val output = """{"type":"response_item","payload":{"type":"function_call_output","call_id":"c1","output":"ok"}}"""
        val mirror = """{"type":"event_msg","payload":{"type":"agent_message","message":"好的"}}"""
        val otherItem = """{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"AgentMessage","content":[{"type":"text","text":"好的"}]}}}"""

        assertEquals(listOf(SessionEvent.UserText("<my-component>修一下</my-component>")), CodexTranscriptDecoder.decode(userMessage))
        assertTrue(CodexTranscriptDecoder.decode(injected).isEmpty())
        assertEquals("子代理通知等注入开启的一轮也有 task_started", listOf(SessionEvent.TurnStarted), CodexTranscriptDecoder.decode(started))
        assertEquals(listOf(SessionEvent.AssistantText("好的")), CodexTranscriptDecoder.decode(assistant))
        assertEquals(listOf(SessionEvent.ToolStarted), CodexTranscriptDecoder.decode(call))
        assertEquals("工具结果标明是哪次调用的", listOf(SessionEvent.ToolResult("c1", null)), CodexTranscriptDecoder.decode(output))
        assertTrue("助手消息的镜像不能重复转发", CodexTranscriptDecoder.decode(mirror).isEmpty())
        assertTrue(CodexTranscriptDecoder.decode(otherItem).isEmpty())
    }

    @Test
    fun `Pi 区分 text、toolCall、toolResult 并跳过 system 与会话头`() {
        val user = """{"type":"message","id":"1","parentId":null,"message":{"role":"user","content":"你好"}}"""
        val assistant =
            """{"type":"message","id":"2","parentId":"1","message":{"role":"assistant","content":[""" +
                """{"type":"text","text":"看看"},{"type":"toolCall","id":"k1","name":"bash","arguments":{}}]}}"""
        val result =
            """{"type":"message","id":"3","parentId":"2","message":{"role":"toolResult","toolCallId":"k1","content":[]}}"""
        val system = """{"type":"message","id":"0","parentId":null,"message":{"role":"system","content":""}}"""
        val header = """{"type":"session","version":3,"id":"s","cwd":"/p"}"""

        assertEquals(listOf(SessionEvent.UserText("你好")), PiTranscriptDecoder.decode(user))
        assertEquals(
            listOf(SessionEvent.AssistantText("看看"), SessionEvent.ToolStarted),
            PiTranscriptDecoder.decode(assistant),
        )
        assertTrue("工具结果不产生事件", PiTranscriptDecoder.decode(result).isEmpty())
        assertTrue(PiTranscriptDecoder.decode(system).isEmpty())
        assertTrue(PiTranscriptDecoder.decode(header).isEmpty())
    }

    @Test
    fun `按 Agent 选择解码器`() {
        assertSame(ClaudeTranscriptDecoder, TranscriptDecoders.forAgent(AgentType.CLAUDE))
        assertSame(CodexTranscriptDecoder, TranscriptDecoders.forAgent(AgentType.CODEX))
        assertSame(PiTranscriptDecoder, TranscriptDecoders.forAgent(AgentType.PI))
    }

    @Test
    fun `Claude 后台命令与后台子代理：发起、开始、续跑与结束通知`() {
        val launch =
            """{"type":"assistant","message":{"role":"assistant","content":[""" +
                """{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"pnpm build","description":"完整构建","run_in_background":true}}]}}"""
        assertEquals(listOf(SessionEvent.BackgroundLaunched("t1", "完整构建"), SessionEvent.ToolStarted), claudeEvents(launch))

        val bash =
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"Command running in background with ID: b9"}]},""" +
                """"toolUseResult":{"stdout":"","backgroundTaskId":"b9"}}"""
        assertEquals(listOf(SessionEvent.BackgroundStarted("b9", "t1", null)), claudeEvents(bash))

        val agent =
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t2","content":[]}]},""" +
                """"toolUseResult":{"isAsync":true,"status":"async_launched","agentId":"a7","description":"评审"}}"""
        assertEquals("后台子代理带上类型", listOf(SessionEvent.BackgroundStarted("a7", "t2", "评审", subagent = true)), claudeEvents(agent))

        val resumed =
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t3","content":[]}]},""" +
                """"toolUseResult":{"success":true,"resumedAgentId":"a7"}}"""
        assertEquals("续跑的也是子代理", listOf(SessionEvent.BackgroundStarted("a7", null, subagent = true)), claudeEvents(resumed))

        val finishedAgent =
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t4","content":"done"}]},""" +
                """"toolUseResult":{"status":"completed","agentId":"a8"}}"""
        assertTrue("同步跑完的子代理不算后台任务", claudeEvents(finishedAgent).isEmpty())

        val notification =
            """{"type":"user","origin":{"kind":"task-notification"},"message":{"role":"user","content":""" +
                """"<task-notification>\n<task-id>b9</task-id>\n<status>completed</status>\n<summary>Background command done</summary>\n</task-notification>"}}"""
        assertEquals("通知既说明任务结束，也开启新一轮", listOf(SessionEvent.BackgroundEnded("b9"), SessionEvent.TurnStarted), claudeEvents(notification))

        val queued =
            """{"type":"queue-operation","operation":"enqueue","content":"<task-notification>\n<task-id>a7</task-id>\n<status>completed</status>\n</task-notification>"}"""
        assertEquals(listOf(SessionEvent.BackgroundEnded("a7")), claudeEvents(queued))

        val waiting =
            """{"type":"queue-operation","operation":"enqueue","content":"<task-notification>\n<task-id>a7</task-id>\n<summary>Agent \"评审\" is waiting on its own background work</summary>\n</task-notification>"}"""
        assertTrue("子代理还在等自己的后台工作，不算结束", claudeEvents(waiting).isEmpty())
    }

    @Test
    fun `用户中断：Claude 的中断标记、Codex 的 turn_aborted、Pi 的 aborted`() {
        for (text in listOf("[Request interrupted by user]", "[Request interrupted by user for tool use]")) {
            val line = """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"$text"}]}}"""
            assertEquals(text, listOf(SessionEvent.TurnInterrupted), ClaudeTranscriptDecoder.decode(line))
        }
        val aborted = """{"type":"event_msg","payload":{"type":"turn_aborted","reason":"interrupted"}}"""
        assertEquals(listOf(SessionEvent.TurnInterrupted), CodexTranscriptDecoder.decode(aborted))
        val replaced = """{"type":"event_msg","payload":{"type":"turn_aborted","reason":"replaced"}}"""
        assertTrue("被新输入替换不算中断", CodexTranscriptDecoder.decode(replaced).isEmpty())
    }

    @Test
    fun `Codex 子代理：spawn 开始，结束通知、wait 结果或 close 结束`() {
        val spawn =
            """{"type":"response_item","payload":{"type":"function_call_output","call_id":"c1","output":"{\"agent_id\":\"ag1\",\"nickname\":\"Boyle\"}"}}"""
        assertEquals(listOf(SessionEvent.BackgroundStarted("ag1", null, "Boyle", subagent = true)), codexEvents(spawn))

        val notification =
            """{"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text",""" +
                """"text":"<subagent_notification>\n{\"agent_path\":\"ag1\",\"status\":{\"completed\":\"ok\"}}\n</subagent_notification>"}]}}"""
        assertEquals(listOf(SessionEvent.BackgroundEnded("ag1")), codexEvents(notification))

        val waited =
            """{"type":"response_item","payload":{"type":"function_call_output","call_id":"c2","output":"{\"status\":{\"ag2\":{\"errored\":\"429\"},\"ag3\":{\"running\":null}}}"}}"""
        assertEquals(listOf(SessionEvent.BackgroundEnded("ag2")), codexEvents(waited))

        val timedOut = """{"type":"response_item","payload":{"type":"function_call_output","call_id":"c3","output":"{\"status\":{},\"timed_out\":true}"}}"""
        assertTrue(codexEvents(timedOut).isEmpty())

        val close =
            """{"type":"response_item","payload":{"type":"function_call","name":"close_agent","arguments":"{\"target\":\"ag4\"}","call_id":"c4"}}"""
        assertEquals(listOf(SessionEvent.BackgroundEnded("ag4"), SessionEvent.ToolStarted), codexEvents(close))

        val plainOutput = """{"type":"response_item","payload":{"type":"function_call_output","call_id":"c5","output":"Process exited with code 0"}}"""
        assertTrue("普通命令输出不产生事件", codexEvents(plainOutput).isEmpty())
    }

    @Test
    fun `Claude 提问的结果带回每题的作答`() {
        val answered =
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"The user answered: ..."}]},""" +
                """"toolUseResult":{"questions":[],"answers":{"你更喜欢哪种宠物？":"cat","颜色？":"蓝色"}}}"""
        assertEquals(
            listOf(SessionEvent.ToolResult("t1", "你更喜欢哪种宠物？\n→ cat\n\n颜色？\n→ 蓝色")),
            ClaudeTranscriptDecoder.decode(answered),
        )

        val empty =
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t2","content":"x"}]},"toolUseResult":{"answers":{}}}"""
        assertEquals("没有作答就不带内容", listOf(SessionEvent.ToolResult("t2", null)), ClaudeTranscriptDecoder.decode(empty))
    }

    @Test
    fun `Claude 的提问调用带出题目与选项，任何一题不完整就不带`() {
        val ask =
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"AskUserQuestion","input":""" +
                """{"questions":[{"question":"选哪个？","header":"水果","multiSelect":false,"options":[{"label":"苹果","description":"红"},{"label":"香蕉"}]},""" +
                """{"question":"配料？","header":"配料","multiSelect":true,"options":[{"label":"糖","description":"甜"}]}]}}]}}"""
        assertEquals(
            listOf(
                SessionEvent.QuestionAsked(
                    "t1",
                    listOf(
                        AskedQuestion("选哪个？", false, listOf(AskedOption("苹果", "红"), AskedOption("香蕉", null))),
                        AskedQuestion("配料？", true, listOf(AskedOption("糖", "甜"))),
                    ),
                ),
                SessionEvent.ToolStarted,
            ),
            ClaudeTranscriptDecoder.decode(ask),
        )

        val broken =
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"t2","name":"AskUserQuestion","input":""" +
                """{"questions":[{"question":"选哪个？","options":[]}]}}]}}"""
        assertEquals("交给读屏", listOf(SessionEvent.ToolStarted), ClaudeTranscriptDecoder.decode(broken))
    }

    @Test
    fun `工具调用带出要批准的内容：命令连同说明、文件路径、网址，认不出的给参数原文，过长截短`() {
        fun call(name: String, input: String) =
            ClaudeTranscriptDecoder.decode(
                """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"$name","input":$input}]}}""",
            ).filterIsInstance<SessionEvent.ToolCalled>().single()
        assertEquals(SessionEvent.ToolCalled("t1", "Bash", "建文件\ntouch a.txt"), call("Bash", """{"command":"touch a.txt","description":"建文件"}"""))
        assertEquals("/p/a.kt", call("Edit", """{"file_path":"/p/a.kt","old_string":"a","new_string":"b"}""").detail)
        assertEquals("https://x.dev", call("WebFetch", """{"url":"https://x.dev","prompt":"读"}""").detail)
        assertEquals("""{"k":1}""", call("mcp__x__y", """{"k":1}""").detail)
        assertEquals("计划确认带出计划全文", "# 计划\n1. 新建", call("ExitPlanMode", """{"plan":"# 计划\n1. 新建","planFilePath":"/p.md"}""").detail)
        assertEquals("计划放得长些", 5000, call("ExitPlanMode", """{"plan":"${"x".repeat(5000)}"}""").detail.length)
        val long = call("Bash", """{"command":"${"x".repeat(3000)}"}""").detail
        assertEquals(1500, long.length)
        assertTrue(long.endsWith("…"))
    }

    /** 后台任务的用例只看后台事件；工具结果另有用例。 */
    private fun claudeEvents(line: String) = ClaudeTranscriptDecoder.decode(line).filterNot { it is SessionEvent.ToolResult || it is SessionEvent.ToolCalled }

    private fun codexEvents(line: String) = CodexTranscriptDecoder.decode(line).filterNot { it is SessionEvent.ToolResult }
}
