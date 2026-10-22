package com.github.izerui.imux.clarification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ClarificationFlowTest {
    private val prompts = CopyOnWriteArrayList<String>()

    private fun flow(vararg outputs: String): ClarificationFlow {
        val queue = ArrayDeque(outputs.toList())
        return ClarificationFlow(
            isChinese = false,
            guidance = "GUIDE",
            runAgent = { prompt, _ -> prompts += prompt; queue.removeFirst() },
        )
    }

    private val first =
        """{"understanding":"u","directions":{"add":[{"tempId":"new-1","title":"T1","why":"w","source":"s","fallback":"F1"},
           {"tempId":"new-2","title":"T2","why":"w","source":"s","fallback":"F2"}]},"notes":"n",
           "next":{"type":"question","directionId":"new-1","question":"Q1","reason":"r","basis":"b",
             "options":[{"id":"a","label":"A","recommended":true,"conclusion":"CA"},{"id":"b","label":"B","recommended":false,"conclusion":"PICKED-MARK-2B"}]}}"""

    private fun askD2(previousStatus: String = "DECIDED") =
        """{"previous":{"id":"d1","status":"$previousStatus","conclusion":"","openPoints":[]},"notes":"n",
           "next":{"type":"question","directionId":"d2","question":"Q2","reason":"r","basis":"b",
             "options":[{"id":"a","label":"A","recommended":true,"conclusion":"C2A"},{"id":"b","label":"B","recommended":false,"conclusion":"C2B"}]}}"""

    private fun done(previous: String = "d2") =
        """{"previous":{"id":"$previous","status":"DECIDED","conclusion":"","openPoints":[]},"notes":"n","next":{"type":"done","requirement":"ADJUSTED"}}"""

    @Test
    fun `首轮没有需要确认的方向时直接给出结果且只调用一次`() {
        val f = flow("""{"understanding":"u","notes":"","next":{"type":"done","requirement":"ADJUSTED"}}""")
        val runId = f.begin("REQ", null)
        f.advance(runId)
        assertEquals(ClarificationState.Result("ADJUSTED"), f.state)
        assertEquals(1, prompts.size)
    }

    @Test
    fun `每轮提示词都带上之前全部已确认的内容`() {
        val f = flow(first, askD2(), done())
        val r1 = f.begin("REQ-MARK-7Q", "BG-MARK-3X")
        f.advance(r1)
        val r2 = f.answer(Answer.Picked("b"), correction = "FIX-MARK-9Z")!!
        f.advance(r2)
        // Round 2 prompt (prompts[1]) must carry requirement, background, round-1 conclusion, and correction
        val round2 = prompts[1]
        assertTrue("round 2 should contain requirement", round2.contains("REQ-MARK-7Q"))
        assertTrue("round 2 should contain background", round2.contains("BG-MARK-3X"))
        assertTrue("round 2 should contain round-1 conclusion", round2.contains("PICKED-MARK-2B"))
        assertTrue("round 2 should contain correction", round2.contains("FIX-MARK-9Z"))
        val r3 = f.answer(Answer.OwnWords("OWN-MARK-5K"), correction = null)!!
        f.advance(r3)
        // Round 3 prompt (prompts[2]) must carry everything above plus the own-words marker
        val round3 = prompts[2]
        assertTrue("round 3 should contain requirement", round3.contains("REQ-MARK-7Q"))
        assertTrue("round 3 should contain background", round3.contains("BG-MARK-3X"))
        assertTrue("round 3 should contain round-1 conclusion", round3.contains("PICKED-MARK-2B"))
        assertTrue("round 3 should contain correction", round3.contains("FIX-MARK-9Z"))
        assertTrue("round 3 should contain own words", round3.contains("OWN-MARK-5K"))
        assertEquals(ClarificationState.Result("ADJUSTED"), f.state)
    }

    @Test
    fun `协议错误附带错误重试一次后成功`() {
        val f = flow("not json", first)
        val runId = f.begin("REQ", null)
        f.advance(runId)
        assertTrue(f.state is ClarificationState.Asking)
        assertEquals(2, prompts.size)
        assertTrue(prompts[1].contains("no JSON object"))
    }

    @Test
    fun `重试后仍出错进入失败状态且不再调用`() {
        val f = flow("not json", "still not json")
        val runId = f.begin("REQ", null)
        f.advance(runId)
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
        val calls = AtomicInteger(0)
        lateinit var f: ClarificationFlow
        f = ClarificationFlow(false, "G", runAgent = { prompt, _ ->
            prompts += prompt
            val c = calls.incrementAndGet()
            if (c == 2) { started.countDown(); release.await(5, TimeUnit.SECONDS) }
            if (c == 1) first else outputs.removeFirst()
        })
        val r1 = f.begin("REQ", null)
        f.advance(r1)
        val r2 = f.answer(Answer.Picked("a"), null)!!
        val worker = thread { f.advance(r2) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertNull(f.skip("d2"))
        release.countDown()
        worker.join(5_000)
        assertEquals(3, calls.get())
        assertEquals(DirectionStatus.DEFAULTED, f.ledger.direction("d2")!!.status)
        assertEquals(ClarificationState.Result("ADJUSTED"), f.state)
    }

    @Test
    fun `跳过当前问题等同于回答跳过`() {
        val f = flow(first)
        val runId = f.begin("REQ", null)
        f.advance(runId)
        val skipRunId = f.skip("d1")
        assertTrue(skipRunId != null)
        assertEquals("F1", f.ledger.history.last().recorded)
        assertTrue(f.state is ClarificationState.Investigating)
    }

    @Test
    fun `取消后晚到的结果被丢弃`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val f = ClarificationFlow(false, "G", runAgent = { _, _ ->
            calls.incrementAndGet()
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            first
        })
        val runId = f.begin("REQ", null)
        val worker = thread { f.advance(runId) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        f.cancelled()
        release.countDown()
        worker.join(5_000)
        assertEquals(1, calls.get())
        assertEquals(FailureReason.CANCELLED, (f.state as ClarificationState.Failed).reason)
        assertTrue(f.ledger.directions.isEmpty())
    }

    @Test
    fun `新一轮开始后旧轮的进度与异常不改变新轮状态`() {
        val runAStarted = CountDownLatch(1)
        val runARelease = CountDownLatch(1)
        val runBStarted = CountDownLatch(1)
        val runBRelease = CountDownLatch(1)
        val capturedOnProgressA = AtomicReference<((String) -> Unit)?>()
        val calls = AtomicInteger(0)
        val doneResponse = """{"understanding":"u","notes":"","next":{"type":"done","requirement":"ADJUSTED"}}"""
        lateinit var f: ClarificationFlow
        f = ClarificationFlow(false, "G", runAgent = { _, onProgress ->
            val c = calls.incrementAndGet()
            when (c) {
                1 -> {
                    capturedOnProgressA.set(onProgress)
                    runAStarted.countDown()
                    runARelease.await(5, TimeUnit.SECONDS)
                    throw RuntimeException("run A error")
                }
                2 -> {
                    runBStarted.countDown()
                    runBRelease.await(5, TimeUnit.SECONDS)
                    doneResponse
                }
                else -> doneResponse
            }
        })
        val runIdA = f.begin("REQ", null)
        val workerA = thread { f.advance(runIdA) }
        assertTrue(runAStarted.await(5, TimeUnit.SECONDS))

        // Cancel run A and retry (run B starts)
        f.cancelled()
        val (_, runIdB) = f.retry()!!
        val workerB = thread { f.advance(runIdB) }
        assertTrue(runBStarted.await(5, TimeUnit.SECONDS))

        // Release A — its exception should be ignored because generation moved on
        runARelease.countDown()
        workerA.join(5_000)

        // Now invoke A's captured onProgress — should be ignored
        capturedOnProgressA.get()!!("STALE-PROGRESS-MARKER")
        // B's state should still be Investigating without A's progress marker
        val stateAfterStaleProgress = f.state
        assertTrue("B should still be Investigating", stateAfterStaleProgress is ClarificationState.Investigating)
        assertNotEquals("B should not have A's progress", "STALE-PROGRESS-MARKER", (stateAfterStaleProgress as ClarificationState.Investigating).progress)

        // Let B finish normally
        runBRelease.countDown()
        workerB.join(5_000)
        assertEquals(ClarificationState.Result("ADJUSTED"), f.state)
    }

    @Test
    fun `过期的 runId 调用 advance 不会触发 runAgent`() {
        val calls = AtomicInteger(0)
        val doneResponse = """{"understanding":"u","notes":"","next":{"type":"done","requirement":"ADJUSTED"}}"""
        val f = ClarificationFlow(false, "G", runAgent = { _, _ ->
            calls.incrementAndGet()
            doneResponse
        })
        val r1 = f.begin("REQ", null)
        f.cancelled()
        val (_, r2) = f.retry()!!
        // Now state is Investigating with r2; advance with stale r1 should be a no-op
        val ledgerBefore = f.ledger
        f.advance(r1)
        assertEquals("stale advance must not call runAgent", 0, calls.get())
        assertTrue("state must remain Investigating", f.state is ClarificationState.Investigating)
        assertEquals("ledger must be unchanged", ledgerBefore, f.ledger)
        // r2 advance works normally
        f.advance(r2)
        assertEquals(ClarificationState.Result("ADJUSTED"), f.state)
    }

    @Test
    fun `生成结果阶段取 requirement`() {
        val f = flow(first, """{"requirement":"FINAL"}""")
        val r1 = f.begin("REQ", null)
        f.advance(r1)
        val r2 = f.beginResult()!!
        f.generateResult(r2)
        assertEquals(ClarificationState.Result("FINAL"), f.state)
        assertTrue(prompts.last().contains("{\"requirement\": \"...\"}"))
    }

    @Test
    fun `失败后重试的是失败的那一步`() {
        val f = flow(first, "x", "y")
        val r1 = f.begin("REQ", null)
        f.advance(r1)
        val r2 = f.beginResult()!!
        f.generateResult(r2)
        val (work, _) = f.retry()!!
        assertEquals(PendingWork.RESULT, work)
        assertTrue(f.state is ClarificationState.Investigating)
    }

    @Test
    fun `发送中切换等待原因且取消后回到结果页文本保留`() {
        val f = flow("""{"notes":"","next":{"type":"done","requirement":"ADJUSTED"}}""")
        val runId = f.begin("REQ", null)
        f.advance(runId)
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
        val r1 = f.begin("REQ", null)
        f.cancelled()
        val (_, r2) = f.retry()!!
        f.attachBackground(r2, "LATE BACKGROUND")
        // 过期 runId 的背景快照必须被忽略
        f.attachBackground(r1, "STALE-BG-MARK")
        assertEquals("LATE BACKGROUND", f.ledger.background)
        f.advance(r2)
        assertTrue(prompts[0].contains("LATE BACKGROUND"))
        assertTrue("stale background must not appear in prompt", !prompts[0].contains("STALE-BG-MARK"))
    }

    private fun askD1Again() =
        """{"notes":"n","next":{"type":"question","directionId":"d1","question":"Q1 again","reason":"r","basis":"b",
             "options":[{"id":"a","label":"A","recommended":true,"conclusion":"CA"},{"id":"b","label":"B","recommended":false,"conclusion":"CB"}]}}"""

    @Test
    fun `问题页直接生成结果后继续澄清仍能再问同一方向`() {
        val f = flow(first, """{"requirement":"FINAL"}""", askD1Again())
        f.advance(f.begin("REQ", null))
        assertEquals("d1", (f.state as ClarificationState.Asking).question.directionId)
        f.generateResult(f.beginResult()!!)
        assertEquals(DirectionStatus.OPEN, f.ledger.direction("d1")!!.status)
        f.advance(f.continueClarifying()!!)
        val asking = f.state as ClarificationState.Asking
        assertEquals("d1", asking.question.directionId)
        assertEquals("Q1 again", asking.question.text)
        assertEquals(3, prompts.size)
    }

    @Test
    fun `调查中生成结果时在途一轮的返回被丢弃`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val f = ClarificationFlow(false, "G", runAgent = { _, _ ->
            when (calls.incrementAndGet()) {
                1 -> { started.countDown(); release.await(5, TimeUnit.SECONDS); first }
                else -> """{"requirement":"FINAL"}"""
            }
        })
        val r1 = f.begin("REQ", null)
        val worker = thread { f.advance(r1) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertTrue(f.canFinish)
        val r2 = f.beginResult()!!
        release.countDown()
        worker.join(5_000)
        assertTrue("在途一轮的问题不能盖掉生成结果", f.state is ClarificationState.Investigating)
        assertTrue("在途一轮的方向不能写进台账", f.ledger.directions.isEmpty())
        f.generateResult(r2)
        assertEquals(ClarificationState.Result("FINAL"), f.state)
    }

    @Test
    fun `输入页不能生成结果`() {
        val f = flow()
        assertFalse(f.canFinish)
        assertNull(f.beginResult())
        assertEquals(ClarificationState.Input, f.state)
    }

    @Test
    fun `结果页不能再生成结果`() {
        val f = flow("""{"notes":"","next":{"type":"done","requirement":"ADJUSTED"}}""")
        f.advance(f.begin("REQ", null))
        assertFalse(f.canFinish)
        assertNull(f.beginResult())
        assertEquals(ClarificationState.Result("ADJUSTED"), f.state)
    }

    @Test
    fun `正在生成结果时不能再次生成结果`() {
        val f = flow(first)
        f.advance(f.begin("REQ", null))
        assertTrue(f.beginResult() != null)
        assertFalse(f.canFinish)
        assertNull(f.beginResult())
    }

    @Test
    fun `取消晚于问题到达时不盖掉问题`() {
        val f = flow(first)
        f.advance(f.begin("REQ", null))
        f.cancelled()
        assertTrue(f.state is ClarificationState.Asking)
        assertEquals(DirectionStatus.ACTIVE, f.ledger.direction("d1")!!.status)
    }

    @Test
    fun `取消晚于结果到达时不盖掉结果`() {
        val f = flow("""{"notes":"","next":{"type":"done","requirement":"ADJUSTED"}}""")
        f.advance(f.begin("REQ", null))
        f.cancelled()
        assertEquals(ClarificationState.Result("ADJUSTED"), f.state)
    }

    @Test
    fun `平台取消异常向上抛出而不记为失败`() {
        val f = ClarificationFlow(false, "G", runAgent = { _, _ -> throw com.intellij.openapi.progress.ProcessCanceledException() })
        val runId = f.begin("REQ", null)
        try {
            f.advance(runId)
            fail("ProcessCanceledException 应向上抛出")
        } catch (_: com.intellij.openapi.progress.ProcessCanceledException) {
        }
        assertTrue(f.state is ClarificationState.Investigating)
    }

    @Test
    fun `指定先问这个后模型返回 done 时附错误重新调用`() {
        val doneOut = """{"notes":"","next":{"type":"done","requirement":"ADJUSTED"}}"""
        val askD2Plain =
            """{"notes":"n","next":{"type":"question","directionId":"d2","question":"Q2","reason":"r","basis":"b",
                 "options":[{"id":"a","label":"A","recommended":true,"conclusion":"C2A"},{"id":"b","label":"B","recommended":false,"conclusion":"C2B"}]}}"""
        val f = flow(first, """{"requirement":"FINAL"}""", doneOut, askD2Plain)
        f.advance(f.begin("REQ", null))
        f.generateResult(f.beginResult()!!)
        f.pin("d2")
        f.advance(f.continueClarifying()!!)
        assertEquals("d2", (f.state as ClarificationState.Asking).question.directionId)
        assertTrue(prompts.last().contains("the user asked to discuss d2 next"))
    }

    @Test
    fun `兜底结果不调用 Agent`() {
        val f = flow(first)
        val runId = f.begin("REQ", null)
        f.advance(runId)
        f.fallbackResult()
        assertEquals(ClarificationState.Result("REQ"), f.state)
        assertEquals(1, prompts.size)
    }

    // ---- 用户刚确认的回答在生成结果中不丢失 ----

    /** 首轮有两个方向：d1 有 CONFIRMED-MARK-4R 选项，d2 有 fallback UNANSWERED-MARK-8T */
    private val firstTwoDirections =
        """{"understanding":"u","directions":{"add":[
           {"tempId":"new-1","title":"T1","why":"w","source":"s","fallback":"F1"},
           {"tempId":"new-2","title":"T2","why":"w","source":"s","fallback":"UNANSWERED-MARK-8T"}
           ]},"notes":"n",
           "next":{"type":"question","directionId":"new-1","question":"Q1","reason":"r","basis":"b",
             "options":[{"id":"a","label":"A","recommended":true,"conclusion":"CONFIRMED-MARK-4R"},
                        {"id":"b","label":"B","recommended":false,"conclusion":"CB"}]}}"""

    @Test
    fun `用户刚确认的回答在 RESULT 提示词的 confirmed 数组中出现`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val resultPromptRef = AtomicReference<String>()
        val f = ClarificationFlow(false, "G", runAgent = { prompt, _ ->
            prompts += prompt
            when (calls.incrementAndGet()) {
                1 -> firstTwoDirections
                2 -> { started.countDown(); release.await(5, TimeUnit.SECONDS); askD2() }
                3 -> { resultPromptRef.set(prompt); """{"requirement":"FINAL"}""" }
                else -> error("unexpected call")
            }
        })
        val r1 = f.begin("REQ", null)
        f.advance(r1)
        // Answer d1 with Picked("a") => conclusion = CONFIRMED-MARK-4R
        val r2 = f.answer(Answer.Picked("a"), null)!!
        // Block the next round
        val worker = thread { f.advance(r2) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        // While round 2 is blocked, begin result
        val r3 = f.beginResult()!!
        release.countDown()
        worker.join(5_000)
        f.generateResult(r3)
        assertEquals(ClarificationState.Result("FINAL"), f.state)
        val resultPrompt = resultPromptRef.get()!!
        // Extract the confirmed array from the JSON — find "confirmed" key
        val confirmedIdx = resultPrompt.indexOf("\"confirmed\"")
        assertTrue("RESULT prompt must contain confirmed array", confirmedIdx >= 0)
        val afterConfirmed = resultPrompt.substring(confirmedIdx)
        assertTrue("confirmed array must contain the user's just-confirmed answer", afterConfirmed.contains("CONFIRMED-MARK-4R"))
        assertFalse("confirmed array must NOT contain unanswered direction's fallback", afterConfirmed.substringBefore("\"constraints\"").contains("UNANSWERED-MARK-8T"))
    }

    @Test
    fun `用户刚确认的回答在兜底结果中出现`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val f = ClarificationFlow(false, "G", runAgent = { prompt, _ ->
            prompts += prompt
            when (calls.incrementAndGet()) {
                1 -> firstTwoDirections
                2 -> { started.countDown(); release.await(5, TimeUnit.SECONDS); askD2() }
                else -> error("unexpected call")
            }
        })
        val r1 = f.begin("REQ", null)
        f.advance(r1)
        val r2 = f.answer(Answer.Picked("a"), null)!!
        val worker = thread { f.advance(r2) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        // beginResult invalidates the in-flight round, then fallback
        f.beginResult()
        release.countDown()
        worker.join(5_000)
        f.fallbackResult()
        val result = (f.state as ClarificationState.Result).requirement
        assertTrue("fallback must contain the user's confirmed answer", result.contains("CONFIRMED-MARK-4R"))
        assertFalse("fallback must NOT contain unanswered direction's fallback", result.contains("UNANSWERED-MARK-8T"))
    }

    @Test
    fun `OPEN 方向的模型部分结论不出现在 confirmed 数组和兜底结果中`() {
        // Build a ledger where d1 is answered (OPEN with open points) and d2 has a model conclusion but no user answer
        val firstWithPartial =
            """{"understanding":"u","directions":{"add":[
               {"tempId":"new-1","title":"T1","why":"w","source":"s","fallback":"F1"},
               {"tempId":"new-2","title":"T2","why":"w","source":"s","fallback":"F2"}
               ]},"notes":"n",
               "next":{"type":"question","directionId":"new-1","question":"Q1","reason":"r","basis":"b",
                 "options":[{"id":"a","label":"A","recommended":true,"conclusion":"CONFIRMED-MARK-4R"},
                            {"id":"b","label":"B","recommended":false,"conclusion":"CB"}]}}"""
        // Round 2: previous reports d1 as OPEN (still has open points), asks d2 but we'll intercept
        val round2WithPartialD1 =
            """{"previous":{"id":"d1","status":"OPEN","conclusion":"PARTIAL-MARK-6W","openPoints":["open"]},"notes":"n",
               "next":{"type":"question","directionId":"d2","question":"Q2","reason":"r","basis":"b",
                 "options":[{"id":"a","label":"A","recommended":true,"conclusion":"C2A"},{"id":"b","label":"B","recommended":false,"conclusion":"C2B"}]}}"""
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val resultPromptRef = AtomicReference<String>()
        val f = ClarificationFlow(false, "G", runAgent = { prompt, _ ->
            prompts += prompt
            when (calls.incrementAndGet()) {
                1 -> firstWithPartial
                2 -> round2WithPartialD1
                3 -> { started.countDown(); release.await(5, TimeUnit.SECONDS); """{"requirement":"FINAL"}""" }
                4 -> { resultPromptRef.set(prompt); """{"requirement":"FINAL2"}""" }
                else -> error("unexpected call")
            }
        })
        val r1 = f.begin("REQ", null)
        f.advance(r1)
        // Answer d1 => CONFIRMED-MARK-4R
        val r2 = f.answer(Answer.Picked("a"), null)!!
        f.advance(r2)
        // Now d1 is OPEN (previous reported OPEN), d2 is ACTIVE (being asked)
        // d1 has conclusion PARTIAL-MARK-6W from the model — but the user's answer CONFIRMED-MARK-4R is in history
        assertEquals(DirectionStatus.OPEN, f.ledger.direction("d1")!!.status)
        assertEquals("PARTIAL-MARK-6W", f.ledger.direction("d1")!!.conclusion)
        // Don't answer d2. Begin result while round 3 would be blocked.
        val r3 = f.beginResult()!!
        // The RESULT prompt should have confirmed containing CONFIRMED-MARK-4R but NOT PARTIAL-MARK-6W (model partial)
        // and NOT unanswered d2's model conclusion
        // First let's check fallback
        f.fallbackResult()
        val fallbackText = (f.state as ClarificationState.Result).requirement
        assertTrue("fallback must contain user-answered OPEN direction", fallbackText.contains("CONFIRMED-MARK-4R"))
        assertFalse("fallback must NOT contain model's partial conclusion on unanswered d2", fallbackText.contains("C2A"))
        assertFalse("fallback must NOT contain model's partial conclusion on unanswered d2 option b", fallbackText.contains("C2B"))
    }
}
