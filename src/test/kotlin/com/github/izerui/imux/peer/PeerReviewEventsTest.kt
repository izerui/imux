package com.github.izerui.imux.peer

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.session.SessionListModel
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 审查对外报告的节点：手机端靠它分清「还在审、先别发」和「已经空闲」，
 * 所以每个开始之后都要恰好有一个结局，被取消、停止结对、主会话开新一轮也不例外，且不重复报告。
 */
class PeerReviewEventsTest {
    private val events = ConcurrentLinkedQueue<PeerReviewEvent>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun coordinator(
        maxRounds: Int = 5,
        sendAutoFeedback: ((String, String) -> Boolean)? = { _, _ -> true },
        resolveMcp: (AgentType) -> PeerMcpConfig = { PeerMcpConfig(null, null, null) },
        runCli: (AgentType, List<String>, Path, String, Map<String, String>, Long, (Process?) -> Unit, (PeerProgressEvent) -> Unit) -> String?,
    ): PeerCoordinator =
        PeerCoordinator(
            project = testProject(),
            projectPath = "/tmp/test-project",
            model = SessionListModel(scan = { emptyList() }, clock = Instant::now),
            viewOf = { null },
            coroutineScope = scope,
            shell = "/bin/zsh",
            edtDispatcher = Dispatchers.Unconfined,
            peerMaxRounds = { maxRounds },
            runCli = runCli,
            resolveMcpConfig = resolveMcp,
            buildPrompt = { _, _ -> "test prompt" },
            sendAutoFeedback = sendAutoFeedback,
            onFeedbackUndelivered = { _, _ -> },
            onOneShotPassed = {},
        ).also { c -> c.addReviewListener(c) { events += it } }

    private fun output(text: String): (AgentType, List<String>, Path, String, Map<String, String>, Long, (Process?) -> Unit, (PeerProgressEvent) -> Unit) -> String? =
        { _, _, _, _, _, _, _, _ -> text }

    /** 等审查这一轮彻底收尾（finally 已跑完）。 */
    private fun awaitIdle(coordinator: PeerCoordinator, start: () -> Unit) {
        val idle = CountDownLatch(1)
        coordinator.addStateListener(coordinator) { key -> if (key == "s1" && coordinator.status("s1")?.running != true && events.size >= 2) idle.countDown() }
        start()
        assertTrue("审查应在时限内结束", idle.await(5, TimeUnit.SECONDS))
    }

    private fun <T> using(coordinator: PeerCoordinator, block: (PeerCoordinator) -> T): T =
        try {
            block(coordinator)
        } finally {
            coordinator.dispose()
            scope.cancel()
        }

    private fun codeLease(onRelease: () -> Unit) = com.github.izerui.imux.code.CodeToolsLease(
        com.github.izerui.imux.terminal.TerminalMcpServer("imux_code", "http://127.0.0.1:63342/imux/code-mcp", "IMUX_CODE_TOKEN"),
        mapOf("IMUX_CODE_URL" to "http://127.0.0.1:63342/imux/code-mcp", "IMUX_CODE_TOKEN" to "code-token"),
        null,
        revoke = onRelease,
    )

    @Test
    fun `挂了查调用链工具时命令和环境带上工具，审查结束撤销凭证，审查提示词不再重复追加（核对调用链写在引导词里）`() {
        var prompt: String? = null
        var command: List<String>? = null
        var environment: Map<String, String>? = null
        var released = 0
        val c = coordinator(
            resolveMcp = { PeerMcpConfig(null, null, null, codeLease { released++ }) },
            runCli = { _, cmd, _, p, env, _, _, _ -> prompt = p; command = cmd; environment = env; "PASS" },
        )
        using(c) {
            it.bind("s1", AgentType.CLAUDE)
            awaitIdle(it) { it.onTurnCompleted("s1") }
        }
        assertEquals("test prompt", prompt)
        assertTrue(command!!.last().contains("imux_code"))
        assertEquals("code-token", environment!!["IMUX_CODE_TOKEN"])
        assertEquals(1, released)
    }

