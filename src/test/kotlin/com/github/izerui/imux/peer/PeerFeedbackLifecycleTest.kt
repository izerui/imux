package com.github.izerui.imux.peer

import com.github.izerui.imux.model.AgentSession
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.session.SessionListModel
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.terminal.frontend.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.plugins.terminal.view.TerminalSendTextBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class PeerFeedbackLifecycleTest {
    @Test
    fun `自动反馈处理中关闭会话后恢复可以重新预约`() = runBlocking {
        Fixture().use { f ->
            f.review()
            assertEquals(PeerScheduleResult.FEEDBACK_TURN, f.schedule())
            f.coordinator.sessionClosed("s1")
            f.coordinator.onTurnStarted("s1")
            assertNull(f.coordinator.lastOutcome("s1"))
            assertEquals(PeerScheduleResult.SCHEDULED, f.schedule())
        }
    }

    @Test
    fun `关闭后旧审查收尾不会清掉恢复会话的新预约`() = runBlocking {
        Fixture().use { f ->
            val decisions = ConcurrentLinkedQueue<PeerScheduleResult>()
            f.coordinator.addReviewListener(f.coordinator) { event ->
                if (event is PeerReviewEvent.Finished) {
                    f.coordinator.sessionClosed("s1")
                    decisions += runBlocking { f.schedule() }
                }
            }
            f.review()
            assertEquals(listOf(PeerScheduleResult.SCHEDULED), decisions.toList())
            assertEquals("旧 finally 不能撤销新预约", AgentType.PI, f.coordinator.scheduledReview("s1"))
            assertFalse(f.coordinator.isHandlingOneShotFeedback("s1"))
        }
    }

    @Test
    fun `反馈处理先于旧审查收尾结束时不会重新设置保护`() = runBlocking {
        Fixture().use { f ->
            f.coordinator.addReviewListener(f.coordinator) { event ->
                if (event is PeerReviewEvent.Finished) {
                    f.coordinator.onTurnStarted("s1")
                    f.coordinator.onTurnCompleted("s1")
                }
            }
            f.review()
            assertEquals(PeerScheduleResult.SCHEDULED, f.schedule())
            assertFalse(f.coordinator.isHandlingOneShotFeedback("s1"))
        }
    }

    @Test
    fun `用户主动再次审查不受反馈轮自动预约保护限制`() = runBlocking {
        Fixture().use { f ->
            f.review()
            assertEquals(PeerScheduleResult.FEEDBACK_TURN, f.schedule())
            f.reply = "PASS"
            f.review()
            assertEquals("用户的再次审查确实执行", 2, f.calls.get())
            assertEquals(PeerOutcomeKind.PASSED, f.coordinator.lastOutcome("s1")?.kind)
            assertEquals(PeerScheduleResult.SCHEDULED, f.schedule())
        }
    }

    @Test
    fun `会话迁移清除源和目标的反馈保护`() = runBlocking {
        Fixture().use { f ->
            f.review("s1")
            f.review("s2")
            assertEquals(PeerScheduleResult.FEEDBACK_TURN, f.schedule("s1"))
            assertEquals(PeerScheduleResult.FEEDBACK_TURN, f.schedule("s2"))
            f.coordinator.migrateSessionKey("s1", "s2")
            assertEquals("源会话不留保护", PeerScheduleResult.SCHEDULED, f.schedule("s1"))
            assertEquals("目标不继承旧保护", PeerScheduleResult.SCHEDULED, f.schedule("s2"))
        }
    }

    @Test
    fun `持续结对迁移后仍能记录新审查结果`() = runBlocking {
        Fixture().use { f ->
            f.coordinator.bind("s1", AgentType.CODEX)
            f.coordinator.migrateSessionKey("s1", "s2")
            f.reply = "PASS"
            f.coordinator.onTurnCompleted("s2")
            f.awaitReviews()
            assertEquals(AgentType.CODEX, f.coordinator.boundTarget("s2"))
            assertEquals(PeerOutcomeKind.PASSED, f.coordinator.lastOutcome("s2")?.kind)
        }
    }

    @Test
    fun `关闭有预约的会话后完成事件不会启动审查`() = runBlocking {
        Fixture().use { f ->
            assertEquals(PeerScheduleResult.SCHEDULED, f.schedule())
            f.coordinator.sessionClosed("s1")
            f.coordinator.onTurnCompleted("s1")
            f.awaitReviews()
            assertNull(f.coordinator.scheduledReview("s1"))
            assertEquals(0, f.calls.get())
        }
    }

    @Test
    fun `项目释放后预约被拒绝并清理反馈保护`() = runBlocking {
        Fixture().use { f ->
            f.review()
            f.coordinator.dispose()
            assertFalse(f.coordinator.isHandlingOneShotFeedback("s1"))
            assertEquals(PeerScheduleResult.CLOSED, f.schedule())
        }
    }

    private class Fixture(
        private val agent: AgentType = AgentType.CLAUDE,
    ) : AutoCloseable {
        val feedback = "请检查空值 并补充测试"
        var reply = feedback
        val calls = AtomicInteger()
        private val file = Files.createTempFile("imux-feedback-lifecycle-", ".jsonl")
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val session = AgentSession("s1", "test", agent, Instant.now(), Instant.now(), file)
        private val model = SessionListModel(scan = { listOf(session) }, clock = Instant::now).also { it.refresh() }
        private val builder = proxy(TerminalSendTextBuilder::class.java) { _, _ -> null }
        private val view = proxy(TerminalView::class.java) { method, _ ->
            if (method == "createSendTextBuilder") builder else null
        }
        val coordinator = PeerCoordinator(
            project = proxy(Project::class.java) { method, _ ->
                when (method) {
                    "isDisposed" -> false
                    "getBasePath" -> "/tmp/test-project"
                    else -> null
                }
            },
            projectPath = "/tmp/test-project",
            model = model,
            viewOf = { view },
            coroutineScope = scope,
            shell = "/bin/zsh",
            edtDispatcher = Dispatchers.Unconfined,
            peerMaxRounds = { 5 },
            runCli = { _, _, _, _, _, _, _, _ -> calls.incrementAndGet(); reply },
            resolveMcpConfig = { PeerMcpConfig(null, null, null) },
            buildPrompt = { _, _ -> "test prompt" },
            sendAutoFeedback = { _, _ -> true },
            onFeedbackUndelivered = { _, _ -> },
            onOneShotPassed = {},
        )

        init { input("实现功能并审查一次") }

        suspend fun review(key: String = "s1") {
            coordinator.reviewOnce(key, AgentType.CODEX, null)
            awaitReviews()
        }

        suspend fun awaitReviews() {
            withTimeout(5.seconds) { scope.coroutineContext[Job]!!.children.toList().joinAll() }
        }

        fun schedule(key: String = "s1") = coordinator.scheduleReviewOnce(key, AgentType.PI, "新的要求")

        fun input(text: String) {
            val message = JsonObject().apply {
                addProperty("role", "user")
                if (agent == AgentType.CODEX) addProperty("type", "message")
                add("content", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", if (agent == AgentType.CODEX) "input_text" else "text")
                        addProperty("text", text)
                    })
                })
            }
            val record = JsonObject().apply {
                addProperty("type", when (agent) {
                    AgentType.CLAUDE -> "user"
                    AgentType.CODEX -> "response_item"
                    AgentType.PI -> "message"
                })
                addProperty("id", java.util.UUID.randomUUID().toString())
                add(if (agent == AgentType.CODEX) "payload" else "message", message)
            }
            Files.writeString(file, "$record\n", StandardOpenOption.APPEND)
        }

        override fun close() {
            coordinator.dispose()
            scope.cancel()
            Files.deleteIfExists(file)
        }

        private fun <T> proxy(type: Class<T>, handle: (String, Array<out Any?>?) -> Any?): T =
            type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(instance)
                    "equals" -> instance === args?.firstOrNull()
                    "toString" -> "Test${type.simpleName}"
                    else -> handle(method.name, args) ?: when {
                        method.returnType.isInstance(instance) -> instance
                        method.returnType == java.lang.Boolean.TYPE -> false
                        else -> null
                    }
                }
            })
    }
}
