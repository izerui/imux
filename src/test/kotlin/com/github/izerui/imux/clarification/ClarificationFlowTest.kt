package com.github.izerui.imux.clarification

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ClarificationFlowTest {
    /** 假的对话：按顺序取回复；回复是 [Throwable] 时抛出，是 [CountDownLatch] 时先等它再取下一个。 */
    private inner class FakeConversation : ClarificationConversation {
        val sent = CopyOnWriteArrayList<String>()
        @Volatile var closed = false

        override fun send(message: String, onProgress: (String) -> Unit): String {
            check(!closed) { "send on a closed conversation" }
            sent += message
            progressSinks += onProgress
            while (true) {
                when (val next = replies.removeFirst()) {
                    is String -> return next
                    is Throwable -> throw next
                    is CountDownLatch -> {
                        sendStarted.countDown()
                        next.await(5, TimeUnit.SECONDS)
                    }
                    else -> error("bad reply $next")
                }
            }
        }

        override fun close() {
            closed = true
        }
    }

    private val replies = java.util.concurrent.LinkedBlockingDeque<Any>()
    private val conversations = CopyOnWriteArrayList<FakeConversation>()
    private val progressSinks = CopyOnWriteArrayList<(String) -> Unit>()
    private val sendStarted = CountDownLatch(1)

    private fun flow(vararg outputs: Any): ClarificationFlow {
        replies.addAll(outputs)
        return ClarificationFlow(
            isChinese = false,
            guidance = "GUIDE-MARK",
            openConversation = { FakeConversation().also { conversations += it } },
        )
    }

    private fun question(header: String, multi: Boolean = false) =
        """{"header":"$header","question":"Q-$header","multiSelect":$multi,"fallback":"F-$header",
           "options":[{"id":"a","label":"A-$header","recommended":true,"description":"D-$header-a"},{"id":"b","label":"B-$header","recommended":false,"description":"D-$header-b"}]}"""

    private fun ask(vararg headers: String) = """{"type":"questions","questions":[${headers.joinToString(",") { question(it) }}]}"""

    private fun done(requirement: String = "ADJUSTED") = """{"type":"done","requirement":"$requirement"}"""

    private val asking get() = (flowUnderTest.state as ClarificationState.Asking).batch
    private lateinit var flowUnderTest: ClarificationFlow

    private fun started(vararg outputs: Any): ClarificationFlow =
        flow(*outputs).also {
            flowUnderTest = it
            it.run(it.begin("REQ-MARK", AgentType.CLAUDE))
        }

    @Test
    fun `首轮没有需要确认的方向时直接给出结果且只发一条开场`() {
        val f = started(done())
        assertEquals(ClarificationState.Result("ADJUSTED"), f.state)
        assertEquals(1, conversations.size)
        val opening = conversations.single().sent.single()
        assertTrue("开场带指南", opening.startsWith("GUIDE-MARK"))
        assertTrue("开场带需求", opening.contains("REQ-MARK"))
        assertTrue("开场带输出格式", opening.contains("\"type\": \"questions\""))
    }

    @Test
    fun `整个澄清只用一段对话，之后每轮只发这一批的回答而不重发需求`() {
        val f = started(ask("H1"), ask("H2"), done())
        f.run(f.submitBatch(mapOf("q1" to Answer.Picked(listOf("b"))))!!)
        f.run(f.submitBatch(mapOf("q1" to Answer.OwnWords("OWN-MARK")))!!)

        assertEquals(ClarificationState.Result("ADJUSTED"), f.state)
        assertEquals("三轮都在同一段对话里", 1, conversations.size)
        val (opening, second, third) = conversations.single().sent
        assertTrue(opening.contains("REQ-MARK"))
        assertEquals("第二轮只有这一批的回答与格式提醒", answersMessage(listOf(f.record.history[0]), isChinese = false), second)
        assertTrue("第二轮写明选了哪一项", second.contains("H1") && second.contains("B-H1") && second.contains("D-H1-b"))
        for ((name, message) in listOf("第二轮" to second, "第三轮" to third)) {
            assertFalse("$name 不重发需求", message.contains("REQ-MARK"))
            assertFalse("$name 不重发指南", message.contains("GUIDE-MARK"))
        }
        assertTrue("第三轮写明用户原话", third.contains("H2") && third.contains("Other: OWN-MARK"))
        assertFalse("第三轮不重发上一批的回答", third.contains("D-H1-b"))
    }

    @Test
    fun `混合批次的开放回答与选择一次提交并在原对话形成成果`() {
        val raw = """{"type":"questions","questions":[
            {"kind":"open","header":"体验","context":"可能是等待的问题。","question":"你认同吗？","fallback":"暂不推断"},
            ${question("范围")}
        ]}"""
        val f = started(raw, done("先研究打断问题"))
        val drafts = BatchDrafts(asking)
        drafts.draftOf(asking[0]).text = "不认同。\n问题是被打断。"
        drafts.draftOf(asking[1]).picked += "b"
        assertEquals("填写不触发模型", 1, conversations.single().sent.size)
        f.run(f.submitBatch(drafts.filled())!!)
        assertEquals("持续同一对话", 1, conversations.size)
        assertEquals("整批只发一次", 2, conversations.single().sent.size)
        val message = conversations.single().sent.last()
        assertTrue("看法保留", message.contains("可能是等待的问题。"))
        assertTrue("开放回答保留", message.contains("→ 不认同。\n问题是被打断。"))
        assertTrue("选择记录保留", message.contains("D-范围-b"))
        assertEquals(ClarificationState.Result("先研究打断问题"), f.state)
    }

    @Test
    fun `开放回答后失败重试恢复问题看法和原话`() {
        val raw = """{"type":"questions","questions":[
            {"kind":"open","header":"体验","context":"可能是等待的问题。","question":"你认同吗？","fallback":"暂不推断"}
        ]}"""
        val f = started(raw, RuntimeException("interrupted"), done())
        f.run(f.submitBatch(mapOf("q1" to Answer.OwnWords("不认同。\n问题是被打断。")))!!)
        assertTrue("失败后结束旧对话", conversations[0].closed)
        f.run(f.retry()!!)
        val reopening = conversations[1].sent.single()
        assertTrue("新对话知道被回应的看法", reopening.contains("可能是等待的问题。"))
        assertTrue("新对话知道问题", reopening.contains("你认同吗？"))
        assertTrue("新对话收到完整原话", reopening.contains("→ 不认同。\n问题是被打断。"))
        assertEquals(ClarificationState.Result("ADJUSTED"), f.state)
    }

    @Test
    fun `一批的回答里不回答的题写明按默认处理，其他写用户原话，选中的写明选项`() {
        val f = started(ask("H1", "H2", "H3"), done())
        f.run(f.submitBatch(mapOf("q2" to Answer.OwnWords("MY-WORDS"), "q3" to Answer.Picked(listOf("a"))))!!)
        val message = conversations.single().sent[1]
        val lines = message.lines().filter { it.startsWith("- ") }
        assertEquals(3, lines.size)
        assertTrue("不回答：${lines[0]}", lines[0].startsWith("- H1") && lines[0].contains("Not answered; use the default: F-H1"))
        assertTrue("其他：${lines[1]}", lines[1].startsWith("- H2") && lines[1].contains("Other: MY-WORDS"))
        assertTrue("选中：${lines[2]}", lines[2].startsWith("- H3") && lines[2].contains("A-H3") && lines[2].contains("D-H3-a"))
    }

    @Test
    fun `已确认就是用户提交的回答，含不回答时的默认处理`() {
        val f = started(ask("H1", "H2"), ask("H3"))
        f.run(f.submitBatch(mapOf("q1" to Answer.Picked(listOf("b"))))!!)
        assertEquals(listOf("D-H1-b", "F-H2"), f.record.confirmed())
        assertEquals("兜底结果列出已确认的回答", "REQ-MARK\n\n- D-H1-b\n- F-H2", composeFallback(f.record))
    }

    @Test
    fun `协议错误在同一段对话里发回错误，重答一次后成功`() {
        val f = started("not json", ask("H1"))
        assertTrue(f.state is ClarificationState.Asking)
        assertEquals(1, conversations.size)
        val sent = conversations.single().sent
        assertEquals(2, sent.size)
        assertTrue("把错误发回", sent[1].contains("no JSON object"))
        assertFalse("纠错消息不重发需求", sent[1].contains("REQ-MARK"))
        assertFalse(conversations.single().closed)
    }

    @Test
    fun `重答后仍不合格进入失败并结束对话`() {
        val f = started("not json", "still not json")
        val state = f.state as ClarificationState.Failed
        assertEquals(FailureReason.PROTOCOL, state.reason)
        assertEquals("still not json", state.raw)
        assertEquals(2, conversations.single().sent.size)
        assertTrue("失败时结束对话", conversations.single().closed)
    }

    @Test
    fun `Agent 出错时进入失败并结束对话`() {
        val f = started(RuntimeException("boom"))
        assertEquals(ClarificationState.Failed(FailureReason.AGENT_ERROR, "boom", null), f.state)
        assertTrue(conversations.single().closed)
    }

    @Test
    fun `开对话就失败时进入失败`() {
        val f = ClarificationFlow(false, "G", openConversation = { throw IllegalStateException("no shell") })
        f.run(f.begin("REQ", AgentType.CLAUDE))
        assertEquals(ClarificationState.Failed(FailureReason.AGENT_ERROR, "no shell", null), f.state)
    }

    @Test
    fun `取消时结束对话、已确认的问答保留，重试时新开对话并以需求与已确认的问答为开场`() {
        val gate = CountDownLatch(1)
        val f = started(ask("H1"), gate, ask("H2"), ask("H3"))
        val r2 = f.submitBatch(mapOf("q1" to Answer.Picked(listOf("b"))))!!
        val worker = thread { f.run(r2) }
        assertTrue(sendStarted.await(5, TimeUnit.SECONDS))

        f.cancelled()
        assertEquals(FailureReason.CANCELLED, (f.state as ClarificationState.Failed).reason)
        assertTrue("取消即结束对话", conversations[0].closed)
        assertEquals("已确认的问答保留", listOf("D-H1-b"), f.record.confirmed())
        gate.countDown()
        worker.join(5_000)
        assertEquals("晚到的回复被丢弃", FailureReason.CANCELLED, (f.state as ClarificationState.Failed).reason)

        f.run(f.retry()!!)
        assertEquals("新开一段对话", 2, conversations.size)
        val reopening = conversations[1].sent.single()
        assertTrue("开场带需求", reopening.contains("REQ-MARK"))
        assertTrue("开场带已确认的问答", reopening.contains("D-H1-b"))
        assertTrue("恢复时带上历史顺序", reopening.contains("in chronological order"))
        assertTrue("保留用户后续修订的权利", reopening.contains("the user's latest explicit revision takes precedence"))
        assertEquals("H3", asking.single().header)
    }

    @Test
    fun `新一轮开始后旧轮的进度与异常不改变新轮状态`() {
        val gateA = CountDownLatch(1)
        val f = flow(gateA, RuntimeException("run A error"), done())
        val r1 = f.begin("REQ", AgentType.CLAUDE)
        val workerA = thread { f.run(r1) }
        assertTrue(sendStarted.await(5, TimeUnit.SECONDS))
        f.cancelled()
        val r2 = f.retry()!!
        val staleProgress = progressSinks.single()

        gateA.countDown()
        workerA.join(5_000)
        staleProgress("STALE-PROGRESS-MARKER")
        val state = f.state as ClarificationState.Investigating
        assertNotEquals("旧轮的进度不进新轮", "STALE-PROGRESS-MARKER", state.progress)

        f.run(r2)
        assertEquals(ClarificationState.Result("ADJUSTED"), f.state)
    }

    @Test
    fun `过期的 runId 调用 run 不会开对话也不会发消息`() {
        val f = flow(done())
        val r1 = f.begin("REQ", AgentType.CLAUDE)
        f.cancelled()
        val r2 = f.retry()!!
        f.run(r1)
        assertTrue(conversations.isEmpty())
        assertTrue(f.state is ClarificationState.Investigating)
        f.run(r2)
        assertEquals(ClarificationState.Result("ADJUSTED"), f.state)
    }

    @Test
    fun `看过结果后继续澄清在同一段对话里接着问`() {
        val f = started(done(), ask("H1"))
        f.run(f.continueClarifying()!!)
        assertEquals("H1", asking.single().header)
        assertEquals(1, conversations.size)
        assertEquals(continueMessage(isChinese = false, plan = "ADJUSTED"), conversations.single().sent[1])
    }

    @Test
    fun `修订执行方案后继续聊把修订传给原对话`() {
        val f = started(done(), ask("H1"))
        f.editResult("使用 PostgreSQL")
        assertEquals("使用 PostgreSQL", undeliveredRequirement(f.state))
        f.run(f.continueClarifying()!!)
        assertEquals(1, conversations.size)
        assertTrue(conversations.single().sent[1].contains("使用 PostgreSQL"))
        assertFalse(conversations.single().sent[1].contains("ADJUSTED"))
    }

    @Test
    fun `修订方案后继续聊失败再生成结果保留修订且再次继续仍传递修订`() {
        val f = started(done(), IllegalStateException("offline"), ask("H2"))
        val edited = "使用 PostgreSQL\n保留用户补充的迁移步骤"
        f.editResult(edited)
        f.run(f.continueClarifying()!!)
        assertTrue(f.state is ClarificationState.Failed)
        val sentBefore = conversations.sumOf { it.sent.size }
        f.fallbackResult()
        assertEquals(ClarificationState.Result(edited), f.state)
        assertEquals("兜底不调用 Agent", sentBefore, conversations.sumOf { it.sent.size })
        f.run(f.continueClarifying()!!)
        assertEquals(2, conversations.size)
        assertTrue(conversations[1].sent.single().contains(edited))
    }

    @Test
    fun `修订方案后取消继续聊再生成结果保留修订`() {
        val f = started(done())
        f.editResult("用户修改的执行方案")
        f.continueClarifying()
        f.cancelled()
        f.fallbackResult()
        assertEquals(ClarificationState.Result("用户修改的执行方案"), f.state)
        assertTrue(conversations.single().closed)
    }

    @Test
    fun `修订方案兜底只追加后续回答且重复兜底不重复追加`() {
        val f = started(ask("H1"), done(), ask("H2"), IllegalStateException("offline"), IllegalStateException("offline again"))
        f.run(f.submitBatch(mapOf("q1" to Answer.OwnWords("使用 MySQL")))!!)
        f.editResult("使用 PostgreSQL")
        f.run(f.continueClarifying()!!)
        f.run(f.submitBatch(mapOf("q1" to Answer.OwnWords("需要迁移脚本")))!!)
        f.fallbackResult()
        val expected = "使用 PostgreSQL\n\n- 需要迁移脚本"
        assertEquals("不把修订前的 MySQL 回答加回来", ClarificationState.Result(expected), f.state)
        f.run(f.continueClarifying()!!)
        f.fallbackResult()
        assertEquals("第二次失败不会重复追加迁移脚本", ClarificationState.Result(expected), f.state)
    }

    @Test
    fun `修订后提交新回答再失败重试按方案和后续回答恢复上下文`() {
        val f = started(ask("H1"), done(), ask("H2"), IllegalStateException("offline"), ask("H3"))
        f.run(f.submitBatch(mapOf("q1" to Answer.OwnWords("OLD-DECISION")))!!)
        f.editResult("REVISED-PLAN")
        f.run(f.continueClarifying()!!)
        f.run(f.submitBatch(mapOf("q1" to Answer.OwnWords("LATEST-ANSWER")))!!)
        f.run(f.retry()!!)
        val opening = conversations[1].sent.single()
        assertFalse("不重新确认修订前的决定", opening.contains("OLD-DECISION"))
        assertTrue("携带修订方案", opening.contains("REVISED-PLAN"))
        assertTrue("携带之后的新回答", opening.contains("LATEST-ANSWER"))
        assertTrue("新回答在方案之后", opening.indexOf("LATEST-ANSWER") > opening.indexOf("REVISED-PLAN"))
        assertTrue("新回答冲突时优先", opening.contains("the later answers take precedence"))
        assertFalse("不能在结尾又要求以旧方案为准", opening.contains("Use the user's current edited outcome:"))
    }

    @Test
    fun `无问答历史的修订方案在调查提问失败时关闭仍需确认`() {
        val f = started(done(), ask("H1"))
        f.editResult("用户修订的方案")
        val run = f.continueClarifying()!!
        assertTrue(f.record.history.isEmpty())
        assertTrue("调查中", closeNeedsConfirmation(f.state, f.record, false, f.hasRetainedPlan()))
        f.run(run)
        assertTrue("提问页尚未填写", closeNeedsConfirmation(f.state, f.record, false, f.hasRetainedPlan()))
        f.end()
        val failed = started(done(), IllegalStateException("offline"))
        failed.editResult("另一个修订方案")
        failed.run(failed.continueClarifying()!!)
        assertTrue(failed.record.history.isEmpty())
        assertTrue("调用失败页", closeNeedsConfirmation(failed.state, failed.record, false, failed.hasRetainedPlan()))
    }

    @Test
    fun `取消继续聊后保留方案要求关闭确认而新流程不沿用旧方案`() {
        val f = started(done())
        f.editResult("用户修订的方案")
        f.continueClarifying()
        f.cancelled()
        assertTrue("失败页没有问答历史", f.record.history.isEmpty())
        assertTrue("失败页仍保留方案", closeNeedsConfirmation(f.state, f.record, false, f.hasRetainedPlan()))
        f.begin("新的目标", AgentType.CLAUDE)
        assertFalse("新目标不保留旧方案", f.hasRetainedPlan())
        assertFalse("新目标第一轮无需确认", closeNeedsConfirmation(f.state, f.record, false, f.hasRetainedPlan()))
    }

    @Test
    fun `继续聊的调查页和提问页可取回保留方案而不改变状态`() {
        val f = started(done(), ask("H1"))
        f.editResult("使用 PostgreSQL")
        val run = f.continueClarifying()!!
        val investigating = f.state
        assertEquals("调查页可复制修订", "使用 PostgreSQL", f.undeliveredPlan())
        assertEquals("读取不改变调查状态", investigating, f.state)
        f.run(run)
        val askingState = f.state
        assertEquals("提问页可复制修订", "使用 PostgreSQL", f.undeliveredPlan())
        assertEquals("读取不改变提问状态", askingState, f.state)
        assertEquals("读取没有增加 Agent 调用", 2, conversations.single().sent.size)
    }

    @Test
    fun `关闭时的保留方案包含后续回答且和失败页兜底一致`() {
        val f = started(ask("H1"), done(), ask("H2"), IllegalStateException("offline"))
        f.run(f.submitBatch(mapOf("q1" to Answer.OwnWords("使用 MySQL")))!!)
        f.editResult("使用 PostgreSQL")
        f.run(f.continueClarifying()!!)
        val run = f.submitBatch(mapOf("q1" to Answer.OwnWords("补充迁移脚本")))!!
        val expected = "使用 PostgreSQL\n\n- 补充迁移脚本"
        assertEquals("调查中保留后续回答而不恢复旧决定", expected, f.undeliveredPlan())
        f.run(run)
        assertTrue(f.state is ClarificationState.Failed)
        assertEquals("失败页也提供完整文本", expected, f.undeliveredPlan())
        f.fallbackResult()
        assertEquals(ClarificationState.Result(expected), f.state)
    }

    @Test
    fun `结果页和发送中的最新编辑优先于此前保留方案`() {
        val f = started(done(), done("第二版"))
        f.editResult("第一版修订")
        f.run(f.continueClarifying()!!)
        f.editResult("第二版修订")
        assertEquals("结果页复制最新编辑", "第二版修订", f.undeliveredPlan())
        f.sending(waitingForTurn = true)
        assertEquals("发送中复制最新编辑", "第二版修订", f.undeliveredPlan())
        f.sendCancelled()
        assertEquals("取消发送后仍是最新编辑", "第二版修订", f.undeliveredPlan())
    }

    @Test
    fun `尚未生成方案的输入调查提问失败状态没有待复制方案`() {
        val f = flow(ask("H1"))
        assertNull("输入页", f.undeliveredPlan())
        val run = f.begin("REQ", AgentType.CLAUDE)
        assertNull("首次调查", f.undeliveredPlan())
        f.run(run)
        assertNull("首次提问", f.undeliveredPlan())
        f.submitBatch(emptyMap())
        f.cancelled()
        assertTrue(f.state is ClarificationState.Failed)
        assertNull("有回答但尚未生成方案", f.undeliveredPlan())
    }

    @Test
    fun `兜底方案的编辑在新对话中保留`() {
        val f = started("bad", "bad again", ask("H2"))
        f.fallbackResult()
        f.editResult("修订后的 PostgreSQL 方案")
        f.run(f.continueClarifying()!!)
        assertEquals(2, conversations.size)
        assertTrue(conversations[1].sent.single().contains("修订后的 PostgreSQL 方案"))
    }

    @Test
    fun `兜底结果不调用 Agent，之后继续澄清新开对话并带上已确认的问答`() {
        val f = started(ask("H1"), "bad", "bad again", ask("H2"))
        f.run(f.submitBatch(mapOf("q1" to Answer.Picked(listOf("b"))))!!)
        assertTrue(f.state is ClarificationState.Failed)
        val sentBefore = conversations.sumOf { it.sent.size }

        f.fallbackResult()
        assertEquals(ClarificationState.Result("REQ-MARK\n\n- D-H1-b"), f.state)
        assertEquals("兜底不发消息", sentBefore, conversations.sumOf { it.sent.size })

        f.run(f.continueClarifying()!!)
        assertEquals(2, conversations.size)
        assertTrue(conversations[1].sent.single().contains("D-H1-b"))
        assertEquals("H2", asking.single().header)
    }

    @Test
    fun `结束澄清时作废在途一轮并结束对话`() {
        val gate = CountDownLatch(1)
        val f = flow(gate, ask("H1"))
        val r1 = f.begin("REQ", AgentType.CLAUDE)
        val worker = thread { f.run(r1) }
        assertTrue(sendStarted.await(5, TimeUnit.SECONDS))

        f.end()
        assertTrue(conversations.single().closed)
        gate.countDown()
        worker.join(5_000)
        assertFalse("晚到的问题不再显示", f.state is ClarificationState.Asking)
    }

    @Test
    fun `结果页结束澄清时结束仍在的对话`() {
        val f = started(done())
        assertFalse(conversations.single().closed)
        f.end()
        assertTrue(conversations.single().closed)
    }

    @Test
    fun `取消晚于问题到达时不盖掉问题，也不结束对话`() {
        val f = started(ask("H1"))
        f.cancelled()
        assertTrue(f.state is ClarificationState.Asking)
        assertFalse(conversations.single().closed)
    }

    @Test
    fun `取消晚于结果到达时不盖掉结果`() {
        val f = started(done())
        f.cancelled()
        assertEquals(ClarificationState.Result("ADJUSTED"), f.state)
    }

    @Test
    fun `平台取消异常向上抛出而不记为失败`() {
        val f = flow(com.intellij.openapi.progress.ProcessCanceledException())
        val runId = f.begin("REQ", AgentType.CLAUDE)
        try {
            f.run(runId)
            fail("ProcessCanceledException 应向上抛出")
        } catch (_: com.intellij.openapi.progress.ProcessCanceledException) {
        }
        assertTrue(f.state is ClarificationState.Investigating)
    }

    @Test
    fun `发送中切换等待原因且取消后回到结果页文本保留`() {
        val f = started(done())
        f.sending(waitingForTurn = true)
        assertEquals(ClarificationState.Sending("ADJUSTED", waitingForTurn = true), f.state)
        f.sending(waitingForTurn = false)
        assertEquals(ClarificationState.Sending("ADJUSTED", waitingForTurn = false), f.state)
        f.sendCancelled()
        assertEquals(ClarificationState.Result("ADJUSTED"), f.state)
    }

    @Test
    fun `提交前不发消息，提交一批只发一条`() {
        val f = started(ask("H1", "H2"), done())
        assertEquals(1, conversations.single().sent.size)
        f.run(f.submitBatch(mapOf("q1" to Answer.Picked(listOf("a")), "q2" to Answer.Picked(listOf("b"))))!!)
        assertEquals(2, conversations.single().sent.size)
        assertEquals(listOf("D-H1-a", "D-H2-b"), f.record.confirmed())
    }

    @Test
    fun `不在提问页时提交无效`() {
        val f = started(done())
        assertNull(f.submitBatch(mapOf("q1" to Answer.Picked(listOf("a")))))
    }

    @Test
    fun `提交后草稿作废，之后一模一样的问题从空白开始`() {
        val f = started(ask("H1"), ask("H1"))
        val batch = asking
        val drafts = BatchDrafts(batch).also { it.draftOf(batch.single()).picked += "b" }
        f.drafts = drafts
        f.run(f.submitBatch(drafts.filled())!!)
        assertEquals("下一批与上一批完全相同", batch, asking)
        assertNull("草稿已随提交清空，不会被当成新一批的草稿", f.drafts)
    }

    @Test
    fun `begin 时传入的 Agent 类型被记住并传给对话工厂`() {
        val capturedTypes = mutableListOf<AgentType>()
        replies += done()
        val f = ClarificationFlow(
            isChinese = false,
            guidance = "G",
            openConversation = { type -> capturedTypes += type; FakeConversation().also { conversations += it } },
        )
        f.run(f.begin("REQ", AgentType.CODEX))
        assertEquals("开场时传入 Codex", listOf(AgentType.CODEX), capturedTypes)
        assertEquals("chosenAgentType 返回 Codex", AgentType.CODEX, f.chosenAgentType())
    }
}
