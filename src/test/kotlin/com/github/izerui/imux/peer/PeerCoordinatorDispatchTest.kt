package com.github.izerui.imux.peer

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.session.SessionListModel
import com.github.izerui.imux.terminal.IdeaMcpEndpoint
import com.intellij.openapi.project.Project
import com.intellij.terminal.frontend.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.plugins.terminal.view.TerminalSendTextBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

class PeerCoordinatorDispatchTest {

    @Test
    fun `Claude Codex Pi 的反馈分别经过自动注入`() {
        val accepted = ConcurrentLinkedQueue<String>()
        val latch = CountDownLatch(3)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = PeerCoordinator(
            project = testProject(),
            projectPath = "/tmp/test-project",
            model = SessionListModel(scan = { emptyList() }, clock = Instant::now),
            viewOf = { null },
            coroutineScope = scope,
            shell = "/bin/zsh",
            edtDispatcher = Dispatchers.Unconfined,
            peerMaxRounds = { 5 },
            peerAutoInject = { true },
            runCli = { _, _, _, _, _, _, _, _ -> "feedback" },
            resolveMcpConfig = { PeerMcpConfig(null, null, null) },
            buildPrompt = { _, _ -> "test prompt" },
            sendAutoFeedback = { sessionKey, feedback ->
                accepted.add("$sessionKey: $feedback")
                latch.countDown()
                true
            },
        )
        try {
            coordinator.bind("s-claude", AgentType.CLAUDE)
            coordinator.bind("s-codex", AgentType.CODEX)
            coordinator.bind("s-pi", AgentType.PI)
            coordinator.onTurnCompleted("s-claude")
            coordinator.onTurnCompleted("s-codex")
            coordinator.onTurnCompleted("s-pi")
            assertTrue("三种反馈应完成分派", latch.await(5, TimeUnit.SECONDS))
            assertTrue("Claude 反馈应被受理", "s-claude: feedback" in accepted)
            assertTrue("Codex 反馈应被受理", "s-codex: feedback" in accepted)
            assertTrue("Pi 反馈应被受理", "s-pi: feedback" in accepted)
        } finally {
            coordinator.dispose()
            scope.cancel()
        }
    }

    /**
     * 为什么必须是 require 而不是 use（手工抓包结论，非本测试守住的行为）：
     * `useBracketedPasteMode()` 只是尽力而为，终端没开 `?2004h` 时它会静默退化成裸发整段文本，
     * 而裸发会命中 Claude Code 的 byte-run 启发式被当成一次粘贴、末尾回车一并被吞，
     * 文本永远停在输入框，调用方却以为成功了。
     *
     * 这里只能钉住：调用形态是 require + shouldExecute + trySend，且 trySend 的结果被如实返回
     * ——代理观察不到括号粘贴模式，也看不到 PTY 字节。
     */
    @Test
    fun `trySend 返回 false 时如实返回 false 并按 require 与 shouldExecute 调用`() {
        val calls = mutableListOf<String>()
        val builder = Proxy.newProxyInstance(
            TerminalSendTextBuilder::class.java.classLoader,
            arrayOf(TerminalSendTextBuilder::class.java),
        ) { proxy, method, args ->
            calls += method.name
            when (method.name) {
                "requireBracketedPasteMode", "shouldExecute" -> proxy
                "trySend" -> {
                    assertEquals("first line\nsecond line", args?.single())
                    false
                }
                else -> error("不应调用 ${method.name}")
            }
        } as TerminalSendTextBuilder

        assertFalse(trySendPeerFeedback(builder, "first line\nsecond line"))
        assertEquals(
            listOf("requireBracketedPasteMode", "shouldExecute", "trySend"),
            calls,
        )
    }

    /**
     * shouldExecute 与正文同一次 trySend 发出，平台由此生成 `ESC[200~ 正文 ESC[201~ \r`、
     * 回车落在括号外——那是手工抓包确认的字节形态，**不是这条单测守住的行为**。
     * 这里只钉住调用形态与返回值。
     */
    @Test
    fun `trySend 返回 true 时如实返回 true 并按 require 与 shouldExecute 调用`() {
        val calls = mutableListOf<String>()
        val builder = Proxy.newProxyInstance(
            TerminalSendTextBuilder::class.java.classLoader,
            arrayOf(TerminalSendTextBuilder::class.java),
        ) { proxy, method, args ->
            calls += method.name
            when (method.name) {
                "requireBracketedPasteMode", "shouldExecute" -> proxy
                "trySend" -> {
                    assertEquals("first line\nsecond line", args?.single())
                    true
                }
                else -> error("不应调用 ${method.name}")
            }
        } as TerminalSendTextBuilder

        assertTrue(trySendPeerFeedback(builder, "first line\nsecond line"))
        assertEquals(
            listOf("requireBracketedPasteMode", "shouldExecute", "trySend"),
            calls,
        )
    }

    @Test
    fun `终端稍后就绪时只发送一次反馈并登记轮次`() {
        val attempts = AtomicInteger()
        val firstAttempt = CountDownLatch(1)
        val accepted = CountDownLatch(1)
        val cliCalls = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = PeerCoordinator(
            project = testProject(),
            projectPath = "/tmp/test-project",
            model = SessionListModel(scan = { emptyList() }, clock = Instant::now),
            viewOf = { null },
            coroutineScope = scope,
            shell = "/bin/zsh",
            edtDispatcher = Dispatchers.Unconfined,
            peerMaxRounds = { 1 },
            peerAutoInject = { true },
            runCli = { _, _, _, _, _, _, _, _ ->
                cliCalls.incrementAndGet()
                "feedback"
            },
            resolveMcpConfig = { PeerMcpConfig(null, null, null) },
            buildPrompt = { _, _ -> "test prompt" },
            sendAutoFeedback = { _, feedback ->
                assertEquals("feedback", feedback)
                if (attempts.incrementAndGet() == 1) {
                    firstAttempt.countDown()
                    false
                } else {
                    accepted.countDown()
                    true
                }
            },
        )
        try {
            coordinator.bind("s-retry", AgentType.CLAUDE)
            coordinator.onTurnCompleted("s-retry")
            assertTrue("应看到第一次未就绪", firstAttempt.await(5, TimeUnit.SECONDS))
            assertTrue("等待就绪期间应保持审查状态", coordinator.status("s-retry")!!.running)
            val review = scope.coroutineContext[Job]!!.children.single()
            assertTrue("稍后应受理反馈", accepted.await(5, TimeUnit.SECONDS))
            runBlocking { withTimeout(5_000) { review.join() } }
            assertEquals("仅重试一次", 2, attempts.get())
            coordinator.onTurnCompleted("s-retry")
            assertEquals("受理后应计入轮次上限", 1, cliCalls.get())
        } finally {
            coordinator.dispose()
            scope.cancel()
        }
    }