    @Test
    fun `没挂查调用链工具时审查提示词原样发出`() {
        var prompt: String? = null
        using(coordinator(runCli = { _, _, _, p, _, _, _, _ -> prompt = p; "PASS" })) {
            it.bind("s1", AgentType.CLAUDE)
            awaitIdle(it) { it.onTurnCompleted("s1") }
        }
        assertEquals("test prompt", prompt)
    }

    @Test
    fun `副驾驶命令启动失败时也撤销查调用链工具的凭证`() {
        var released = 0
        val c = coordinator(
            resolveMcp = { PeerMcpConfig(null, null, null, codeLease { released++ }) },
            runCli = { _, _, _, _, _, _, _, _ -> throw IllegalStateException("cli missing") },
        )
        using(c) {
            it.bind("s1", AgentType.CLAUDE)
            awaitIdle(it) { it.onTurnCompleted("s1") }
        }
        assertEquals(1, released)
    }

    @Test
    fun `持续结对通过：先报开始再报通过`() =
        using(coordinator(runCli = output("PASS"))) { c ->
            c.bind("s1", AgentType.CODEX)
            awaitIdle(c) { c.onTurnCompleted("s1") }
            val list = events.toList()
            assertEquals(PeerReviewEvent.Started("s1", AgentType.CODEX, round = 1, oneShot = false), list[0])
            assertEquals(PeerOutcomeKind.PASSED, (list[1] as PeerReviewEvent.Finished).outcome.kind)
            assertEquals(2, list.size)
        }

    @Test
    fun `意见自动注入时带上原文`() =
        using(coordinator(runCli = output("第 12 行空指针，请修复"))) { c ->
            c.bind("s1", AgentType.CODEX)
            awaitIdle(c) { c.onTurnCompleted("s1") }
            val finished = events.last() as PeerReviewEvent.Finished
            assertEquals(PeerOutcomeKind.FEEDBACK, finished.outcome.kind)
            assertEquals("第 12 行空指针，请修复", finished.feedback)
        }

    @Test
    fun `意见没送到终端时报告审查中断，不让手机一直等`() =
        using(coordinator(sendAutoFeedback = null, runCli = output("请修复"))) { c ->
            c.bind("s1", AgentType.CODEX)
            awaitIdle(c) { c.onTurnCompleted("s1") }
            assertEquals(PeerReviewEvent.Stopped("s1", AgentType.CODEX, PeerStopReason.INTERRUPTED), events.last())
            assertEquals(2, events.size)
        }

    @Test
    fun `连续注入达到上限后，下一轮结束报告不再审查`() =
        using(coordinator(maxRounds = 1, runCli = output("请修复"))) { c ->
            c.bind("s1", AgentType.CODEX)
            awaitIdle(c) { c.onTurnCompleted("s1") }
            events.clear()
            // 注入的意见让主会话又跑了一轮，这一轮结束
            c.onTurnCompleted("s1")
            assertEquals(listOf(PeerReviewEvent.Stopped("s1", AgentType.CODEX, PeerStopReason.MAX_ROUNDS, maxRounds = 1)), events.toList())
        }

    /** 到上限只在手机上说过；IDE 靠 pausedAtLimit 显示「自动审查已暂停」，否则按钮仍是就绪、看不出为什么停了。 */
    @Test
    fun `达到上限后记下暂停，用户再发消息时解除`() =
        using(coordinator(maxRounds = 1, runCli = output("请修复"))) { c ->
            c.bind("s1", AgentType.CODEX)
            awaitIdle(c) { c.onTurnCompleted("s1") }
            assertEquals(null, c.pausedAtLimit("s1"))

            c.onTurnCompleted("s1")
            assertEquals(1, c.pausedAtLimit("s1"))

            c.onTurnStarted("s1")
            assertEquals(null, c.pausedAtLimit("s1"))
        }

