package com.github.izerui.imux.bridge.core

import com.github.izerui.imux.model.AgentType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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

        override fun released(sessionTitle: String) = "RELEASED($sessionTitle)"

        override fun transcriptRestarted() = "RESTARTED"

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
}