    /**
     * 等待没有时限，唯一"没地方放"的情形是终端标签已经没了。
     * 这里只钉住"交给兜底、且带上原文"；兜底具体做什么（弹通知）由默认实现负责。
     */
    @Test
    fun `终端已消失时把反馈交给兜底而不是静默丢弃`() {
        val undelivered = ConcurrentLinkedQueue<String>()
        val done = CountDownLatch(1)
        val cliCalls = AtomicInteger()
        val twoReviews = CountDownLatch(2)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = PeerCoordinator(
            project = testProject(),
            projectPath = "/tmp/test-project",
            model = SessionListModel(scan = { emptyList() }, clock = Instant::now),
            viewOf = { null },
            coroutineScope = scope,
            shell = "/bin/zsh",
            edtDispatcher = Dispatchers.Unconfined,
            peerMaxRounds = { 5 },
            peerAutoInject = { true },
            runCli = { _, _, _, _, _, _, _, _ ->
                cliCalls.incrementAndGet()
                twoReviews.countDown()
                "feedback"
            },
            resolveMcpConfig = { PeerMcpConfig(null, null, null) },
            buildPrompt = { _, _ -> "test prompt" },
            onFeedbackUndelivered = { sessionKey, feedback ->
                undelivered.add("$sessionKey: $feedback")
                done.countDown()
            },
        )
        try {
            coordinator.bind("s-undeliver", AgentType.CLAUDE)
            coordinator.onTurnCompleted("s-undeliver")
            assertTrue("重试耗尽后应触发兜底", done.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("s-undeliver: feedback"), undelivered.toList())
            val review = scope.coroutineContext[Job]!!.children.singleOrNull()
            if (review != null) runBlocking { withTimeout(5_000) { review.join() } }
            // 没送达就不该计入轮次上限，否则用户白白少一轮审查。
            coordinator.onTurnCompleted("s-undeliver")
            assertTrue("未送达不应计入轮次，应能再次审查", twoReviews.await(5, TimeUnit.SECONDS))
        } finally {
            coordinator.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `新用户轮次取消等待时不再重试反馈`() {
        val attempts = AtomicInteger()
        val firstAttempt = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = PeerCoordinator(
            project = testProject(),
            projectPath = "/tmp/test-project",
            model = SessionListModel(scan = { emptyList() }, clock = Instant::now),
            viewOf = { null },
            coroutineScope = scope,
            shell = "/bin/zsh",
            edtDispatcher = Dispatchers.Unconfined,
            peerMaxRounds = { 5 },
            peerAutoInject = { true },
            runCli = { _, _, _, _, _, _, _, _ -> "feedback" },
            resolveMcpConfig = { PeerMcpConfig(null, null, null) },
            buildPrompt = { _, _ -> "test prompt" },
            sendAutoFeedback = { _, _ ->
                attempts.incrementAndGet()
                firstAttempt.countDown()
                false
            },
        )
        try {
            coordinator.bind("s-cancel", AgentType.CLAUDE)
            coordinator.onTurnCompleted("s-cancel")
            assertTrue("应开始等待终端就绪", firstAttempt.await(5, TimeUnit.SECONDS))
            val review = scope.coroutineContext[Job]!!.children.single()
            coordinator.onTurnStarted("s-cancel")
            runBlocking { withTimeout(5_000) { review.join() } }
            assertEquals("取消后不得再次发送旧反馈", 1, attempts.get())
        } finally {
            coordinator.dispose()
            scope.cancel()
        }
    }

    private data class CapturedCliCall(
        val agentType: AgentType,
        val command: List<String>,
        val environment: Map<String, String>,
    )

    private fun buildCoordinator(
        mcpConfig: PeerMcpConfig,
        capture: AtomicReference<CapturedCliCall>,
        latch: CountDownLatch,
        scope: CoroutineScope,
    ): PeerCoordinator {
        return PeerCoordinator(
            project = testProject(),
            projectPath = "/tmp/test-project",
            model = SessionListModel(scan = { emptyList() }, clock = Instant::now),
            viewOf = { null },
            coroutineScope = scope,
            shell = "/bin/zsh",
            edtDispatcher = Dispatchers.Unconfined,
            peerMaxRounds = { 5 },
            peerAutoInject = { true },
            runCli = { agentType, command, _, _, environment, _, _, _ ->
                capture.set(CapturedCliCall(agentType, command, environment))
                latch.countDown()
                null
            },
            resolveMcpConfig = { mcpConfig },
            buildPrompt = { task, conversation -> "test prompt" },
        )
    }

    @Test
    fun `onTurnCompleted 触发 runCli 时传入 endpoint 非 null 的 MCP 配置`() {
        val endpoint = IdeaMcpEndpoint(64342, "/workspace")
        val piScript = Path.of("/tmp/pi-imux-idea-mcp.js")
        val mcpConfig = PeerMcpConfig(endpoint, "Guide", piScript)
        val capture = AtomicReference<CapturedCliCall>()
        val latch = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val coordinator = buildCoordinator(mcpConfig, capture, latch, scope)
        try {
            coordinator.bind("session-1", AgentType.CLAUDE)
            coordinator.onTurnCompleted("session-1")
            assertTrue("runCli 应在 5 秒内被调用", latch.await(5, TimeUnit.SECONDS))

            val call = capture.get()
            assertNotNull("应捕获到 runCli 调用", call)
            assertEquals(AgentType.CLAUDE, call.agentType)
            assertTrue("command 应含 --mcp-config", call.command.last().contains("--mcp-config"))
            assertTrue("command 应含端点 URL", call.command.last().contains(endpoint.url))
        } finally {
            coordinator.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `onTurnCompleted 触发 runCli 时传入 endpoint 为 null 的 MCP 配置`() {
        val mcpConfig = PeerMcpConfig(null, null, null)
        val capture = AtomicReference<CapturedCliCall>()
        val latch = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val coordinator = buildCoordinator(mcpConfig, capture, latch, scope)
        try {
            coordinator.bind("session-2", AgentType.CODEX)
            coordinator.onTurnCompleted("session-2")
            assertTrue("runCli 应在 5 秒内被调用", latch.await(5, TimeUnit.SECONDS))

            val call = capture.get()
            assertNotNull(call)
            assertEquals(AgentType.CODEX, call.agentType)
            assertFalse("command 不应含 MCP", call.command.last().contains("mcp_servers.idea"))
            assertTrue("environment 应为空", call.environment.isEmpty())
        } finally {
            coordinator.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `onTurnCompleted 触发 Pi runCli 时有 endpoint 但无扩展脚本`() {
        val endpoint = IdeaMcpEndpoint(64342, "/workspace")
        val mcpConfig = PeerMcpConfig(endpoint, "Guide", null)
        val capture = AtomicReference<CapturedCliCall>()
        val latch = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val coordinator = buildCoordinator(mcpConfig, capture, latch, scope)
        try {
            coordinator.bind("session-3", AgentType.PI)
            coordinator.onTurnCompleted("session-3")
            assertTrue("runCli 应在 5 秒内被调用", latch.await(5, TimeUnit.SECONDS))

            val call = capture.get()
            assertNotNull(call)
            assertEquals(AgentType.PI, call.agentType)
            assertFalse("command 不应含 idea_mcp", call.command.last().contains("idea_mcp"))
            assertEquals("environment 应含端点 URL", endpoint.url, call.environment["IMUX_IDEA_MCP_URL"])
        } finally {
            coordinator.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `达到自动注入上限后不再启动副驾驶`() {
        val mcpConfig = PeerMcpConfig(null, null, null)
        val callCount = java.util.concurrent.atomic.AtomicInteger(0)
        val feedbackSent = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val coordinator = PeerCoordinator(
            project = testProject(),
            projectPath = "/tmp/test-project",
            model = SessionListModel(scan = { emptyList() }, clock = Instant::now),
            viewOf = { null },
            coroutineScope = scope,
            shell = "/bin/zsh",
            edtDispatcher = Dispatchers.Unconfined,
            peerMaxRounds = { 1 },
            peerAutoInject = { true },
            runCli = { _, _, _, _, _, _, _, _ ->
                callCount.incrementAndGet()
                "feedback"
            },
            resolveMcpConfig = { mcpConfig },
            buildPrompt = { _, _ -> "test prompt" },
            sendAutoFeedback = { _, _ ->
                feedbackSent.countDown()
                true
            },
        )

        try {
            coordinator.bind("s-limit", AgentType.CLAUDE)
            coordinator.onTurnCompleted("s-limit")
            assertTrue("第一轮反馈应自动发送", feedbackSent.await(5, TimeUnit.SECONDS))
            val firstReview = scope.coroutineContext[Job]!!.children.single()
            runBlocking { withTimeout(5_000) { firstReview.join() } }

            coordinator.onTurnCompleted("s-limit")

            assertEquals("达到上限后不应再次启动副驾驶", 1, callCount.get())
        } finally {
            coordinator.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `达到上限的自动反馈轮次停止而下一次用户轮次恢复副驾驶`() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val firstCliStarted = CountDownLatch(1)
        val releaseFirstCli = CountDownLatch(1)
        val firstFeedback = CountDownLatch(1)
        val resumedReview = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = PeerCoordinator(
            project = testProject(),
            projectPath = "/tmp/test-project",
            model = SessionListModel(scan = { emptyList() }, clock = Instant::now),
            viewOf = { null },
            coroutineScope = scope,
            shell = "/bin/zsh",
            edtDispatcher = Dispatchers.Unconfined,
            peerMaxRounds = { 1 },
            peerAutoInject = { true },
            runCli = { _, _, _, _, _, _, _, _ ->
                if (calls.incrementAndGet() == 1) {
                    firstCliStarted.countDown()
                    releaseFirstCli.await(5, TimeUnit.SECONDS)
                } else {
                    resumedReview.countDown()
                }
                "feedback"
            },
            resolveMcpConfig = { PeerMcpConfig(null, null, null) },
            buildPrompt = { _, _ -> "test prompt" },
            sendAutoFeedback = { _, _ ->
                firstFeedback.countDown()
                true
            },
        )

        try {
            coordinator.bind("s-limit-reset", AgentType.CLAUDE)
            coordinator.onTurnCompleted("s-limit-reset")
            assertTrue("首轮 CLI 应启动", firstCliStarted.await(5, TimeUnit.SECONDS))
            val firstReview = scope.coroutineContext[Job]!!.children.single()
            releaseFirstCli.countDown()
            assertTrue("首轮反馈应发送", firstFeedback.await(5, TimeUnit.SECONDS))
            runBlocking { withTimeout(5_000) { firstReview.join() } }

            coordinator.onTurnStarted("s-limit-reset")
            coordinator.onTurnCompleted("s-limit-reset")
            assertEquals("上限轮次不得启动副驾驶 CLI", 1, calls.get())
            assertFalse("上限轮次不得保留运行状态", coordinator.status("s-limit-reset")!!.running)

            coordinator.onTurnStarted("s-limit-reset")
            coordinator.onTurnCompleted("s-limit-reset")
            assertTrue("后续用户轮次应重新启动副驾驶", resumedReview.await(5, TimeUnit.SECONDS))
            assertEquals("只在用户新轮次增加一次调用", 2, calls.get())
        } finally {
            releaseFirstCli.countDown()
            coordinator.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `第二个 onTurnCompleted 取消旧副驾驶并触发新一轮`() {
        val mcpConfig = PeerMcpConfig(null, null, null)
        val callCount = java.util.concurrent.atomic.AtomicInteger(0)
        val firstCliStarted = CountDownLatch(1)
        val firstCliBlocked = CountDownLatch(1)
        val secondCliFinished = CountDownLatch(1)
        val firstRunCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val coordinator = PeerCoordinator(
            project = testProject(),
            projectPath = "/tmp/test-project",
            model = SessionListModel(scan = { emptyList() }, clock = Instant::now),
            viewOf = { null },
            coroutineScope = scope,
            shell = "/bin/zsh",
            edtDispatcher = Dispatchers.Unconfined,
            peerMaxRounds = { 5 },
            peerAutoInject = { true },
            runCli = { agentType, _, _, _, _, _, onProcess, _ ->
                val n = callCount.incrementAndGet()
                if (n == 1) {
                    firstCliStarted.countDown()
                    firstCliBlocked.await(5, TimeUnit.SECONDS)
                    null
                } else {
                    secondCliFinished.countDown()
                    "feedback from new run"
                }
            },
            resolveMcpConfig = { mcpConfig },
            buildPrompt = { _, _ -> "test prompt" },
        )

        try {
            coordinator.bind("s1", AgentType.CLAUDE)
            coordinator.onTurnCompleted("s1")
            assertTrue("第一轮 runCli 应启动", firstCliStarted.await(5, TimeUnit.SECONDS))

            val statusDuringFirst = coordinator.status("s1")
            assertNotNull("旧 run 运行期间应有状态", statusDuringFirst)
            assertTrue("旧 run 运行期间应显示 running", statusDuringFirst!!.running)

            coordinator.onTurnCompleted("s1")
            firstCliBlocked.countDown()

            assertTrue("第二轮 runCli 应完成", secondCliFinished.await(5, TimeUnit.SECONDS))
            assertEquals("runCli 应被调用两次", 2, callCount.get())
            assertEquals(
                "绑定的 targetAgentType 不应被破坏",
                AgentType.CLAUDE,
                coordinator.boundTarget("s1"),
            )
        } finally {
            coordinator.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `新轮次到达时旧 run 的反馈不注入主会话`() {
        val mcpConfig = PeerMcpConfig(null, null, null)
        val callCount = java.util.concurrent.atomic.AtomicInteger(0)
        val firstCliStarted = CountDownLatch(1)
        val firstCliBlocked = CountDownLatch(1)
        val secondCliFinished = CountDownLatch(1)
        val injected = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val feedbackSent = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val coordinator = PeerCoordinator(
            project = testProject(),
            projectPath = "/tmp/test-project",
            model = SessionListModel(scan = { emptyList() }, clock = Instant::now),
            viewOf = { null },
            coroutineScope = scope,
            shell = "/bin/zsh",
            edtDispatcher = Dispatchers.Unconfined,
            peerMaxRounds = { 5 },
            peerAutoInject = { true },
            runCli = { _, _, _, prompt, _, _, _, _ ->
                val n = callCount.incrementAndGet()
                if (n == 1) {
                    firstCliStarted.countDown()
                    firstCliBlocked.await(5, TimeUnit.SECONDS)
                    "stale feedback from old run"
                } else {
                    secondCliFinished.countDown()
                    "fresh feedback from new run"
                }
            },
            resolveMcpConfig = { mcpConfig },
            buildPrompt = { _, _ -> "test prompt" },
            sendAutoFeedback = { sessionKey, feedback ->
                injected.add("$sessionKey: $feedback")
                feedbackSent.countDown()
                true
            },
        )

        try {
            coordinator.bind("s1", AgentType.CLAUDE)
            coordinator.onTurnCompleted("s1")
            assertTrue("第一轮 runCli 应启动", firstCliStarted.await(5, TimeUnit.SECONDS))
            val oldReview = scope.coroutineContext[Job]!!.children.single()

            coordinator.onTurnCompleted("s1")
            firstCliBlocked.countDown()

            assertTrue("第二轮 runCli 应完成", secondCliFinished.await(5, TimeUnit.SECONDS))
            assertTrue("新轮次反馈应发送", feedbackSent.await(5, TimeUnit.SECONDS))
            runBlocking { withTimeout(5_000) { oldReview.join() } }

            assertEquals("只应注入新轮次反馈", listOf("s1: fresh feedback from new run"), injected.toList())
            assertEquals(
                "绑定的 targetAgentType 不应被破坏",
                AgentType.CLAUDE,
                coordinator.boundTarget("s1"),
            )
        } finally {
            coordinator.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `unbind 加 bind 切换 AgentType 后旧 run 不使用新类型调用 CLI`() {
        val mcpConfig = PeerMcpConfig(null, null, null)
        val callCount = java.util.concurrent.atomic.AtomicInteger(0)
        val firstCliStarted = CountDownLatch(1)
        val firstCliBlocked = CountDownLatch(1)
        val secondCliFinished = CountDownLatch(1)
        val cliAgentTypes = java.util.concurrent.ConcurrentLinkedQueue<AgentType>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val coordinator = PeerCoordinator(
            project = testProject(),
            projectPath = "/tmp/test-project",
            model = SessionListModel(scan = { emptyList() }, clock = Instant::now),
            viewOf = { null },
            coroutineScope = scope,
            shell = "/bin/zsh",
            edtDispatcher = Dispatchers.Unconfined,
            peerMaxRounds = { 5 },
            peerAutoInject = { true },
            runCli = { agentType, _, _, _, _, _, _, _ ->
                val n = callCount.incrementAndGet()
                cliAgentTypes.add(agentType)
                if (n == 1) {
                    firstCliStarted.countDown()
                    firstCliBlocked.await(5, TimeUnit.SECONDS)
                    null
                } else {
                    secondCliFinished.countDown()
                    null
                }
            },
            resolveMcpConfig = { mcpConfig },
            buildPrompt = { _, _ -> "test prompt" },
        )

        try {
            coordinator.bind("s1", AgentType.CLAUDE)
            coordinator.onTurnCompleted("s1")
            assertTrue("第一轮 runCli 应启动", firstCliStarted.await(5, TimeUnit.SECONDS))

            coordinator.unbind("s1")
            coordinator.bind("s1", AgentType.CODEX)
            coordinator.onTurnCompleted("s1")
            firstCliBlocked.countDown()

            assertTrue("第二轮 runCli 应完成", secondCliFinished.await(5, TimeUnit.SECONDS))

            assertEquals(
                "切换后 boundTarget 应为 CODEX",
                AgentType.CODEX,
                coordinator.boundTarget("s1"),
            )

            val types = cliAgentTypes.toList()
            assertEquals("第一次 CLI 应使用 CLAUDE", AgentType.CLAUDE, types[0])
            assertEquals("第二次 CLI 应使用 CODEX", AgentType.CODEX, types[1])
        } finally {
            coordinator.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `unbind 加 bind 后旧 run 因 generation 不匹配退出不调用 CLI`() {
        val mcpConfig = PeerMcpConfig(null, null, null)
        val callCount = java.util.concurrent.atomic.AtomicInteger(0)
        val promptStarted = CountDownLatch(1)
        val promptBlocked = CountDownLatch(1)
        val secondCliFinished = CountDownLatch(1)
        val cliAgentTypes = java.util.concurrent.ConcurrentLinkedQueue<AgentType>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val coordinator = PeerCoordinator(
            project = testProject(),
            projectPath = "/tmp/test-project",
            model = SessionListModel(scan = { emptyList() }, clock = Instant::now),
            viewOf = { null },
            coroutineScope = scope,
            shell = "/bin/zsh",
            edtDispatcher = Dispatchers.Unconfined,
            peerMaxRounds = { 5 },
            peerAutoInject = { true },
            runCli = { agentType, _, _, _, _, _, _, _ ->
                cliAgentTypes.add(agentType)
                callCount.incrementAndGet()
                secondCliFinished.countDown()
                null
            },
            resolveMcpConfig = { mcpConfig },
            buildPrompt = { _, _ ->
                val n = callCount.get()
                if (n == 0) {
                    promptStarted.countDown()
                    promptBlocked.await(5, TimeUnit.SECONDS)
                }
                "test prompt"
            },
        )

        try {
            coordinator.bind("s1", AgentType.CLAUDE)
            coordinator.onTurnCompleted("s1")
            assertTrue("第一轮 buildPrompt 应启动", promptStarted.await(5, TimeUnit.SECONDS))

            coordinator.unbind("s1")
            coordinator.bind("s1", AgentType.CODEX)
            coordinator.onTurnCompleted("s1")
            promptBlocked.countDown()

            assertTrue("应有 runCli 完成", secondCliFinished.await(5, TimeUnit.SECONDS))

            Thread.sleep(200)

            assertEquals(
                "切换后 boundTarget 应为 CODEX",
                AgentType.CODEX,
                coordinator.boundTarget("s1"),
            )
            assertTrue(
                "调用 CLI 的应为 CODEX（旧 run 应因 generation 不匹配而退出）",
                cliAgentTypes.all { it == AgentType.CODEX },
            )
        } finally {
            coordinator.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `unbind 加 bind 后 onTurnCompleted 使用新 guard 和新 generation`() {
        val mcpConfig = PeerMcpConfig(null, null, null)
        val callCount = java.util.concurrent.atomic.AtomicInteger(0)
        val firstCliStarted = CountDownLatch(1)
        val firstCliBlocked = CountDownLatch(1)
        val secondCliFinished = CountDownLatch(1)
        val cliAgentTypes = java.util.concurrent.ConcurrentLinkedQueue<AgentType>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val coordinator = PeerCoordinator(
            project = testProject(),
            projectPath = "/tmp/test-project",
            model = SessionListModel(scan = { emptyList() }, clock = Instant::now),
            viewOf = { null },
            coroutineScope = scope,
            shell = "/bin/zsh",
            edtDispatcher = Dispatchers.Unconfined,
            peerMaxRounds = { 5 },
            peerAutoInject = { true },
            runCli = { agentType, _, _, _, _, _, _, _ ->
                val n = callCount.incrementAndGet()
                cliAgentTypes.add(agentType)
                if (n == 1) {
                    firstCliStarted.countDown()
                    firstCliBlocked.await(5, TimeUnit.SECONDS)
                    null
                } else {
                    secondCliFinished.countDown()
                    null
                }
            },
            resolveMcpConfig = { mcpConfig },
            buildPrompt = { _, _ -> "test prompt" },
        )

        try {
            coordinator.bind("s1", AgentType.CLAUDE)
            coordinator.onTurnCompleted("s1")
            assertTrue("CLAUDE runCli 应启动", firstCliStarted.await(5, TimeUnit.SECONDS))

            coordinator.unbind("s1")
            coordinator.bind("s1", AgentType.CODEX)
            coordinator.onTurnCompleted("s1")
            firstCliBlocked.countDown()

            assertTrue("CODEX runCli 应完成", secondCliFinished.await(5, TimeUnit.SECONDS))

            Thread.sleep(200)

            assertEquals(
                "boundTarget 应为 CODEX",
                AgentType.CODEX,
                coordinator.boundTarget("s1"),
            )
            val types = cliAgentTypes.toList()
            assertEquals("第一次 CLI 应使用 CLAUDE", AgentType.CLAUDE, types[0])
            assertEquals("第二次 CLI 应使用 CODEX", AgentType.CODEX, types[1])
        } finally {
            coordinator.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `cancelCurrentRun 取消运行中的副驾驶`() {
        val mcpConfig = PeerMcpConfig(null, null, null)
        val cliStarted = CountDownLatch(1)
        val cliBlocked = CountDownLatch(1)
        val callCount = java.util.concurrent.atomic.AtomicInteger(0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val coordinator = PeerCoordinator(
            project = testProject(),
            projectPath = "/tmp/test-project",
            model = SessionListModel(scan = { emptyList() }, clock = Instant::now),
            viewOf = { null },
            coroutineScope = scope,
            shell = "/bin/zsh",
            edtDispatcher = Dispatchers.Unconfined,
            peerMaxRounds = { 5 },
            peerAutoInject = { true },
            runCli = { _, _, _, _, _, _, _, _ ->
                callCount.incrementAndGet()
                cliStarted.countDown()
                cliBlocked.await(5, TimeUnit.SECONDS)
                "feedback"
            },
            resolveMcpConfig = { mcpConfig },
            buildPrompt = { _, _ -> "test prompt" },
        )

        try {
            coordinator.bind("s1", AgentType.CLAUDE)
            coordinator.onTurnCompleted("s1")
            assertTrue("runCli 应启动", cliStarted.await(5, TimeUnit.SECONDS))

            coordinator.cancelCurrentRun("s1")
            cliBlocked.countDown()

            Thread.sleep(300)

            val status = coordinator.status("s1")
            assertNotNull(status)
            assertFalse("取消后不应显示 running", status!!.running)
            assertEquals("runCli 只应调用一次", 1, callCount.get())
        } finally {
            coordinator.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `被取消的旧 run 在 buildPrompt 后不调用 runCli`() {
        val mcpConfig = PeerMcpConfig(null, null, null)
        val promptCount = java.util.concurrent.atomic.AtomicInteger(0)
        val promptReached = CountDownLatch(1)
        val promptGate = CountDownLatch(1)
        val cliCalls = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val allDone = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val coordinator = PeerCoordinator(
            project = testProject(),
            projectPath = "/tmp/test-project",
            model = SessionListModel(scan = { emptyList() }, clock = Instant::now),
            viewOf = { null },
            coroutineScope = scope,
            shell = "/bin/zsh",
            edtDispatcher = Dispatchers.Unconfined,
            peerMaxRounds = { 5 },
            peerAutoInject = { true },
            runCli = { _, _, _, prompt, _, _, _, _ ->
                cliCalls.add(prompt)
                allDone.countDown()
                null
            },
            resolveMcpConfig = { mcpConfig },
            buildPrompt = { _, _ ->
                val n = promptCount.incrementAndGet()
                if (n == 1) {
                    promptReached.countDown()
                    promptGate.await(5, TimeUnit.SECONDS)
                }
                "prompt-from-run-$n"
            },
        )

        try {
            coordinator.bind("s1", AgentType.CLAUDE)
            coordinator.onTurnCompleted("s1")
            assertTrue("旧 run 应到达 buildPrompt", promptReached.await(5, TimeUnit.SECONDS))

            coordinator.onTurnCompleted("s1")
            promptGate.countDown()

            assertTrue("应有 runCli 完成", allDone.await(5, TimeUnit.SECONDS))
            Thread.sleep(300)

            assertEquals("runCli 只应调用一次（来自新 run）", 1, cliCalls.size)
            assertEquals(
                "CLI 应使用新 run 的 prompt",
                "prompt-from-run-2",
                cliCalls.single(),
            )
        } finally {
            coordinator.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `取消当前副驾驶后运行状态清除`() {
        val mcpConfig = PeerMcpConfig(null, null, null)
        val callCount = java.util.concurrent.atomic.AtomicInteger(0)
        val cliStarted = CountDownLatch(1)
        val cliBlocked = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val coordinator = PeerCoordinator(
            project = testProject(),
            projectPath = "/tmp/test-project",
            model = SessionListModel(scan = { emptyList() }, clock = Instant::now),
            viewOf = { null },
            coroutineScope = scope,
            shell = "/bin/zsh",
            edtDispatcher = Dispatchers.Unconfined,
            peerMaxRounds = { 5 },
            peerAutoInject = { true },
            runCli = { _, _, _, _, _, _, _, _ ->
                callCount.incrementAndGet()
                cliStarted.countDown()
                cliBlocked.await(5, TimeUnit.SECONDS)
                null
            },
            resolveMcpConfig = { mcpConfig },
            buildPrompt = { _, _ -> "test prompt" },
        )

        try {
            coordinator.bind("s1", AgentType.CLAUDE)
            coordinator.onTurnCompleted("s1")
            assertTrue("runCli 应启动", cliStarted.await(5, TimeUnit.SECONDS))

            coordinator.cancelCurrentRun("s1")
            cliBlocked.countDown()
            Thread.sleep(300)

            val statusAfterCancel = coordinator.status("s1")
            assertNotNull(statusAfterCancel)
            assertFalse("取消后不应 running", statusAfterCancel!!.running)
            assertEquals("runCli 只应调用一次", 1, callCount.get())
        } finally {
            coordinator.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `旧 run 在 runCli 内被取消后其反馈不被注入`() {
        val mcpConfig = PeerMcpConfig(null, null, null)
        val callCount = java.util.concurrent.atomic.AtomicInteger(0)
        val oldRunInCli = CountDownLatch(1)
        val oldRunGate = CountDownLatch(1)
        val newRunDone = CountDownLatch(1)
        val injectedFeedback = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val coordinator = PeerCoordinator(
            project = testProject(),
            projectPath = "/tmp/test-project",
            model = SessionListModel(scan = { emptyList() }, clock = Instant::now),
            viewOf = { null },
            coroutineScope = scope,
            shell = "/bin/zsh",
            edtDispatcher = Dispatchers.Unconfined,
            peerMaxRounds = { 5 },
            peerAutoInject = { true },
            runCli = { _, _, _, _, _, _, _, _ ->
                val n = callCount.incrementAndGet()
                if (n == 1) {
                    oldRunInCli.countDown()
                    oldRunGate.await(5, TimeUnit.SECONDS)
                    "stale feedback"
                } else {
                    newRunDone.countDown()
                    "fresh feedback"
                }
            },
            resolveMcpConfig = { mcpConfig },
            buildPrompt = { _, _ -> "test prompt" },
            sendAutoFeedback = { _, feedback ->
                injectedFeedback.add(feedback)
                true
            },
        )

        try {
            coordinator.bind("s1", AgentType.CLAUDE)
            coordinator.onTurnCompleted("s1")
            assertTrue("旧 run 应进入 runCli", oldRunInCli.await(5, TimeUnit.SECONDS))

            coordinator.onTurnCompleted("s1")
            oldRunGate.countDown()

            assertTrue("新 run 应完成 runCli", newRunDone.await(5, TimeUnit.SECONDS))
            Thread.sleep(300)

            assertFalse(
                "旧 run 的 stale feedback 不应被注入",
                injectedFeedback.contains("stale feedback"),
            )
            assertTrue(
                "新 run 的 fresh feedback 应被注入",
                injectedFeedback.contains("fresh feedback"),
            )
        } finally {
            coordinator.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `cancelCurrentRun 的 killProcess 期间新 onTurnCompleted 启动的 run 不受影响`() {
        val mcpConfig = PeerMcpConfig(null, null, null)
        val callCount = java.util.concurrent.atomic.AtomicInteger(0)
        val firstCliStarted = CountDownLatch(1)
        val firstCliBlocked = CountDownLatch(1)
        val destroyStarted = CountDownLatch(1)
        val destroyGate = CountDownLatch(1)
        val secondCliStarted = CountDownLatch(1)
        val secondCliFinished = CountDownLatch(1)
        val injectedFeedback = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val oldProcessDestroyed = java.util.concurrent.atomic.AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val coordinator = PeerCoordinator(
            project = testProject(),
            projectPath = "/tmp/test-project",
            model = SessionListModel(scan = { emptyList() }, clock = Instant::now),
            viewOf = { null },
            coroutineScope = scope,
            shell = "/bin/zsh",
            edtDispatcher = Dispatchers.Unconfined,
            peerMaxRounds = { 5 },
            peerAutoInject = { true },
            runCli = { _, _, _, _, _, _, onProcess, _ ->
                val n = callCount.incrementAndGet()
                if (n == 1) {
                    onProcess(object : Process() {
                        override fun getOutputStream() = java.io.OutputStream.nullOutputStream()
                        override fun getInputStream() = java.io.InputStream.nullInputStream()
                        override fun getErrorStream() = java.io.InputStream.nullInputStream()
                        override fun waitFor() = 0
                        override fun exitValue() = 0
                        override fun destroy() { oldProcessDestroyed.set(true) }
                        override fun destroyForcibly(): Process {
                            destroyStarted.countDown()
                            destroyGate.await(5, TimeUnit.SECONDS)
                            oldProcessDestroyed.set(true)
                            return this
                        }
                        override fun descendants(): java.util.stream.Stream<ProcessHandle> =
                            java.util.stream.Stream.empty()
                    })
                    firstCliStarted.countDown()
                    firstCliBlocked.await(5, TimeUnit.SECONDS)
                    null
                } else {
                    secondCliStarted.countDown()
                    secondCliFinished.countDown()
                    "new-run-feedback"
                }
            },
            resolveMcpConfig = { mcpConfig },
            buildPrompt = { _, _ -> "test prompt" },
            sendAutoFeedback = { _, feedback ->
                injectedFeedback.add(feedback)
                true
            },
        )

        var cancelThread: Thread? = null
        try {
            coordinator.bind("s1", AgentType.CLAUDE)
            coordinator.onTurnCompleted("s1")
            assertTrue("旧 run 应进入 runCli", firstCliStarted.await(5, TimeUnit.SECONDS))

            cancelThread = Thread { coordinator.cancelCurrentRun("s1") }
            cancelThread.start()
            assertTrue("旧进程 destroyForcibly 应开始", destroyStarted.await(5, TimeUnit.SECONDS))

            coordinator.onTurnCompleted("s1")

            assertTrue(
                "新 run 应在旧进程销毁完成前进入 runCli",
                secondCliStarted.await(5, TimeUnit.SECONDS),
            )

            destroyGate.countDown()
            firstCliBlocked.countDown()
            cancelThread.join(5000)
            cancelThread = null

            assertTrue("新 run 应完成 runCli", secondCliFinished.await(5, TimeUnit.SECONDS))
            Thread.sleep(300)

            assertTrue("旧进程应被终止", oldProcessDestroyed.get())
            assertTrue(
                "新 run 的反馈应被注入",
                injectedFeedback.contains("new-run-feedback"),
            )
            val status = coordinator.status("s1")
            assertNotNull("绑定应仍存在", status)
            assertFalse("新 run 完成后不应 running", status!!.running)
        } finally {
            destroyGate.countDown()
            firstCliBlocked.countDown()
            cancelThread?.join(5000)
            coordinator.dispose()
            scope.cancel()
        }
    }

    /**
     * 贯通测试：stdout JSON 报上下文溢出 → runPeerCli 合并进异常 → 协调器识别并缩减重试。
     *
     * 注入的 runCli 第一次真实调用 runPeerCli，用临时 shell 脚本在 stdout 输出 marker +
     * Claude 超限 JSON、stderr 为空、退出码非零；第二次返回成功反馈。
     * 断言：CLI 调用两次，且第二次 prompt 比第一次短。
     */
    @Test
    fun `stdout JSON 报上下文溢出经 runPeerCli 合并后触发协调器缩减重试`() {
        org.junit.Assume.assumeFalse("需要 POSIX shell", com.intellij.openapi.util.SystemInfo.isWindows)
        val sessionFile = java.io.File.createTempFile("imux-e2e-", ".jsonl").also { it.deleteOnExit() }
        val longMessage = "x".repeat(10_000)
        sessionFile.writeText(buildString {
            appendLine("""{"message":{"role":"user","content":[{"type":"text","text":"$longMessage"}]}}""")
            appendLine("""{"message":{"role":"assistant","content":[{"type":"text","text":"$longMessage"}]}}""")
        })
        val session = com.github.izerui.imux.model.AgentSession(
            id = "s-e2e", title = "e2e", agentType = AgentType.CLAUDE,
            lastActiveAt = Instant.now(), createdAt = Instant.now(),
            filePath = sessionFile.toPath(),
        )
        val capturedPrompts = ConcurrentLinkedQueue<String>()
        val callCount = AtomicInteger()
        val feedbackSent = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = SessionListModel(scan = { listOf(session) }, clock = Instant::now)
        model.refresh()
        val tmpDir = System.getProperty("java.io.tmpdir")
        val coordinator = PeerCoordinator(
            project = testProject(),
            projectPath = tmpDir,
            model = model,
            viewOf = { null },
            coroutineScope = scope,
            shell = "/bin/zsh",
            edtDispatcher = Dispatchers.Unconfined,
            peerMaxRounds = { 5 },
            peerAutoInject = { true },
            runCli = { agentType, _, cwd, prompt, _, timeoutSeconds, onProcess, onProgress ->
                capturedPrompts.add(prompt)
                if (callCount.incrementAndGet() == 1) {
                    runPeerCli(
                        agentType,
                        listOf("/bin/sh", "-c",
                            "echo '${PEER_OUTPUT_MARKER}'; " +
                            """echo '{"type":"result","subtype":"success","is_error":true,"result":"Your prompt is too long. Please reduce the number of tokens."}'; """ +
                            "exit 1",
                        ),
                        cwd,
                        prompt,
                        emptyMap(),
                        timeoutSeconds,
                        onProcess,
                        onProgress,
                    )
                } else {
                    "feedback after shrink"
                }
            },
            resolveMcpConfig = { PeerMcpConfig(null, null, null) },
            buildPrompt = { task, conversation -> "task=$task\nconversation=$conversation" },
            sendAutoFeedback = { _, feedback ->
                assertEquals("feedback after shrink", feedback)
                feedbackSent.countDown()
                true
            },
        )
        try {
            coordinator.bind("s-e2e", AgentType.CLAUDE)
            coordinator.onTurnCompleted("s-e2e")
            assertTrue("缩减重试后应成功发送反馈", feedbackSent.await(10, TimeUnit.SECONDS))
            assertEquals("CLI 应调用两次", 2, callCount.get())
            val prompts = capturedPrompts.toList()
            assertTrue(
                "第二次 prompt (${prompts[1].length}) 应比第一次 (${prompts[0].length}) 短",
                prompts[1].length < prompts[0].length,
            )
        } finally {
            coordinator.dispose()
            scope.cancel()
            sessionFile.delete()
        }
    }

    @Test
    fun `非上下文溢出错误不触发重试`() {
        val callCount = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = PeerCoordinator(
            project = testProject(),
            projectPath = "/tmp/test-project",
            model = SessionListModel(scan = { emptyList() }, clock = Instant::now),
            viewOf = { null },
            coroutineScope = scope,
            shell = "/bin/zsh",
            edtDispatcher = Dispatchers.Unconfined,
            peerMaxRounds = { 5 },
            peerAutoInject = { true },
            runCli = { _, _, _, _, _, _, _, _ ->
                callCount.incrementAndGet()
                throw PeerCliException("authentication failed")
            },
            resolveMcpConfig = { PeerMcpConfig(null, null, null) },
            buildPrompt = { _, _ -> "test prompt" },
        )
        try {
            coordinator.bind("s-auth", AgentType.CLAUDE)
            coordinator.onTurnCompleted("s-auth")
            val review = scope.coroutineContext[Job]!!.children.single()
            runBlocking { withTimeout(5_000) { review.join() } }
            assertEquals("非溢出错误只应调用 CLI 一次", 1, callCount.get())
        } finally {
            coordinator.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `对话已无法缩减时上下文溢出不做无意义重试`() {
        val callCount = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = PeerCoordinator(
            project = testProject(),
            projectPath = "/tmp/test-project",
            model = SessionListModel(scan = { emptyList() }, clock = Instant::now),
            viewOf = { null },
            coroutineScope = scope,
            shell = "/bin/zsh",
            edtDispatcher = Dispatchers.Unconfined,
            peerMaxRounds = { 5 },
            peerAutoInject = { true },
            runCli = { _, _, _, _, _, _, _, _ ->
                callCount.incrementAndGet()
                throw PeerCliException("context window exceeded")
            },
            resolveMcpConfig = { PeerMcpConfig(null, null, null) },
            buildPrompt = { _, _ -> "fixed size prompt" },
        )
        try {
            coordinator.bind("s-noshrink", AgentType.CLAUDE)
            coordinator.onTurnCompleted("s-noshrink")
            val review = scope.coroutineContext[Job]!!.children.single()
            runBlocking { withTimeout(5_000) { review.join() } }
            assertEquals("对话无法缩减时不应重试", 1, callCount.get())
        } finally {
            coordinator.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `前三次仍超限第四次缩减成功且每次 prompt 严格递减`() {
        val sessionFile = java.io.File.createTempFile("imux-multi-", ".jsonl").also { it.deleteOnExit() }
        val msg = "x".repeat(20_000)
        sessionFile.writeText(buildString {
            appendLine("""{"message":{"role":"user","content":[{"type":"text","text":"$msg"}]}}""")
            appendLine("""{"message":{"role":"assistant","content":[{"type":"text","text":"$msg"}]}}""")
        })
        val session = com.github.izerui.imux.model.AgentSession(
            id = "s-multi", title = "multi", agentType = AgentType.CLAUDE,
            lastActiveAt = Instant.now(), createdAt = Instant.now(),
            filePath = sessionFile.toPath(),
        )
        val capturedPrompts = ConcurrentLinkedQueue<String>()
        val callCount = AtomicInteger()
        val feedbackSent = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = SessionListModel(scan = { listOf(session) }, clock = Instant::now)
        model.refresh()
        val templatePrefix = "TEMPLATE:"
        val coordinator = PeerCoordinator(
            project = testProject(),
            projectPath = System.getProperty("java.io.tmpdir"),
            model = model,
            viewOf = { null },
            coroutineScope = scope,
            shell = "/bin/zsh",
            edtDispatcher = Dispatchers.Unconfined,
            peerMaxRounds = { 5 },
            peerAutoInject = { true },
            runCli = { _, _, _, prompt, _, _, _, _ ->
                capturedPrompts.add(prompt)
                if (callCount.incrementAndGet() <= 3) {
                    throw PeerCliException("context window exceeded")
                }
                "feedback"
            },
            resolveMcpConfig = { PeerMcpConfig(null, null, null) },
            buildPrompt = { task, conv -> "$templatePrefix$task\n$conv" },
            sendAutoFeedback = { _, _ ->
                feedbackSent.countDown()
                true
            },
        )
        try {
            coordinator.bind("s-multi", AgentType.CLAUDE)
            coordinator.onTurnCompleted("s-multi")
            assertTrue("多次缩减后应成功", feedbackSent.await(10, TimeUnit.SECONDS))
            val prompts = capturedPrompts.toList()
            assertEquals("应调用 CLI 四次（前三次超限+第四次成功）", 4, prompts.size)
            for (i in 1 until prompts.size) {
                assertTrue(
                    "第 ${i + 1} 次 prompt (${prompts[i].length}) 应严格短于第 $i 次 (${prompts[i - 1].length})",
                    prompts[i].length < prompts[i - 1].length,
                )
            }
            assertTrue("每次 prompt 都应包含完整模板", prompts.all { it.startsWith(templatePrefix) })
        } finally {
            coordinator.dispose()
            scope.cancel()
            sessionFile.delete()
        }
    }

    @Test
    fun `大模板加较短对话时上下文溢出仍能缩减`() {
        val sessionFile = java.io.File.createTempFile("imux-tmpl-", ".jsonl").also { it.deleteOnExit() }
        val shortConversation = "x".repeat(2_000)
        sessionFile.writeText(buildString {
            appendLine("""{"message":{"role":"user","content":[{"type":"text","text":"$shortConversation"}]}}""")
            appendLine("""{"message":{"role":"assistant","content":[{"type":"text","text":"$shortConversation"}]}}""")
        })
        val session = com.github.izerui.imux.model.AgentSession(
            id = "s-tmpl", title = "tmpl", agentType = AgentType.CLAUDE,
            lastActiveAt = Instant.now(), createdAt = Instant.now(),
            filePath = sessionFile.toPath(),
        )
        val capturedPrompts = ConcurrentLinkedQueue<String>()
        val callCount = AtomicInteger()
        val feedbackSent = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = SessionListModel(scan = { listOf(session) }, clock = Instant::now)
        model.refresh()
        val largeTemplate = "T".repeat(10_000)
        val coordinator = PeerCoordinator(
            project = testProject(),
            projectPath = System.getProperty("java.io.tmpdir"),
            model = model,
            viewOf = { null },
            coroutineScope = scope,
            shell = "/bin/zsh",
            edtDispatcher = Dispatchers.Unconfined,
            peerMaxRounds = { 5 },
            peerAutoInject = { true },
            runCli = { _, _, _, prompt, _, _, _, _ ->
                capturedPrompts.add(prompt)
                if (callCount.incrementAndGet() == 1) {
                    throw PeerCliException("context window exceeded")
                }
                "feedback"
            },
            resolveMcpConfig = { PeerMcpConfig(null, null, null) },
            buildPrompt = { _, conv -> "$largeTemplate\n$conv" },
            sendAutoFeedback = { _, _ ->
                feedbackSent.countDown()
                true
            },
        )
        try {
            coordinator.bind("s-tmpl", AgentType.CLAUDE)
            coordinator.onTurnCompleted("s-tmpl")
            assertTrue("大模板 + 较短对话应能缩减后成功", feedbackSent.await(5, TimeUnit.SECONDS))
            assertEquals("应调用 CLI 两次", 2, callCount.get())
            val prompts = capturedPrompts.toList()
            assertTrue(
                "第二次 prompt (${prompts[1].length}) 应比第一次 (${prompts[0].length}) 短",
                prompts[1].length < prompts[0].length,
            )
            assertTrue(
                "两次 prompt 都应包含完整模板",
                prompts.all { it.startsWith(largeTemplate) },
            )
        } finally {
            coordinator.dispose()
            scope.cancel()
            sessionFile.delete()
        }
    }

    @Test
    fun `stdout is_error 且 exit 0 仍判失败并触发缩减重试`() {
        org.junit.Assume.assumeFalse("需要 POSIX shell", com.intellij.openapi.util.SystemInfo.isWindows)
        val sessionFile = java.io.File.createTempFile("imux-exit0-", ".jsonl").also { it.deleteOnExit() }
        val msg = "x".repeat(10_000)
        sessionFile.writeText(buildString {
            appendLine("""{"message":{"role":"user","content":[{"type":"text","text":"$msg"}]}}""")
            appendLine("""{"message":{"role":"assistant","content":[{"type":"text","text":"$msg"}]}}""")
        })
        val session = com.github.izerui.imux.model.AgentSession(
            id = "s-exit0", title = "exit0", agentType = AgentType.CLAUDE,
            lastActiveAt = Instant.now(), createdAt = Instant.now(),
            filePath = sessionFile.toPath(),
        )
        val capturedPrompts = ConcurrentLinkedQueue<String>()
        val callCount = AtomicInteger()
        val feedbackSent = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = SessionListModel(scan = { listOf(session) }, clock = Instant::now)
        model.refresh()
        val tmpDir = System.getProperty("java.io.tmpdir")
        val coordinator = PeerCoordinator(
            project = testProject(),
            projectPath = tmpDir,
            model = model,
            viewOf = { null },
            coroutineScope = scope,
            shell = "/bin/zsh",
            edtDispatcher = Dispatchers.Unconfined,
            peerMaxRounds = { 5 },
            peerAutoInject = { true },
            runCli = { agentType, _, cwd, prompt, _, timeoutSeconds, onProcess, onProgress ->
                capturedPrompts.add(prompt)
                if (callCount.incrementAndGet() == 1) {
                    runPeerCli(
                        agentType,
                        listOf("/bin/sh", "-c",
                            "echo '${PEER_OUTPUT_MARKER}'; " +
                            """echo '{"type":"result","subtype":"success","is_error":true,"result":"prompt_too_long"}'; """ +
                            "exit 0",
                        ),
                        cwd,
                        prompt,
                        emptyMap(),
                        timeoutSeconds,
                        onProcess,
                        onProgress,
                    )
                } else {
                    "feedback after exit0 retry"
                }
            },
            resolveMcpConfig = { PeerMcpConfig(null, null, null) },
            buildPrompt = { task, conv -> "task=$task\nconv=$conv" },
            sendAutoFeedback = { _, feedback ->
                assertEquals("feedback after exit0 retry", feedback)
                feedbackSent.countDown()
                true
            },
        )
        try {
            coordinator.bind("s-exit0", AgentType.CLAUDE)
            coordinator.onTurnCompleted("s-exit0")
            assertTrue("exit 0 + is_error 应触发缩减重试后成功", feedbackSent.await(10, TimeUnit.SECONDS))
            val prompts = capturedPrompts.toList()
            assertEquals("应调用两次", 2, prompts.size)
            assertTrue(
                "第二次 prompt (${prompts[1].length}) 应严格短于第一次 (${prompts[0].length})",
                prompts[1].length < prompts[0].length,
            )
        } finally {
            coordinator.dispose()
            scope.cancel()
            sessionFile.delete()
        }
    }

    private fun testProject(): Project =
        Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getBasePath" -> "/tmp/test-project"
                "isDisposed" -> false
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "TestProject"
                else -> defaultValue(method.returnType)
            }
        } as Project

    private fun defaultValue(type: Class<*>): Any? =
        when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> ' '
            else -> null
        }
}