    @Test
    fun `暂停中换伙伴重新开启，暂停状态随之清掉`() {
        using(coordinator(maxRounds = 1, runCli = output("请修复"))) { c ->
            c.bind("s1", AgentType.CODEX)
            awaitIdle(c) { c.onTurnCompleted("s1") }
            c.onTurnCompleted("s1")
            assertEquals(1, c.pausedAtLimit("s1"))

            c.bind("s1", AgentType.CLAUDE)
            assertEquals(null, c.pausedAtLimit("s1"))
        }
    }

    @Test
    fun `暂停中停止结对后查不到暂停状态`() =
        using(coordinator(maxRounds = 1, runCli = output("请修复"))) { c ->
            c.bind("s1", AgentType.CODEX)
            awaitIdle(c) { c.onTurnCompleted("s1") }
            c.onTurnCompleted("s1")
            assertEquals(1, c.pausedAtLimit("s1"))

            c.unbind("s1")
            assertEquals(null, c.pausedAtLimit("s1"))
        }

    @Test
    fun `单次审查标明是单次`() =
        using(coordinator(runCli = output("PASS"))) { c ->
            awaitIdle(c) { c.reviewOnce("s1", AgentType.CLAUDE, null) }
            assertEquals(PeerReviewEvent.Started("s1", AgentType.CLAUDE, round = 1, oneShot = true), events.first())
            assertEquals(PeerOutcomeKind.PASSED, (events.last() as PeerReviewEvent.Finished).outcome.kind)
        }

    private fun blockingCli(entered: CountDownLatch, release: CountDownLatch) =
        { _: AgentType, _: List<String>, _: Path, _: String, _: Map<String, String>, _: Long, _: (Process?) -> Unit, _: (PeerProgressEvent) -> Unit ->
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            "PASS"
        }

    private fun stoppedWhileRunning(
        reason: PeerStopReason,
        stop: (PeerCoordinator) -> Unit,
    ) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        using(coordinator(runCli = blockingCli(entered, release))) { c ->
            c.bind("s1", AgentType.CODEX)
            c.onTurnCompleted("s1")
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            stop(c)
            release.countDown()
            Thread.sleep(200)
            val list = events.toList()
            assertEquals(2, list.size)
            assertEquals(PeerReviewEvent.Stopped("s1", AgentType.CODEX, reason), list[1])
        }
    }

    @Test
    fun `审查中取消：报告已取消，之后跑完的结果不再报告`() = stoppedWhileRunning(PeerStopReason.CANCELLED) { it.cancelCurrentRun("s1") }

    @Test
    fun `审查中停止结对：报告已停止`() = stoppedWhileRunning(PeerStopReason.UNBOUND) { it.unbind("s1") }

    @Test
    fun `审查中主会话开了新一轮：报告审查中止`() = stoppedWhileRunning(PeerStopReason.TURN_STARTED) { it.onTurnStarted("s1") }

    @Test
    fun `审查中换了伙伴：报告已取消`() = stoppedWhileRunning(PeerStopReason.CANCELLED) { it.bind("s1", AgentType.CLAUDE) }

    @Test
    fun `审查中标签换成了另一个会话：按新会话报告审查中止`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        using(coordinator(runCli = blockingCli(entered, release))) { c ->
            c.bind("s1", AgentType.CODEX)
            c.onTurnCompleted("s1")
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            c.migrateSessionKey("s1", "s2")
            release.countDown()
            Thread.sleep(200)
            assertEquals(PeerReviewEvent.Stopped("s2", AgentType.CODEX, PeerStopReason.SESSION_CHANGED), events.last())
            assertEquals(2, events.size)
        }
    }

    @Test
    fun `没有在途审查时停止结对或开新一轮不报告`() =
        using(coordinator(runCli = output("PASS"))) { c ->
            c.bind("s1", AgentType.CODEX)
            awaitIdle(c) { c.onTurnCompleted("s1") }
            events.clear()
            c.onTurnStarted("s1")
            c.cancelCurrentRun("s1")
            c.unbind("s1")
            assertEquals(emptyList<PeerReviewEvent>(), events.toList())
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
