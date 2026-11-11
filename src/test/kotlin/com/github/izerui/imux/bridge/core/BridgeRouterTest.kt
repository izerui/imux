package com.github.izerui.imux.bridge.core

import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.transcript.ClaudeTranscriptDecoder
import com.github.izerui.imux.bridge.transcript.CodexTranscriptDecoder
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.session.ClaudeRuntimeSession
import com.github.izerui.imux.turn.RuntimeStatusTracker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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

        override fun permission(listing: String) = "PERMISSION($listing)"

        override fun plan(plan: String) = "PLAN($plan)"


        override fun dialogOpen() = "DIALOG_OPEN"

        override fun question(listing: String, number: Int, total: Int) = "QUESTION$number/$total($listing)"


        override fun multiSelectMark() = "(多选)"

        override fun promptAnsweredLocally(answer: String?) = "ANSWERED_LOCALLY($answer)"

        override fun promptRejected() = "PROMPT_REJECTED"

        override fun released(sessionTitle: String) = "RELEASED($sessionTitle)"

        override fun suspended(sessionTitle: String) = "SUSPENDED($sessionTitle)"

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
            assertEquals(listOf("SUSPENDED(修复登录)\n\n---\n` imux · Codex · 修复登录 `"), sentTexts())
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
            assertEquals(listOf("SUSPENDED(修复登录)"), sentTexts())
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
                assertEquals(listOf("SUSPENDED(修复登录)"), delivered)
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
                assertEquals(listOf("SUSPENDED(修复登录)"), sentTexts())
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
            assertEquals(listOf("答案\n\n---\n` imux · Claude Code · 代理商改造 `"), sentTexts())
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
            assertTrue(texts.last().endsWith("\n\n---\n` imux · Claude Code · 代理商改造 `"))
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
            val footer = sentTexts().single().substringAfter("\n---\n` ").removeSuffix(" `")
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
            assertEquals(listOf("LOCAL:\n改一下登录页\n\n---\n` imux · Claude Code · 代理商改造 `"), sentTexts())
        }

    @Test
    fun `真实署名经过路由后，长工程名不会挤掉智能体名和会话标题`() =
        runBlocking {
            val label = com.github.izerui.imux.bridge.sessionLabel("工".repeat(200), "Claude Code", "会".repeat(200))
            val r = titledRouter(channel, label)
            registry.bind(robot, target)
            r.onSessionEvents("tab-1", listOf(SessionEvent.AssistantText("答案")))
            r.onTurnCompleted("tab-1", r.currentTurn("tab-1"))
            val footer = sentTexts().single().substringAfter("\n---\n` ").removeSuffix(" `")
            assertEquals("路由没有再截断", label, footer)
            assertTrue("智能体名还在", footer.contains(" · Claude Code · "))
            assertTrue("会话标题还在", footer.endsWith(" · " + "会".repeat(40) + "…"))
        }

    @Test
    fun `署名放进行内代码，标题号列表号不用转义，带反引号时加长外层`() =
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
            assertEquals("` # 修复登录 `", footerOf("# 修复登录"))
            assertEquals("` 12. 修复登录 `", footerOf("12. 修复登录"))
            assertEquals("``` 修复 `a` 和 ``b`` ```", footerOf("修复 `a` 和 ``b``"))
        }

    private val label = "\n\n---\n` imux · Claude Code · 代理商改造 `"

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

    /** 记录按键与提交，按键结果可以指定。选择框不看屏幕，端口也不提供读屏。 */
    private class KeysPort(var pressResult: SubmitResult = SubmitResult.SUBMITTED_TO_TERMINAL) : TerminalSessionPort {
        val pressed = mutableListOf<String>()
        val submitted = mutableListOf<String>()

        override suspend fun submit(
            target: SessionTarget,
            text: String,
        ): SubmitResult {
            submitted += text
            return SubmitResult.SUBMITTED_TO_TERMINAL
        }

        override suspend fun press(
            target: SessionTarget,
            keys: String,
        ): SubmitResult {
            pressed += keys
            return pressResult
        }
    }

    private val fruit = AskedQuestion("选哪个？", false, listOf(AskedOption("苹果", "红"), AskedOption("香蕉", null)))
    private val drink = AskedQuestion("喝什么？", false, listOf(AskedOption("茶", null), AskedOption("咖啡", null)))

    private fun asked(vararg questions: AskedQuestion) = SessionEvent.QuestionAsked("q1", questions.toList())

    private val firstPushed = "QUESTION1/2(${AskedQuestions.render(fruit, "(多选)")})"
    private val secondPushed = "QUESTION2/2(${AskedQuestions.render(drink, "(多选)")})"

    // ---- 提问 ----

    @Test
    fun `提问：等待时只推第 1 题，等待通知重复到达不重发`() =
        runBlocking {
            registry.bind(robot, target)
            val r = promptRouter(KeysPort())
            r.onSessionEvents("tab-1", listOf(asked(fruit, drink)))
            r.onWaiting("tab-1", "input needed")
            r.onWaiting("tab-1", "input needed")
            assertEquals(listOf(firstPushed), sentTexts())
        }

    @Test
    fun `提问：等待通知先于记录到达，读到提问后补推`() =
        runBlocking {
            registry.bind(robot, target)
            val r = promptRouter(KeysPort())
            r.onWaiting("tab-1", "input needed")
            r.onSessionEvents("tab-1", listOf(asked(fruit, drink)))
            assertEquals(listOf("WAIT(input needed)", firstPushed), sentTexts())
        }

    @Test
    fun `提问：一题一题答，电脑上同时按右键翻到下一题；最后一题答完 Esc 关框，各题答案作为一条消息发出，随后的结果、中断与回显不推`() =
        runBlocking {
            registry.bind(robot, target)
            val port = KeysPort()
            val r = promptRouter(port)
            r.onSessionEvents("tab-1", listOf(asked(fruit, drink)))
            r.onWaiting("tab-1", "input needed")
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, r.onInbound(inbound("1", "2")))
            assertEquals("电脑上只按右键翻到第 2 题，不按编号", listOf(TerminalKeys.RIGHT), port.pressed)
            assertEquals(listOf(firstPushed, secondPushed), sentTexts())
            assertTrue(port.submitted.isEmpty())
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, r.onInbound(inbound("2", "1")))
            val answer = "选哪个？ → 香蕉\n喝什么？ → 茶"
            assertEquals(listOf(TerminalKeys.RIGHT, TerminalKeys.ESC), port.pressed)
            assertEquals(listOf(answer), port.submitted)
            r.onSessionEvents("tab-1", listOf(SessionEvent.ToolResult("q1", null), SessionEvent.TurnInterrupted, SessionEvent.UserText(answer)))
            assertEquals(2, sentTexts().size)
        }

    @Test
    fun `提问：中间一题随口一说，电脑上只翻页不作答，原话作为这道题的回答`() =
        runBlocking {
            registry.bind(robot, target)
            val port = KeysPort()
            val r = promptRouter(port)
            r.onSessionEvents("tab-1", listOf(asked(fruit, drink)))
            r.onWaiting("tab-1", "input needed")
            r.onInbound(inbound("1", "都行，你定"))
            r.onInbound(inbound("2", "咖啡"))
            assertEquals(listOf(TerminalKeys.RIGHT, TerminalKeys.ESC), port.pressed)
            assertEquals(listOf("选哪个？ → 都行，你定\n喝什么？ → 咖啡"), port.submitted)
        }

    @Test
    fun `提问：只有一题时随口一说，Esc 关框后原话作为回答发出`() =
        runBlocking {
            registry.bind(robot, target)
            val port = KeysPort()
            val r = promptRouter(port)
            r.onSessionEvents("tab-1", listOf(asked(fruit)))
            r.onWaiting("tab-1", "input needed")
            r.onInbound(inbound("1", "第二个吧"))
            assertEquals(listOf(TerminalKeys.ESC), port.pressed)
            assertEquals(listOf("选哪个？ → 第二个吧"), port.submitted)
        }

    @Test
    fun `提问：手机答到一半在电脑上答完了，手机收到答了什么，已记下的答案不再发出`() =
        runBlocking {
            registry.bind(robot, target)
            val port = KeysPort()
            val r = promptRouter(port)
            r.onSessionEvents("tab-1", listOf(asked(fruit, drink)))
            r.onWaiting("tab-1", "input needed")
            r.onInbound(inbound("1", "2"))
            r.onSessionEvents("tab-1", listOf(SessionEvent.ToolResult("q1", "选哪个？\n→ 香蕉\n\n喝什么？\n→ 咖啡")))
            assertEquals("ANSWERED_LOCALLY(选哪个？\n→ 香蕉\n\n喝什么？\n→ 咖啡)", sentTexts().last())
            r.onResumed("tab-1")
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, r.onInbound(inbound("2", "继续")))
            assertEquals(listOf("继续"), port.submitted)
        }

    @Test
    fun `提问：在电脑上答了，手机收到答了什么；别的调用的结果不算`() =
        runBlocking {
            registry.bind(robot, target)
            val r = promptRouter(KeysPort())
            r.onSessionEvents("tab-1", listOf(asked(fruit)))
            r.onWaiting("tab-1", "input needed")
            r.onSessionEvents("tab-1", listOf(SessionEvent.ToolResult("other", null)))
            assertEquals(1, sentTexts().size)
            r.onSessionEvents("tab-1", listOf(SessionEvent.ToolResult("q1", "选哪个？\n→ 苹果")))
            assertEquals("ANSWERED_LOCALLY(选哪个？\n→ 苹果)", sentTexts().last())
        }

    @Test
    fun `提问：按键失败时这题不算答过，再回一次照样作答`() =
        runBlocking {
            registry.bind(robot, target)
            val port = KeysPort(SubmitResult.OFFLINE)
            val r = promptRouter(port)
            r.onSessionEvents("tab-1", listOf(asked(fruit, drink)))
            r.onWaiting("tab-1", "input needed")
            assertEquals(InboundOutcome.OFFLINE, r.onInbound(inbound("1", "1")))
            assertEquals("没翻页就不推下一题，只回离线提示", listOf(firstPushed, "OFFLINE"), sentTexts())
            port.pressResult = SubmitResult.SUBMITTED_TO_TERMINAL
            r.onInbound(inbound("2", "2"))
            port.pressResult = SubmitResult.OFFLINE
            assertEquals(InboundOutcome.OFFLINE, r.onInbound(inbound("3", "1")))
            port.pressResult = SubmitResult.SUBMITTED_TO_TERMINAL
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, r.onInbound(inbound("4", "2")))
            assertEquals(listOf(TerminalKeys.RIGHT, TerminalKeys.RIGHT, TerminalKeys.ESC, TerminalKeys.ESC), port.pressed)
            assertEquals("失败的那次不算", listOf("选哪个？ → 香蕉\n喝什么？ → 咖啡"), port.submitted)
        }

    @Test
    fun `提问已弹出、等待通知还没到时手机发话：不普通提交`() =
        runBlocking {
            registry.bind(robot, target)
            val port = KeysPort()
            val r = promptRouter(port)
            r.onSessionEvents("tab-1", listOf(asked(fruit)))
            assertEquals(InboundOutcome.DIALOG_OPEN, r.onInbound(inbound("1", "顺便把测试也跑一下")))
            assertTrue(port.pressed.isEmpty() && port.submitted.isEmpty())
        }

    // ---- 权限确认 ----

    @Test
    fun `空闲会话关闭弹框后手机消息能提交且不发送完成提示`() =
        runBlocking {
            registry.bind(robot, target)
            val port = KeysPort()
            val r = promptRouter(port)
            val tracker = RuntimeStatusTracker()
            suspend fun observe(status: String) {
                val outcome = tracker.completedSince(
                    mapOf("s1" to ClaudeRuntimeSession("s1", pid = 1, kind = "interactive", status = status, cwd = "/p")),
                )
                outcome.waiting.forEach { r.onWaiting("tab-1", it.reason) }
                outcome.resumed.forEach { r.onResumed("tab-1") }
                outcome.completed.forEach { r.onTurnCompleted("tab-1", r.currentTurn("tab-1")) }
            }
            observe("idle")
            observe("waiting")
            assertEquals(InboundOutcome.DIALOG_OPEN, r.onInbound(inbound("1", "继续")))
            observe("idle")
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, r.onInbound(inbound("2", "继续")))
            r.onQuiet("tab-1")
            assertEquals(listOf("继续"), port.submitted)
            assertEquals(listOf("WAIT(null)", "DIALOG_OPEN"), sentTexts())
        }

    @Test
    fun `权限数字写入失败后电脑作答仍通知手机`() =
        runBlocking {
            registry.bind(robot, target)
            val r = promptRouter(KeysPort(SubmitResult.OFFLINE))
            r.onSessionEvents("tab-1", listOf(SessionEvent.ToolCalled("b1", "Bash", "touch a.txt")))
            r.onWaiting("tab-1", "permission prompt")
            assertEquals(InboundOutcome.OFFLINE, r.onInbound(inbound("1", "1")))
            r.onSessionEvents("tab-1", listOf(SessionEvent.ToolResult("b1", null)))
            assertEquals("ANSWERED_LOCALLY(null)", sentTexts().last())
        }

    @Test
    fun `权限数字送达后仍等待时可以再次允许再拒绝`() =
        runBlocking {
            registry.bind(robot, target)
            val port = KeysPort()
            val r = promptRouter(port)
            r.onSessionEvents("tab-1", listOf(SessionEvent.ToolCalled("b1", "Bash", "touch a.txt")))
            r.onWaiting("tab-1", "permission prompt")
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, r.onInbound(inbound("1", "1")))
            r.onWaiting("tab-1", "permission prompt")
            assertEquals(1, sentTexts().size)
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, r.onInbound(inbound("2", "1")))
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, r.onInbound(inbound("3", "拒绝")))
            assertEquals(listOf("1", "1", TerminalKeys.ESC), port.pressed)
            assertTrue(port.submitted.isEmpty())
        }

    @Test
    fun `计划数字送达后仍等待时可以改选再拒绝`() =
        runBlocking {
            registry.bind(robot, target)
            val port = KeysPort()
            val r = promptRouter(port)
            r.onSessionEvents("tab-1", listOf(SessionEvent.ToolCalled("p1", "ExitPlanMode", "# 计划")))
            r.onWaiting("tab-1", "permission prompt")
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, r.onInbound(inbound("1", "1")))
            r.onWaiting("tab-1", "permission prompt")
            assertEquals(1, sentTexts().size)
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, r.onInbound(inbound("2", "2")))
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, r.onInbound(inbound("3", "不执行")))
            assertEquals(listOf("1", "2", TerminalKeys.ESC), port.pressed)
            assertTrue(port.submitted.isEmpty())
        }

    @Test
    fun `手机允许后的工具结果不误报电脑作答且退出等待后正常提交`() =
        runBlocking {
            registry.bind(robot, target)
            val port = KeysPort()
            val r = promptRouter(port)
            r.onSessionEvents("tab-1", listOf(SessionEvent.ToolCalled("b1", "Bash", "touch a.txt")))
            r.onWaiting("tab-1", "permission prompt")
            r.onInbound(inbound("1", "1"))
            r.onSessionEvents("tab-1", listOf(SessionEvent.ToolResult("b1", null)))
            assertEquals(1, sentTexts().size)
            r.onResumed("tab-1")
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, r.onInbound(inbound("2", "继续")))
            assertEquals(listOf("1"), port.pressed)
            assertEquals(listOf("继续"), port.submitted)
        }

    @Test
    fun `权限：唯一一个没结果的调用推送它的内容；回 1 只按 1，拒绝按 Esc，其他说法按 Esc 后发出原话`() =
        runBlocking {
            registry.bind(robot, target)
            for ((reply, keys, submitted) in listOf(
                Triple("1", listOf("1"), emptyList()),
                Triple("拒绝", listOf(TerminalKeys.ESC), emptyList()),
                Triple("3", listOf(TerminalKeys.ESC), emptyList()),
                Triple("别建文件，改成打印 hello", listOf(TerminalKeys.ESC), listOf("别建文件，改成打印 hello")),
            )) {
                channel.sent.clear()
                val port = KeysPort()
                val r = promptRouter(port)
                r.onSessionEvents("tab-1", listOf(SessionEvent.ToolCalled("b1", "Bash", "建文件\ntouch a.txt")))
                r.onWaiting("tab-1", "permission prompt")
                assertEquals(listOf("PERMISSION(**Bash**  \n建文件  \ntouch a.txt)"), sentTexts())
                assertEquals(reply, InboundOutcome.SUBMITTED_TO_TERMINAL, r.onInbound(inbound(reply, reply)))
                assertEquals(reply, keys, port.pressed)
                assertEquals(reply, submitted, port.submitted)
            }
        }

    @Test
    fun `权限：在电脑上批准，命令跑完写进结果时告诉手机`() =
        runBlocking {
            registry.bind(robot, target)
            val r = promptRouter(KeysPort())
            r.onSessionEvents("tab-1", listOf(SessionEvent.ToolCalled("b1", "Bash", "touch a.txt")))
            r.onWaiting("tab-1", "permission prompt")
            r.onSessionEvents("tab-1", listOf(SessionEvent.ToolResult("b1", null)))
            assertEquals("ANSWERED_LOCALLY(null)", sentTexts().last())
        }

    @Test
    fun `同步子代理的权限等待不提供手机批准入口，调用结束后主工具可以作答`() =
        runBlocking {
            registry.bind(robot, target.copy(agentType = AgentType.CLAUDE))
            val port = KeysPort()
            val r = promptRouter(port)
            val events = ClaudeTranscriptDecoder.decode(
                """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"a1","name":"Agent","input":{"prompt":"整理文件","run_in_background":false}}]}}""",
            )
            assertTrue(events.any { it is SessionEvent.ToolCalled && it.name == "Agent" })
            r.onSessionEvents("tab-1", events)
            r.onWaiting("tab-1", "permission prompt")
            assertEquals(listOf("WAIT(permission prompt)"), sentTexts())
            assertEquals(InboundOutcome.DIALOG_OPEN, r.onInbound(inbound("1", "1")))
            assertTrue(port.pressed.isEmpty())
            assertTrue(port.submitted.isEmpty())

            r.onResumed("tab-1")
            r.onSessionEvents("tab-1", ClaudeTranscriptDecoder.decode(
                """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"a1","content":"完成"}]}}""",
            ))
            r.onSessionEvents("tab-1", ClaudeTranscriptDecoder.decode(
                """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"b1","name":"Bash","input":{"command":"touch a.txt"}}]}}""",
            ))
            r.onWaiting("tab-1", "permission prompt")
            assertEquals("PERMISSION(**Bash**  \ntouch a.txt)", sentTexts().last())
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, r.onInbound(inbound("2", "1")))
            assertEquals(listOf("1"), port.pressed)
            assertTrue(port.submitted.isEmpty())
        }

    @Test
    fun `确定不了停着的是哪个框：只提示到 IDE 中处理，手机的话不按键也不提交`() =
        runBlocking {
            registry.bind(robot, target)
            for (events in listOf(
                // 两个没结果的调用：A 可能已获准还在跑，停着的是 B
                listOf(SessionEvent.ToolCalled("a", "Bash", "sleep 60"), SessionEvent.ToolCalled("b", "Bash", "rm -rf build")),
                // Read 首次读工作目录外的路径也会弹确认
                listOf(SessionEvent.ToolCalled("a", "Bash", "sleep 60"), SessionEvent.ToolCalled("b", "Read", "/etc/hosts")),
                // 提问与别的调用并存
                listOf(asked(fruit), SessionEvent.ToolCalled("b", "Bash", "rm a")),
                // 计划与别的调用并存
                listOf(SessionEvent.ToolCalled("p", "ExitPlanMode", "# 计划"), SessionEvent.ToolCalled("b", "Bash", "rm a")),
                // 后台子代理在跑：框可能来自它内部
                listOf(SessionEvent.BackgroundStarted("ag", "t", subagent = true), SessionEvent.ToolCalled("b", "Bash", "rm a")),
            )) {
                channel.sent.clear()
                val port = KeysPort()
                val r = promptRouter(port)
                r.onSessionEvents("tab-1", events)
                r.onWaiting("tab-1", "permission prompt")
                assertEquals(InboundOutcome.DIALOG_OPEN, r.onInbound(inbound("x${port.hashCode()}", "1")))
                assertTrue(port.pressed.isEmpty() && port.submitted.isEmpty())
                assertEquals(listOf("WAIT(permission prompt)", "DIALOG_OPEN"), sentTexts())
            }
        }

    @Test
    fun `等待原因不是权限确认时，唯一在跑的调用不当成等批准`() =
        runBlocking {
            registry.bind(robot, target)
            val r = promptRouter(KeysPort())
            r.onSessionEvents("tab-1", listOf(SessionEvent.ToolCalled("b", "Bash", "pnpm build")))
            r.onWaiting("tab-1", "dialog open")
            assertEquals(listOf("WAIT(dialog open)"), sentTexts())
        }

    @Test
    fun `先推了 A，又出现 B 而确定不了：撤销 A，手机回 1 不按键`() =
        runBlocking {
            registry.bind(robot, target)
            val port = KeysPort()
            val r = promptRouter(port)
            r.onSessionEvents("tab-1", listOf(SessionEvent.ToolCalled("a", "Bash", "sleep 60")))
            r.onWaiting("tab-1", "permission prompt")
            r.onSessionEvents("tab-1", listOf(SessionEvent.ToolCalled("b", "Bash", "rm -rf build")))
            r.onWaiting("tab-1", "permission prompt")
            assertEquals(InboundOutcome.DIALOG_OPEN, r.onInbound(inbound("1", "1")))
            assertTrue(port.pressed.isEmpty() && port.submitted.isEmpty())
        }

    // ---- 计划确认 ----

    @Test
    fun `计划：推送计划全文；回 1、2 照按，不执行按 Esc，其他说法按 Esc 后发出意见`() =
        runBlocking {
            registry.bind(robot, target)
            for ((reply, keys, submitted) in listOf(
                Triple("1", listOf("1"), emptyList()),
                Triple("2", listOf("2"), emptyList()),
                Triple("不执行", listOf(TerminalKeys.ESC), emptyList()),
                Triple("第二步不用读回来", listOf(TerminalKeys.ESC), listOf("第二步不用读回来")),
            )) {
                channel.sent.clear()
                val port = KeysPort()
                val r = promptRouter(port)
                r.onSessionEvents("tab-1", listOf(SessionEvent.ToolCalled("p1", "ExitPlanMode", "# 计划\n1. 新建文件")))
                r.onWaiting("tab-1", "permission prompt")
                assertEquals(listOf("PLAN(# 计划\n1. 新建文件)"), sentTexts())
                assertEquals(reply, InboundOutcome.SUBMITTED_TO_TERMINAL, r.onInbound(inbound(reply, reply)))
                assertEquals(reply, keys, port.pressed)
                assertEquals(reply, submitted, port.submitted)
            }
        }

    @Test
    fun `长计划、长命令截短到一条消息之内`() =
        runBlocking {
            registry.bind(robot, target)
            val small = FakeChannel(robot, maxTextBytes = 1000)
            for (event in listOf(
                SessionEvent.ToolCalled("p1", "ExitPlanMode", "步骤".repeat(800)),
                SessionEvent.ToolCalled("b1", "Bash", "echo " + "x".repeat(3000)),
            )) {
                small.sent.clear()
                val r = promptRouter(KeysPort(), small)
                r.onSessionEvents("tab-1", listOf(event))
                r.onWaiting("tab-1", "permission prompt")
                val text = small.sent.single().text
                assertTrue(text, text.toByteArray().size <= 1000)
                assertTrue(text, text.contains("\n…"))
            }
        }

    // ---- 等待的开始与结束 ----

    @Test
    fun `认不出的框：只提示到 IDE 中处理，手机的话不提交；退出等待后照常提交`() =
        runBlocking {
            registry.bind(robot, target)
            val port = KeysPort()
            val r = promptRouter(port)
            r.onWaiting("tab-1", "dialog open")
            assertEquals(InboundOutcome.DIALOG_OPEN, r.onInbound(inbound("1", "继续")))
            r.onSessionEvents("tab-1", listOf(SessionEvent.ToolResult("x", null), SessionEvent.AssistantText("好的")))
            assertEquals("记录里的结果与输出都说明不了等待已结束", InboundOutcome.DIALOG_OPEN, r.onInbound(inbound("2", "继续")))
            r.onResumed("tab-1")
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, r.onInbound(inbound("3", "继续")))
            assertEquals(listOf("继续"), port.submitted)
        }

    @Test
    fun `退出等待后：推过的框不再可作答、之后读到的调用不推送，手机的话照常提交`() =
        runBlocking {
            registry.bind(robot, target)
            val port = KeysPort()
            val r = promptRouter(port)
            r.onSessionEvents("tab-1", listOf(SessionEvent.ToolCalled("b1", "Bash", "sleep 60")))
            r.onWaiting("tab-1", "permission prompt")
            r.onResumed("tab-1")
            r.onSessionEvents("tab-1", listOf(SessionEvent.ToolCalled("b2", "Bash", "ls")))
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, r.onInbound(inbound("1", "1")))
            assertTrue(port.pressed.isEmpty())
            assertEquals(listOf("1"), port.submitted)
            assertEquals(1, sentTexts().size)
        }

    @Test
    fun `已获准还在跑的调用，之后再来等待通知也不当成在等批准`() =
        runBlocking {
            registry.bind(robot, target)
            val r = promptRouter(KeysPort())
            r.onSessionEvents("tab-1", listOf(SessionEvent.ToolCalled("b", "Bash", "sleep 60")))
            r.onWaiting("tab-1", "permission prompt")
            r.onResumed("tab-1")
            r.onWaiting("tab-1", "permission prompt")
            assertEquals(listOf("PERMISSION(**Bash**  \nsleep 60)", "WAIT(permission prompt)"), sentTexts())
        }

    @Test
    fun `退出等待后旧提问的结果还没读到：手机的话暂不提交，读到结果后照常提交`() =
        runBlocking {
            registry.bind(robot, target)
            val port = KeysPort()
            val r = promptRouter(port)
            r.onSessionEvents("tab-1", listOf(asked(fruit)))
            r.onWaiting("tab-1", "input needed")
            r.onResumed("tab-1")
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, r.onInbound(inbound("1", "继续")))
            assertEquals("退出等待时已登记的旧提问算已过去", listOf("继续"), port.submitted)
        }

    @Test
    fun `本轮结束（IDE 完成或结束标记）后：推过、还没结果的框不再可作答`() =
        runBlocking {
            registry.bind(robot, target)
            for (complete in listOf<suspend (BridgeRouter) -> Unit>(
                { it.onTurnCompleted("tab-1", it.currentTurn("tab-1")) },
                { it.onSessionEvents("tab-1", listOf(SessionEvent.TurnEnded)) },
            )) {
                val port = KeysPort()
                val r = promptRouter(port)
                r.onSessionEvents("tab-1", listOf(SessionEvent.TurnStarted, SessionEvent.ToolCalled("b1", "Bash", "rm a")))
                r.onWaiting("tab-1", "permission prompt")
                complete(r)
                assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, r.onInbound(inbound("x${port.hashCode()}", "1")))
                assertTrue(port.pressed.isEmpty())
                assertEquals(listOf("1"), port.submitted)
            }
        }

    @Test
    fun `上一轮的完成信号迟到，不清当前轮的等待`() =
        runBlocking {
            registry.bind(robot, target)
            val port = KeysPort()
            val r = promptRouter(port)
            val previous = r.currentTurn("tab-1")
            r.onSessionEvents("tab-1", listOf(SessionEvent.TurnStarted))
            r.onWaiting("tab-1", "dialog open")
            r.onTurnCompleted("tab-1", previous)
            assertEquals(InboundOutcome.DIALOG_OPEN, r.onInbound(inbound("1", "1")))
            assertTrue(port.pressed.isEmpty() && port.submitted.isEmpty())
        }

    @Test
    fun `新一轮、中断或记录重启后：推过的框不再可作答`() =
        runBlocking {
            registry.bind(robot, target)
            for (skip in listOf<suspend (BridgeRouter) -> Unit>(
                { it.onSessionEvents("tab-1", listOf(SessionEvent.TurnStarted)) },
                { it.onSessionEvents("tab-1", listOf(SessionEvent.TurnInterrupted)) },
                { it.onTranscriptRestarted("tab-1") },
            )) {
                val port = KeysPort()
                val r = promptRouter(port)
                r.onSessionEvents("tab-1", listOf(asked(fruit)))
                r.onWaiting("tab-1", "input needed")
                skip(r)
                assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, r.onInbound(inbound("x${port.hashCode()}", "2")))
                assertEquals(listOf("2"), port.submitted)
                assertTrue(port.pressed.isEmpty())
            }
        }

    // ---- 插件按 Esc 引起的中断 ----

    @Test
    fun `手机拒绝引起的中断不推给手机；电脑上自己中断照常推`() =
        runBlocking {
            registry.bind(robot, target)
            val r = promptRouter(KeysPort())
            r.onSessionEvents("tab-1", listOf(SessionEvent.ToolCalled("b1", "Bash", "rm a")))
            r.onWaiting("tab-1", "permission prompt")
            r.onInbound(inbound("1", "拒绝"))
            r.onSessionEvents("tab-1", listOf(SessionEvent.ToolResult("b1", null)))
            r.onSessionEvents("tab-1", listOf(SessionEvent.TurnInterrupted))
            assertEquals("结果与中断分两次读到也不推", 1, sentTexts().size)
            r.onSessionEvents("tab-1", listOf(SessionEvent.TurnStarted, SessionEvent.TurnInterrupted))
            assertEquals("INTERRUPTED", sentTexts().last())
        }

    @Test
    fun `手机 Esc 拒绝后没有中断标记、会话接着做，随后电脑上真的中断：手机仍收到本轮已中断`() =
        runBlocking {
            registry.bind(robot, target)
            val r = promptRouter(KeysPort())
            r.onSessionEvents("tab-1", listOf(SessionEvent.ToolCalled("b1", "Bash", "rm a")))
            r.onWaiting("tab-1", "permission prompt")
            r.onInbound(inbound("1", "拒绝"))
            r.onSessionEvents("tab-1", listOf(SessionEvent.ToolResult("b1", null)))
            r.onSessionEvents("tab-1", listOf(SessionEvent.AssistantText("那我换个办法"), SessionEvent.ToolStarted))
            r.onSessionEvents("tab-1", listOf(SessionEvent.TurnInterrupted))
            assertEquals("INTERRUPTED", sentTexts().last())
        }

}
