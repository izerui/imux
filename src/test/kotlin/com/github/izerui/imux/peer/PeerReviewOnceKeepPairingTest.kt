package com.github.izerui.imux.peer

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.session.SessionListModel
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 审查一次时勾选“同时开启结对编程”：确认即结对，审查要求只用于这一次，之后按普通结对执行。 */
class PeerReviewOnceKeepPairingTest {
    private val events = ConcurrentLinkedQueue<PeerReviewEvent>()
    private val prompts = ConcurrentLinkedQueue<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val instruction = "重点看并发"

    /** [replies] 依次作为每次审查的输出；用完后重复最后一个。[gate] 非空时每次审查先等它放行。 */
    private fun coordinator(
        replies: List<String>,
        maxRounds: Int = 5,
        autoInject: Boolean = true,
        entered: CountDownLatch? = null,
        gate: CountDownLatch? = null,
    ): PeerCoordinator {
        val queue = ConcurrentLinkedQueue(replies)
        return PeerCoordinator(
            project = testProject(),
            projectPath = "/tmp/test-project",
            model = SessionListModel(scan = { emptyList() }, clock = Instant::now),
            viewOf = { null },
            coroutineScope = scope,
            shell = "/bin/zsh",
            edtDispatcher = Dispatchers.Unconfined,
            peerMaxRounds = { maxRounds },
            peerAutoInject = { autoInject },
            runCli = { _, _, _, prompt, _, _, _, _ ->
                prompts += prompt
                entered?.countDown()
                gate?.await(5, TimeUnit.SECONDS)
                if (queue.size > 1) queue.poll() else queue.peek()
            },
            resolveMcpConfig = { PeerMcpConfig(null, null, null) },
            buildPrompt = { _, _ -> "test prompt" },
            sendAutoFeedback = { _, _ -> true },
            onFeedbackUndelivered = { _, _ -> },
            onOneShotPassed = {},
        ).also { c -> c.addReviewListener(c) { events += it } }
    }

    private fun concluded() = events.count { it !is PeerReviewEvent.Started }

    /** 等到第 [n] 个结局（通过、意见、失败或中止）报告出来，且这一轮已收尾。 */
    private fun awaitConcluded(c: PeerCoordinator, n: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (concluded() >= n && c.status("s1")?.running != true) return
            Thread.sleep(10)
        }
        throw AssertionError("第 $n 个审查结局没有在时限内出现：${events.toList()}")
    }

    private fun <T> using(c: PeerCoordinator, block: (PeerCoordinator) -> T): T =
        try {
            block(c)
        } finally {
            c.dispose()
            scope.cancel()
        }

    @Test
    fun `确认即结对：审查还没结束就已处于结对状态，结束后仍在结对`() {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        using(coordinator(listOf("PASS"), entered = entered, gate = gate)) { c ->
            c.reviewOnce("s1", AgentType.CODEX, instruction, keepPairing = true)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(AgentType.CODEX, c.boundTarget("s1"))
            gate.countDown()
            awaitConcluded(c, 1)
            assertEquals(AgentType.CODEX, c.boundTarget("s1"))
            assertEquals(1, prompts.size)
        }
    }

    @Test
    fun `主链路：首次带要求，意见发送后主会话完成下一轮自动再审且不带要求`() =
        using(coordinator(listOf("请修复第 3 行", "PASS"))) { c ->
            c.reviewOnce("s1", AgentType.CODEX, instruction, keepPairing = true)
            awaitConcluded(c, 1)
            assertEquals(PeerOutcomeKind.FEEDBACK, (events.last() as PeerReviewEvent.Finished).outcome.kind)
            assertEquals(AgentType.CODEX, c.boundTarget("s1"))

            // 意见注入后主会话开工、做完
            c.onTurnStarted("s1")
            assertEquals(AgentType.CODEX, c.boundTarget("s1"))
            c.onTurnCompleted("s1")
            awaitConcluded(c, 2)

            val sent = prompts.toList()
            assertEquals(2, sent.size)
            assertTrue(sent[0], sent[0].contains(instruction))
            assertFalse(sent[1], sent[1].contains(instruction))
            assertEquals(PeerOutcomeKind.PASSED, (events.last() as PeerReviewEvent.Finished).outcome.kind)
            assertEquals(AgentType.CODEX, c.boundTarget("s1"))
        }

    @Test
    fun `轮次上限为 1：首次意见成功发送后不再追加审查`() =
        using(coordinator(listOf("请修复"), maxRounds = 1)) { c ->
            c.reviewOnce("s1", AgentType.CODEX, instruction, keepPairing = true)
            awaitConcluded(c, 1)
            c.onTurnStarted("s1")
            c.onTurnCompleted("s1")
            assertEquals(1, prompts.size)
            assertEquals(PeerReviewEvent.Stopped("s1", AgentType.CODEX, PeerStopReason.MAX_ROUNDS, maxRounds = 1), events.last())
        }

    @Test
    fun `首次通过后保持结对，不发起额外审查`() =
        using(coordinator(listOf("PASS"))) { c ->
            c.reviewOnce("s1", AgentType.CODEX, instruction, keepPairing = true)
            awaitConcluded(c, 1)
            Thread.sleep(100)
            assertEquals(AgentType.CODEX, c.boundTarget("s1"))
            assertEquals(1, prompts.size)
        }

    @Test
    fun `意见没能自动发送时不计轮次：上限为 1 时下一轮仍会审查`() =
        using(coordinator(listOf("请修复", "PASS"), maxRounds = 1, autoInject = false)) { c ->
            c.reviewOnce("s1", AgentType.CODEX, instruction, keepPairing = true)
            awaitConcluded(c, 1)
            c.onTurnStarted("s1")
            c.onTurnCompleted("s1")
            awaitConcluded(c, 2)
            assertEquals(2, prompts.size)
        }

    @Test
    fun `取消这一轮审查后保持结对`() {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        using(coordinator(listOf("PASS"), entered = entered, gate = gate)) { c ->
            c.reviewOnce("s1", AgentType.CODEX, instruction, keepPairing = true)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            c.cancelCurrentRun("s1")
            gate.countDown()
            Thread.sleep(100)
            assertEquals(AgentType.CODEX, c.boundTarget("s1"))
        }
    }

    @Test
    fun `审查期间停止结对：立即解绑，之后主会话完成新一轮也不再审查`() {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        using(coordinator(listOf("PASS"), entered = entered, gate = gate)) { c ->
            c.reviewOnce("s1", AgentType.CODEX, instruction, keepPairing = true)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            c.unbind("s1")
            assertNull(c.boundTarget("s1"))
            gate.countDown()
            Thread.sleep(100)
            c.onTurnStarted("s1")
            c.onTurnCompleted("s1")
            Thread.sleep(100)
            assertNull(c.boundTarget("s1"))
            assertEquals(1, prompts.size)
        }
    }

    @Test
    fun `不勾选时仍是单次审查：带要求，审查完解绑`() =
        using(coordinator(listOf("PASS"))) { c ->
            c.reviewOnce("s1", AgentType.CODEX, instruction)
            awaitConcluded(c, 1)
            Thread.sleep(100)
            assertTrue(prompts.single().contains(instruction))
            assertNull(c.boundTarget("s1"))
        }

    private fun testProject(): Project =
        Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { proxy, method, args ->
            when (method.name) {
                "getBasePath" -> "/tmp/test-project"
                "isDisposed" -> false
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "TestProject"
                else -> if (method.returnType == java.lang.Boolean.TYPE) false else null
            }
        } as Project
}
