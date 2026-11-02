package com.github.izerui.imux.peer.mcp

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.peer.PeerCoordinator
import com.github.izerui.imux.peer.PeerMcpConfig
import com.github.izerui.imux.peer.PeerReviewEvent
import com.github.izerui.imux.session.SessionListModel
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

/** 会话通过 MCP 开关自己的结对：只作用于当前会话，状态未变时不重复绑定也不留痕。 */
class PeerSessionToolsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var review: () -> String = { "PASS" }
    private val coordinator = PeerCoordinator(
        project = testProject(),
        projectPath = "/tmp/test-project",
        model = SessionListModel(scan = { emptyList() }, clock = Instant::now),
        viewOf = { null },
        coroutineScope = scope,
        shell = "/bin/zsh",
        edtDispatcher = Dispatchers.Unconfined,
        peerMaxRounds = { 5 },
        peerAutoInject = { true },
        runCli = { _, _, _, _, _, _, _, _ -> review() },
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

    @Test fun `默认伙伴优先用设置，否则取与当前类型不同的第一个，只启用自己时用自己`() {
        val all = listOf(AgentType.CLAUDE, AgentType.CODEX, AgentType.PI)
        assertEquals(AgentType.PI, defaultPeerPartner(AgentType.CLAUDE, all, "pi"))
        assertEquals(AgentType.CODEX, defaultPeerPartner(AgentType.CLAUDE, all, null))
        assertEquals(AgentType.CLAUDE, defaultPeerPartner(AgentType.CODEX, all, null))
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
