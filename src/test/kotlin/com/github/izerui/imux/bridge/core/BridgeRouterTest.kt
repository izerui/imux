package com.github.izerui.imux.bridge.core

import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.transcript.CodexTranscriptDecoder
import com.github.izerui.imux.model.AgentType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class BridgeRouterTest {
    private val robot = RobotId("r1")
    private val owner = RobotOwner(senderId = "me", chatId = "chat-1")
    private val target = SessionTarget("/p", "tab-1", AgentType.CODEX, "s1")

    private class FakePort(var result: SubmitResult = SubmitResult.SUBMITTED_TO_TERMINAL) : TerminalSessionPort {
        val submitted = mutableListOf<Pair<String, String>>()

        override suspend fun submit(
            target: SessionTarget,
            text: String,
        ): SubmitResult {
            submitted += target.tabId to text
            return result
        }
    }

    private class FakeChannel(
        override val robotId: RobotId,
        override val maxTextBytes: Int = 1000,
        override val rendersMarkdown: Boolean = true,
    ) : ChannelAdapter {
        val sent = mutableListOf<OutboundMessage>()
        var afterSend: () -> Unit = {}

        override suspend fun send(message: OutboundMessage): DeliveryResult {
            sent += message
            afterSend()
            return DeliveryResult.Accepted
        }
    }

    private object Texts : BridgeTexts {
        override fun unbound() = "UNBOUND"

        override fun offline() = "OFFLINE"

        override fun rebound() = "REBOUND"

        override fun notReady() = "NOT_READY"

        override fun emptyInput() = "EMPTY"

        override fun localInputLabel() = "LOCAL:"


        override fun waiting(reason: String?) = "WAIT($reason)"

        override fun prompt(screen: String) = "PROMPT($screen)"

        override fun promptRejected() = "PROMPT_REJECTED"

        override fun released(sessionTitle: String) = "RELEASED($sessionTitle)"

        override fun transcriptRestarted() = "RESTARTED"

        override fun backgroundRunning(tasks: List<String?>) = "BG(${tasks.joinToString(",")})"

        override fun turnWithoutReply() = "NO_REPLY"

        override fun turnFailed(reason: String) = "FAILED($reason)"

        override fun turnInterrupted() = "INTERRUPTED"

        override fun connected(
            project: String,
            agent: String,
            sessionTitle: String,
        ) = "CONNECTED($project/$agent/$sessionTitle)"




        override fun attachmentLine(path: String) = "ATT($path)"

        override fun attachmentsOnly(paths: List<String>) = "ONLY(${paths.joinToString(",")})"

        override fun attachmentFailed() = "ATT_FAILED"

        override fun attachmentTooLarge() = "ATT_TOO_LARGE"
    }

    private class FakeAttachment(override val kind: AttachmentKind = AttachmentKind.IMAGE) : InboundAttachment {
        override suspend fun fetch(maxBytes: Long): FetchResult = error("路由器不应直接调用 fetch")
    }

    /** 按附件在列表中的顺序返回预设结果，并记录调用。 */
    private class FakeStore(private val results: List<PlaceResult>) : AttachmentStore {
        val placed = mutableListOf<InboundAttachment>()

        override suspend fun place(
            target: SessionTarget,
            attachment: InboundAttachment,
        ): PlaceResult {
            placed += attachment
            return results[placed.size - 1]
        }
    }

    private fun routerWith(store: AttachmentStore) =
        BridgeRouter(
            registry,
            { if (it == robot) owner else null },
            port,
            { if (it == robot) channel else null },
            Texts,
            attachmentStore = store,
            notReadyRetryDelayMs = 0,
        )

    private fun inboundWith(
        id: String,
        text: String,
        vararg attachments: InboundAttachment,
        sender: String = owner.senderId,
    ) = InboundMessage(robot, id, sender, owner.chatId, text, attachments.toList())

    private val registry = BindingRegistry()
    private val port = FakePort()
    private val channel = FakeChannel(robot)
    private val router =
        BridgeRouter(
            registry,
            { if (it == robot) owner else null },
            port,
            { if (it == robot) channel else null },
            Texts,
            notReadyRetryDelayMs = 0,
        )

    /** 按脚本逐次返回结果；每次调用前执行 beforeCall（第几次调用，从 0 开始）。 */
    private class ScriptedPort(
        private val results: List<SubmitResult>,
        private val beforeCall: (Int) -> Unit = {},
    ) : TerminalSessionPort {
        val submitted = mutableListOf<Pair<String, String>>()

        override suspend fun submit(
            target: SessionTarget,
            text: String,
        ): SubmitResult {
            val call = submitted.size
            submitted += target.tabId to text
            beforeCall(call)
            return results[minOf(call, results.lastIndex)]
        }
    }

    private fun routerWith(p: TerminalSessionPort) =
        BridgeRouter(
            registry,
            { if (it == robot) owner else null },
            p,
            { if (it == robot) channel else null },
            Texts,
            notReadyRetryDelayMs = 0,
        )

    private fun inbound(
        id: String,
        text: String,
        sender: String = "me",
        chat: String = "chat-1",
    ) = InboundMessage(robot, id, sender, chat, text)

    private fun sentTexts() = channel.sent.map { it.text }

    @Test
    fun `非本人或其它聊天的消息不进入终端也不回复`() =
        runBlocking {
            registry.bind(robot, target)
            assertEquals(InboundOutcome.UNAUTHORIZED, router.onInbound(inbound("1", "rm -rf", sender = "stranger")))
            assertEquals(InboundOutcome.UNAUTHORIZED, router.onInbound(inbound("2", "hi", chat = "group-9")))
            assertTrue(port.submitted.isEmpty())
            assertTrue(channel.sent.isEmpty())
        }

    @Test
    fun `引用的文字以引用块放在正文前提交`() =
        runBlocking {
            registry.bind(robot, target)
            val message = InboundMessage(robot, "1", "me", "chat-1", "这里改一下", quote = "第一行\n\n第二行  ")
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, router.onInbound(message))
            assertEquals("> 第一行\n>\n> 第二行\n\n这里改一下", port.submitted.single().second)
        }

    @Test
    fun `只有引用没有文字时按空消息处理`() =
        runBlocking {
            registry.bind(robot, target)
            val message = InboundMessage(robot, "1", "me", "chat-1", "  ", quote = "上一条")
            assertEquals(InboundOutcome.EMPTY, router.onInbound(message))
            assertTrue(port.submitted.isEmpty())
        }

    @Test
    fun `空白引用不加引用块`() =
        runBlocking {
            registry.bind(robot, target)
            router.onInbound(InboundMessage(robot, "1", "me", "chat-1", "继续", quote = " \n "))
            assertEquals("继续", port.submitted.single().second)
        }

    @Test
    fun `未配对机器人的消息被忽略`() =
        runBlocking {
            val stranger = InboundMessage(RobotId("other"), "1", "me", "chat-1", "hi")
            assertEquals(InboundOutcome.NOT_PAIRED, router.onInbound(stranger))
            assertTrue(port.submitted.isEmpty())
        }

    @Test
    fun `未绑定时回复提示`() =
        runBlocking {
            assertEquals(InboundOutcome.UNBOUND, router.onInbound(inbound("1", "hi")))
            assertEquals(listOf("UNBOUND"), sentTexts())
        }

    @Test
    fun `平台重投同一消息只提交一次，相同文本不同 id 照常提交`() =
        runBlocking {
            registry.bind(robot, target)
            router.onInbound(inbound("1", "继续"))
            assertEquals(InboundOutcome.DUPLICATE, router.onInbound(inbound("1", "继续")))
            router.onInbound(inbound("2", "继续"))
            assertEquals(listOf("tab-1" to "继续", "tab-1" to "继续"), port.submitted)
        }

    @Test
    fun `空消息不提交并提示`() =
        runBlocking {
            registry.bind(robot, target)
            assertEquals(InboundOutcome.EMPTY, router.onInbound(inbound("1", "   ")))
            assertTrue(port.submitted.isEmpty())
            assertEquals(listOf("EMPTY"), sentTexts())
        }

    @Test
    fun `远程输入在记录中出现时不回显，电脑输入带标签转发，助手回复原文转发`() =
        runBlocking {
            registry.bind(robot, target)
            router.onInbound(inbound("1", "修一下  测试"))
            router.onSessionEvents(
                "tab-1",
                listOf(
                    SessionEvent.UserText("修一下 测试"),
                    SessionEvent.AssistantText("好的\n```\ncode\n```"),
                ),
            )
            router.onTurnCompleted("tab-1", router.currentTurn("tab-1"))
            router.onSessionEvents("tab-1", listOf(SessionEvent.UserText("电脑上补一句")))
            assertEquals(listOf("好的\n```\ncode\n```", "LOCAL:\n电脑上补一句"), sentTexts())
        }

    @Test
    fun `一轮结束时还有后台任务：回复之后补一条；任务结束后的那一轮不再提示`() =
        runBlocking {
            registry.bind(robot, target)
            router.onSessionEvents(
                "tab-1",
                listOf(
                    SessionEvent.UserText("跑一下构建"),
                    SessionEvent.BackgroundLaunched("t1", "完整构建"),
                    SessionEvent.ToolStarted,
                    SessionEvent.BackgroundStarted("b1", "t1"),
                    SessionEvent.BackgroundStarted("a1", null, "评审子代理"),
                    SessionEvent.AssistantText("构建已在后台运行"),
                    SessionEvent.TurnEnded,
                ),
            )
            // 构建结束：通知开启新一轮，助手回应
            router.onSessionEvents("tab-1", listOf(SessionEvent.BackgroundEnded("b1"), SessionEvent.TurnStarted, SessionEvent.AssistantText("构建通过"), SessionEvent.TurnEnded))
            router.onSessionEvents("tab-1", listOf(SessionEvent.BackgroundEnded("a1"), SessionEvent.TurnStarted, SessionEvent.AssistantText("评审完成")))
            router.onTurnCompleted("tab-1", router.currentTurn("tab-1"))
            assertEquals(
                listOf("LOCAL:\n跑一下构建", "构建已在后台运行", "BG(完整构建,评审子代理)", "构建通过", "BG(评审子代理)", "评审完成"),
                sentTexts(),
            )
        }

    private suspend fun turn(vararg events: SessionEvent) =
        router.onSessionEvents("tab-1", listOf(SessionEvent.TurnStarted) + events + SessionEvent.TurnEnded)

    @Test
    fun `后台任务列表和上次提示的一样就不重复，变了或清空后再出现才提示`() =
        runBlocking {
            registry.bind(robot, target)
            turn(SessionEvent.BackgroundStarted("a1", null, "A"), SessionEvent.BackgroundStarted("b1", null, "B"), SessionEvent.AssistantText("第一轮"))
            turn(SessionEvent.AssistantText("第二轮"))
            turn(SessionEvent.BackgroundEnded("a1"), SessionEvent.AssistantText("第三轮"))
            turn(SessionEvent.BackgroundEnded("b1"), SessionEvent.AssistantText("第四轮"))
            turn(SessionEvent.BackgroundStarted("b1", null), SessionEvent.AssistantText("第五轮"))
            assertEquals(listOf("第一轮", "BG(A,B)", "第二轮", "第三轮", "BG(B)", "第四轮", "第五轮", "BG(B)"), sentTexts())
        }

    @Test
    fun `换了会话或会话记录被替换后，之前的后台任务不再提示`() =
        runBlocking {
            registry.bind(robot, target)
            turn(SessionEvent.BackgroundStarted("a1", null, "A"), SessionEvent.AssistantText("第一轮"))
            router.resetBackground("tab-1")
            turn(SessionEvent.AssistantText("新会话"))
            turn(SessionEvent.BackgroundStarted("b1", null, "B"), SessionEvent.AssistantText("又开了一个"))
            router.onTranscriptRestarted("tab-1")
            turn(SessionEvent.AssistantText("记录替换后"))
            assertEquals(listOf("第一轮", "BG(A)", "新会话", "又开了一个", "BG(B)", "RESTARTED", "记录替换后"), sentTexts())
        }

    @Test
    fun `说这一轮状态的提示：回复还没到就排在回复之后，已经发出就立即发`() =
        runBlocking {
            registry.bind(robot, target)
            router.onSessionEvents("tab-1", listOf(SessionEvent.TurnStarted, SessionEvent.AssistantText("结论还没写完")))
            router.onAfterReply("tab-1", "审查中")
            router.onSessionEvents("tab-1", listOf(SessionEvent.AssistantText("结论"), SessionEvent.TurnEnded))
            router.onAfterReply("tab-1", "到上限")
            assertEquals(listOf("结论还没写完\n\n结论", "审查中", "到上限"), sentTexts())
        }

    @Test
    fun `完成信号没能结算时也补后台任务提示`() =
        runBlocking {
            registry.bind(robot, target)
            router.onSessionEvents("tab-1", listOf(SessionEvent.TurnStarted, SessionEvent.BackgroundStarted("a1", null, "A")))
            router.flushAfterReply("tab-1")
            assertEquals(listOf("BG(A)"), sentTexts())
        }

    @Test
    fun `没等到结算就开了新一轮：排着的提示先发，不落到新输入后面`() =
        runBlocking {
            registry.bind(robot, target)
            router.onSessionEvents("tab-1", listOf(SessionEvent.TurnStarted))
            router.onAfterReply("tab-1", "审查中")
            router.onSessionEvents("tab-1", listOf(SessionEvent.UserText("新的一句")))
            assertEquals(listOf("审查中", "LOCAL:\n新的一句"), sentTexts())
        }

    @Test
    fun `续跑的后台子代理沿用上次的说明`() =
        runBlocking {
            registry.bind(robot, target)
            router.onSessionEvents(
                "tab-1",
                listOf(
                    SessionEvent.BackgroundStarted("a1", null, "评审子代理"),
                    SessionEvent.BackgroundEnded("a1"),
                    SessionEvent.BackgroundStarted("a1", null),
                    SessionEvent.AssistantText("已让它继续"),
                    SessionEvent.TurnEnded,
                ),
            )
            assertEquals(listOf("已让它继续", "BG(评审子代理)"), sentTexts())
        }

    @Test
    fun `关闭提示保留绑定且重新挂接后使用已连接文案`() =
        runBlocking {
            val binding = (registry.bind(robot, target) as BindResult.Bound).binding
            router.onSuspended(binding, "修复登录", "imux · Codex · 修复登录")
            assertEquals(listOf("RELEASED(修复登录)\n\n---\n> imux · Codex · 修复登录"), sentTexts())
            assertEquals(binding, registry.bindingOf(robot))
            val restored = registry.reattach(robot, "new-tab")!!
            router.onReattached(restored, "imux", "Codex", "修复登录")
            assertEquals("CONNECTED(imux/Codex/修复登录)", sentTexts().last())
            assertEquals("new-tab", registry.bindingOf(robot)!!.target.tabId)
        }

    @Test
    fun `关闭快照的绑定已被替换时不向新会话发送断开提示`() =
        runBlocking {
            val old = (registry.bind(robot, target) as BindResult.Bound).binding
            val replacement = (registry.bind(robot, target.copy(tabId = "other-tab")) as BindResult.Bound).binding
            router.onSuspended(old, "旧会话", null)
            assertTrue(sentTexts().isEmpty())
            assertEquals(replacement, registry.bindingOf(robot))
        }

    @Test
    fun `取消恢复任务后机器人上线不再发送已连接提示`() =
        runBlocking {
            val binding = (registry.bind(robot, target) as BindResult.Bound).binding
            val online = CompletableDeferred<Unit>()
            val waiting = CompletableDeferred<Unit>()
            val r = BridgeRouter(registry, { owner }, port, { channel }, Texts, awaitOnline = {
                waiting.complete(Unit)
                online.await()
            })
            val job = launch { r.onReattached(binding, "imux", "Codex", "修复登录") }
            waiting.await()
            job.cancel()
            r.onSuspended(binding, "修复登录", null)
            online.complete(Unit)
            job.join()
            assertEquals(listOf("RELEASED(修复登录)"), sentTexts())
            assertEquals(binding, registry.bindingOf(robot))
        }

    @Test(timeout = 5_000)
    fun `手动连接等机器人真正上线后才通知手机`() =
        runBlocking {
            val binding = (registry.bind(robot, target) as BindResult.Bound).binding
            val online = CompletableDeferred<Unit>()
            val waiting = CompletableDeferred<Unit>()
            val r = BridgeRouter(registry, { owner }, port, { channel }, Texts, awaitOnline = {
                waiting.complete(Unit)
                online.await()
            })
            val job = launch { r.onRebound(emptyList(), binding, "imux", "Codex", "修复登录") }
            waiting.await()
            assertTrue("还未上线，不把保存绑定误报为连接成功", sentTexts().isEmpty())
            online.complete(Unit)
            job.join()
            assertEquals(listOf("CONNECTED(imux/Codex/修复登录)"), sentTexts())
        }

    @Test(timeout = 5_000)
    fun `手动连接限频重试期间关闭项目后手机只收到断开提示`() =
        runBlocking {
            val binding = (registry.bind(robot, target) as BindResult.Bound).binding
            val firstAttempt = CompletableDeferred<Unit>()
            val delivered = mutableListOf<String>()
            var connectionAttempts = 0
            val limited = object : ChannelAdapter {
                override val robotId = robot
                override val maxTextBytes = 1000

                override suspend fun send(message: OutboundMessage): DeliveryResult {
                    if (message.text.startsWith("CONNECTED") && ++connectionAttempts == 1) {
                        firstAttempt.complete(Unit)
                        return DeliveryResult.Rejected(DeliveryResult.RATE_LIMITED)
                    }
                    delivered += message.text
                    return DeliveryResult.Accepted
                }
            }
            val r = BridgeRouter(registry, { owner }, port, { limited }, Texts, rateLimitRetryDelayMs = 50)
            val projectScope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
            try {
                val job = projectScope.launch { r.onRebound(emptyList(), binding, "imux", "Codex", "修复登录") }
                firstAttempt.await()
                projectScope.cancel()
                r.onSuspended(binding, "修复登录", null)
                job.join()
                assertEquals(listOf("RELEASED(修复登录)"), delivered)
                assertEquals("关闭后不能重试已连接提示", 1, connectionAttempts)
                assertEquals("关闭项目保留绑定供恢复", binding, registry.bindingOf(robot))
            } finally {
                projectScope.cancel()
            }
        }

    @Test(timeout = 5_000)
    fun `换绑还在等待旧会话断开提示时关闭项目不再通知新会话已连接`() =
        runBlocking {
            val oldRobot = RobotId("old")
            val old = (registry.bind(oldRobot, target) as BindResult.Bound).binding
            registry.unbind(oldRobot)
            val binding = (registry.bind(robot, target) as BindResult.Bound).binding
            val waiting = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            val oldChannel = object : ChannelAdapter {
                override val robotId = oldRobot
                override val maxTextBytes = 1000

                override suspend fun send(message: OutboundMessage): DeliveryResult {
                    waiting.complete(Unit)
                    released.await()
                    return DeliveryResult.Accepted
                }
            }
            val r = BridgeRouter(registry, { owner }, port, { if (it == oldRobot) oldChannel else channel }, Texts)
            val projectScope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
            try {
                val job = projectScope.launch { r.onRebound(listOf(old to "旧会话"), binding, "imux", "Codex", "修复登录") }
                waiting.await()
                projectScope.cancel()
                r.onSuspended(binding, "修复登录", null)
                released.complete(Unit)
                job.join()
                assertEquals(listOf("RELEASED(修复登录)"), sentTexts())
                assertEquals(binding, registry.bindingOf(robot))
            } finally {
                projectScope.cancel()
            }
        }

    @Test
    fun `机器人已登记但仍在连接时等待上线再发送恢复通知`() =
        runBlocking {
            registry.bind(robot, target)
            val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connecting)
            val waiting =
                BridgeRouter(
                    registry, { if (it == robot) owner else null }, port, { if (it == robot) channel else null }, Texts,
                    reconnectWaitMs = 5_000,
                    awaitOnline = { id ->
                        assertEquals(robot, id)
                        status.first { it == ConnectionStatus.Online }
                    },
                )
            val job = launch { waiting.onReattached(registry.bindingOf(robot)!!, "imux", "Claude", "修复登录") }
            yield()
            assertTrue("机器人还没上线，不发", sentTexts().isEmpty())
            assertTrue("等待真实连接状态", job.isActive)
            status.value = ConnectionStatus.Online
            job.join()
            assertEquals(listOf("CONNECTED(imux/Claude/修复登录)"), sentTexts())
        }

    @Test
    fun `恢复等待超时不尝试向离线渠道发送`() =
        runBlocking {
            registry.bind(robot, target)
            val warnings = mutableListOf<String>()
            val waiting =
                BridgeRouter(
                    registry, { owner }, port, { channel }, Texts,
                    reconnectWaitMs = 20,
                    onWarning = { warnings += it },
                    awaitOnline = { CompletableDeferred<Unit>().await() },
                )
            waiting.onReattached(registry.bindingOf(robot)!!, "imux", "Claude", "修复登录")
            assertTrue(sentTexts().isEmpty())
            assertEquals(1, warnings.size)
            assertTrue(warnings.single(), warnings.single().contains("未上线"))
        }

    @Test
    fun `恢复等待期间解绑后上线不发送旧绑定通知`() =
        runBlocking {
            registry.bind(robot, target)
            val online = CompletableDeferred<Unit>()
            val waiting =
                BridgeRouter(registry, { owner }, port, { channel }, Texts, awaitOnline = { online.await() })
            val binding = registry.bindingOf(robot)!!
            val job = launch { waiting.onReattached(binding, "imux", "Claude", "修复登录") }
            yield()
            registry.unbind(robot)
            online.complete(Unit)
            job.join()
            assertTrue(sentTexts().isEmpty())
        }

    @Test
    fun `结束标记时没有文字回复：说一声已完成`() =
        runBlocking {
            registry.bind(robot, target)
            router.onSessionEvents("tab-1", listOf(SessionEvent.TurnStarted, SessionEvent.AssistantText("我先跑一下"), SessionEvent.ToolStarted, SessionEvent.TurnEnded))
            assertEquals(listOf("NO_REPLY"), sentTexts())
        }

    @Test
    fun `Codex 中途重试报错不推送且恢复成功后只发送最终回复`() =
        runBlocking {
            registry.bind(robot, target)
            clocked.onSessionEvents("tab-1", CodexTranscriptDecoder.decode(
                """{"type":"event_msg","payload":{"type":"task_started"}}""",
            ))
            clocked.onSessionEvents("tab-1", CodexTranscriptDecoder.decode(
                """{"type":"event_msg","payload":{"type":"stream_error","message":"stream disconnected; retrying 1/5"}}""",
            ))
            clock = 10_000
            clocked.onQuiet("tab-1")
            assertTrue("重试期间不发异常或空回复提示", sentTexts().isEmpty())

            clocked.onSessionEvents("tab-1", CodexTranscriptDecoder.decode(
                """{"type":"event_msg","payload":{"type":"error","message":"503 Service Unavailable"}}""",
            ))
            assertTrue("独立错误事件没有结束标记时不发失败提示", sentTexts().isEmpty())

            clocked.onSessionEvents("tab-1", CodexTranscriptDecoder.decode(
                """{"type":"response_item","payload":{"type":"message","role":"assistant","phase":"final_answer","content":[{"type":"output_text","text":"重试后已完成"}]}}""",
            ))
            clocked.onSessionEvents("tab-1", CodexTranscriptDecoder.decode(
                """{"type":"event_msg","payload":{"type":"task_complete","last_agent_message":"重试后已完成","error":null}}""",
            ))
            clocked.onTurnCompleted("tab-1", clocked.currentTurn("tab-1"))
            clock = 20_000
            clocked.onQuiet("tab-1")
            assertEquals(listOf("重试后已完成"), sentTexts())
        }

    @Test
    fun `Codex 失败结束将异常原因发到手机并取消空回复提示`() =
        runBlocking {
            registry.bind(robot, target)
            clocked.onSessionEvents("tab-1", listOf(SessionEvent.TurnStarted, SessionEvent.AssistantText("我先检查")))
            val failure = """{"type":"event_msg","payload":{"type":"task_complete","last_agent_message":null,"error":{"message":"unexpected status 503 Service Unavailable: Service temporarily unavailable","codex_error_info":{"http_connection_failed":{"http_status_code":503}}}}}"""
            clocked.onSessionEvents("tab-1", CodexTranscriptDecoder.decode(failure))
            clocked.onTurnCompleted("tab-1", clocked.currentTurn("tab-1"))
            clock = 10_000
            clocked.onQuiet("tab-1")
            assertEquals(listOf("FAILED(unexpected status 503 Service Unavailable: Service temporarily unavailable)"), sentTexts())
        }

    @Test
    fun `IDE 完成信号先到时迟到的 Codex 失败替换空回复提示并发出排队状态`() =
        runBlocking {
            registry.bind(robot, target)
            clocked.onSessionEvents("tab-1", listOf(SessionEvent.TurnStarted))
            clocked.onTurnCompleted("tab-1", clocked.currentTurn("tab-1"))
            clocked.onAfterReply("tab-1", "后续状态")
            clocked.onSessionEvents("tab-1", CodexTranscriptDecoder.decode(
                """{"type":"event_msg","payload":{"type":"task_complete","error":{"message":"connection closed"}}}""",
            ))
            clock = 10_000
            clocked.onQuiet("tab-1")
            assertEquals(listOf("FAILED(connection closed)", "后续状态"), sentTexts())
        }

    private var clock = 0L

    @Test
    fun `失败仅发送首行原因且下一轮正常回复不受影响`() =
        runBlocking {
            registry.bind(robot, target)
            router.onSessionEvents("tab-1", listOf(
                SessionEvent.TurnStarted,
                SessionEvent.TurnFailed("\n connection closed \nstack trace"),
                SessionEvent.TurnStarted,
                SessionEvent.AssistantText("修复完成"),
                SessionEvent.TurnEnded,
            ))
            assertEquals(listOf("FAILED(connection closed)", "修复完成"), sentTexts())
        }

    private val clocked =
        BridgeRouter(registry, { if (it == robot) owner else null }, port, { if (it == robot) channel else null }, Texts, noReplyGraceMs = 3_000, now = { clock })

    @Test
    fun `完成信号到了还没有回复：等一会儿仍没有才说已完成，排着的提示随后`() =
        runBlocking {
            registry.bind(robot, target)
            clocked.onSessionEvents("tab-1", listOf(SessionEvent.TurnStarted, SessionEvent.ToolStarted))
            clocked.onTurnCompleted("tab-1", clocked.currentTurn("tab-1"))
            clocked.onAfterReply("tab-1", "审查中")
            clock = 2_000
            clocked.onQuiet("tab-1")
            assertTrue("还在等迟到的回复", sentTexts().isEmpty())
            clock = 3_000
            clocked.onQuiet("tab-1")
            clocked.onQuiet("tab-1")
            assertEquals(listOf("NO_REPLY", "审查中"), sentTexts())
        }

    @Test
    fun `完成信号先到、回复随后读到：照常发回复，不说没有回复`() =
        runBlocking {
            registry.bind(robot, target)
            clocked.onSessionEvents("tab-1", listOf(SessionEvent.TurnStarted))
            clocked.onTurnCompleted("tab-1", clocked.currentTurn("tab-1"))
            clocked.onAfterReply("tab-1", "审查中")
            clocked.onSessionEvents("tab-1", listOf(SessionEvent.AssistantText("结论")))
            clock = 10_000
            clocked.onQuiet("tab-1")
            assertEquals(listOf("结论", "审查中"), sentTexts())
        }

    @Test
    fun `电脑上中断这一轮：丢掉过程说明、说一声已中断，之后的完成信号不再说没有回复`() =
        runBlocking {
            registry.bind(robot, target)
            clocked.onSessionEvents("tab-1", listOf(SessionEvent.TurnStarted, SessionEvent.AssistantText("我先看看"), SessionEvent.TurnInterrupted))
            clocked.onTurnCompleted("tab-1", clocked.currentTurn("tab-1"))
            clock = 10_000
            clocked.onQuiet("tab-1")
            assertEquals(listOf("INTERRUPTED"), sentTexts())
        }

    @Test
    fun `部分发送后的尾段限频仍受单次发送重试上限约束`() = runBlocking {
        registry.bind(robot, target)
        val attempts = mutableListOf<String>()
        val limited = object : ChannelAdapter {
            override val robotId = robot
            override val maxTextBytes = 1000

            override suspend fun send(message: OutboundMessage): DeliveryResult {
                attempts += message.text
                return if (attempts.size == 1) DeliveryResult.RetryRemaining("尾段")
                else DeliveryResult.Rejected(DeliveryResult.RATE_LIMITED)
            }
        }
        val retrying = BridgeRouter(registry, { owner }, port, { limited }, Texts, rateLimitRetryDelayMs = 0)
        retrying.onStatus("tab-1", "首段尾段")
        assertEquals(listOf("首段尾段") + List(6) { "尾段" }, attempts)
    }

    @Test
    fun `渠道限频拒发时稍后重发，换绑后不再重发`() =
        runBlocking {
            registry.bind(robot, target)
            var rejections = 2
            val limited =
                object : ChannelAdapter {
                    override val robotId = robot
                    override val maxTextBytes = 1000
                    val attempts = mutableListOf<String>()

                    override suspend fun send(message: OutboundMessage): DeliveryResult {
                        attempts += message.text
                        return if (rejections-- > 0) DeliveryResult.Rejected(DeliveryResult.RATE_LIMITED) else DeliveryResult.Accepted
                    }
                }
            val retrying = BridgeRouter(registry, { if (it == robot) owner else null }, port, { limited }, Texts, rateLimitRetryDelayMs = 0)
            retrying.onStatus("tab-1", "结论")
            assertEquals("限频两次后第三次发出", listOf("结论", "结论", "结论"), limited.attempts)

            limited.attempts.clear()
            rejections = 100
            val rebinding =
                BridgeRouter(registry, { if (it == robot) owner else null }, port, { limited }, Texts, rateLimitRetryDelayMs = 0, onDelivery = { _, _ -> registry.unbind(robot) })
            rebinding.onStatus("tab-1", "另一条")
            assertEquals("断开后不再重发", listOf("另一条"), limited.attempts)
        }

    @Test
    fun `终端未就绪或离线时回复对应提示`() =
        runBlocking {
            registry.bind(robot, target)
            port.result = SubmitResult.NOT_READY
            assertEquals(InboundOutcome.NOT_READY, router.onInbound(inbound("1", "a")))
            port.result = SubmitResult.OFFLINE
            assertEquals(InboundOutcome.OFFLINE, router.onInbound(inbound("2", "b")))
            assertEquals(listOf("NOT_READY", "OFFLINE"), sentTexts())
        }

    @Test
    fun `未提交成功的远程文本不参与回显抑制`() =
        runBlocking {
            registry.bind(robot, target)
            port.result = SubmitResult.NOT_READY
            router.onInbound(inbound("1", "同一句"))
            channel.sent.clear()
            router.onSessionEvents("tab-1", listOf(SessionEvent.UserText("同一句")))
            assertEquals(listOf("LOCAL:\n同一句"), sentTexts())
        }

    @Test
    fun `工具调用不单独通知，等待确认带原因`() =
        runBlocking {
            registry.bind(robot, target)
            router.onSessionEvents(
                "tab-1",
                listOf(
                    SessionEvent.ToolStarted,
                    SessionEvent.ToolStarted,
                ),
            )
            router.onWaiting("tab-1", "Edit")
            assertEquals(listOf("WAIT(Edit)"), sentTexts())
        }

    @Test
    fun `未绑定终端的事件不转发`() =
        runBlocking {
            router.onSessionEvents("tab-1", listOf(SessionEvent.AssistantText("x")))
            router.onWaiting("tab-1", null)
            assertTrue(channel.sent.isEmpty())
        }

    @Test
    fun `分段发送途中换绑，剩余分段不再发往旧目标`() =
        runBlocking {
            val small = FakeChannel(robot, maxTextBytes = 64)
            val r = BridgeRouter(registry, { owner }, port, { small }, Texts)
            registry.bind(robot, target)
            small.afterSend = { registry.bind(robot, target.copy(tabId = "tab-2")) }
            r.onSessionEvents("tab-1", listOf(SessionEvent.AssistantText("行内容\n".repeat(40))))
            r.onTurnCompleted("tab-1", r.currentTurn("tab-1"))
            assertEquals(1, small.sent.size)
        }

    private fun titledRouter(
        ch: FakeChannel,
        title: String? = "imux · Claude Code · 代理商改造",
    ) = BridgeRouter(registry, { owner }, port, { ch }, Texts, sessionLabelOf = { if (it.target == target) title else null })

    @Test
    fun `回复末尾署名：分割线下一行引用块`() =
        runBlocking {
            val r = titledRouter(channel)
            registry.bind(robot, target)
            r.onSessionEvents("tab-1", listOf(SessionEvent.AssistantText("答案\n")))
            r.onTurnCompleted("tab-1", r.currentTurn("tab-1"))
            assertEquals(listOf("答案\n\n---\n> imux · Claude Code · 代理商改造"), sentTexts())
        }

    @Test
    fun `不渲染 Markdown 的渠道用字符横线署名`() =
        runBlocking {
            val plain = FakeChannel(robot, rendersMarkdown = false)
            val r = titledRouter(plain)
            registry.bind(robot, target)
            r.onSessionEvents("tab-1", listOf(SessionEvent.AssistantText("答案")))
            r.onTurnCompleted("tab-1", r.currentTurn("tab-1"))
            assertEquals(listOf("答案\n\n──────────\nimux · Claude Code · 代理商改造"), plain.sent.map { it.text })
        }

    @Test
    fun `分段发送时只有最后一段署名，每段都不超过渠道上限`() =
        runBlocking {
            val small = FakeChannel(robot, maxTextBytes = 120)
            val r = titledRouter(small)
            registry.bind(robot, target)
            r.onSessionEvents("tab-1", listOf(SessionEvent.AssistantText("行内容\n".repeat(40).trimEnd())))
            r.onTurnCompleted("tab-1", r.currentTurn("tab-1"))
            val texts = small.sent.map { it.text }
            assertTrue("应当分成多段", texts.size > 1)
            assertTrue(texts.last().endsWith("\n\n---\n> imux · Claude Code · 代理商改造"))
            assertTrue("前面各段不署名", texts.dropLast(1).none { "代理商改造" in it })
            assertTrue(texts.all { it.toByteArray(Charsets.UTF_8).size <= 120 })
        }

    @Test
    fun `标题里的换行压成空格，过长的标题截短`() =
        runBlocking {
            val r = titledRouter(channel, "第一行\n第二行" + "长".repeat(150))
            registry.bind(robot, target)
            r.onSessionEvents("tab-1", listOf(SessionEvent.AssistantText("答案")))
            r.onTurnCompleted("tab-1", r.currentTurn("tab-1"))
            val footer = sentTexts().single().substringAfter("\n> ")
            assertTrue(footer.startsWith("第一行 第二行"))
            assertEquals(100, footer.length)
            assertTrue(footer.endsWith("…"))
        }

    @Test
    fun `电脑端输入转发到手机时同样署名`() =
        runBlocking {
            val r = titledRouter(channel)
            registry.bind(robot, target)
            r.onSessionEvents("tab-1", listOf(SessionEvent.UserText("改一下登录页")))
            assertEquals(listOf("LOCAL:\n改一下登录页\n\n---\n> imux · Claude Code · 代理商改造"), sentTexts())
        }

    @Test
    fun `真实署名经过路由后，长工程名不会挤掉智能体名和会话标题`() =
        runBlocking {
            val label = com.github.izerui.imux.bridge.sessionLabel("工".repeat(200), "Claude Code", "会".repeat(200))
            val r = titledRouter(channel, label)
            registry.bind(robot, target)
            r.onSessionEvents("tab-1", listOf(SessionEvent.AssistantText("答案")))
            r.onTurnCompleted("tab-1", r.currentTurn("tab-1"))
            val footer = sentTexts().single().substringAfter("\n> ")
            assertEquals("路由没有再截断", label, footer)
            assertTrue("智能体名还在", footer.contains(" · Claude Code · "))
            assertTrue("会话标题还在", footer.endsWith("会".repeat(39) + "…"))
        }

    @Test
    fun `标题开头像标题号或列表号时转义，纯文本渠道不转义`() =
        runBlocking {
            registry.bind(robot, target)
            suspend fun footerOf(
                title: String,
                ch: FakeChannel = FakeChannel(robot),
            ): String {
                val r = titledRouter(ch, title)
                r.onSessionEvents("tab-1", listOf(SessionEvent.AssistantText("答案")))
                r.onTurnCompleted("tab-1", r.currentTurn("tab-1"))
                return ch.sent.single().text.substringAfterLast("\n")
            }
            assertEquals("> \\# 修复登录", footerOf("# 修复登录"))
            assertEquals("> \\- 修复登录", footerOf("- 修复登录"))
            assertEquals("> 12\\. 修复登录", footerOf("12. 修复登录"))
            assertEquals("> 修复 #12", footerOf("修复 #12"))
            assertEquals("# 修复登录", footerOf("# 修复登录", FakeChannel(robot, rendersMarkdown = false)))
        }

    private val label = "\n\n---\n> imux · Claude Code · 代理商改造"

    @Test
    fun `等待确认、没有文字回复与结对状态这类提示都署名`() =
        runBlocking {
            val r = titledRouter(channel)
            registry.bind(robot, target)
            r.onWaiting("tab-1", "input needed")
            r.onSessionEvents("tab-1", listOf(SessionEvent.TurnStarted, SessionEvent.TurnEnded))
            r.onStatus("tab-1", "PEER")
            assertEquals(listOf("WAIT(input needed)$label", "NO_REPLY$label", "PEER$label"), sentTexts())
        }

    @Test
    fun `连接与恢复连接的提示正文已写明会话，不再署名；断开提示署名`() =
        runBlocking {
            val r = titledRouter(channel)
            val binding = (registry.bind(robot, target) as BindResult.Bound).binding
            r.onBound(binding, "imux", "Claude Code", "代理商改造")
            assertEquals("CONNECTED(imux/Claude Code/代理商改造)", sentTexts().last())
            r.onReattached(binding, "imux", "Claude Code", "代理商改造")
            assertEquals("CONNECTED(imux/Claude Code/代理商改造)", sentTexts().last())
            r.onReleased(binding, "代理商改造")
            assertEquals("RELEASED(代理商改造)$label", sentTexts().last())
        }

    @Test
    fun `回应手机消息时连着会话就署名，没连会话不署名`() =
        runBlocking {
            val r = titledRouter(channel)
            r.onInbound(inboundWith("m1", "你好"))
            assertEquals("没连会话", "UNBOUND", sentTexts().last())
            registry.bind(robot, target)
            r.onInbound(inboundWith("m2", "   "))
            assertEquals("连着会话", "EMPTY$label", sentTexts().last())
        }

    @Test
    fun `取不到署名时回复不署名`() =
        runBlocking {
            val untitled = titledRouter(channel, title = null)
            registry.bind(robot, target)
            untitled.onSessionEvents("tab-1", listOf(SessionEvent.AssistantText("答案")))
            untitled.onTurnCompleted("tab-1", untitled.currentTurn("tab-1"))
            assertEquals(listOf("答案"), sentTexts())
        }

    @Test
    fun `每轮只推最后一次工具调用之后的回复，中间过程不推`() =
        runBlocking {
            registry.bind(robot, target)
            router.onSessionEvents(
                "tab-1",
                listOf(
                    SessionEvent.AssistantText("我先看看"),
                    SessionEvent.ToolStarted,
                    SessionEvent.AssistantText("现在改"),
                    SessionEvent.ToolStarted,
                    SessionEvent.AssistantText("改好了"),
                ),
            )
            assertTrue("轮次完成前不推", channel.sent.isEmpty())
            router.onSessionEvents("tab-1", listOf(SessionEvent.AssistantText("总结")))
            router.onTurnCompleted("tab-1", router.currentTurn("tab-1"))
            assertEquals(listOf("改好了\n\n总结"), sentTexts())
        }

    @Test
    fun `没有工具调用时推整段回复，完成信号重复不重发`() =
        runBlocking {
            registry.bind(robot, target)
            router.onSessionEvents("tab-1", listOf(SessionEvent.AssistantText("答案")))
            router.onTurnCompleted("tab-1", router.currentTurn("tab-1"))
            router.onTurnCompleted("tab-1", router.currentTurn("tab-1"))
            assertEquals(listOf("答案"), sentTexts())
        }

    @Test
    fun `完成之后才读到的回复立即推送`() =
        runBlocking {
            registry.bind(robot, target)
            router.onTurnCompleted("tab-1", router.currentTurn("tab-1"))
            router.onSessionEvents("tab-1", listOf(SessionEvent.AssistantText("迟到的结论")))
            assertEquals(listOf("迟到的结论"), sentTexts())
        }

    @Test
    fun `本轮结论与下一轮开头在同一次读取里：按记录里的结束标记先发结论，迟到的 IDE 完成信号不再推新一轮的过程说明`() =
        runBlocking {
            registry.bind(robot, target)
            // IDE 的完成信号属于这一轮
            val firstTurn = router.currentTurn("tab-1")
            router.onSessionEvents(
                "tab-1",
                listOf(
                    SessionEvent.AssistantText("本轮结论"),
                    SessionEvent.TurnEnded,
                    // CLI 里排队的消息在本轮结束后立刻开始
                    SessionEvent.UserText("排队的下一句"),
                    SessionEvent.AssistantText("我先看看"),
                ),
            )
            assertEquals(listOf("本轮结论", "LOCAL:\n排队的下一句"), sentTexts())
            // IDE 的完成信号在轮询之后才到：此时下一轮已经开始，信号属于上一轮，作废
            router.onTurnCompleted("tab-1", firstTurn)
            assertEquals(listOf("本轮结论", "LOCAL:\n排队的下一句"), sentTexts())
            // 下一轮按自己的结束标记发结论
            router.onSessionEvents(
                "tab-1",
                listOf(
                    SessionEvent.ToolStarted,
                    SessionEvent.AssistantText("下一轮结论"),
                    SessionEvent.TurnEnded,
                ),
            )
            assertEquals(listOf("本轮结论", "LOCAL:\n排队的下一句", "下一轮结论"), sentTexts())
        }

    @Test
    fun `同一次连接里连续两轮：第一轮有结束标记，第二轮缺标记，仍以 IDE 完成信号发出第二轮结论`() =
        runBlocking {
            registry.bind(robot, target)
            router.onSessionEvents(
                "tab-1",
                listOf(
                    SessionEvent.UserText("第一句"),
                    SessionEvent.AssistantText("第一轮结论"),
                    SessionEvent.TurnEnded,
                ),
            )
            router.onTurnCompleted("tab-1", router.currentTurn("tab-1"))
            router.onSessionEvents(
                "tab-1",
                listOf(
                    SessionEvent.UserText("第二句"),
                    SessionEvent.ToolStarted,
                    SessionEvent.AssistantText("第二轮结论"),
                ),
            )
            assertEquals("没有结束标记时不提前发", listOf("LOCAL:\n第一句", "第一轮结论", "LOCAL:\n第二句"), sentTexts())
            router.onTurnCompleted("tab-1", router.currentTurn("tab-1"))
            assertEquals(listOf("LOCAL:\n第一句", "第一轮结论", "LOCAL:\n第二句", "第二轮结论"), sentTexts())
        }

    @Test
    fun `记录没有结束标记的标签，仍以 IDE 完成信号发结论`() =
        runBlocking {
            registry.bind(robot, target)
            router.onSessionEvents("tab-1", listOf(SessionEvent.AssistantText("结论")))
            router.onTurnCompleted("tab-1", router.currentTurn("tab-1"))
            assertEquals(listOf("结论"), sentTexts())
        }

    @Test
    fun `上一轮完成后，由注入消息开启的新一轮，过程说明不提前推送`() =
        runBlocking {
            registry.bind(robot, target)
            router.onSessionEvents("tab-1", listOf(SessionEvent.AssistantText("第一轮结论")))
            router.onTurnCompleted("tab-1", router.currentTurn("tab-1"))
            // 斜杠命令或后台任务完成通知开启了新一轮：没有人写的输入
            router.onSessionEvents(
                "tab-1",
                listOf(
                    SessionEvent.TurnStarted,
                    SessionEvent.AssistantText("我先看看日志"),
                    SessionEvent.ToolStarted,
                    SessionEvent.AssistantText("第二轮结论"),
                ),
            )
            assertEquals(listOf("第一轮结论"), sentTexts())
            router.onTurnCompleted("tab-1", router.currentTurn("tab-1"))
            assertEquals(listOf("第一轮结论", "第二轮结论"), sentTexts())
        }

    @Test
    fun `轮次进行中插话：此前的过程说明不推也不混进结论，电脑输入单独推`() =
        runBlocking {
            registry.bind(robot, target)
            router.onSessionEvents(
                "tab-1",
                listOf(
                    SessionEvent.AssistantText("我先看看日志"),
                    SessionEvent.UserText("顺便改一下 X"),
                    SessionEvent.AssistantText("好的，已经一起改了"),
                ),
            )
            assertEquals(listOf("LOCAL:\n顺便改一下 X"), sentTexts())
            router.onTurnCompleted("tab-1", router.currentTurn("tab-1"))
            assertEquals(listOf("LOCAL:\n顺便改一下 X", "好的，已经一起改了"), sentTexts())
        }

    @Test
    fun `轮次进行中的注入消息同样丢弃此前的过程说明`() =
        runBlocking {
            registry.bind(robot, target)
            router.onSessionEvents(
                "tab-1",
                listOf(
                    SessionEvent.AssistantText("我先等后台任务"),
                    SessionEvent.TurnStarted,
                    SessionEvent.AssistantText("后台任务完成了，结论是 Y"),
                ),
            )
            router.onTurnCompleted("tab-1", router.currentTurn("tab-1"))
            assertEquals(listOf("后台任务完成了，结论是 Y"), sentTexts())
        }

    @Test
    fun `等待确认立即推送，不带出未完成的回复`() =
        runBlocking {
            registry.bind(robot, target)
            router.onSessionEvents("tab-1", listOf(SessionEvent.AssistantText("还没说完")))
            router.onWaiting("tab-1", "Bash")
            assertEquals(listOf("WAIT(Bash)"), sentTexts())
        }

    @Test
    fun `换绑后上一绑定的待发结果作废`() =
        runBlocking {
            registry.bind(robot, target)
            router.onSessionEvents("tab-1", listOf(SessionEvent.AssistantText("旧结果")))
            registry.unbind(robot)
            registry.bind(robot, target)
            router.onTurnCompleted("tab-1", router.currentTurn("tab-1"))
            assertTrue(channel.sent.isEmpty())
        }

    @Test
    fun `过程说明之后、未收到完成信号就断开：只发断开提示，之后也不补发`() =
        runBlocking {
            val bound = (registry.bind(robot, target) as BindResult.Bound).binding
            router.onSessionEvents("tab-1", listOf(SessionEvent.AssistantText("我先看看")))
            registry.unbind(robot)
            router.onReleased(bound, "修复滚动")
            assertEquals(listOf("RELEASED(修复滚动)"), sentTexts())
            // 同一机器人重新绑回来，本轮完成时也不能把断开前的过程说明带出来
            registry.bind(robot, target)
            router.onTurnCompleted("tab-1", router.currentTurn("tab-1"))
            assertEquals(listOf("RELEASED(修复滚动)"), sentTexts())
        }

    @Test
    fun `断开后重新绑定，记录没有结束标记时重新以 IDE 完成信号发结论`() =
        runBlocking {
            val bound = (registry.bind(robot, target) as BindResult.Bound).binding
            router.onSessionEvents("tab-1", listOf(SessionEvent.AssistantText("一"), SessionEvent.TurnEnded))
            registry.unbind(robot)
            router.onReleased(bound, "修复滚动")
            registry.bind(robot, target)
            router.onSessionEvents("tab-1", listOf(SessionEvent.UserText("再来"), SessionEvent.AssistantText("二")))
            router.onTurnCompleted("tab-1", router.currentTurn("tab-1"))
            assertEquals("二", sentTexts().last())
        }

    @Test
    fun `关闭远程控制后，IDE 完成信号重新生效`() =
        runBlocking {
            registry.bind(robot, target)
            router.onSessionEvents("tab-1", listOf(SessionEvent.AssistantText("一"), SessionEvent.TurnEnded))
            router.discardPending()
            router.onSessionEvents("tab-1", listOf(SessionEvent.UserText("再来"), SessionEvent.AssistantText("二")))
            router.onTurnCompleted("tab-1", router.currentTurn("tab-1"))
            assertEquals("二", sentTexts().last())
        }

    private val robotB = RobotId("r2")
    private val ownerB = RobotOwner(senderId = "me-b", chatId = "chat-b")
    private val channelB = FakeChannel(robotB)

    /** 两个机器人共用同一标签：换绑时旧机器人 [robot] 让位给 [robotB]。 */
    private val switchRouter =
        BridgeRouter(
            registry,
            { mapOf(robot to owner, robotB to ownerB)[it] },
            port,
            { mapOf(robot to channel, robotB to channelB)[it] },
            Texts,
            notReadyRetryDelayMs = 0,
        )

    /** 与切换机器人的界面操作同序：先解绑旧的、绑定新的，旧绑定的清理随后异步执行。 */
    private fun switchToB(): Binding {
        val old = (registry.bind(robot, target) as BindResult.Bound).binding
        registry.unbind(robot)
        registry.bind(robotB, target)
        return old
    }

    @Test
    fun `换绑后旧绑定的清理晚到，不删新机器人已攒下的回复`() =
        runBlocking {
            val old = switchToB()
            switchRouter.onSessionEvents("tab-1", listOf(SessionEvent.AssistantText("新回复")))
            switchRouter.onReleased(old, "修复滚动")
            switchRouter.onTurnCompleted("tab-1", switchRouter.currentTurn("tab-1"))
            assertEquals(listOf("RELEASED(修复滚动)"), channel.sent.map { it.text })
            assertEquals(listOf("新回复"), channelB.sent.map { it.text })
        }

    @Test
    fun `换绑后旧绑定的清理晚到，不清新绑定的完成状态`() =
        runBlocking {
            val old = switchToB()
            switchRouter.onTurnCompleted("tab-1", switchRouter.currentTurn("tab-1"))
            switchRouter.onReleased(old, "修复滚动")
            // 完成之后才读到的回复是迟到的最终输出，应立即发出
            switchRouter.onSessionEvents("tab-1", listOf(SessionEvent.AssistantText("迟到的回复")))
            assertEquals(listOf("迟到的回复"), channelB.sent.map { it.text })
        }

    @Test
    fun `换绑后旧绑定的清理晚到，不清新绑定的回显登记`() =
        runBlocking {
            val old = switchToB()
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, switchRouter.onInbound(InboundMessage(robotB, "1", "me-b", "chat-b", "跑测试")))
            switchRouter.onReleased(old, "修复滚动")
            switchRouter.onSessionEvents("tab-1", listOf(SessionEvent.UserText("跑测试")))
            assertTrue(channelB.sent.isEmpty())
        }

    @Test
    fun `换绑机器人：旧机器人只收到断开提示，换绑前攒下的文字作废，不发给新机器人`() =
        runBlocking {
            val old = (registry.bind(robot, target) as BindResult.Bound).binding
            switchRouter.onSessionEvents("tab-1", listOf(SessionEvent.AssistantText("结论前半")))
            registry.unbind(robot)
            registry.bind(robotB, target)
            switchRouter.onReleased(old, "修复滚动")
            switchRouter.onTurnCompleted("tab-1", switchRouter.currentTurn("tab-1"))
            assertEquals(listOf("RELEASED(修复滚动)"), channel.sent.map { it.text })
            assertTrue(channelB.sent.isEmpty())
        }

    @Test
    fun `换绑机器人：换绑前的过程说明在之后调用工具时清掉，不发给任何一方`() =
        runBlocking {
            val old = (registry.bind(robot, target) as BindResult.Bound).binding
            switchRouter.onSessionEvents("tab-1", listOf(SessionEvent.AssistantText("我先看看")))
            registry.unbind(robot)
            registry.bind(robotB, target)
            switchRouter.onReleased(old, "修复滚动")
            switchRouter.onSessionEvents(
                "tab-1",
                listOf(
                    SessionEvent.ToolStarted,
                    SessionEvent.AssistantText("结论"),
                ),
            )
            switchRouter.onTurnCompleted("tab-1", switchRouter.currentTurn("tab-1"))
            assertEquals(listOf("RELEASED(修复滚动)"), channel.sent.map { it.text })
            assertEquals(listOf("结论"), channelB.sent.map { it.text })
        }

    @Test
    fun `关闭远程控制时丢弃未发出的回复`() =
        runBlocking {
            registry.bind(robot, target)
            router.onSessionEvents("tab-1", listOf(SessionEvent.AssistantText("关闭前的回复")))
            router.discardPending()
            router.onTurnCompleted("tab-1", router.currentTurn("tab-1"))
            assertTrue(channel.sent.isEmpty())
        }

    @Test
    fun `释放时向原聊天发送断开提示`() =
        runBlocking {
            val bound = (registry.bind(robot, target) as BindResult.Bound).binding
            registry.unbind(robot)
            router.onReleased(bound, "修复滚动")
            assertEquals(listOf("RELEASED(修复滚动)"), sentTexts())
        }

    @Test
    fun `port 抛出异常时返回 OFFLINE 且回显登记不泄漏`() =
        runBlocking {
            val throwingPort =
                object : TerminalSessionPort {
                    override suspend fun submit(target: SessionTarget, text: String): SubmitResult {
                        throw IllegalStateException("boom")
                    }
                }
            val r = BridgeRouter(registry, { if (it == robot) owner else null }, throwingPort, { if (it == robot) channel else null }, Texts)
            registry.bind(robot, target)
            assertEquals(InboundOutcome.OFFLINE, r.onInbound(inbound("1", "炸了")))
            assertEquals(listOf("OFFLINE"), sentTexts())
            // 回显登记已清除：同样文本的本地输入应带标签转发
            channel.sent.clear()
            r.onSessionEvents("tab-1", listOf(SessionEvent.UserText("炸了")))
            assertEquals(listOf("LOCAL:\n炸了"), channel.sent.map { it.text })
        }

    @Test(expected = kotlin.coroutines.cancellation.CancellationException::class)
    fun `port 抛出 CancellationException 时传播且消息 id 不被标记为已见`(): Unit =
        runBlocking {
            val cancellingPort =
                object : TerminalSessionPort {
                    override suspend fun submit(target: SessionTarget, text: String): SubmitResult {
                        throw kotlin.coroutines.cancellation.CancellationException("cancelled")
                    }
                }
            val r = BridgeRouter(registry, { if (it == robot) owner else null }, cancellingPort, { if (it == robot) channel else null }, Texts)
            registry.bind(robot, target)
            try {
                r.onInbound(inbound("1", "取消"))
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                // 重发同一 id 不应被视为 DUPLICATE
                // 换一个不抛异常的 port 来验证
                val normalPort = FakePort()
                val r2 = BridgeRouter(registry, { if (it == robot) owner else null }, normalPort, { if (it == robot) channel else null }, Texts)
                assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, r2.onInbound(inbound("1", "取消")))
                throw e
            }
        }

    @Test
    fun `排队期间绑定变更后不提交到旧终端`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val suspendingPort =
                object : TerminalSessionPort {
                    val submitted = mutableListOf<Pair<String, String>>()

                    override suspend fun submit(target: SessionTarget, text: String): SubmitResult {
                        gate.await()
                        submitted += target.tabId to text
                        return SubmitResult.SUBMITTED_TO_TERMINAL
                    }
                }
            val r = BridgeRouter(registry, { if (it == robot) owner else null }, suspendingPort, { if (it == robot) channel else null }, Texts)
            registry.bind(robot, target)

            // 第一条消息进入 submit 后挂起
            val job1 = launch { r.onInbound(inbound("1", "先到")) }
            // 让 job1 有时间拿到 submitLock 并进入 submit
            kotlinx.coroutines.yield()
            kotlinx.coroutines.yield()

            // 第二条消息排队等锁
            val deferred2 = async { r.onInbound(inbound("2", "后到")) }
            kotlinx.coroutines.yield()

            // 换绑到另一个终端
            registry.bind(robot, target.copy(tabId = "tab-2"))

            // 放行第一条
            gate.complete(Unit)
            job1.join()

            val outcome2 = deferred2.await()
            // 第二条不应提交到任何终端：检测到绑定已变，提示本人重新发送
            assertEquals(InboundOutcome.REBOUND, outcome2)
            // 只有第一条被提交
            assertEquals(listOf("tab-1" to "先到"), suspendingPort.submitted)
            assertTrue(channel.sent.any { it.text == "REBOUND" })
        }

    @Test
    fun `终端未就绪时由路由有限重试，就绪后提交成功`() =
        runBlocking {
            val scripted = ScriptedPort(listOf(SubmitResult.NOT_READY, SubmitResult.NOT_READY, SubmitResult.SUBMITTED_TO_TERMINAL))
            val r = routerWith(scripted)
            registry.bind(robot, target)
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, r.onInbound(inbound("1", "稍等就绪")))
            assertEquals(List(3) { "tab-1" to "稍等就绪" }, scripted.submitted)
            assertTrue(channel.sent.isEmpty())
        }

    @Test
    fun `终端一直未就绪时重试三次后回复未就绪`() =
        runBlocking {
            val scripted = ScriptedPort(listOf(SubmitResult.NOT_READY))
            val r = routerWith(scripted)
            registry.bind(robot, target)
            assertEquals(InboundOutcome.NOT_READY, r.onInbound(inbound("1", "a")))
            assertEquals(3, scripted.submitted.size)
            assertEquals(listOf("NOT_READY"), sentTexts())
        }

    @Test
    fun `未就绪重试期间换绑，不再提交到旧终端`() =
        runBlocking {
            val scripted =
                ScriptedPort(listOf(SubmitResult.NOT_READY)) { call ->
                    if (call == 0) registry.bind(robot, target.copy(tabId = "tab-2"))
                }
            val r = routerWith(scripted)
            registry.bind(robot, target)
            assertEquals(InboundOutcome.REBOUND, r.onInbound(inbound("1", "换绑了")))
            assertEquals(listOf("tab-1" to "换绑了"), scripted.submitted)
            assertEquals(listOf("REBOUND"), sentTexts())
        }

    @Test
    fun `绑定后向本人发送当前连接`() =
        runBlocking {
            val bound = (registry.bind(robot, target) as BindResult.Bound).binding
            router.onBound(bound, "imux", "Codex", "修复终端滚动")
            assertEquals(listOf("CONNECTED(imux/Codex/修复终端滚动)"), sentTexts())
        }

    @Test
    fun `换绑提示按顺序发：被断开一方的提示还在等发送时，新连接的提示不抢先`() =
        runBlocking {
            val occupantRobot = RobotId("occupant")
            val gate = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()

            // 被断开一方的渠道卡在发送上（例如限频等待），直到放行
            class Recording(
                override val robotId: RobotId,
                val held: Boolean,
            ) : ChannelAdapter {
                override val maxTextBytes = 1000

                override suspend fun send(message: OutboundMessage): DeliveryResult {
                    if (held) gate.await()
                    order += "${robotId.value}:${message.text}"
                    return DeliveryResult.Accepted
                }
            }
            val channels = mapOf(robot to Recording(robot, held = false), occupantRobot to Recording(occupantRobot, held = true))
            val r = BridgeRouter(registry, { owner }, port, { channels[it] }, Texts, notReadyRetryDelayMs = 0)
            val occupant = (registry.bind(occupantRobot, target) as BindResult.Bound).binding
            registry.unbind(occupantRobot)
            val bound = (registry.bind(robot, target) as BindResult.Bound).binding

            val job = launch { r.onRebound(listOf(occupant to "旧会话"), bound, "imux", "Codex", "新会话") }
            repeat(5) { yield() }
            assertEquals("断开提示还没发出，连接提示不能先发", emptyList<String>(), order)
            gate.complete(Unit)
            job.join()
            assertEquals(listOf("occupant:RELEASED(旧会话)", "r1:CONNECTED(imux/Codex/新会话)"), order)
        }

    @Test
    fun `绑定没成功时只发断开提示`() =
        runBlocking {
            val old = (registry.bind(robot, target) as BindResult.Bound).binding
            registry.unbind(robot)
            router.onRebound(listOf(old to "旧会话"), null, "imux", "Codex", "新会话")
            assertEquals(listOf("RELEASED(旧会话)"), sentTexts())
        }

    @Test
    fun `已被换绑的旧绑定不再发送当前连接`() =
        runBlocking {
            val old = (registry.bind(robot, target) as BindResult.Bound).binding
            registry.bind(robot, target.copy(tabId = "tab-2"))
            router.onBound(old, "imux", "Codex", "旧会话")
            assertTrue(channel.sent.isEmpty())
        }

    @Test
    fun `提交失败时清除的是提交目标标签上的回显登记`() =
        runBlocking {
            // 提交期间换绑到 tab-2，再失败：必须清 tab-1 上的登记，而不是 tab-2
            val scripted =
                ScriptedPort(listOf(SubmitResult.OFFLINE)) { registry.bind(robot, target.copy(tabId = "tab-2")) }
            val r = routerWith(scripted)
            registry.bind(robot, target)
            assertEquals(InboundOutcome.OFFLINE, r.onInbound(inbound("1", "同一句")))
            // 换回 tab-1 后，电脑上输入的同样文本应带标签转发，不被残留登记吞掉
            registry.bind(robot, target)
            channel.sent.clear()
            r.onSessionEvents("tab-1", listOf(SessionEvent.UserText("同一句")))
            assertEquals(listOf("LOCAL:\n同一句"), sentTexts())
        }

    @Test
    fun `非本人、重复或未绑定时不取回附件`() =
        runBlocking {
            val store = FakeStore(listOf(PlaceResult.Saved("p.png"), PlaceResult.Saved("p.png")))
            val r = routerWith(store)
            assertEquals(InboundOutcome.UNAUTHORIZED, r.onInbound(inboundWith("1", "", FakeAttachment(), sender = "stranger")))
            assertEquals(InboundOutcome.UNBOUND, r.onInbound(inboundWith("2", "", FakeAttachment())))
            registry.bind(robot, target)
            r.onInbound(inboundWith("3", "", FakeAttachment()))
            assertEquals(InboundOutcome.DUPLICATE, r.onInbound(inboundWith("3", "", FakeAttachment())))
            assertEquals(1, store.placed.size)
        }

    @Test
    fun `文字加附件时提交文字与附件行`() =
        runBlocking {
            registry.bind(robot, target)
            val r = routerWith(FakeStore(listOf(PlaceResult.Saved(".imux/a.png"), PlaceResult.Saved(".imux/b.pdf"))))
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, r.onInbound(inboundWith("1", " 看看 ", FakeAttachment(), FakeAttachment(AttachmentKind.FILE))))
            assertEquals(listOf("tab-1" to "看看\n\nATT(.imux/a.png)\nATT(.imux/b.pdf)"), port.submitted)
            assertTrue(channel.sent.isEmpty())
        }

    @Test
    fun `只有附件时用附件提示提交`() =
        runBlocking {
            registry.bind(robot, target)
            val r = routerWith(FakeStore(listOf(PlaceResult.Saved(".imux/a.png"))))
            r.onInbound(inboundWith("1", "", FakeAttachment()))
            assertEquals(listOf("tab-1" to "ONLY(.imux/a.png)"), port.submitted)
        }

    @Test
    fun `部分附件失败时回复一次提示，成功的照常提交`() =
        runBlocking {
            registry.bind(robot, target)
            val r = routerWith(FakeStore(listOf(PlaceResult.Failed("x"), PlaceResult.Saved(".imux/b.png"), PlaceResult.Failed("y"))))
            r.onInbound(inboundWith("1", "", FakeAttachment(), FakeAttachment(), FakeAttachment()))
            assertEquals(listOf("ATT_FAILED"), sentTexts())
            assertEquals(listOf("tab-1" to "ONLY(.imux/b.png)"), port.submitted)
        }

    @Test
    fun `附件全部失败但有文字时，回复提示并照常提交文字`() =
        runBlocking {
            registry.bind(robot, target)
            val r = routerWith(FakeStore(listOf(PlaceResult.TooLarge)))
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, r.onInbound(inboundWith("1", "看看", FakeAttachment(AttachmentKind.VIDEO))))
            assertEquals(listOf("ATT_TOO_LARGE"), sentTexts())
            assertEquals(listOf("tab-1" to "看看"), port.submitted)
        }

    @Test
    fun `附件全部失败且没有文字时不提交`() =
        runBlocking {
            registry.bind(robot, target)
            val r = routerWith(FakeStore(listOf(PlaceResult.Failed("x"), PlaceResult.TooLarge)))
            assertEquals(InboundOutcome.ATTACHMENT_FAILED, r.onInbound(inboundWith("1", "", FakeAttachment(), FakeAttachment())))
            assertEquals(listOf("ATT_TOO_LARGE", "ATT_FAILED"), sentTexts())
            assertTrue(port.submitted.isEmpty())
        }

    @Test
    fun `只有空白文字且没有附件仍按空输入处理`() =
        runBlocking {
            registry.bind(robot, target)
            assertEquals(InboundOutcome.EMPTY, routerWith(FakeStore(emptyList())).onInbound(inboundWith("1", "  ")))
        }

    @Test
    fun `附件失败原因记一条警告，只含原因`() =
        runBlocking {
            registry.bind(robot, target)
            val warnings = mutableListOf<String>()
            val r =
                BridgeRouter(
                    registry,
                    { if (it == robot) owner else null },
                    port,
                    { if (it == robot) channel else null },
                    Texts,
                    attachmentStore = FakeStore(listOf(PlaceResult.Failed("HTTP 404"), PlaceResult.Saved(".imux/b.png"), PlaceResult.TooLarge)),
                    notReadyRetryDelayMs = 0,
                    onWarning = { warnings += it },
                )
            r.onInbound(inboundWith("1", "看看", FakeAttachment(), FakeAttachment(), FakeAttachment()))
            assertEquals(1, warnings.size)
            assertTrue(warnings[0].contains("HTTP 404"))
            assertFalse(warnings[0].contains(owner.senderId))
            assertFalse(warnings[0].contains(owner.chatId))
            assertFalse(warnings[0].contains("看看"))
        }

    /** 终端屏幕上的框按脚本逐次读出（读完停在最后一个），并记录提交与按键。 */
    private class PromptPort(private val screens: List<ScreenPrompt?>) : TerminalSessionPort {
        val submitted = mutableListOf<String>()
        val pressed = mutableListOf<String>()
        private var reads = 0

        override suspend fun submit(
            target: SessionTarget,
            text: String,
        ): SubmitResult {
            submitted += text
            return SubmitResult.SUBMITTED_TO_TERMINAL
        }

        override suspend fun prompt(target: SessionTarget): ScreenPrompt? = screens[minOf(reads++, screens.lastIndex)]

        override suspend fun press(
            target: SessionTarget,
            keys: String,
        ): SubmitResult {
            pressed += keys
            return SubmitResult.SUBMITTED_TO_TERMINAL
        }
    }

    private val choose = ScreenPrompt("Do you want to proceed?\n❯ 1. Yes\n  2. No", options = setOf(1, 2))
    private val ask =
        ScreenPrompts.extract(
            "你更喜欢哪种宠物？\n❯ 1. cat\n  2. dog\n  3. Type something.\n  4. Chat about this\nEnter to select · Esc to cancel",
        )!!

    private fun promptRouter(
        p: TerminalSessionPort,
        ch: FakeChannel = channel,
    ) = BridgeRouter(
        registry,
        { if (it == robot) owner else null },
        p,
        { if (it == robot) ch else null },
        Texts,
        notReadyRetryDelayMs = 0,
        keyGapMs = 0,
        promptSettleMs = 0,
    )

    @Test
    fun `等待时屏幕上有选择框就排成列表发到手机，渲染 Markdown 的编号加粗，不渲染的发纯文字`() =
        runBlocking {
            registry.bind(robot, target)
            promptRouter(PromptPort(listOf(choose))).onWaiting("tab-1", "permission prompt")
            assertEquals(listOf("PROMPT(Do you want to proceed?\n\n**1.** Yes  \n**2.** No)"), sentTexts())

            val plain = FakeChannel(robot, rendersMarkdown = false)
            promptRouter(PromptPort(listOf(choose)), plain).onWaiting("tab-1", "permission prompt")
            assertEquals(listOf("PROMPT(Do you want to proceed?\n\n1. Yes\n2. No)"), plain.sent.map { it.text })
        }

    @Test
    fun `等待时第一次没读到框会再读一次，仍读不到才只报等待原因`() =
        runBlocking {
            registry.bind(robot, target)
            promptRouter(PromptPort(listOf(null, choose))).onWaiting("tab-1", "permission prompt")
            promptRouter(PromptPort(listOf(null))).onWaiting("tab-1", "permission prompt")
            assertEquals(listOf("PROMPT(${ScreenPrompts.render(choose, true)})", "WAIT(permission prompt)"), sentTexts())
        }

    @Test
    fun `停在选择框上时明确选择只按键，不当作消息提交`() =
        runBlocking {
            registry.bind(robot, target)
            val p = PromptPort(listOf(choose))
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, promptRouter(p).onInbound(inbound("1", "选 2")))
            assertEquals(listOf("2"), p.pressed)
            assertTrue(p.submitted.isEmpty())
        }

    @Test
    fun `回复个人要求时移动到输入项并粘贴原文后提交`() =
        runBlocking {
            registry.bind(robot, target)
            val p = PromptPort(listOf(ask, null))
            promptRouter(p).onInbound(inbound("1", "仓鼠\n小的那种"))
            assertEquals(listOf("\u001b[B", "\u001b[B", "\u001b[200~仓鼠\n小的那种\u001b[201~", ScreenPrompts.ENTER), p.pressed)
            assertTrue(p.submitted.isEmpty())
        }

    @Test
    fun `没有可填写选项时按 Esc 拒绝这个框，再把这句话照常提交给会话`() =
        runBlocking {
            registry.bind(robot, target)
            val p = PromptPort(listOf(choose))
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, promptRouter(p).onInbound(inbound("1", "改用 pnpm")))
            assertEquals(listOf(ScreenPrompts.ESC), p.pressed)
            assertEquals(listOf("改用 pnpm"), p.submitted)
            assertFalse(sentTexts().contains("PROMPT_REJECTED"))
        }

    @Test
    fun `屏幕上没有框时消息照常提交`() =
        runBlocking {
            registry.bind(robot, target)
            val none = PromptPort(listOf(null))
            promptRouter(none).onInbound(inbound("1", "2"))
            assertEquals(listOf("2"), none.submitted)
            assertTrue(none.pressed.isEmpty())

        }

    @Test
    fun `引用选择框作答时只使用回复正文选择`() =
        runBlocking {
            registry.bind(robot, target)
            val quoted = PromptPort(listOf(choose))
            assertEquals(
                InboundOutcome.SUBMITTED_TO_TERMINAL,
                promptRouter(quoted).onInbound(InboundMessage(robot, "2", "me", "chat-1", "选 1", quote = "上一段")),
            )
            assertTrue(quoted.submitted.isEmpty())
            assertEquals(listOf("1"), quoted.pressed)
        }

    @Test
    fun `选择框打开时附件不提交以免误确认当前选项`() =
        runBlocking {
            registry.bind(robot, target)
            val attached = PromptPort(listOf(choose))
            val r =
                BridgeRouter(
                    registry, { owner }, attached, { channel }, Texts,
                    attachmentStore = { _, _ -> PlaceResult.Saved("a.png") },
                    notReadyRetryDelayMs = 0, keyGapMs = 0, promptSettleMs = 0,
                )
            assertEquals(InboundOutcome.PROMPT_REJECTED, r.onInbound(inboundWith("3", "1", FakeAttachment())))
            assertTrue(attached.submitted.isEmpty())
            assertTrue(attached.pressed.isEmpty())
        }

    @Test
    fun `作答后屏幕换成新框就推给手机，框没变或已关闭都不推`() =
        runBlocking {
            registry.bind(robot, target)
            val next = ScreenPrompt("你喜欢哪些水果？\n❯ 1. [ ] 苹果", options = setOf(1), checkboxOptions = setOf(1))
            promptRouter(PromptPort(listOf(ask, next))).onInbound(inbound("1", "选 1"))
            assertEquals(listOf("PROMPT(${ScreenPrompts.render(next, true)})"), sentTexts())

            promptRouter(PromptPort(listOf(ask, ask))).onInbound(inbound("2", "下"))
            promptRouter(PromptPort(listOf(ask, null))).onInbound(inbound("3", "选 2"))
            assertEquals(1, channel.sent.size)
        }

    @Test
    fun `单选题回多个编号或不存在的编号时不按编号、不替人答下一题，原话填进自定义输入项`() =
        runBlocking {
            registry.bind(robot, target)
            val p = PromptPort(listOf(ask, null))
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, promptRouter(p).onInbound(inbound("1", "选 1 3")))
            assertEquals(listOf("\u001b[B", "\u001b[B", "\u001b[200~选 1 3\u001b[201~", ScreenPrompts.ENTER), p.pressed)
            assertTrue(p.submitted.isEmpty())
            assertFalse(sentTexts().contains("PROMPT_REJECTED"))
        }

    @Test
    fun `多选题回多个编号勾好后提交这道题`() =
        runBlocking {
            registry.bind(robot, target)
            val p = PromptPort(listOf(ScreenPrompts.extract(RealScreens.MULTI_SELECT)))
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, promptRouter(p).onInbound(inbound("1", "选 1 2")))
            assertEquals(listOf("1", "2", "\u001b[B", "\u001b[B", "\u001b[B", ScreenPrompts.ENTER), p.pressed)
            assertTrue(p.submitted.isEmpty())
        }

    @Test
    fun `回自定义输入项本身的编号不单独按编号，原话填进输入项提交`() =
        runBlocking {
            registry.bind(robot, target)
            val p = PromptPort(listOf(ask, null))
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, promptRouter(p).onInbound(inbound("1", "选 3")))
            assertEquals(listOf("\u001b[B", "\u001b[B", "\u001b[200~选 3\u001b[201~", ScreenPrompts.ENTER), p.pressed)
            assertFalse(sentTexts().contains("PROMPT_REJECTED"))
        }

    @Test
    fun `真实多选画面混选离题项时不按编号，原话填进自定义输入项后提交`() =
        runBlocking {
            registry.bind(robot, target)
            val p = PromptPort(listOf(ScreenPrompts.extract(RealScreens.MULTI_SELECT), null))
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, promptRouter(p).onInbound(inbound("1", "选 4 1")))
            assertEquals(listOf("\u001b[B", "\u001b[B", "\u001b[200~选 4 1\u001b[201~", "\u001b[B", ScreenPrompts.ENTER), p.pressed)
            assertTrue(p.submitted.isEmpty())
        }

    @Test
    fun `多选框的个人要求填入自定义输入项后提交，不触发取消或普通消息通道`() =
        runBlocking {
            registry.bind(robot, target)
            val p = PromptPort(listOf(ScreenPrompts.extract(RealScreens.MULTI_SELECT), null))
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, promptRouter(p).onInbound(inbound("1", "苹果，但不要太酸")))
            assertEquals(
                listOf("\u001b[B", "\u001b[B", "\u001b[200~苹果，但不要太酸\u001b[201~", "\u001b[B", ScreenPrompts.ENTER),
                p.pressed,
            )
            assertTrue(p.submitted.isEmpty())
        }

    @Test
    fun `只回数字时对上选项就按编号，对不上的 8080 原话填进自定义输入项`() =
        runBlocking {
            registry.bind(robot, target)
            val p = PromptPort(listOf(ask, null, ask, null))
            val r = promptRouter(p)
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, r.onInbound(inbound("1", "2")))
            assertEquals(listOf("2"), p.pressed)
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, r.onInbound(inbound("2", "8080")))
            assertEquals(listOf("2", "\u001b[B", "\u001b[B", "\u001b[200~8080\u001b[201~", ScreenPrompts.ENTER), p.pressed)
            assertTrue(p.submitted.isEmpty())
        }

    @Test
    fun `自定义答案保留首尾空格与内部换行`() =
        runBlocking {
            registry.bind(robot, target)
            val p = PromptPort(listOf(ask, null))
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, promptRouter(p).onInbound(inbound("1", "  猫\n  不要黑色的  ")))
            assertEquals(
                listOf("\u001b[B", "\u001b[B", "\u001b[200~  猫\n  不要黑色的  \u001b[201~", ScreenPrompts.ENTER),
                p.pressed,
            )
            assertTrue(p.submitted.isEmpty())
        }
}
