package com.github.izerui.imux.bridge.core

import com.github.izerui.imux.bridge.transcript.ClaudeTranscriptDecoder
import com.github.izerui.imux.model.AgentType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** hook 路径：确认框经 PermissionRequest hook 到达，按标签排队推给手机，手机作答译成 hook 决定，不按键。 */
class HookPromptRouterTest {
    private val robot = RobotId("r1")
    private val owner = RobotOwner(senderId = "me", chatId = "chat-1")
    private val target = SessionTarget("/p", "tab-1", AgentType.CLAUDE, "s1")

    /** 记录提交：hook 作答不应贴进终端。 */
    private class FakePort : TerminalSessionPort {
        val submitted = mutableListOf<String>()

        override suspend fun submit(
            target: SessionTarget,
            text: String,
        ): SubmitResult {
            submitted += text
            return SubmitResult.SUBMITTED_TO_TERMINAL
        }
    }

    private class FakeChannel(
        override val robotId: RobotId,
        override val maxTextBytes: Int = 1000,
    ) : ChannelAdapter {
        val sent = mutableListOf<OutboundMessage>()

        override suspend fun send(message: OutboundMessage): DeliveryResult {
            sent += message
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

        override fun plan(plan: String) = "PLAN($plan)"

        override fun dialogOpen() = "DIALOG_OPEN"

        override fun question(listing: String, number: Int, total: Int) = "QUESTION$number/$total($listing)"

        override fun multiSelectMark() = "(多选)"

        override fun promptAnsweredLocally(answer: String?) = "ANSWERED_LOCALLY($answer)"

        override fun promptRejected() = "PROMPT_REJECTED"

        override fun released(session: String) = "RELEASED($session)"

        override fun suspended(session: String) = "SUSPENDED($session)"

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

        override fun hookPermission(listing: String) = "HOOK_PERMISSION($listing)"

        override fun hookQueued(count: Int) = "HOOK_QUEUED($count)"

        override fun hookAllowed() = "HOOK_ALLOWED"

        override fun hookDenied() = "HOOK_DENIED"

        override fun hookAnswersSubmitted() = "HOOK_ANSWERS_SUBMITTED"

        override fun hookPlanApproved(auto: Boolean) = "HOOK_PLAN_APPROVED($auto)"

        override fun hookPlanRevising() = "HOOK_PLAN_REVISING"

        override fun hookAllowedLocally() = "HOOK_ALLOWED_LOCALLY"

        override fun hookDeniedLocally() = "HOOK_DENIED_LOCALLY"
    }

    private class FakeAttachment : InboundAttachment {
        override val kind: AttachmentKind = AttachmentKind.IMAGE

        override suspend fun fetch(maxBytes: Long): FetchResult = error("路由器不应直接调用 fetch")
    }

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
            attachmentStore = { _, _ -> PlaceResult.Saved(".imux/a.png") },
            notReadyRetryDelayMs = 0,
        )

    private fun inbound(
        id: String,
        text: String,
    ) = InboundMessage(robot, id, owner.senderId, owner.chatId, text)

    private fun sentTexts() = channel.sent.map { it.text }

    /** 一个请求与它收到的决定。 */
    private class Pending(val request: HookRequest) {
        val decisions = mutableListOf<HookDecision>()
    }

    private suspend fun request(
        id: Long,
        toolName: String = "Bash",
        detail: String = "rm a.txt",
        agentId: String? = null,
        agentType: String? = null,
        questions: List<AskedQuestion>? = null,
        input: Map<String, String> = mapOf("command" to "\"key-$id\""),
    ): Pending {
        val pending = Pending(HookRequest(id, toolName, agentId, agentType, detail, questions, input))
        router.onHookRequest("tab-1", pending.request) { pending.decisions += it }
        return pending
    }

    /** 测试里电脑端允许的那条 Bash 的输入。 */
    private val k = mapOf("command" to "\"k\"")

    private fun done(
        toolName: String = "Bash",
        agentId: String? = null,
        input: Map<String, String> = k,
    ) = HookToolDone(agentId, toolName, input)

