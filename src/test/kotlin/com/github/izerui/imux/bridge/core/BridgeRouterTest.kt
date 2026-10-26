package com.github.izerui.imux.bridge.core

import com.github.izerui.imux.model.AgentType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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

        override fun notReady() = "NOT_READY"

        override fun emptyInput() = "EMPTY"

        override fun localInputLabel() = "LOCAL:"

        override fun completed(toolCalls: Int) = "DONE($toolCalls)"

        override fun waiting(reason: String?) = "WAIT($reason)"

        override fun released(sessionTitle: String) = "RELEASED($sessionTitle)"

        override fun transcriptRestarted() = "RESTARTED"
    }

    private val registry = BindingRegistry()
    private val port = FakePort()
    private val channel = FakeChannel(robot)
    private val router = BridgeRouter(registry, { if (it == robot) owner else null }, port, { if (it == robot) channel else null }, Texts)

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
    fun `轮次完成报告工具次数，等待确认带原因`() =
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
            router.onTurnCompleted("tab-1")
            router.onTurnCompleted("tab-1")
            assertEquals(listOf("WAIT(Edit)", "DONE(2)", "DONE(0)"), sentTexts())
        }

    @Test
    fun `未绑定终端的事件不转发`() =
        runBlocking {
            router.onSessionEvents("tab-1", listOf(SessionEvent(SessionEventKind.ASSISTANT_TEXT, "x")))
            router.onTurnCompleted("tab-1")
            assertTrue(channel.sent.isEmpty())
        }

    @Test
    fun `分段发送途中换绑，剩余分段不再发往旧目标`() =
        runBlocking {
            val small = FakeChannel(robot, maxTextBytes = 64)
            val r = BridgeRouter(registry, { owner }, port, { small }, Texts)
            registry.bind(robot, target)
            small.afterSend = { registry.bind(robot, target.copy(tabId = "tab-2")) }
            r.onSessionEvents("tab-1", listOf(SessionEvent(SessionEventKind.ASSISTANT_TEXT, "行内容\n".repeat(40))))
            assertEquals(1, small.sent.size)
        }

    @Test
    fun `释放时向原聊天发送断开提示`() =
        runBlocking {
            val bound = (registry.bind(robot, target) as BindResult.Bound).binding
            registry.unbind(robot)
            router.onReleased(bound, "修复滚动")
            assertEquals(listOf("RELEASED(修复滚动)"), sentTexts())
        }
}
