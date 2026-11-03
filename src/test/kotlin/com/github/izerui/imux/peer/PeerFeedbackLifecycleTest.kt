package com.github.izerui.imux.peer

import com.github.izerui.imux.model.AgentSession
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.peer.mcp.PeerSessionTools
import com.github.izerui.imux.session.SessionListModel
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.terminal.frontend.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.plugins.terminal.view.TerminalSendTextBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class PeerFeedbackLifecycleTest {
    @Test
    fun `Claude 手动提交反馈后拒绝重复预约且本轮结束后可重新预约`() = runBlocking {
        Fixture(AgentType.CLAUDE).use { f ->
            f.review()
            assertEquals("反馈已暂存", listOf(f.feedback), f.staged.toList())
            assertEquals("暂存不能自动提交", 0, f.executions.get())
            f.input(f.feedback)
            f.coordinator.onTurnStarted("s1")
            assertEquals(PeerScheduleResult.FEEDBACK_TURN, f.schedule())
            assertNull(f.coordinator.scheduledReview("s1"))
            f.coordinator.onTurnCompleted("s1")
            assertEquals(PeerScheduleResult.SCHEDULED, f.schedule())
        }
    }

    @Test
    fun `Codex 手动提交反馈后拒绝重复预约`() = runBlocking {
        Fixture(AgentType.CODEX).use { f ->
            f.review()
            f.input(f.feedback)
            f.coordinator.onTurnStarted("s1")
            assertEquals(PeerScheduleResult.FEEDBACK_TURN, f.schedule())
        }
    }

    @Test
    fun `Pi 手动提交反馈后拒绝重复预约`() = runBlocking {
        Fixture(AgentType.PI).use { f ->
            f.review()
            f.input(f.feedback)
            f.coordinator.onTurnStarted("s1")
            assertEquals(PeerScheduleResult.FEEDBACK_TURN, f.schedule())
        }
    }

    @Test
    fun `手动反馈尚未提交时不把历史同文消息当成反馈轮`() = runBlocking {
        Fixture().use { f ->
            f.input(f.feedback)
            f.review()
            assertFalse(f.coordinator.isHandlingOneShotFeedback("s1"))
            assertEquals(PeerScheduleResult.SCHEDULED, f.schedule())
        }
    }

    @Test
    fun `用户替换暂存反馈后新任务可以预约`() = runBlocking {
        Fixture().use { f ->
            f.review()
            f.input("先不处理审查意见，帮我检查新功能")
            f.coordinator.onTurnStarted("s1")
            assertEquals(PeerScheduleResult.SCHEDULED, f.schedule())
            assertFalse(f.coordinator.isHandlingOneShotFeedback("s1"))
        }
    }

    @Test
    fun `手动反馈只修改空白仍识别为反馈轮`() = runBlocking {
        Fixture().use { f ->
            f.review()
            f.input(f.feedback.replace(" ", "\n\n"))
            assertEquals(PeerScheduleResult.FEEDBACK_TURN, f.schedule())
        }
    }

    @Test
    fun `暂存失败不留下反馈轮保护`() = runBlocking {
        Fixture(acceptStage = false).use { f ->
            f.review()
            assertEquals("实际尝试过暂存", listOf(f.feedback), f.staged.toList())
            assertFalse(f.coordinator.isHandlingOneShotFeedback("s1"))
            assertEquals(PeerScheduleResult.SCHEDULED, f.schedule())
        }
    }

    @Test
    fun `手动反馈在审查收尾前提交仍能保护反馈轮`() = runBlocking {
        Fixture().use { f ->
            val decisions = ConcurrentLinkedQueue<PeerScheduleResult>()
            f.coordinator.addReviewListener(f.coordinator) { event ->
                if (event is PeerReviewEvent.Finished) {
                    f.input(f.feedback)
                    f.coordinator.onTurnStarted("s1")
                    decisions += runBlocking { f.schedule() }
                }
            }
            f.review()
            assertEquals(listOf(PeerScheduleResult.FEEDBACK_TURN), decisions.toList())
            assertEquals("收尾不能丢掉保护", PeerScheduleResult.FEEDBACK_TURN, f.schedule())
        }
    }

    @Test
    fun `自动反馈处理中关闭会话后恢复可以重新预约`() = runBlocking {
        Fixture(autoInject = true).use { f ->
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
        Fixture(autoInject = true).use { f ->
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
    fun `关闭清除尚未提交的手动反馈记录`() = runBlocking {
        Fixture().use { f ->
            f.review()
            f.coordinator.sessionClosed("s1")
            f.input(f.feedback)
            assertEquals(PeerScheduleResult.SCHEDULED, f.schedule())
        }
    }

    @Test
    fun `反馈处理先于旧审查收尾结束时不会重新设置保护`() = runBlocking {
        Fixture(autoInject = true).use { f ->
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
        Fixture(autoInject = true).use { f ->
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
        Fixture(autoInject = true).use { f ->
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
        Fixture(autoInject = true).use { f ->
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
    fun `读取提交记录期间轮次结束不会覆盖下一轮的新预约`() = runBlocking {
        Fixture().use { f ->
            f.review()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.beforeRead = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            try {
                val oldRequest = async(Dispatchers.Default) { f.schedule() }
                assertTrue("旧请求正在读取提交记录", entered.await(5, TimeUnit.SECONDS))
                f.coordinator.onTurnCompleted("s1")
                assertEquals(PeerScheduleResult.SCHEDULED, f.coordinator.scheduleReviewOnce("s1", AgentType.CLAUDE, "下一轮"))
                release.countDown()
                assertEquals(PeerScheduleResult.STATE_CHANGED, withTimeout(5.seconds) { oldRequest.await() })
                assertEquals("保留新预约的伙伴", AgentType.CLAUDE, f.coordinator.scheduledReview("s1"))
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `读取提交记录期间关闭恢复会话不会让旧请求修改新预约`() = runBlocking {
        Fixture().use { f ->
            f.review()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.beforeRead = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            try {
                val oldRequest = async(Dispatchers.Default) { f.schedule() }
                assertTrue("旧请求正在读取提交记录", entered.await(5, TimeUnit.SECONDS))
                f.coordinator.sessionClosed("s1")
                assertEquals(PeerScheduleResult.SCHEDULED, f.coordinator.scheduleReviewOnce("s1", AgentType.CLAUDE, "恢复后的任务"))
                release.countDown()
                assertEquals(PeerScheduleResult.CLOSED, withTimeout(5.seconds) { oldRequest.await() })
                assertEquals("恢复后的预约不受旧请求影响", AgentType.CLAUDE, f.coordinator.scheduledReview("s1"))
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `读取提交记录期间关闭结对使尚未落地的预约失效`() = runBlocking {
        Fixture().use { f ->
            f.review()
            val tools = PeerSessionTools(
                AgentType.CLAUDE,
                sessionKey = { "s1" },
                coordinator = { f.coordinator },
                enabledAgents = { listOf(AgentType.CLAUDE, AgentType.CODEX, AgentType.PI) },
                configuredPartner = { null },
            )
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.beforeRead = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            try {
                val oldRequest = async(Dispatchers.Default) { tools.reviewOnce("pi", null) }
                assertTrue("旧请求正在读取提交记录", entered.await(5, TimeUnit.SECONDS))
                assertEquals("already_disabled", tools.disable().value["outcome"].asString)
                release.countDown()
                val result = withTimeout(5.seconds) { oldRequest.await() }
                assertEquals("取消后旧请求不能重新预约", "skipped_state_changed", result.value["outcome"].asString)
                assertNull(f.coordinator.scheduledReview("s1"))
                f.beforeRead = null
                f.input(f.feedback)
                assertEquals("取消预约不能解除反馈保护", PeerScheduleResult.FEEDBACK_TURN, f.schedule())
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `项目释放后预约被拒绝并清理反馈保护`() = runBlocking {
        Fixture(autoInject = true).use { f ->
            f.review()
            f.coordinator.dispose()
            assertFalse(f.coordinator.isHandlingOneShotFeedback("s1"))
            assertEquals(PeerScheduleResult.CLOSED, f.schedule())
        }
    }

    private class Fixture(
        private val agent: AgentType = AgentType.CLAUDE,
        autoInject: Boolean = false,
        acceptStage: Boolean = true,
    ) : AutoCloseable {
        val feedback = "请检查空值 并补充测试"
        var reply = feedback
        val calls = AtomicInteger()
        val staged = ConcurrentLinkedQueue<String>()
        val executions = AtomicInteger()
        @Volatile var beforeRead: (() -> Unit)? = null
        private val file = Files.createTempFile("imux-feedback-lifecycle-", ".jsonl")
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val session = AgentSession("s1", "test", agent, Instant.now(), Instant.now(), file)
        private val model = SessionListModel(scan = { listOf(session) }, clock = Instant::now).also { it.refresh() }
        private val builder = proxy(TerminalSendTextBuilder::class.java) { method, args ->
            when (method) {
                "trySend" -> { staged += args!![0] as String; acceptStage }
                "shouldExecute" -> { executions.incrementAndGet(); null }
                else -> null
            }
        }
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
            peerAutoInject = { autoInject },
            runCli = { _, _, _, _, _, _, _, _ -> calls.incrementAndGet(); reply },
            resolveMcpConfig = { PeerMcpConfig(null, null, null) },
            buildPrompt = { _, _ -> "test prompt" },
            sendAutoFeedback = { _, _ -> true },
            onFeedbackUndelivered = { _, _ -> },
            onOneShotPassed = {},
            readUserInput = {
                beforeRead?.invoke()
                latestPeerUserInput(it)
            },
        )

        init { input("实现功能并审查一次") }

        suspend fun review(key: String = "s1") {
            coordinator.reviewOnce(key, AgentType.CODEX, null)
            awaitReviews()
        }

        suspend fun awaitReviews() {
            withTimeout(5.seconds) { scope.coroutineContext[Job]!!.children.toList().joinAll() }
        }

        suspend fun schedule(key: String = "s1") = coordinator.scheduleReviewOnce(key, AgentType.PI, "新的要求")

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
