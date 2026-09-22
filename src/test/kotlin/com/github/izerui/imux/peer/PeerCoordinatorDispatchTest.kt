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

    @Test
    fun `自动反馈使用括号粘贴并始终提交`() {
        val calls = mutableListOf<String>()
        val builder = Proxy.newProxyInstance(
            TerminalSendTextBuilder::class.java.classLoader,
            arrayOf(TerminalSendTextBuilder::class.java),
        ) { proxy, method, args ->
            calls += method.name
            when (method.name) {
                "useBracketedPasteMode", "shouldExecute" -> proxy
                "send" -> {
                    assertEquals("first line\nsecond line", args?.single())
                    null
                }
                else -> error("不应调用 ${method.name}")
            }
        } as TerminalSendTextBuilder

        assertTrue(trySendPeerFeedback(builder, "first line\nsecond line"))
        assertEquals(
            listOf("useBracketedPasteMode", "shouldExecute", "send"),
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
