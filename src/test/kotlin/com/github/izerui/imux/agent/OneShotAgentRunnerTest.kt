package com.github.izerui.imux.agent

import com.github.izerui.imux.model.AgentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class OneShotAgentRunnerTest {
    private val cwd = Path.of(System.getProperty("user.dir"))
    private val noClaudeHome: () -> Path? = { null }

    private fun invocation(mode: String, sessionId: String = "00000000-0000-0000-0000-000000000000") =
        OneShotAgentInvocation(AgentTestProcesses.command(mode, sessionId), emptyMap())

    @Test
    fun `跳过标记行之前的杂讯并返回最终回答与进度`() {
        val events = CopyOnWriteArrayList<AgentProgressEvent>()
        val answer = runOneShotAgent(
            AgentType.CLAUDE, invocation("claude-ok"), cwd, "hello", 30,
            claudeHome = noClaudeHome, onProgress = events::add,
        )

        assertEquals("标记行前伪装成 result 的杂讯不能当作回答", "[MAIN] received 5 chars", answer)
        assertEquals(
            listOf(
                AgentProgressKind.STARTING,
                AgentProgressKind.TOOL_STARTED,
                AgentProgressKind.TOOL_FINISHED,
                AgentProgressKind.RESPONDING,
                AgentProgressKind.COMPLETED,
            ),
            events.map(AgentProgressEvent::kind),
        )
        assertTrue(events[1].subject.contains("/tmp/session.jsonl"))
    }

    @Test
    fun `超过 4 万字符的中文 prompt 经 stdin 完整送达`() {
        val prompt = "会话记录：" + "中文内容".repeat(12_000)
        val answer = runOneShotAgent(AgentType.CLAUDE, invocation("claude-ok"), cwd, prompt, 30, claudeHome = noClaudeHome)

        assertEquals("[MAIN] received ${prompt.length} chars", answer)
        assertFalse("prompt 不应出现在命令行里", invocation("claude-ok").command.any { it.contains("中文内容") })
    }

    @Test
    fun `CLI 报告失败时异常带上 stderr 和失败详情`() {
        try {
            runOneShotAgent(AgentType.CLAUDE, invocation("claude-error"), cwd, "x", 30, claudeHome = noClaudeHome)
            fail("应抛出异常")
        } catch (e: OneShotAgentException) {
            assertTrue(e.message!!, e.message!!.contains("stderr detail"))
            assertTrue(e.message!!, e.message!!.contains("context window exceeded"))
        }
    }

    @Test
    fun `退出码非零时异常带上 stderr`() {
        try {
            runOneShotAgent(AgentType.CLAUDE, invocation("exit-nonzero"), cwd, "x", 30, claudeHome = noClaudeHome)
            fail("应抛出异常")
        } catch (e: OneShotAgentException) {
            assertTrue(e.message!!, e.message!!.contains("command not found: claude"))
        }
    }

    @Test
    fun `最终回答超过上限时报错而不返回截断内容`() {
        try {
            runOneShotAgent(AgentType.CLAUDE, invocation("huge-answer"), cwd, "x", 30, claudeHome = noClaudeHome)
            fail("应抛出异常")
        } catch (e: OneShotAgentException) {
            assertTrue(e.message!!, e.message!!.contains("exceeded $MAX_AGENT_OUTPUT_CHARS"))
        }
    }

    @Test
    fun `非 JSON 输出作为回答返回`() {
        assertEquals(
            "[MAIN] plain text answer",
            runOneShotAgent(AgentType.CLAUDE, invocation("plain"), cwd, "x", 30, claudeHome = noClaudeHome),
        )
    }

    @Test
    fun `子进程提前关闭 stdin 时报错而不是返回只看了开头的回答`() {
        try {
            runOneShotAgent(AgentType.CLAUDE, invocation("close-early"), cwd, "x".repeat(2_000_000), 30, claudeHome = noClaudeHome)
            fail("应抛出异常")
        } catch (e: OneShotAgentException) {
            assertTrue(e.message!!, e.message!!.contains("not fully sent"))
        }
    }

    @Test
    fun `超时抛出专用异常且返回时进程已退出并清空引用`() {
        val processRef = AtomicReference<TrackedProcess?>()
        var started: Process? = null
        try {
            runOneShotAgent(
                AgentType.CLAUDE, invocation("block"), cwd, "x", 1, processRef,
                onStarted = { started = it }, claudeHome = noClaudeHome,
            )
            fail("应超时")
        } catch (e: OneShotAgentTimeoutException) {
            assertEquals(1L, e.timeoutSeconds)
            assertFalse("抛出时进程应已退出", started!!.isAlive)
        }
        assertNull("确认退出后才清空引用", processRef.get())
    }

    @Test
    fun `协程取消后返回时 CLI 进程已退出`() {
        val processes = CopyOnWriteArrayList<Process>()
        val started = CountDownLatch(1)
        val aliveAtReturn = AtomicReference<Boolean?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch {
            runInterruptible {
                try {
                    runOneShotAgent(
                        AgentType.CLAUDE, invocation("block"), cwd, "x", 60,
                        onStarted = { processes.add(it); started.countDown() }, claudeHome = noClaudeHome,
                    )
                } finally {
                    // 在函数返回的同一时刻取快照，不给协程收尾留出让进程“自己死掉”的时间
                    aliveAtReturn.set(processes.single().isAlive)
                }
            }
        }

        assertTrue("CLI 应已启动", started.await(10, TimeUnit.SECONDS))
        scope.cancel("dialog closed")
        runBlocking { job.join() }

        assertEquals("返回时 CLI 进程应已退出", false, aliveAtReturn.get())
    }

    @Test
    fun `Claude 调用结束后删除本次会话的临时目录`() {
        val sessionId = "11111111-2222-3333-4444-555555555555"
        val home = Files.createTempDirectory("claude-home")
        val scratch = Files.createDirectories(home.resolve("projects/-work-app/$sessionId/tool-results"))
        Files.writeString(scratch.resolve("toolu_1.txt"), "big tool output")

        runOneShotAgent(AgentType.CLAUDE, invocation("claude-ok", sessionId), cwd, "x", 30, claudeHome = { home })

        assertFalse("临时目录应被删除", Files.exists(home.resolve("projects/-work-app/$sessionId")))
        assertTrue("项目目录本身保留", Files.isDirectory(home.resolve("projects/-work-app")))
    }

    @Test
    fun `临时目录旁有同名会话文件时不删除`() {
        val sessionId = "11111111-2222-3333-4444-666666666666"
        val home = Files.createTempDirectory("claude-home")
        val project = Files.createDirectories(home.resolve("projects/-work-app"))
        Files.createDirectories(project.resolve("$sessionId/tool-results"))
        Files.writeString(project.resolve("$sessionId.jsonl"), "{}")

        assertTrue(removeClaudeScratchDirs(home, sessionId).isEmpty())
        assertTrue("正常保存的会话的目录不能动", Files.isDirectory(project.resolve(sessionId)))
    }

    @Test
    fun `会话 ID 不是 UUID 时不删除任何目录`() {
        val home = Files.createTempDirectory("claude-home")
        val project = Files.createDirectories(home.resolve("projects/-work-app"))
        // 与传入 ID 同名的目录真实存在，旁边也没有会话文件：只因 ID 不是 UUID 才不能删
        val sameName = Files.createDirectories(project.resolve("keep"))

        assertTrue(removeClaudeScratchDirs(home, "keep").isEmpty())
        assertTrue(removeClaudeScratchDirs(home, "..").isEmpty())
        assertTrue(removeClaudeScratchDirs(home, "../-work-app").isEmpty())
        assertTrue(Files.isDirectory(sameName))
        assertTrue(Files.isDirectory(project))
    }

    // ── Pi 停止原因：输出了合法节点、退出码为 0，也不能把未正常结束的回答当成功 ──

    private fun assertPiFails(mode: String, vararg expected: String) {
        val events = CopyOnWriteArrayList<AgentProgressEvent>()
        try {
            val answer = runOneShotAgent(AgentType.PI, invocation(mode), cwd, "x", 30, claudeHome = noClaudeHome, onProgress = events::add)
            fail("$mode 应抛出异常，却返回了：$answer")
        } catch (e: OneShotAgentException) {
            expected.forEach { assertTrue("$mode 的报错应包含 $it：${e.message}", e.message!!.contains(it)) }
        }
        assertFalse("$mode 不应报告完成", events.any { it.kind == AgentProgressKind.COMPLETED })
    }

    @Test
    fun `Pi 因长度截断停止时即使已有合法节点且退出码为 0 也报错`() {
        assertPiFails("pi-length", "stopReason=length")
    }

    @Test
    fun `Pi 因错误停止时报错并带上 errorMessage`() {
        assertPiFails("pi-error", "stopReason=error", "429 rate limited")
    }

    @Test
    fun `Pi 截断后没有 agent_end 也报错`() {
        assertPiFails("pi-length-no-end", "stopReason=length")
    }

    @Test
    fun `Pi 最后停在工具调用没有最终回答时报错`() {
        assertPiFails("pi-tool-use-last", "stopReason=toolUse")
    }

    @Test
    fun `Pi 出错并发出 agent_end 后自动重试成功时返回最后的完整回答`() {
        val events = CopyOnWriteArrayList<AgentProgressEvent>()
        val answer = runOneShotAgent(AgentType.PI, invocation("pi-recovered"), cwd, "x", 30, claudeHome = noClaudeHome, onProgress = events::add)

        assertEquals("[MAIN] 完整时间线", answer)
        assertEquals(AgentProgressKind.COMPLETED, events.last().kind)
    }

    @Test
    fun `Pi 截断后重试的最后一条正文为空时不返回旧节点`() {
        try {
            val answer = runOneShotAgent(AgentType.PI, invocation("pi-recovered-empty"), cwd, "x", 30, claudeHome = noClaudeHome)
            fail("最后一条没有正文，不能拿前面截断的回答顶替，却返回了：$answer")
        } catch (e: OneShotAgentException) {
            assertFalse("报错里不应带出旧节点：${e.message}", e.message!!.contains("旧节点"))
            assertTrue(e.message!!, e.message!!.contains("No answer"))
        }
    }

    // ── Codex：只有 turn.completed 之后的回答才算最终回答 ──

    @Test
    fun `Codex 输出节点后继续调用工具却未完成就退出时生成失败`() {
        val events = CopyOnWriteArrayList<AgentProgressEvent>()
        try {
            val answer = runOneShotAgent(AgentType.CODEX, invocation("codex-incomplete"), cwd, "x", 30, claudeHome = noClaudeHome, onProgress = events::add)
            fail("没有 turn.completed，不能把中途输出当最终回答，却返回了：$answer")
        } catch (e: OneShotAgentException) {
            assertTrue(e.message!!, e.message!!.contains("turn.completed"))
            assertFalse("报错里不应带出中途节点：${e.message}", e.message!!.contains("中途节点"))
        }
        assertFalse(events.any { it.kind == AgentProgressKind.COMPLETED })
    }

    @Test
    fun `Codex 收到 turn completed 时返回工具调用之后的最终回答`() {
        val answer = runOneShotAgent(AgentType.CODEX, invocation("codex-ok"), cwd, "x", 30, claudeHome = noClaudeHome)
        assertEquals("[MAIN] 最终节点", answer)
    }

    @Test
    fun `Codex 第二轮没有新回答就完成时报没有回答而不返回第一轮的节点`() {
        try {
            val answer = runOneShotAgent(AgentType.CODEX, invocation("codex-second-turn-empty"), cwd, "x", 30, claudeHome = noClaudeHome)
            fail("第二轮没有回答，不能沿用第一轮的节点，却返回了：$answer")
        } catch (e: OneShotAgentException) {
            assertTrue(e.message!!, e.message!!.contains("No answer"))
            assertFalse("报错里不应带出旧节点：${e.message}", e.message!!.contains("旧节点"))
        }
    }

    @Test
    fun `Codex 第二轮输出新节点后完成时返回新节点`() {
        assertEquals(
            "[MAIN] 第二轮新节点",
            runOneShotAgent(AgentType.CODEX, invocation("codex-second-turn-answer"), cwd, "x", 30, claudeHome = noClaudeHome),
        )
    }

    @Test
    fun `Codex 流断开报错后重连成功并完成时返回回答`() {
        val events = CopyOnWriteArrayList<AgentProgressEvent>()
        val answer = runOneShotAgent(AgentType.CODEX, invocation("codex-reconnect"), cwd, "x", 30, claudeHome = noClaudeHome, onProgress = events::add)

        assertEquals("[MAIN] 重连后的完整时间线", answer)
        assertTrue("重连时的报错仍在进度里显示过", events.any { it.kind == AgentProgressKind.FAILED })
        assertEquals(AgentProgressKind.COMPLETED, events.last().kind)
    }

    @Test
    fun `Codex 报错后没有完成时生成失败并带上报错原因`() {
        try {
            val answer = runOneShotAgent(AgentType.CODEX, invocation("codex-error-final"), cwd, "x", 30, claudeHome = noClaudeHome)
            fail("报错后没有恢复，却返回了：$answer")
        } catch (e: OneShotAgentException) {
            assertTrue(e.message!!, e.message!!.contains("stream disconnected before completion"))
        }
    }

    // ── CLI 先退出时，它留下的后台进程 ──

    @Test
    fun `CLI 先退出时它启动的后台子进程在返回前也被清理`() {
        val processRef = AtomicReference<TrackedProcess?>()
        val answer = runOneShotAgent(AgentType.CLAUDE, invocation("spawn-orphan"), cwd, "x", 30, processRef, claudeHome = noClaudeHome)
        val pid = Regex("""orphan (\d+)""").find(answer)!!.groupValues[1].toLong()
        val orphan = ProcessHandle.of(pid)
        try {
            assertFalse("CLI 已退出、后台子进程已被托管，返回时也应已被清理", orphan.map(ProcessHandle::isAlive).orElse(false))
            assertNull("确认整棵进程树退出后才清空引用", processRef.get())
        } finally {
            orphan.ifPresent { it.destroyForcibly() }
        }
    }

    @Test
    fun `工具启动后台进程后 CLI 立即退出时按工具事件记下的子进程也被清理`() {
        // 关掉定时扫描（间隔远长于 CLI 存活时间），只靠工具事件时的记录
        val answer = runOneShotAgent(
            AgentType.CLAUDE, invocation("tool-orphan"), cwd, "x", 30,
            claudeHome = noClaudeHome, descendantPollMillis = 60_000,
        )
        val orphan = ProcessHandle.of(Regex("""orphan (\d+)""").find(answer)!!.groupValues[1].toLong())
        try {
            assertFalse("工具留下的后台进程应已被清理", orphan.map(ProcessHandle::isAlive).orElse(false))
        } finally {
            orphan.ifPresent { it.destroyForcibly() }
        }
    }
}
