package com.github.izerui.imux.bridge.core

import com.github.izerui.imux.model.AgentType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
            return DeliveryResult.Accepted(null)
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

        override fun released(sessionTitle: String) = "RELEASED($sessionTitle)"

        override fun transcriptRestarted() = "RESTARTED"

        override fun connected(
            project: String,
            agent: String,
            sessionTitle: String,
        ) = "CONNECTED($project/$agent/$sessionTitle)"

        override fun pairingSucceeded() = "PAIRED"

        override fun pairingExpired() = "PAIR_EXPIRED"

        override fun pairingLocked() = "PAIR_LOCKED"

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
            assertEquals(null, channel.sent.single().bindingEpoch)
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
                    SessionEvent(SessionEventKind.USER_TEXT, "修一下 测试"),
                    SessionEvent(SessionEventKind.ASSISTANT_TEXT, "好的\n```\ncode\n```"),
                    SessionEvent(SessionEventKind.USER_TEXT, "电脑上补一句"),
                ),
            )
            assertEquals(listOf("好的\n```\ncode\n```", "LOCAL:\n电脑上补一句"), sentTexts())
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
            router.onSessionEvents("tab-1", listOf(SessionEvent(SessionEventKind.USER_TEXT, "同一句")))
            assertEquals(listOf("LOCAL:\n同一句"), sentTexts())
        }

    @Test
    fun `工具调用不单独通知，等待确认带原因`() =
        runBlocking {
            registry.bind(robot, target)
            router.onSessionEvents(
                "tab-1",
                listOf(
                    SessionEvent(SessionEventKind.TOOL_STARTED, "Read", "t1"),
                    SessionEvent(SessionEventKind.TOOL_FINISHED, "", "t1"),
                    SessionEvent(SessionEventKind.TOOL_STARTED, "Edit", "t2"),
                ),
            )
            router.onWaiting("tab-1", "Edit")
            assertEquals(listOf("WAIT(Edit)"), sentTexts())
        }

    @Test
    fun `未绑定终端的事件不转发`() =
        runBlocking {
            router.onSessionEvents("tab-1", listOf(SessionEvent(SessionEventKind.ASSISTANT_TEXT, "x")))
            router.onWaiting("tab-1", null)
            assertTrue(channel.sent.isEmpty())
        }

    @Test
    fun `分段发送途中换绑，回复照样发完，不截断`() =
        runBlocking {
            val small = FakeChannel(robot, maxTextBytes = 64)
            val r = BridgeRouter(registry, { owner }, port, { small }, Texts)
            registry.bind(robot, target)
            small.afterSend = { registry.bind(robot, target.copy(tabId = "tab-2")) }
            r.onSessionEvents("tab-1", listOf(SessionEvent(SessionEventKind.ASSISTANT_TEXT, "行内容\n".repeat(40))))
            r.onTurnCompleted("tab-1")
            assertTrue(small.sent.size > 1)
            assertEquals(40, small.sent.sumOf { Regex("行内容").findAll(it.text).count() })
        }

    @Test
    fun `分段发送途中换绑，状态提示不再继续发`() =
        runBlocking {
            val small = FakeChannel(robot, maxTextBytes = 64)
            val r = BridgeRouter(registry, { owner }, port, { small }, Texts)
            registry.bind(robot, target)
            small.afterSend = { registry.bind(robot, target.copy(tabId = "tab-2")) }
            r.onWaiting("tab-1", "行内容\n".repeat(40))
            assertEquals(1, small.sent.size)
        }

    @Test
    fun `每轮只推最后一次工具调用之后的回复，中间过程不推`() =
        runBlocking {
            registry.bind(robot, target)
            router.onSessionEvents(
                "tab-1",
                listOf(
                    SessionEvent(SessionEventKind.ASSISTANT_TEXT, "我先看看"),
                    SessionEvent(SessionEventKind.TOOL_STARTED, "Read", "t1"),
                    SessionEvent(SessionEventKind.TOOL_FINISHED, "", "t1"),
                    SessionEvent(SessionEventKind.ASSISTANT_TEXT, "现在改"),
                    SessionEvent(SessionEventKind.TOOL_STARTED, "Edit", "t2"),
                    SessionEvent(SessionEventKind.ASSISTANT_TEXT, "改好了"),
                ),
            )
            assertTrue("轮次完成前不推", channel.sent.isEmpty())
            router.onSessionEvents("tab-1", listOf(SessionEvent(SessionEventKind.ASSISTANT_TEXT, "总结")))
            router.onTurnCompleted("tab-1")
            assertEquals(listOf("改好了\n\n总结"), sentTexts())
        }

    @Test
    fun `没有工具调用时推整段回复，完成信号重复不重发`() =
        runBlocking {
            registry.bind(robot, target)
            router.onSessionEvents("tab-1", listOf(SessionEvent(SessionEventKind.ASSISTANT_TEXT, "答案")))
            router.onTurnCompleted("tab-1")
            router.onTurnCompleted("tab-1")
            assertEquals(listOf("答案"), sentTexts())
        }

    @Test
    fun `完成之后才读到的回复立即推送`() =
        runBlocking {
            registry.bind(robot, target)
            router.onTurnCompleted("tab-1")
            router.onSessionEvents("tab-1", listOf(SessionEvent(SessionEventKind.ASSISTANT_TEXT, "迟到的结论")))
            assertEquals(listOf("迟到的结论"), sentTexts())
        }

    @Test
    fun `没等到完成信号时，下一轮开始前补发上一轮结果，电脑输入单独推`() =
        runBlocking {
            registry.bind(robot, target)
            router.onSessionEvents(
                "tab-1",
                listOf(
                    SessionEvent(SessionEventKind.ASSISTANT_TEXT, "上一轮结果"),
                    SessionEvent(SessionEventKind.USER_TEXT, "电脑上新需求"),
                    SessionEvent(SessionEventKind.ASSISTANT_TEXT, "新一轮回复"),
                ),
            )
            assertEquals(listOf("上一轮结果", "LOCAL:\n电脑上新需求"), sentTexts())
            router.onTurnCompleted("tab-1")
            assertEquals(listOf("上一轮结果", "LOCAL:\n电脑上新需求", "新一轮回复"), sentTexts())
        }

    @Test
    fun `等待确认立即推送，不带出未完成的回复`() =
        runBlocking {
            registry.bind(robot, target)
            router.onSessionEvents("tab-1", listOf(SessionEvent(SessionEventKind.ASSISTANT_TEXT, "还没说完")))
            router.onWaiting("tab-1", "Bash")
            assertEquals(listOf("WAIT(Bash)"), sentTexts())
        }

    @Test
    fun `换绑后上一绑定的待发结果交给当前绑定，不丢`() =
        runBlocking {
            registry.bind(robot, target)
            router.onSessionEvents("tab-1", listOf(SessionEvent(SessionEventKind.ASSISTANT_TEXT, "旧结果")))
            registry.unbind(robot)
            registry.bind(robot, target)
            router.onTurnCompleted("tab-1")
            assertEquals(listOf("旧结果"), sentTexts())
        }

    @Test
    fun `断开时先把已产生的结果发给原聊天，再告知已断开`() =
        runBlocking {
            val bound = (registry.bind(robot, target) as BindResult.Bound).binding
            router.onSessionEvents("tab-1", listOf(SessionEvent(SessionEventKind.ASSISTANT_TEXT, "最后结果")))
            registry.unbind(robot)
            router.onReleased(bound, "修复滚动")
            assertEquals(listOf("最后结果", "RELEASED(修复滚动)"), sentTexts())
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
            switchRouter.onSessionEvents("tab-1", listOf(SessionEvent(SessionEventKind.ASSISTANT_TEXT, "新回复")))
            switchRouter.onReleased(old, "修复滚动")
            switchRouter.onTurnCompleted("tab-1")
            assertEquals(listOf("RELEASED(修复滚动)"), channel.sent.map { it.text })
            assertEquals(listOf("新回复"), channelB.sent.map { it.text })
        }

    @Test
    fun `换绑后旧绑定的清理晚到，不清新绑定的完成状态`() =
        runBlocking {
            val old = switchToB()
            switchRouter.onTurnCompleted("tab-1")
            switchRouter.onReleased(old, "修复滚动")
            // 完成之后才读到的回复是迟到的最终输出，应立即发出
            switchRouter.onSessionEvents("tab-1", listOf(SessionEvent(SessionEventKind.ASSISTANT_TEXT, "迟到的回复")))
            assertEquals(listOf("迟到的回复"), channelB.sent.map { it.text })
        }

    @Test
    fun `换绑后旧绑定的清理晚到，不清新绑定的回显登记`() =
        runBlocking {
            val old = switchToB()
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, switchRouter.onInbound(InboundMessage(robotB, "1", "me-b", "chat-b", "跑测试")))
            switchRouter.onReleased(old, "修复滚动")
            switchRouter.onSessionEvents("tab-1", listOf(SessionEvent(SessionEventKind.USER_TEXT, "跑测试")))
            assertTrue(channelB.sent.isEmpty())
        }

    @Test
    fun `换绑前旧绑定未发出的回复仍发给原聊天，不落到新机器人`() =
        runBlocking {
            val old = (registry.bind(robot, target) as BindResult.Bound).binding
            switchRouter.onSessionEvents("tab-1", listOf(SessionEvent(SessionEventKind.ASSISTANT_TEXT, "旧回复")))
            registry.unbind(robot)
            registry.bind(robotB, target)
            switchRouter.onReleased(old, "修复滚动")
            switchRouter.onTurnCompleted("tab-1")
            assertEquals(listOf("旧回复", "RELEASED(修复滚动)"), channel.sent.map { it.text })
            assertTrue(channelB.sent.isEmpty())
        }

    /** 远程控制开关：null 为关闭，数字为开启代次。 */
    private var generation: Long? = 1L

    private fun switchableRouter(store: AttachmentStore = AttachmentStore.NONE) =
        BridgeRouter(
            registry,
            { if (it == robot) owner else null },
            port,
            { if (it == robot) channel else null },
            Texts,
            attachmentStore = store,
            notReadyRetryDelayMs = 0,
            switchGeneration = { generation },
        )

    /** 落盘时执行 [during]（模拟下载期间的操作），然后保存成功。 */
    private class SlowStore(private val during: () -> Unit) : AttachmentStore {
        override suspend fun place(
            target: SessionTarget,
            attachment: InboundAttachment,
        ): PlaceResult {
            during()
            return PlaceResult.Saved(".imux/a.png")
        }
    }

    @Test
    fun `下载附件期间关闭远程控制，下载完也不提交、不回复`() =
        runBlocking {
            registry.bind(robot, target)
            val r = switchableRouter(SlowStore { generation = null })
            assertEquals(InboundOutcome.SWITCHED_OFF, r.onInbound(inboundWith("1", "看图", FakeAttachment())))
            assertTrue(port.submitted.isEmpty())
            assertTrue(channel.sent.isEmpty())
        }

    @Test
    fun `下载附件期间关闭又开启，旧指令仍不提交`() =
        runBlocking {
            registry.bind(robot, target)
            val r = switchableRouter(SlowStore { generation = 2L })
            assertEquals(InboundOutcome.SWITCHED_OFF, r.onInbound(inboundWith("1", "看图", FakeAttachment())))
            assertTrue(port.submitted.isEmpty())
        }

    @Test
    fun `到达时远程控制已关闭的消息不处理`() =
        runBlocking {
            registry.bind(robot, target)
            val r = switchableRouter()
            generation = null
            val arrival = r.arrival(robot)
            generation = 2L
            assertEquals(InboundOutcome.SWITCHED_OFF, r.onInbound(inbound("1", "hi"), arrival))
            assertTrue(port.submitted.isEmpty())
            assertTrue(channel.sent.isEmpty())
        }

    @Test
    fun `排队时绑定项目 A，处理前换绑到 B：不提交到 B，提示重新发送`() =
        runBlocking {
            registry.bind(robot, target)
            val arrival = router.arrival(robot)
            registry.bind(robot, target.copy(projectPath = "/b", tabId = "tab-b"))
            assertEquals(InboundOutcome.REBOUND, router.onInbound(inbound("1", "改一下代码"), arrival))
            assertTrue(port.submitted.isEmpty())
            assertEquals(listOf("REBOUND"), sentTexts())
        }

    @Test
    fun `到达时未绑定、处理前才绑定：按未绑定回复，不提交`() =
        runBlocking {
            val arrival = router.arrival(robot)
            registry.bind(robot, target)
            assertEquals(InboundOutcome.UNBOUND, router.onInbound(inbound("1", "hi"), arrival))
            assertTrue(port.submitted.isEmpty())
        }

    @Test
    fun `换绑机器人前攒下的回复，旧绑定还没取走时并入新机器人这一轮，不丢`() =
        runBlocking {
            registry.bind(robot, target)
            switchRouter.onSessionEvents("tab-1", listOf(SessionEvent(SessionEventKind.ASSISTANT_TEXT, "换绑前")))
            registry.unbind(robot)
            registry.bind(robotB, target)
            switchRouter.onSessionEvents("tab-1", listOf(SessionEvent(SessionEventKind.ASSISTANT_TEXT, "换绑后")))
            switchRouter.onTurnCompleted("tab-1")
            assertEquals(listOf("换绑前\n\n换绑后"), channelB.sent.map { it.text })
            assertTrue(channel.sent.isEmpty())
        }

    @Test
    fun `关闭远程控制时丢弃未发出的回复`() =
        runBlocking {
            registry.bind(robot, target)
            router.onSessionEvents("tab-1", listOf(SessionEvent(SessionEventKind.ASSISTANT_TEXT, "关闭前的回复")))
            router.discardPending()
            router.onTurnCompleted("tab-1")
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
            r.onSessionEvents("tab-1", listOf(SessionEvent(SessionEventKind.USER_TEXT, "炸了")))
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
            r.onSessionEvents("tab-1", listOf(SessionEvent(SessionEventKind.USER_TEXT, "同一句")))
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
}
