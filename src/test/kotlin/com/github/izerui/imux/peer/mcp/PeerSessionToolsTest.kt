package com.github.izerui.imux.peer.mcp

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.peer.PeerCoordinator
import com.github.izerui.imux.peer.PeerMcpConfig
import com.github.izerui.imux.peer.PeerOutcomeKind
import com.github.izerui.imux.peer.PeerReviewEvent
import com.github.izerui.imux.session.SessionListModel
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** 会话通过 MCP 开关自己的结对：只作用于当前会话，状态未变时不重复绑定也不留痕。 */
class PeerSessionToolsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var review: () -> String = { "PASS" }
    private val prompts = ConcurrentLinkedQueue<String>()
    private val coordinator = PeerCoordinator(
        project = testProject(),
        projectPath = "/tmp/test-project",
        model = SessionListModel(scan = { emptyList() }, clock = Instant::now),
        viewOf = { null },
        coroutineScope = scope,
        shell = "/bin/zsh",
        edtDispatcher = Dispatchers.Unconfined,
        peerMaxRounds = { 5 },
        runCli = { _, _, _, prompt, _, _, _, _ -> prompts += prompt; review() },
        resolveMcpConfig = { PeerMcpConfig(null, null, null) },
        buildPrompt = { _, _ -> "test prompt" },
        sendAutoFeedback = { _, _ -> true },
        onFeedbackUndelivered = { _, _ -> },
        onOneShotPassed = {},
    )
    private var key: String? = "s1"
    private var configured: String? = null
    private var enabled = listOf(AgentType.CLAUDE, AgentType.CODEX, AgentType.PI)
    private val switched = mutableListOf<AgentType?>()

    private fun tools(current: AgentType = AgentType.CLAUDE) = PeerSessionTools(
        current,
        sessionKey = { key },
        coordinator = { coordinator },
        enabledAgents = { enabled },
        configuredPartner = { configured },
        onSwitched = { switched += it },
    )

    @After fun tearDown() {
        coordinator.dispose()
        scope.cancel()
    }

    @Test fun `默认伙伴优先用设置，否则从当前类型往后顺延一个，到末尾回到开头，只启用自己时用自己`() {
        val all = listOf(AgentType.CLAUDE, AgentType.CODEX, AgentType.PI)
        assertEquals(AgentType.PI, defaultPeerPartner(AgentType.CLAUDE, all, "pi"))
        assertEquals(AgentType.CODEX, defaultPeerPartner(AgentType.CLAUDE, all, null))
        assertEquals("顺延而不是取第一个不同的", AgentType.PI, defaultPeerPartner(AgentType.CODEX, all, null))
        assertEquals("到末尾回到开头", AgentType.CLAUDE, defaultPeerPartner(AgentType.PI, all, null))
        assertEquals("当前类型未启用时取第一个", AgentType.CLAUDE, defaultPeerPartner(AgentType.PI, listOf(AgentType.CLAUDE, AgentType.CODEX), null))
        assertEquals(AgentType.CODEX, defaultPeerPartner(AgentType.CLAUDE, listOf(AgentType.CLAUDE, AgentType.CODEX), "pi"))
        assertEquals(AgentType.CLAUDE, defaultPeerPartner(AgentType.CLAUDE, listOf(AgentType.CLAUDE), null))
        assertNull(defaultPeerPartner(AgentType.CLAUDE, emptyList(), null))
    }

    @Test fun `开启、重复开启、换伙伴、关闭依次给出对应结果`() {
        val tools = tools()
        val first = tools.enable(null)
        assertEquals("enabled", first.value["outcome"].asString)
        assertEquals("codex", first.value["partner"].asString)
        assertEquals(AgentType.CODEX, coordinator.boundTarget("s1"))

        assertEquals("already_enabled", tools.enable("codex").value["outcome"].asString)
        assertEquals("switched", tools.enable("pi").value["outcome"].asString)
        assertEquals(AgentType.PI, coordinator.boundTarget("s1"))

        val status = tools.status().value
        assertTrue(status["enabled"].asBoolean)
        assertEquals("pi", status["partner"].asString)

        assertEquals("disabled", tools.disable().value["outcome"].asString)
        assertNull(coordinator.boundTarget("s1"))
        assertEquals("already_disabled", tools.disable().value["outcome"].asString)
        assertFalse(tools.status().value["enabled"].asBoolean)

        assertEquals(listOf(AgentType.CODEX, AgentType.PI, null), switched)
    }

    @Test fun `未启用的伙伴被拒绝并列出可用值`() {
        enabled = listOf(AgentType.CLAUDE, AgentType.CODEX)
        val result = tools().enable("pi")
        assertTrue(result.failed)
        assertEquals("agent_not_enabled", result.value["reason"].asString)
        assertEquals(listOf("claude", "codex"), result.value.getAsJsonArray("available").map { it.asString })
        assertNull(coordinator.boundTarget("s1"))
        assertTrue(switched.isEmpty())
    }

    @Test fun `单次审查中开启同一伙伴保留当前审查且结束后继续结对`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val events = ConcurrentLinkedQueue<PeerReviewEvent>()
        val finished = LinkedBlockingQueue<String>()
        review = {
            calls.incrementAndGet()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "审查未获放行" }
            "PASS"
        }
        coordinator.addReviewListener(coordinator) { events += it }
        coordinator.addStateListener(coordinator) { session ->
            if (coordinator.lastOutcome(session) != null && coordinator.status(session)?.running != true) {
                finished.offer(session)
            }
        }
        val tools = tools()
        try {
            coordinator.reviewOnce("s1", AgentType.CODEX, null)
            assertTrue("单次审查已进入 CLI", entered.await(5, TimeUnit.SECONDS))

            assertEquals("enabled", tools.enable("codex").value["outcome"].asString)
            assertTrue("转换后当前审查仍在进行", tools.status().value["reviewing"].asBoolean)
            assertEquals("already_enabled", tools.enable("codex").value["outcome"].asString)
            assertEquals("只记录一次开启持续结对", listOf(AgentType.CODEX), switched)

            release.countDown()
            assertEquals("等待审查收尾", "s1", finished.poll(5, TimeUnit.SECONDS))
            assertEquals("审查完成后仍绑定原伙伴", AgentType.CODEX, coordinator.boundTarget("s1"))
            assertEquals("转换没有重新启动审查", 1, calls.get())
            assertTrue("原审查正常结束", events.last() is PeerReviewEvent.Finished)
            assertFalse("转换没有取消原审查", events.any { it is PeerReviewEvent.Stopped })

            coordinator.onTurnStarted("s1")
            finished.clear()
            coordinator.onTurnCompleted("s1")
            assertEquals("下一轮自动审查并收尾", "s1", finished.poll(5, TimeUnit.SECONDS))
            assertEquals("持续结对实际发起下一轮", 2, calls.get())
            assertEquals(AgentType.CODEX, coordinator.boundTarget("s1"))
        } finally {
            release.countDown()
        }
    }

    /** 等 [session] 的审查跑完并收尾（单次审查收尾时会解除绑定）。 */
    private fun awaitReviewed(session: String, runs: Int = 1) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (prompts.size >= runs && coordinator.lastOutcome(session) != null && coordinator.status(session)?.running != true) return
            Thread.sleep(10)
        }
        throw AssertionError("审查没有在时限内收尾")
    }

    @Test fun `预约审查一次：本轮结束才开审，带上要求，审完解除且不留结对`() = runBlocking {
        val events = ConcurrentLinkedQueue<PeerReviewEvent>()
        coordinator.addReviewListener(coordinator) { events += it }
        val scheduled = mutableListOf<AgentType>()
        val tools = PeerSessionTools(AgentType.CLAUDE, sessionKey = { key }, coordinator = { coordinator }, enabledAgents = { enabled }, configuredPartner = { configured }, onScheduled = { scheduled += it })

        val result = tools.reviewOnce(null, "  重点看并发  ")
        assertEquals("scheduled", result.value["outcome"].asString)
        assertEquals("codex", result.value["partner"].asString)
        assertEquals(listOf(AgentType.CODEX), scheduled)
        assertEquals("预约时还没开审", 0, prompts.size)
        assertNull("预约不显示为结对中", coordinator.boundTarget("s1"))
        assertEquals("codex", tools.status().value["scheduled_reviewer"].asString)

        coordinator.onTurnCompleted("s1")
        awaitReviewed("s1")
        assertEquals("本轮结束开审一次", 1, prompts.size)
        assertTrue("审查要求去掉首尾空白后带给审查者", prompts.single().contains("\n重点看并发"))
        assertEquals("标明是单次审查", true, (events.first() as PeerReviewEvent.Started).oneShot)
        assertNull("审完解除", coordinator.boundTarget("s1"))
        assertTrue("预约已用掉", tools.status().value["scheduled_reviewer"].isJsonNull)

        coordinator.onTurnCompleted("s1")
        Thread.sleep(100)
        assertEquals("下一轮不再审", 1, prompts.size)
    }

    @Test fun `意见发回后处理意见的这一轮不再预约，这一轮结束后可以；审查通过时不受限`() = runBlocking {
        val tools = tools()
        review = { "第 12 行空指针，需要判空" }
        tools.reviewOnce(null, null)
        coordinator.onTurnCompleted("s1")
        awaitReviewed("s1")

        val refused = tools.reviewOnce(null, null)
        assertFalse(refused.failed)
        assertEquals("skipped_feedback_turn", refused.value["outcome"].asString)
        assertNull("处理意见的这一轮没有预约", coordinator.scheduledReview("s1"))

        coordinator.onTurnCompleted("s1")
        Thread.sleep(100)
        assertEquals("处理意见的这一轮结束时没有再审", 1, prompts.size)
        assertEquals("用户的下一轮可以再预约", "scheduled", tools.reviewOnce(null, null).value["outcome"].asString)

        review = { "PASS" }
        coordinator.onTurnCompleted("s1")
        awaitReviewed("s1", runs = 2)
        Thread.sleep(100)
        assertEquals("通过时没有意见发回，接着就能再预约", "scheduled", tools.reviewOnce(null, null).value["outcome"].asString)
    }

    @Test fun `预约与撤销预约都通知界面刷新，没有预约时撤销不通知`() = runBlocking {
        val changed = mutableListOf<String>()
        coordinator.addStateListener(coordinator) { changed += it }
        tools().reviewOnce(null, null)
        assertEquals("预约后通知", listOf("s1"), changed)
        assertTrue(coordinator.cancelScheduledReview("s1"))
        assertEquals("撤销后通知", listOf("s1", "s1"), changed)
        assertFalse(coordinator.cancelScheduledReview("s1"))
        assertEquals("没有预约时不通知", 2, changed.size)
    }

    @Test fun `反馈已发送而审查尚未收尾时新轮开始仍拒绝重复预约`() = runBlocking {
        val tools = tools()
        val checked = CountDownLatch(1)
        val results = LinkedBlockingQueue<String>()
        review = { "需要补充空值检查" }
        coordinator.addReviewListener(coordinator) { event ->
            if (event is PeerReviewEvent.Finished && event.feedback != null) {
                // Finished 在发送成功的 EDT 操作内发布，此时 finally 尚未执行。
                coordinator.onTurnStarted("s1")
                results.offer(runBlocking { tools.reviewOnce(null, null).value["outcome"].asString })
                checked.countDown()
            }
        }
        tools.reviewOnce(null, null)
        coordinator.onTurnCompleted("s1")
        assertTrue("应在收尾前处理新轮开始事件", checked.await(5, TimeUnit.SECONDS))
        assertEquals("skipped_feedback_turn", results.poll())
        runBlocking { withTimeout(5.seconds) { scope.coroutineContext[Job]!!.children.toList().joinAll() } }
        assertNull("收尾后仍未重复预约", coordinator.scheduledReview("s1"))
        assertTrue("防重复标记保留到反馈处理轮结束", coordinator.isHandlingOneShotFeedback("s1"))
        coordinator.onTurnCompleted("s1")
        assertEquals("处理意见结束后允许新预约", "scheduled", tools.reviewOnce(null, null).value["outcome"].asString)
    }

    @Test fun `失败后重新预约清除旧失败并按新伙伴和要求审查`() = runBlocking {
        val tools = tools()
        review = { "" }
        tools.reviewOnce("codex", "检查旧要求")
        coordinator.onTurnCompleted("s1")
        awaitReviewed("s1")
        assertEquals(PeerOutcomeKind.FAILED, coordinator.lastOutcome("s1")?.kind)

        val refreshed = mutableListOf<PeerOutcomeKind?>()
        coordinator.addStateListener(coordinator) {
            if (coordinator.scheduledReview(it) != null) refreshed += coordinator.lastOutcome(it)?.kind
        }
        assertEquals("scheduled", tools.reviewOnce("pi", "检查新要求").value["outcome"].asString)
        assertEquals("通知界面刷新时旧失败已清掉", listOf<PeerOutcomeKind?>(null), refreshed)
        assertNull("不再提供旧失败的重试", coordinator.lastOutcome("s1"))
        assertEquals(AgentType.PI, coordinator.scheduledReview("s1"))
        assertEquals("预约不立即开审", 1, prompts.size)
        assertTrue("新预约可以取消", coordinator.cancelScheduledReview("s1"))
        assertNull("取消后不再预约", coordinator.scheduledReview("s1"))
        assertNull("取消后不会恢复旧失败", coordinator.lastOutcome("s1"))

        tools.reviewOnce("pi", "检查新要求")
        review = { "PASS" }
        coordinator.onTurnCompleted("s1")
        awaitReviewed("s1", runs = 2)
        assertEquals(AgentType.PI, coordinator.lastOutcome("s1")?.targetAgentType)
        assertTrue("实际审查带上新要求", prompts.last().contains("检查新要求"))
        assertFalse("不会重试旧要求", prompts.last().contains("检查旧要求"))
    }

    @Test fun `已结对时不预约，返回 already_paired，这一轮照常按结对审`() = runBlocking {
        val tools = tools()
        tools.enable("pi")
        val result = tools.reviewOnce("codex", null)
        assertFalse(result.failed)
        assertEquals("already_paired", result.value["outcome"].asString)
        assertEquals("pi", result.value["partner"].asString)
        assertNull(coordinator.scheduledReview("s1"))
        assertEquals(AgentType.PI, coordinator.boundTarget("s1"))
    }

    @Test fun `未启用的审查者被拒绝且不预约`() = runBlocking {
        enabled = listOf(AgentType.CLAUDE, AgentType.CODEX)
        val result = tools().reviewOnce("pi", null)
        assertTrue(result.failed)
        assertEquals("agent_not_enabled", result.value["reason"].asString)
        assertNull(coordinator.scheduledReview("s1"))
    }

    @Test fun `关闭结对撤销还没开始的预约，本轮结束不再审`() = runBlocking {
        val tools = tools()
        tools.reviewOnce(null, null)
        assertEquals("disabled", tools.disable().value["outcome"].asString)
        assertNull(coordinator.scheduledReview("s1"))
        coordinator.onTurnCompleted("s1")
        Thread.sleep(100)
        assertEquals(0, prompts.size)
    }

    @Test fun `预约期间开启结对：预约作废，本轮按持续结对审，审完仍结对`() = runBlocking {
        val tools = tools()
        tools.reviewOnce("pi", null)
        assertEquals("enabled", tools.enable("codex").value["outcome"].asString)
        assertNull("开启结对清掉预约", coordinator.scheduledReview("s1"))
        coordinator.onTurnCompleted("s1")
        awaitReviewed("s1")
        assertEquals(1, prompts.size)
        assertEquals(AgentType.CODEX, coordinator.boundTarget("s1"))
    }

    @Test fun `首轮里会话 key 从 pending 换成正式 key，预约跟着过去`() = runBlocking {
        tools().reviewOnce(null, null)
        coordinator.migrateSessionKey("s1", "real-id")
        assertNull(coordinator.scheduledReview("s1"))
        assertEquals(AgentType.CODEX, coordinator.scheduledReview("real-id"))
        coordinator.onTurnCompleted("real-id")
        awaitReviewed("real-id")
        assertEquals(1, prompts.size)
        assertNull(coordinator.boundTarget("real-id"))
    }

    @Test fun `会话 key 迁移后作用于新 key，标签关闭后报告会话已关闭`() {
        val tools = tools()
        key = "real-id"
        tools.enable("codex")
        assertEquals(AgentType.CODEX, coordinator.boundTarget("real-id"))
        assertNull(coordinator.boundTarget("s1"))

        key = null
        listOf(tools.status(), tools.enable(null), tools.disable()).forEach {
            assertTrue(it.failed)
            assertEquals("session_closed", it.value["reason"].asString)
        }
        assertEquals(AgentType.CODEX, coordinator.boundTarget("real-id"))
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