    private val colors = AskedQuestion("颜色？", false, listOf(AskedOption("红", null), AskedOption("绿", null)))
    private val fruits = AskedQuestion("水果？", true, listOf(AskedOption("苹果", null), AskedOption("香蕉", null), AskedOption("橙子", null)))

    private val bashToolUse =
        """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"b1","name":"Bash","input":{"command":"touch a.txt"}}]}}"""

    private fun bind(): Binding = (registry.bind(robot, target) as BindResult.Bound).binding

    @Test
    fun `未绑定的标签立即放行`() =
        runBlocking {
            val pending = request(1)
            assertEquals(listOf(HookDecision.Pass), pending.decisions)
            assertTrue(channel.sent.isEmpty())
        }

    @Test
    fun `没有题目的提问不入队直接交给电脑，null 与空列表各自放行`() =
        runBlocking {
            bind()
            val missing = request(1, toolName = "AskUserQuestion", detail = "", questions = null)
            val empty = request(2, toolName = "AskUserQuestion", detail = "", questions = emptyList())
            assertEquals(listOf<HookDecision>(HookDecision.Pass), missing.decisions)
            assertEquals(listOf<HookDecision>(HookDecision.Pass), empty.decisions)
            assertTrue(channel.sent.isEmpty())
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, router.onInbound(inbound("m1", "继续")))
            assertEquals(listOf("继续"), port.submitted)
        }

    @Test
    fun `权限请求推送内容与来源，回 1 后允许并确认`() =
        runBlocking {
            bind()
            val pending = request(1, agentType = "general-purpose")
            val pushed = sentTexts().single()
            assertTrue(pushed, pushed.startsWith("HOOK_PERMISSION("))
            assertTrue(pushed, pushed.contains("**Bash** · general-purpose"))
            assertTrue(pushed, pushed.contains("rm a.txt"))
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, router.onInbound(inbound("m1", "1")))
            assertEquals(listOf<HookDecision>(HookDecision.Allow()), pending.decisions)
            assertEquals("HOOK_ALLOWED", sentTexts().last())
            assertTrue(port.submitted.isEmpty())
        }

    @Test
    fun `其他说法拒绝并把原话交给 Claude`() =
        runBlocking {
            bind()
            val pending = request(1)
            router.onInbound(inbound("m1", "改用 git rm"))
            assertEquals(
                listOf<HookDecision>(HookDecision.Deny("The user denied this tool call from their phone and said: 改用 git rm")),
                pending.decisions,
            )
            assertEquals("HOOK_DENIED", sentTexts().last())
            assertTrue(port.submitted.isEmpty())
        }

    @Test
    fun `排队的请求只推队首并标出排队数，答完推下一个`() =
        runBlocking {
            bind()
            val first = request(1, detail = "rm a.txt")
            val second = request(2, detail = "rm b.txt")
            val third = request(3, detail = "rm c.txt")
            // 后到的两个只排队，不推
            assertEquals(1, sentTexts().size)
            router.onInbound(inbound("m1", "1"))
            assertEquals(listOf<HookDecision>(HookDecision.Allow()), first.decisions)
            val next = sentTexts().drop(1)
            assertEquals("HOOK_ALLOWED", next[0])
            assertTrue(next[1], next[1].contains("rm b.txt"))
            assertTrue(next[1], next[1].endsWith("\n\nHOOK_QUEUED(1)"))
            assertTrue(second.decisions.isEmpty())
            router.onInbound(inbound("m2", "1"))
            assertEquals(listOf<HookDecision>(HookDecision.Allow()), second.decisions)
            val last = sentTexts().last()
            assertTrue(last, last.contains("rm c.txt"))
            assertFalse(last, last.contains("HOOK_QUEUED"))
            assertTrue(third.decisions.isEmpty())
        }

    @Test
    fun `提问一题一题答，最后一次性提交答案`() =
        runBlocking {
            bind()
            val pending = request(1, toolName = "AskUserQuestion", detail = "", questions = listOf(colors, fruits))
            assertTrue(sentTexts().single().startsWith("QUESTION1/2("))
            router.onInbound(inbound("m1", "2"))
            assertTrue(pending.decisions.isEmpty())
            assertTrue(sentTexts().last().startsWith("QUESTION2/2("))
            router.onInbound(inbound("m2", "1 3"))
            assertEquals(
                listOf<HookDecision>(HookDecision.Allow(answers = mapOf("颜色？" to "绿", "水果？" to "苹果, 橙子"))),
                pending.decisions,
            )
            assertEquals("HOOK_ANSWERS_SUBMITTED", sentTexts().last())
            assertTrue(port.submitted.isEmpty())
        }

    @Test
    fun `计划回 1 按电脑第 1 项批准`() =
        runBlocking {
            bind()
            val pending = request(1, toolName = "ExitPlanMode", detail = "# 计划")
            router.onInbound(inbound("m1", "1"))
            assertEquals(listOf<HookDecision>(HookDecision.Allow(modes = listOf("auto", "bypassPermissions"))), pending.decisions)
            assertEquals("HOOK_PLAN_APPROVED(true)", sentTexts().last())
        }

    @Test
    fun `计划回 2 批准并切手动模式`() =
        runBlocking {
            bind()
            val pending = request(1, toolName = "ExitPlanMode", detail = "# 计划")
            assertEquals(listOf("PLAN(# 计划)"), sentTexts())
            router.onInbound(inbound("m1", "2"))
            assertEquals(listOf<HookDecision>(HookDecision.Allow(modes = listOf("default"))), pending.decisions)
            assertEquals("HOOK_PLAN_APPROVED(false)", sentTexts().last())
        }

    @Test
    fun `计划回别的话转告 Claude 修改`() =
        runBlocking {
            bind()
            val pending = request(1, toolName = "ExitPlanMode", detail = "# 计划")
            router.onInbound(inbound("m1", "先别动数据库"))
            assertEquals(listOf<HookDecision>(HookDecision.Deny("先别动数据库")), pending.decisions)
            assertEquals("HOOK_PLAN_REVISING", sentTexts().last())
        }

    @Test
    fun `电脑端允许后告诉手机且不重复`() =
        runBlocking {
            bind()
            val pending = request(1, input = k)
            router.onHookToolDone("tab-1", done(), null)
            assertEquals(listOf<HookDecision>(HookDecision.Pass), pending.decisions)
            assertEquals("HOOK_ALLOWED_LOCALLY", sentTexts().last())
            assertEquals(2, sentTexts().size)
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, router.onInbound(inbound("m1", "1")))
            assertEquals(listOf("1"), port.submitted)
            assertEquals(listOf<HookDecision>(HookDecision.Pass), pending.decisions)
            assertEquals(2, sentTexts().size)
        }

    @Test
    fun `提问在电脑端作答后能对上`() =
        runBlocking {
            bind()
            val questions = mapOf("questions" to "[\"颜色？\"]")
            val pending = request(1, toolName = "AskUserQuestion", detail = "", questions = listOf(colors), input = questions)
            val next = request(2, detail = "rm b.txt")
            router.onHookToolDone(
                "tab-1",
                done("AskUserQuestion", input = questions + mapOf("answers" to "{\"颜色？\":\"红\"}", "annotations" to "{}")),
                "颜色？ → 红",
            )
            assertEquals(listOf<HookDecision>(HookDecision.Pass), pending.decisions)
            val after = sentTexts().takeLast(2)
            assertEquals("ANSWERED_LOCALLY(颜色？ → 红)", after[0])
            assertTrue(after[1], after[1].startsWith("HOOK_PERMISSION(") && after[1].contains("rm b.txt"))
            assertTrue(next.decisions.isEmpty())
        }

    @Test
    fun `计划在电脑端批准后能对上`() =
        runBlocking {
            bind()
            val pending = request(1, toolName = "ExitPlanMode", detail = "# 计划", input = mapOf("plan" to "\"# 计划\"", "planFilePath" to "\"/tmp/p.md\""))
            val next = request(2, detail = "rm b.txt")
            router.onHookToolDone("tab-1", done("ExitPlanMode", input = emptyMap()), null)
            assertEquals(listOf<HookDecision>(HookDecision.Pass), pending.decisions)
            val after = sentTexts().takeLast(2)
            assertEquals("ANSWERED_LOCALLY(null)", after[0])
            assertTrue(after[1], after[1].startsWith("HOOK_PERMISSION(") && after[1].contains("rm b.txt"))
            assertTrue(next.decisions.isEmpty())
        }

    @Test
    fun `共有字段不同则不匹配`() =
        runBlocking {
            bind()
            val first = request(1, detail = "rm a.txt", input = mapOf("command" to "\"rm a.txt\"", "description" to "\"删\""))
            val second = request(2, detail = "rm b.txt", input = mapOf("command" to "\"rm b.txt\"", "description" to "\"删\""))
            router.onHookToolDone("tab-1", done(input = mapOf("command" to "\"rm b.txt\"")), null)
            assertTrue(first.decisions.isEmpty())
            assertEquals(listOf<HookDecision>(HookDecision.Pass), second.decisions)
            // 了结的不是推过的队首，静默出队
            assertEquals(1, sentTexts().size)
        }

    @Test
    fun `不同代理的请求互不了结`() =
        runBlocking {
            bind()
            val pending = request(1, agentId = "a1", agentType = "general-purpose", input = k)
            router.onHookToolDone("tab-1", done(agentId = "a2"), null)
            router.onHookToolDone("tab-1", done(agentId = null), null)
            assertTrue(pending.decisions.isEmpty())
            router.onHookToolDone("tab-1", done(agentId = "a1"), null)
            assertEquals(listOf<HookDecision>(HookDecision.Pass), pending.decisions)
        }

    @Test
    fun `电脑端拒绝后告诉手机，不再回写`() =
        runBlocking {
            bind()
            val pending = request(1)
            router.onHookClosed("tab-1", 1)
            assertEquals("HOOK_DENIED_LOCALLY", sentTexts().last())
            assertTrue(pending.decisions.isEmpty())
            router.onInbound(inbound("m1", "1"))
            assertTrue(pending.decisions.isEmpty())
            assertEquals(listOf("1"), port.submitted)
        }

    @Test
    fun `没推过的请求在电脑端了结时静默出队`() =
        runBlocking {
            bind()
            request(1)
            val queued = request(2, input = k)
            val closed = request(3)
            router.onHookToolDone("tab-1", done(), null)
            router.onHookClosed("tab-1", 3)
            assertEquals(listOf<HookDecision>(HookDecision.Pass), queued.decisions)
            assertTrue(closed.decisions.isEmpty())
            assertEquals(1, sentTexts().size)
        }

    @Test
    fun `手机作答后电脑端结果到达不再通知`() =
        runBlocking {
            bind()
            val pending = request(1, input = k)
            router.onInbound(inbound("m1", "1"))
            router.onHookToolDone("tab-1", done(), null)
            assertEquals(2, sentTexts().size)
            assertEquals("HOOK_ALLOWED", sentTexts().last())
            assertEquals(listOf<HookDecision>(HookDecision.Allow()), pending.decisions)
        }

    @Test
    fun `参数相同的两个请求只了结最早的一个`() =
        runBlocking {
            bind()
            val first = request(1, detail = "rm a.txt", input = k)
            val second = request(2, detail = "rm a.txt", input = k)
            router.onHookToolDone("tab-1", done(), null)
            assertEquals(listOf<HookDecision>(HookDecision.Pass), first.decisions)
            assertTrue(second.decisions.isEmpty())
            assertEquals("HOOK_ALLOWED_LOCALLY", sentTexts()[1])
            assertTrue(sentTexts().last(), sentTexts().last().startsWith("HOOK_PERMISSION("))
            assertEquals(3, sentTexts().size)
        }

    @Test
    fun `本轮结束后后台子代理的权限请求照常推送`() =
        runBlocking {
            bind()
            router.onSessionEvents("tab-1", listOf(SessionEvent.TurnEnded))
            val before = sentTexts().size
            val pending = request(1, agentId = "a1", agentType = "general-purpose")
            assertTrue(sentTexts().last().startsWith("HOOK_PERMISSION("))
            assertEquals(before + 1, sentTexts().size)
            router.onSessionEvents("tab-1", listOf(SessionEvent.TurnEnded))
            assertTrue(pending.decisions.isEmpty())
        }

    @Test
    fun `本轮结束只放行主会话的挂起请求，子代理的照常保留`() =
        runBlocking {
            bind()
            val main = request(1, detail = "rm a.txt")
            val agent = request(2, detail = "rm b.txt", agentId = "a1", agentType = "general-purpose")
            assertEquals(1, sentTexts().size)
            router.onSessionEvents("tab-1", listOf(SessionEvent.TurnEnded))
            assertEquals(listOf<HookDecision>(HookDecision.Pass), main.decisions)
            assertTrue(agent.decisions.isEmpty())
            // 主会话的静默出队，子代理的成为队首推给手机，仍可作答
            assertFalse(sentTexts().joinToString(), sentTexts().any { it.startsWith("HOOK_DENIED_LOCALLY") || it.startsWith("HOOK_ALLOWED_LOCALLY") })
            val pushed = sentTexts().last()
            assertTrue(pushed, pushed.startsWith("HOOK_PERMISSION(") && pushed.contains("rm b.txt"))
            router.onInbound(inbound("m1", "1"))
            assertEquals(listOf<HookDecision>(HookDecision.Allow()), agent.decisions)
            assertEquals(listOf<HookDecision>(HookDecision.Pass), main.decisions)
        }

    @Test
    fun `中断也放行主会话的挂起请求`() =
        runBlocking {
            bind()
            val pending = request(1)
            router.onSessionEvents("tab-1", listOf(SessionEvent.TurnInterrupted))
            assertEquals(listOf<HookDecision>(HookDecision.Pass), pending.decisions)
        }

    @Test
    fun `出错结束也放行主会话的挂起请求`() =
        runBlocking {
            bind()
            val pending = request(1)
            router.onSessionEvents("tab-1", listOf(SessionEvent.TurnFailed("boom")))
            assertEquals(listOf<HookDecision>(HookDecision.Pass), pending.decisions)
        }

    @Test
    fun `提问答一半时电脑端了结，手机收到答案且队列继续`() =
        runBlocking {
            bind()
            val asked = request(1, toolName = "AskUserQuestion", detail = "", questions = listOf(colors, fruits), input = k)
            val next = request(2, detail = "rm b.txt")
            router.onInbound(inbound("m1", "2"))
            assertTrue(sentTexts().last().startsWith("QUESTION2/2("))
            router.onHookToolDone("tab-1", done("AskUserQuestion"), "颜色？ → 红\n水果？ → 苹果")
            assertEquals(listOf<HookDecision>(HookDecision.Pass), asked.decisions)
            val after = sentTexts().takeLast(2)
            assertEquals("ANSWERED_LOCALLY(颜色？ → 红\n水果？ → 苹果)", after[0])
            assertTrue(after[1], after[1].startsWith("HOOK_PERMISSION(") && after[1].contains("rm b.txt"))
            router.onInbound(inbound("m2", "1"))
            assertEquals(listOf<HookDecision>(HookDecision.Allow()), next.decisions)
            assertEquals(listOf<HookDecision>(HookDecision.Pass), asked.decisions)
        }

    @Test
    fun `解绑时挂起的请求都放行给电脑`() =
        runBlocking {
            val binding = bind()
            val first = request(1)
            val second = request(2)
            registry.unbind(robot)
            router.onReleased(binding, "会话")
            assertEquals(listOf<HookDecision>(HookDecision.Pass), first.decisions)
            assertEquals(listOf<HookDecision>(HookDecision.Pass), second.decisions)
            assertEquals(InboundOutcome.UNBOUND, router.onInbound(inbound("m1", "1")))
            assertEquals("UNBOUND", sentTexts().last())
        }

    @Test
    fun `换绑时旧会话挂起的请求放行给电脑`() =
        runBlocking {
            val binding = bind()
            val pending = request(1)
            registry.unbind(robot)
            router.onRebound(listOf(binding to "会话"), null, "p", "Claude", "t")
            assertEquals(listOf<HookDecision>(HookDecision.Pass), pending.decisions)
        }

    @Test
    fun `记录重启时挂起的请求放行给电脑`() =
        runBlocking {
            bind()
            val pending = request(1)
            router.onTranscriptRestarted("tab-1")
            assertEquals(listOf<HookDecision>(HookDecision.Pass), pending.decisions)
            assertEquals("RESTARTED", sentTexts().last())
        }

    @Test
    fun `远程控制关闭时挂起的请求放行给电脑`() =
        runBlocking {
            bind()
            val pending = request(1)
            router.discardPending()
            assertEquals(listOf<HookDecision>(HookDecision.Pass), pending.decisions)
        }

    @Test
    fun `会话结束时挂起的请求放行给电脑且不发消息`() =
        runBlocking {
            bind()
            router.hookAttached("tab-1", true)
            val pending = request(1)
            router.hookAttached("tab-1", false)
            assertEquals(listOf<HookDecision>(HookDecision.Pass), pending.decisions)
            assertEquals(1, sentTexts().size)
        }

    @Test
    fun `带附件的回复不作答`() =
        runBlocking {
            bind()
            val pending = request(1)
            val withImage = InboundMessage(robot, "m1", owner.senderId, owner.chatId, "1", listOf(FakeAttachment()))
            assertEquals(InboundOutcome.PROMPT_REJECTED, router.onInbound(withImage))
            assertEquals("PROMPT_REJECTED", sentTexts().last())
            assertTrue(pending.decisions.isEmpty())
            router.onInbound(inbound("m2", "1"))
            assertEquals(listOf<HookDecision>(HookDecision.Allow()), pending.decisions)
        }

    @Test
    fun `注入 hook 的标签有挂起请求时等待通知不提示`() =
        runBlocking {
            bind()
            router.hookAttached("tab-1", true)
            request(1)
            router.onWaiting("tab-1", "permission prompt")
            assertEquals(1, sentTexts().size)
        }

    @Test
    fun `读到记录里的工具调用也不推权限框`() =
        runBlocking {
            bind()
            router.hookAttached("tab-1", true)
            router.onSessionEvents("tab-1", ClaudeTranscriptDecoder.decode(bashToolUse))
            router.onWaiting("tab-1", "permission prompt")
            assertEquals(listOf("WAIT(permission prompt)"), sentTexts())
        }

    @Test
    fun `等待通知先到时读到记录里的工具调用也不补推权限框`() =
        runBlocking {
            bind()
            router.hookAttached("tab-1", true)
            router.onWaiting("tab-1", "permission prompt")
            router.onSessionEvents("tab-1", ClaudeTranscriptDecoder.decode(bashToolUse))
            assertEquals(listOf("WAIT(permission prompt)"), sentTexts())
        }

    @Test
    fun `长计划、长命令截短到一条消息之内`() =
        runBlocking {
            bind()
            for ((tool, detail) in listOf("ExitPlanMode" to "步骤".repeat(800), "Bash" to "echo " + "x".repeat(3000))) {
                val small = FakeChannel(robot, maxTextBytes = 1000)
                val r = BridgeRouter(registry, { if (it == robot) owner else null }, port, { if (it == robot) small else null }, Texts)
                r.onHookRequest("tab-1", HookRequest(1, tool, null, null, detail, null, emptyMap())) {}
                val text = small.sent.single().text
                assertTrue(tool, text.toByteArray().size <= 1000)
                assertTrue(tool, text.contains("\n…"))
            }
        }
}
