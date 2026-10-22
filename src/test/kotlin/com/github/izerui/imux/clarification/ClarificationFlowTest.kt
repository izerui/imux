package com.github.izerui.imux.clarification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ClarificationFlowTest {
    private val prompts = CopyOnWriteArrayList<String>()

    private fun flow(vararg outputs: String): ClarificationFlow {
        val queue = ArrayDeque(outputs.toList())
        return ClarificationFlow(isChinese = false, guidance = "GUIDE", runAgent = { prompt -> prompts += prompt; queue.removeFirst() })
    }

    private val first =
        """{"understanding":"u","directions":{"add":[{"tempId":"new-1","title":"T1","why":"w","source":"s","fallback":"F1"},
           {"tempId":"new-2","title":"T2","why":"w","source":"s","fallback":"F2"}]},"notes":"n",
           "next":{"type":"question","directionId":"new-1","question":"Q1","reason":"r","basis":"b",
             "options":[{"id":"a","label":"A","recommended":true,"conclusion":"CA"},{"id":"b","label":"B","recommended":false,"conclusion":"CB"}]}}"""

    private fun askD2(previousStatus: String = "DECIDED") =
        """{"previous":{"id":"d1","status":"$previousStatus","conclusion":"","openPoints":[]},"notes":"n",
           "next":{"type":"question","directionId":"d2","question":"Q2","reason":"r","basis":"b",
             "options":[{"id":"a","label":"A","recommended":true,"conclusion":"C2A"},{"id":"b","label":"B","recommended":false,"conclusion":"C2B"}]}}"""

    private fun done(previous: String = "d2") =
        """{"previous":{"id":"$previous","status":"DECIDED","conclusion":"","openPoints":[]},"notes":"n","next":{"type":"done","requirement":"ADJUSTED"}}"""

    @Test
    fun `首轮没有需要确认的方向时直接给出结果且只调用一次`() {
        val f = flow("""{"understanding":"u","notes":"","next":{"type":"done","requirement":"ADJUSTED"}}""")
        f.begin("REQ", null)
        f.advance()
        assertEquals(ClarificationState.Result("ADJUSTED"), f.state)
        assertEquals(1, prompts.size)
    }

    @Test
    fun `每轮提示词都带上之前全部已确认的内容`() {
        val f = flow(first, askD2(), done())
        f.begin("REQ", "BACKGROUND")
        f.advance()
        f.answer(Answer.Picked("b"), correction = "FIX")
        f.advance()
        f.answer(Answer.OwnWords("MY WORDS"), correction = null)
        f.advance()
        val last = prompts.last()
        assertTrue(last.contains("REQ"))
        assertTrue(last.contains("BACKGROUND"))
        assertTrue(last.contains("CB"))
        assertTrue(last.contains("FIX"))
        assertTrue(last.contains("MY WORDS"))
        assertEquals(ClarificationState.Result("ADJUSTED"), f.state)
    }

    @Test
    fun `协议错误附带错误重试一次后成功`() {
        val f = flow("not json", first)
        f.begin("REQ", null)
        f.advance()
        assertTrue(f.state is ClarificationState.Asking)
        assertEquals(2, prompts.size)
        assertTrue(prompts[1].contains("no JSON object"))
    }

    @Test
    fun `重试后仍出错进入失败状态且不再调用`() {
        val f = flow("not json", "still not json")
        f.begin("REQ", null)
        f.advance()
        val state = f.state as ClarificationState.Failed
        assertEquals(FailureReason.PROTOCOL, state.reason)
        assertEquals("still not json", state.raw)
        assertEquals(2, prompts.size)
    }

    @Test
    fun `调查期间跳过的方向使问题失效时用新台账重新调用且跳过保持`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val outputs = ArrayDeque(listOf(askD2(), """{"previous":{"id":"d1","status":"DECIDED","conclusion":"","openPoints":[]},"notes":"n","next":{"type":"done","requirement":"ADJUSTED"}}"""))
        var calls = 0
        lateinit var f: ClarificationFlow
        f = ClarificationFlow(false, "G", runAgent = { prompt ->
            prompts += prompt
            calls++
            if (calls == 2) { started.countDown(); release.await(5, TimeUnit.SECONDS) }
            if (calls == 1) first else outputs.removeFirst()
        })
        f.begin("REQ", null)
        f.advance()
        f.answer(Answer.Picked("a"), null)
        val worker = thread { f.advance() }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertTrue(!f.skip("d2"))
        release.countDown()
        worker.join(5_000)
        assertEquals(3, calls)
        assertEquals(DirectionStatus.DEFAULTED, f.ledger.direction("d2")!!.status)
        assertEquals(ClarificationState.Result("ADJUSTED"), f.state)
    }

    @Test
    fun `跳过当前问题等同于回答跳过`() {
        val f = flow(first)
        f.begin("REQ", null)
        f.advance()
        assertTrue(f.skip("d1"))
        assertEquals("F1", f.ledger.history.last().recorded)
        assertTrue(f.state is ClarificationState.Investigating)
    }

    @Test
    fun `取消后晚到的结果被丢弃`() {
        val release = CountDownLatch(1)
        val f = ClarificationFlow(false, "G", runAgent = { release.await(5, TimeUnit.SECONDS); first })
        f.begin("REQ", null)
        val worker = thread { f.advance() }
        f.cancelled()
        release.countDown()
        worker.join(5_000)
        assertEquals(FailureReason.CANCELLED, (f.state as ClarificationState.Failed).reason)
        assertTrue(f.ledger.directions.isEmpty())
    }

    @Test
    fun `生成结果阶段取 requirement`() {
        val f = flow(first, """{"requirement":"FINAL"}""")
        f.begin("REQ", null)
        f.advance()
        f.beginResult()
        f.generateResult()
        assertEquals(ClarificationState.Result("FINAL"), f.state)
        assertTrue(prompts.last().contains("{\"requirement\": \"...\"}"))
    }

    @Test
    fun `失败后重试的是失败的那一步`() {
        val f = flow(first, "x", "y")
        f.begin("REQ", null)
        f.advance()
        f.beginResult()
        f.generateResult()
        assertEquals(PendingWork.RESULT, f.retry())
        assertTrue(f.state is ClarificationState.Investigating)
    }

    @Test
    fun `发送中切换等待原因且取消后回到结果页文本保留`() {
        val f = flow("""{"notes":"","next":{"type":"done","requirement":"ADJUSTED"}}""")
        f.begin("REQ", null)
        f.advance()
        f.sending(waitingForTurn = true)
        assertEquals(ClarificationState.Sending("ADJUSTED", waitingForTurn = true), f.state)
        f.sending(waitingForTurn = false)
        assertEquals(ClarificationState.Sending("ADJUSTED", waitingForTurn = false), f.state)
        f.sendCancelled()
        assertEquals(ClarificationState.Result("ADJUSTED"), f.state)
    }

    @Test
    fun `背景快照补进台账后进入提示词`() {
        val f = flow(first)
        f.begin("REQ", null)
        f.attachBackground("LATE BACKGROUND")
        f.advance()
        assertTrue(prompts.single().contains("LATE BACKGROUND"))
    }

    @Test
    fun `兜底结果不调用 Agent`() {
        val f = flow(first)
        f.begin("REQ", null)
        f.advance()
        f.fallbackResult()
        assertEquals(ClarificationState.Result("REQ"), f.state)
        assertEquals(1, prompts.size)
    }
}
