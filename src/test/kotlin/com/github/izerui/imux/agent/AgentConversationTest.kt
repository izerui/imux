package com.github.izerui.imux.agent

import com.github.izerui.imux.model.AgentType
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class AgentConversationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val cwd = Path.of(System.getProperty("user.dir"))
    private val invocation = OneShotAgentInvocation(listOf("fake"), emptyMap())
    private val started = CopyOnWriteArrayList<FakeAgentProcess>()

    private fun starter(script: FakeAgentProcess.(java.io.BufferedReader) -> Unit) =
        ProcessStarter { _, _ -> FakeAgentProcess(script = script).also { started += it } }

    private fun live(agentType: AgentType, timeoutSeconds: Long = 10, claudeHome: Path? = null, script: FakeAgentProcess.(java.io.BufferedReader) -> Unit) =
        LiveAgentConversation(agentType, invocation, cwd, timeoutSeconds, starter(script), claudeHome = { claudeHome }, descendantPollMillis = 50)

    private fun textOf(claudeLine: String): String =
        JsonParser.parseString(claudeLine).asJsonObject
            .getAsJsonObject("message").getAsJsonArray("content")[0].asJsonObject["text"].asString

    /** Claude stream-json：每读到一行 user 消息，回一组事件并以 result 结束这一轮。 */
    private fun claudeEcho(sessionId: String = "11111111-2222-3333-4444-555555555555"): FakeAgentProcess.(java.io.BufferedReader) -> Unit = { stdin ->
        out("login shell noise")
        out(AGENT_OUTPUT_MARKER)
        var n = 0
        while (true) {
            val line = stdin.next() ?: break
            n++
            val text = textOf(line)
            out("""{"type":"system","subtype":"init","session_id":"$sessionId","model":"m"}""")
            out("""{"type":"assistant","message":{"content":[{"type":"text","text":"thinking"}]}}""")
            out("""{"type":"result","subtype":"success","is_error":false,"session_id":"$sessionId","result":${com.google.gson.JsonPrimitive("turn $n: $text")},"stop_reason":"end_turn"}""")
        }
    }

    @Test
    fun `Claude 常驻进程每轮写一行 stream-json 的 user 消息并读到 result 为止`() {
        val conversation = live(AgentType.CLAUDE, script = claudeEcho())
        val events = CopyOnWriteArrayList<AgentProgressEvent>()

        assertEquals("turn 1: remember ZEBRA", conversation.send("remember ZEBRA", events::add))
        assertEquals("turn 2: which word?", conversation.send("which word?"))

        assertEquals("两轮只启动一个进程", 1, started.size)
        val lines = started.single().received
        assertEquals(2, lines.size)
        for (line in lines) {
            val root = JsonParser.parseString(line).asJsonObject
            assertEquals("user", root["type"].asString)
            assertEquals("user", root.getAsJsonObject("message")["role"].asString)
            assertEquals("text", root.getAsJsonObject("message").getAsJsonArray("content")[0].asJsonObject["type"].asString)
        }
        assertEquals(listOf("remember ZEBRA", "which word?"), lines.map(::textOf))
        assertTrue("进度事件经回调交出", events.any { it.kind == AgentProgressKind.COMPLETED })
        conversation.close()
        assertTrue("关闭时结束进程", started.single().destroyed)
    }

    @Test
    fun `Claude 消息里的换行与引号原样送达`() {
        val conversation = live(AgentType.CLAUDE, script = claudeEcho())
        val message = "第一行\n第二行 \"引号\" \\ 反斜杠"
        assertEquals("turn 1: $message", conversation.send(message))
        assertEquals("一条消息只占 stdin 的一行", 1, started.single().received.size)
        conversation.close()
    }

    @Test
    fun `Claude 的 result 报错时这一轮失败`() {
        val conversation =
            live(AgentType.CLAUDE) { stdin ->
                out(AGENT_OUTPUT_MARKER)
                stdin.next()
                out("""{"type":"result","is_error":true,"errors":["context window exceeded"]}""")
                hang()
            }
        val error = runCatching { conversation.send("x") }.exceptionOrNull()
        assertTrue(error is OneShotAgentException && error.message!!.contains("context window exceeded"))
        conversation.close()
    }

    @Test
    fun `Pi 常驻进程按 RPC 发 prompt、取消扩展对话框并读到 agent_settled 为止`() {
        val conversation =
            live(AgentType.PI) { stdin ->
                out(AGENT_OUTPUT_MARKER)
                var n = 0
                while (true) {
                    val line = stdin.next() ?: break
                    val command = JsonParser.parseString(line).asJsonObject
                    if (command["type"].asString != "prompt") continue
                    n++
                    out("""{"type":"response","id":"${command["id"].asString}","command":"prompt","success":true,"data":{"disposition":"started"}}""")
                    out("""{"type":"agent_start"}""")
                    out("""{"type":"extension_ui_request","id":"status-$n","method":"setStatus","statusKey":"k","statusText":"t"}""")
                    out("""{"type":"extension_ui_request","id":"ask-$n","method":"confirm","title":"Allow?"}""")
                    // 对话框必须得到回复，否则真实的 Pi 会一直等
                    val reply = JsonParser.parseString(stdin.next()!!).asJsonObject
                    check(reply["type"].asString == "extension_ui_response" && reply["id"].asString == "ask-$n")
                    out("""{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"turn $n: ${command["message"].asString}"}],"stopReason":"stop"}}""")
                    out("""{"type":"agent_end","messages":[],"willRetry":false}""")
                    out("""{"type":"agent_settled","aborted":false}""")
                }
            }

        assertEquals("turn 1: remember ZEBRA", conversation.send("remember ZEBRA"))
        assertEquals("turn 2: which word?", conversation.send("which word?"))

        assertEquals("两轮只启动一个进程", 1, started.size)
        val commands = started.single().received.map { JsonParser.parseString(it).asJsonObject }
        val prompts = commands.filter { it["type"].asString == "prompt" }
        assertEquals(listOf("imux-1", "imux-2"), prompts.map { it["id"].asString })
        assertEquals(listOf("remember ZEBRA", "which word?"), prompts.map { it["message"].asString })
        val replies = commands.filter { it["type"].asString == "extension_ui_response" }
        assertEquals("只回复对话框，不回复 setStatus 这类不需要回复的请求", listOf("ask-1", "ask-2"), replies.map { it["id"].asString })
        assertTrue("对话框一律取消", replies.all { it["cancelled"].asBoolean })
        conversation.close()
    }

    @Test
    fun `Pi 发来不认识的带 id 请求时也回复取消，不让它一直等`() {
        val conversation =
            live(AgentType.PI, timeoutSeconds = 3) { stdin ->
                out(AGENT_OUTPUT_MARKER)
                stdin.next()
                out("""{"type":"extension_ui_request","id":"pick-1","method":"pickColor"}""")
                val reply = JsonParser.parseString(stdin.next()!!).asJsonObject
                check(reply["id"].asString == "pick-1" && reply["cancelled"].asBoolean)
                out("""{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"done"}],"stopReason":"stop"}}""")
                out("""{"type":"agent_settled","aborted":false}""")
                hang()
            }
        assertEquals("done", conversation.send("x"))
        conversation.close()
    }

    @Test
    fun `Pi 拒绝 prompt 时这一轮失败并带上原因`() {
        val conversation =
            live(AgentType.PI) { stdin ->
                out(AGENT_OUTPUT_MARKER)
                stdin.next()
                out("""{"type":"response","id":"imux-1","command":"prompt","success":false,"error":"no model configured"}""")
                hang()
            }
        val error = runCatching { conversation.send("x") }.exceptionOrNull()
        assertTrue(error is OneShotAgentException && error.message!!.contains("no model configured"))
        conversation.close()
    }

    @Test
    fun `Pi 最后一条助手消息出错时这一轮失败`() {
        val conversation =
            live(AgentType.PI) { stdin ->
                out(AGENT_OUTPUT_MARKER)
                stdin.next()
                out("""{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"half"}],"stopReason":"error","errorMessage":"429"}}""")
                out("""{"type":"agent_settled","aborted":false}""")
                hang()
            }
        val error = runCatching { conversation.send("x") }.exceptionOrNull()
        assertTrue(error is OneShotAgentException && error.message!!.contains("429"))
        conversation.close()
    }

    @Test
    fun `每轮等不到结束时抛出超时异常`() {
        val conversation =
            live(AgentType.CLAUDE, timeoutSeconds = 1) { stdin ->
                out(AGENT_OUTPUT_MARKER)
                stdin.next()
                hang()
            }
        val error = runCatching { conversation.send("x") }.exceptionOrNull()
        assertTrue("超时：$error", error is OneShotAgentTimeoutException)
        conversation.close()
        assertTrue(started.single().destroyed)
    }

    @Test
    fun `关闭时正在等待的一轮立即结束、进程被结束，之后不能再发`() {
        val conversation =
            live(AgentType.CLAUDE, timeoutSeconds = 60) { stdin ->
                out(AGENT_OUTPUT_MARKER)
                stdin.next()
                hang()
            }
        val error = AtomicReference<Throwable?>()
        val worker = thread { error.set(runCatching { conversation.send("x") }.exceptionOrNull()) }
        while (started.isEmpty() || started.single().received.isEmpty()) Thread.sleep(10)

        conversation.close()
        worker.join(5_000)

        assertFalse("等待中的一轮在关闭后结束", worker.isAlive)
        assertTrue("抛出对话已关闭：${error.get()}", error.get() is ConversationClosedException)
        assertTrue("进程被结束", started.single().destroyed)
        assertTrue("之后再发也是已关闭", runCatching { conversation.send("y") }.exceptionOrNull() is ConversationClosedException)
        assertEquals("关闭后不再启动新进程", 1, started.size)
    }

    @Test
    fun `进程中途退出时这一轮报错`() {
        val conversation =
            live(AgentType.CLAUDE) { stdin ->
                out(AGENT_OUTPUT_MARKER)
                stdin.next()
            }
        val error = runCatching { conversation.send("x") }.exceptionOrNull()
        assertTrue("$error", error is OneShotAgentException && error !is ConversationClosedException && error.message!!.contains("CLI exited"))
        conversation.close()
    }

    @Test
    fun `stderr 很多时报错带上的是最近的输出`() {
        val conversation =
            live(AgentType.CLAUDE) { stdin ->
                err("shell rc noise ".repeat(1_000))
                out(AGENT_OUTPUT_MARKER)
                stdin.next()
                err("REAL-ERROR-MARK")
                Thread.sleep(200)
            }
        val error = runCatching { conversation.send("x") }.exceptionOrNull()
        assertTrue("$error", error is OneShotAgentException && error.message!!.contains("REAL-ERROR-MARK"))
        conversation.close()
    }

    @Test
    fun `stderr 只留最后的字符`() {
        val tail = StringBuilder()
        appendTail(tail, CharArray(10) { 'a' }, 10, max = 8)
        appendTail(tail, "XYZ".toCharArray(), 3, max = 8)
        assertEquals("aaaaaXYZ", tail.toString())
    }

    @Test
    fun `还没发过消息就关闭时不启动进程`() {
        val conversation = live(AgentType.CLAUDE, script = claudeEcho())
        assertTrue(conversation.close().isEmpty())
        assertTrue(started.isEmpty())
    }

    @Test
    fun `Claude 关闭时删除本次会话的临时目录`() {
        val id = "11111111-2222-3333-4444-555555555555"
        val home = tmp.newFolder("claude").toPath()
        val scratch = Files.createDirectories(home.resolve("projects/-tmp-project/$id/tool-results"))
        val conversation = live(AgentType.CLAUDE, claudeHome = home, script = claudeEcho(id))
        conversation.send("x")
        assertTrue(Files.exists(scratch))

        conversation.close()

        assertFalse(Files.exists(home.resolve("projects/-tmp-project/$id")))
    }

    // ---- Codex ----

    private val threadId = "01a16450-3d47-7b90-ad5a-97f82254eaa6"

    private fun rollout(root: Path, id: String): Path =
        Files.createDirectories(root.resolve("2026/10/22")).resolve("rollout-2026-10-22T23-52-22-$id.jsonl").also { Files.writeString(it, "{}") }

    /** 每轮一个 codex exec 进程：读完 stdin，报告 thread.started 与回答。 */
    private fun codexTurn(): FakeAgentProcess.(java.io.BufferedReader) -> Unit = { stdin ->
        val prompt = stdin.readText()
        out(AGENT_OUTPUT_MARKER)
        out("""{"type":"thread.started","thread_id":"$threadId"}""")
        out("""{"type":"turn.started"}""")
        out("""{"type":"item.completed","item":{"id":"i","type":"agent_message","text":"got: $prompt"}}""")
        out("""{"type":"turn.completed","usage":{}}""")
    }

    @Test
    fun `Codex 首轮取得会话 ID，之后每轮以它续接且消息经 stdin 写入`() {
        val resumeIds = CopyOnWriteArrayList<String?>()
        val sessions = tmp.newFolder("sessions").toPath()
        val conversation =
            CodexConversation(
                { id -> resumeIds += id; invocation },
                cwd, 10, sessionsDir = { sessions }, start = starter(codexTurn()), descendantPollMillis = 50,
            )

        assertEquals("got: first", conversation.send("first"))
        assertEquals(threadId, conversation.threadId)
        assertEquals("got: second", conversation.send("second"))
        assertEquals("got: third", conversation.send("third"))

        assertEquals("首轮新开会话，之后都续接同一个会话", listOf(null, threadId, threadId), resumeIds)
        assertEquals("每轮一个进程", 3, started.size)
        conversation.close()
    }

    @Test
    fun `Codex 关闭时只删除这个会话的 rollout 文件`() {
        val sessions = tmp.newFolder("sessions").toPath()
        val mine = rollout(sessions, threadId)
        val other = rollout(sessions, "99999999-3d47-7b90-ad5a-97f82254eaa6")
        val conversation = CodexConversation({ invocation }, cwd, 10, sessionsDir = { sessions }, start = starter(codexTurn()))
        conversation.send("x")

        conversation.close()

        assertFalse("这个会话的文件已删除", Files.exists(mine))
        assertTrue("别的会话不动", Files.exists(other))
    }

    @Test
    fun `Codex 一轮进行中关闭时结束进程并删除会话文件`() {
        val sessions = tmp.newFolder("sessions").toPath()
        val mine = rollout(sessions, threadId)
        val conversation =
            CodexConversation(
                { invocation }, cwd, 60, sessionsDir = { sessions },
                start = starter { stdin ->
                    stdin.readText()
                    out(AGENT_OUTPUT_MARKER)
                    out("""{"type":"thread.started","thread_id":"$threadId"}""")
                    hang()
                },
                descendantPollMillis = 50,
            )
        val error = AtomicReference<Throwable?>()
        val worker = thread { error.set(runCatching { conversation.send("x") }.exceptionOrNull()) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (conversation.threadId == null && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals("一到 thread.started 就记下会话 ID", threadId, conversation.threadId)

        conversation.close()
        worker.join(10_000)

        assertFalse(worker.isAlive)
        assertTrue("进程被结束", started.single().destroyed)
        assertTrue("这一轮报已关闭：${error.get()}", error.get() is ConversationClosedException)
        assertFalse("会话文件已删除", Files.exists(mine))
    }

    @Test
    fun `Codex 关闭之后才到的会话 ID 立即删除它的会话文件`() {
        val sessions = tmp.newFolder("sessions").toPath()
        val mine = rollout(sessions, threadId)
        val gate = java.util.concurrent.CountDownLatch(1)
        val conversation =
            CodexConversation(
                { invocation }, cwd, 60, sessionsDir = { sessions },
                // 截止时间内结束不了的进程：关闭之后它还在输出
                start = ProcessStarter { _, _ ->
                    FakeAgentProcess(stubborn = true) { stdin ->
                        stdin.readText()
                        out(AGENT_OUTPUT_MARKER)
                        gate.await()
                        out("""{"type":"thread.started","thread_id":"$threadId"}""")
                        hang()
                    }.also { started += it }
                },
                descendantPollMillis = 50,
                exitTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(100),
            )
        val worker = thread { runCatching { conversation.send("x") } }
        try {
            while (started.isEmpty()) Thread.sleep(10)
            Thread.sleep(200)
            assertEquals("进程没在截止时间内退出", 1, conversation.close().size)
            gate.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (Files.exists(mine) && System.nanoTime() < deadline) Thread.sleep(20)
            assertFalse("关闭后才到的会话 ID，文件立即删除，不等这一轮结束", Files.exists(mine))
            assertTrue("这一轮仍在等进程", worker.isAlive)
        } finally {
            started.forEach { it.release() }
            worker.join(10_000)
        }
    }

    @Test
    fun `Codex 首轮还没拿到会话 ID 就关闭时不删除任何文件`() {
        val sessions = tmp.newFolder("sessions").toPath()
        val other = rollout(sessions, threadId)
        val conversation = CodexConversation({ invocation }, cwd, 10, sessionsDir = { sessions }, start = starter(codexTurn()))
        conversation.close()
        assertNull(conversation.threadId)
        assertTrue(Files.exists(other))
        assertTrue("关闭后不再发", runCatching { conversation.send("x") }.exceptionOrNull() is ConversationClosedException)
    }

    @Test
    fun `删除 Codex 会话文件时会话 ID 不是 UUID 就不删`() {
        val sessions = tmp.newFolder("sessions").toPath()
        val file = Files.createDirectories(sessions.resolve("2026/10/22")).resolve("rollout-x-*.jsonl")
        Files.writeString(file, "{}")
        assertTrue(deleteCodexRollout(sessions, "*").isEmpty())
        assertTrue(Files.exists(file))
    }

    @Test
    fun `Claude 与 Pi 用常驻进程，Codex 每轮续接`() {
        val claude = agentConversation(AgentType.CLAUDE, { invocation }, cwd, 10)
        val pi = agentConversation(AgentType.PI, { invocation }, cwd, 10)
        val codex = agentConversation(AgentType.CODEX, { invocation }, cwd, 10)
        assertTrue(claude is LiveAgentConversation)
        assertTrue(pi is LiveAgentConversation)
        assertTrue(codex is CodexConversation)
        try {
            LiveAgentConversation(AgentType.CODEX, invocation, cwd, 10)
            fail("Codex 没有常驻对话模式")
        } catch (_: IllegalArgumentException) {
        }
    }
}
