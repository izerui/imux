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
           "next":{"type":"questions","questions":[{"directionId":"new-1","question":"Q1","reason":"r","basis":"b",
             "options":[{"id":"a","label":"A","recommended":true,"conclusion":"CA"},{"id":"b","label":"B","recommended":false,"conclusion":"PICKED-MARK-2B"}]}]}}"""

    private fun askD2(previousStatus: String = "DECIDED") =
        """{"answered":[{"id":"d1","status":"$previousStatus","conclusion":"","openPoints":[]}],"notes":"n",
           "next":{"type":"questions","questions":[{"directionId":"d2","question":"Q2","reason":"r","basis":"b",
             "options":[{"id":"a","label":"A","recommended":true,"conclusion":"C2A"},{"id":"b","label":"B","recommended":false,"conclusion":"C2B"}]}]}}"""

    private fun done(previous: String = "d2") =
        """{"answered":[{"id":"$previous","status":"DECIDED","conclusion":"","openPoints":[]}],"notes":"n","next":{"type":"done","requirement":"ADJUSTED"}}"""

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
        val outputs = ArrayDeque(listOf(askD2(), """{"answered":[{"id":"d1","status":"DECIDED","conclusion":"","openPoints":[]}],"notes":"n","next":{"type":"done","requirement":"ADJUSTED"}}"""))
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
        """{"notes":"n","next":{"type":"questions","questions":[{"directionId":"d1","question":"Q1 again","reason":"r","basis":"b",
             "options":[{"id":"a","label":"A","recommended":true,"conclusion":"CA"},{"id":"b","label":"B","recommended":false,"conclusion":"CB"}]}]}}"""

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
            """{"notes":"n","next":{"type":"questions","questions":[{"directionId":"d2","question":"Q2","reason":"r","basis":"b",
                 "options":[{"id":"a","label":"A","recommended":true,"conclusion":"C2A"},{"id":"b","label":"B","recommended":false,"conclusion":"C2B"}]}]}}"""
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
           "next":{"type":"questions","questions":[{"directionId":"new-1","question":"Q1","reason":"r","basis":"b",
             "options":[{"id":"a","label":"A","recommended":true,"conclusion":"CONFIRMED-MARK-4R"},
                        {"id":"b","label":"B","recommended":false,"conclusion":"CB"}]}]}}"""

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
        // Extract the confirmed slice: from "confirmed" up to "constraints"
        val confirmedIdx = resultPrompt.indexOf("\"confirmed\"")
        assertTrue("RESULT prompt must contain confirmed array", confirmedIdx >= 0)
        val confirmedSlice = resultPrompt.substring(confirmedIdx).substringBefore("\"constraints\"")
        assertTrue("confirmed slice must contain the user's just-confirmed answer", confirmedSlice.contains("CONFIRMED-MARK-4R"))
        assertFalse("confirmed slice must NOT contain unanswered direction's fallback", confirmedSlice.contains("UNANSWERED-MARK-8T"))
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
        // Setup: d1 user-answered then reported OPEN (partial conclusion PARTIAL-MARK-6W);
        //        d2 unanswered, being asked
        val firstWithPartial =
            """{"understanding":"u","directions":{"add":[
               {"tempId":"new-1","title":"T1","why":"w","source":"s","fallback":"F1"},
               {"tempId":"new-2","title":"T2","why":"w","source":"s","fallback":"F2"}
               ]},"notes":"n",
               "next":{"type":"questions","questions":[{"directionId":"new-1","question":"Q1","reason":"r","basis":"b",
                 "options":[{"id":"a","label":"A","recommended":true,"conclusion":"CONFIRMED-MARK-4R"},
                            {"id":"b","label":"B","recommended":false,"conclusion":"CB"}]}]}}"""
        val round2WithPartialD1 =
            """{"answered":[{"id":"d1","status":"OPEN","conclusion":"PARTIAL-MARK-6W","openPoints":["open"]}],"notes":"n",
               "next":{"type":"questions","questions":[{"directionId":"d2","question":"Q2","reason":"r","basis":"b",
                 "options":[{"id":"a","label":"A","recommended":true,"conclusion":"C2A"},{"id":"b","label":"B","recommended":false,"conclusion":"C2B"}]}]}}"""
        val resultPromptRef = AtomicReference<String>()
        val f = ClarificationFlow(false, "G", runAgent = { prompt, _ ->
            prompts += prompt
            when (prompts.size) {
                1 -> firstWithPartial
                2 -> round2WithPartialD1
                3 -> { resultPromptRef.set(prompt); """{"requirement":"FINAL"}""" }
                else -> error("unexpected call")
            }
        })
        f.advance(f.begin("REQ", null))
        val r2 = f.answer(Answer.Picked("a"), null)!!
        f.advance(r2)
        // d1 is OPEN with model conclusion PARTIAL-MARK-6W, user answer CONFIRMED-MARK-4R in history
        assertEquals(DirectionStatus.OPEN, f.ledger.direction("d1")!!.status)
        assertEquals("PARTIAL-MARK-6W", f.ledger.direction("d1")!!.conclusion)

        // Generate result via agent
        val r3 = f.beginResult()!!
        f.generateResult(r3)
        assertEquals(ClarificationState.Result("FINAL"), f.state)
        val resultPrompt = resultPromptRef.get()!!
        val confirmedSlice = resultPrompt.substring(resultPrompt.indexOf("\"confirmed\"")).substringBefore("\"constraints\"")
        assertFalse("confirmed slice must NOT contain model's partial conclusion", confirmedSlice.contains("PARTIAL-MARK-6W"))
        assertTrue("confirmed slice must contain user's confirmed answer", confirmedSlice.contains("CONFIRMED-MARK-4R"))

        // Fallback path (fresh flow, same state)
        val f2 = ClarificationFlow(false, "G", runAgent = { prompt, _ ->
            when (prompts.size) {
                // reuse prompts list size as counter — f2 never calls runAgent for fallback
                else -> error("fallbackResult must not call agent")
            }
        })
        // Rebuild same state: begin, advance round 1, answer, advance round 2, beginResult, then fallback
        val ff = ClarificationFlow(false, "G", runAgent = { prompt, _ ->
            prompts += prompt
            when (prompts.size) {
                4 -> firstWithPartial
                5 -> round2WithPartialD1
                else -> error("unexpected call in f2")
            }
        })
        ff.advance(ff.begin("REQ", null))
        ff.advance(ff.answer(Answer.Picked("a"), null)!!)
        ff.beginResult()
        ff.fallbackResult()
        val fallbackText = (ff.state as ClarificationState.Result).requirement
        assertFalse("fallback must NOT contain model's partial conclusion", fallbackText.contains("PARTIAL-MARK-6W"))
        assertTrue("fallback must contain user's confirmed answer", fallbackText.contains("CONFIRMED-MARK-4R"))
    }

    // ---- reopen voids earlier answers in prompt and fallback ----

    @Test
    fun `重新打开方向后 RESULT 提示词的 confirmed 不含旧回答且 ledgerJson 中 voided 为 true`() {
        // Round 1: ask d1 with OLD-MARK-3P option;
        // Round 2: model reports d1 DECIDED, asks d2;
        // User reopens d1 while at d2;
        // User skips d2; Round 3: model reports d2 DEFAULTED, asks d1 again with NEW-MARK-7H option;
        // User answers d1 again; Round 4: model reports d1 DECIDED, done;
        // Then generate result.
        val firstWithOld =
            """{"understanding":"u","directions":{"add":[
               {"tempId":"new-1","title":"T1","why":"w","source":"s","fallback":"F1"},
               {"tempId":"new-2","title":"T2","why":"w","source":"s","fallback":"F2"}
               ]},"notes":"n",
               "next":{"type":"questions","questions":[{"directionId":"new-1","question":"Q1","reason":"r","basis":"b",
                 "options":[{"id":"a","label":"A","recommended":true,"conclusion":"OLD-MARK-3P"},
                            {"id":"b","label":"B","recommended":false,"conclusion":"CB"}]}]}}"""
        val round2AskD2 =
            """{"answered":[{"id":"d1","status":"DECIDED","conclusion":"","openPoints":[]}],"notes":"n",
               "next":{"type":"questions","questions":[{"directionId":"d2","question":"Q2","reason":"r","basis":"b",
                 "options":[{"id":"a","label":"A","recommended":true,"conclusion":"C2A"},{"id":"b","label":"B","recommended":false,"conclusion":"C2B"}]}]}}"""
        // After skip d2 + reopen d1, model asks d1 again
        // d2 was skipped, so nothing is awaiting a report: answered stays empty
        val round3AskD1Again =
            """{"answered":[],"notes":"n",
               "next":{"type":"questions","questions":[{"directionId":"d1","question":"Q1 again","reason":"r","basis":"b",
                 "options":[{"id":"c","label":"C","recommended":true,"conclusion":"NEW-MARK-7H"},
                            {"id":"d","label":"D","recommended":false,"conclusion":"CD"}]}]}}"""
        val round4Done =
            """{"answered":[{"id":"d1","status":"DECIDED","conclusion":"","openPoints":[]}],"notes":"n",
               "next":{"type":"done","requirement":"ADJUSTED"}}"""
        val resultPromptRef = java.util.concurrent.atomic.AtomicReference<String>()
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val f = ClarificationFlow(false, "G", runAgent = { prompt, _ ->
            prompts += prompt
            when (calls.incrementAndGet()) {
                1 -> firstWithOld
                2 -> round2AskD2
                3 -> round3AskD1Again
                4 -> round4Done
                5 -> { resultPromptRef.set(prompt); """{"requirement":"FINAL"}""" }
                else -> error("unexpected call")
            }
        })
        // Round 1
        f.advance(f.begin("REQ", null))
        // Answer d1 => OLD-MARK-3P; Round 2 reports DECIDED, asks d2
        f.advance(f.answer(Answer.Picked("a"), null)!!)
        assertEquals(DirectionStatus.DECIDED, f.ledger.direction("d1")!!.status)
        // Reopen d1 while looking at d2 question
        f.reopen("d1")
        assertEquals(DirectionStatus.OPEN, f.ledger.direction("d1")!!.status)
        // Skip d2; Round 3 asks d1 again
        f.advance(f.skip("d2")!!)
        assertEquals("d1", (f.state as ClarificationState.Asking).question.directionId)
        // Answer d1 again => NEW-MARK-7H; Round 4 reports DECIDED, done
        f.advance(f.answer(Answer.Picked("c"), null)!!)
        assertEquals(ClarificationState.Result("ADJUSTED"), f.state)

        // Round 4 prompt (prompts[3]) carries the ledger with voided records and updated confirmed
        val round4Prompt = prompts[3]
        val confirmedIdx = round4Prompt.indexOf("\"confirmed\"")
        assertTrue("round 4 prompt must contain confirmed array", confirmedIdx >= 0)
        val confirmedSlice = round4Prompt.substring(confirmedIdx).substringBefore("\"constraints\"")
        assertFalse("confirmed slice must NOT contain OLD-MARK-3P", confirmedSlice.contains("OLD-MARK-3P"))

        // composeFallback must also lack OLD-MARK-3P
        val fallback = composeFallback(f.ledger)
        assertFalse("composeFallback must NOT contain OLD-MARK-3P", fallback.contains("OLD-MARK-3P"))

        // ledger JSON history entry for the old answer has voided: true
        val historySlice = round4Prompt.substring(round4Prompt.indexOf("\"history\"")).substringBefore("\"notes\"")
        assertTrue("history must contain voided: true", historySlice.contains("\"voided\": true"))
    }

    // ---- 每轮一批问题 ----

    private fun batchQuestion(dir: String, n: Int) =
        """{"directionId":"$dir","question":"BQ$n","reason":"r","basis":"b",
             "options":[{"id":"a","label":"A","recommended":true,"conclusion":"BATCH-PICK-$n"},{"id":"b","label":"B","recommended":false,"conclusion":"BATCH-OTHER-$n"}]}"""

    /** 首轮三个方向、一批三题，每题针对一个方向。 */
    private val firstBatchOfThree =
        """{"understanding":"u","directions":{"add":[
           {"tempId":"new-1","title":"T1","why":"w","source":"s","fallback":"BATCH-FB-1"},
           {"tempId":"new-2","title":"T2","why":"w","source":"s","fallback":"BATCH-FB-2"},
           {"tempId":"new-3","title":"T3","why":"w","source":"s","fallback":"BATCH-FB-3"}]},"notes":"n",
           "next":{"type":"questions","questions":[${batchQuestion("new-1", 1)},${batchQuestion("new-2", 2)},${batchQuestion("new-3", 3)}]}}"""

    private fun answeredDone(vararg ids: String) =
        """{"answered":[${ids.joinToString(",") { """{"id":"$it","status":"DECIDED","conclusion":"","openPoints":[]}""" }}],"notes":"n",
           "next":{"type":"done","requirement":"ADJUSTED"}}"""

    @Test
    fun `一批三题时前两题的回答不调用 Agent 答完第三题只调用一次且提示词带上三题的结论`() {
        val f = flow(firstBatchOfThree, answeredDone("d1", "d2", "d3"))
        f.advance(f.begin("REQ", null))
        assertEquals(3, (f.state as ClarificationState.Asking).batch.size)

        assertNull("第一题答完不发起调查", f.answer(Answer.Picked("a"), null))
        assertEquals(1, prompts.size)
        assertEquals("d2", (f.state as ClarificationState.Asking).question.directionId)
        assertNull("第二题答完不发起调查", f.answer(Answer.Picked("a"), null))
        assertEquals(1, prompts.size)
        assertEquals("d3", (f.state as ClarificationState.Asking).question.directionId)

        val runId = f.answer(Answer.Picked("a"), null)
        assertTrue("最后一题答完发起调查", runId != null)
        f.advance(runId!!)
        assertEquals("整批只调用一次", 2, prompts.size)
        val round2 = prompts[1]
        assertTrue("带上第一题的结论", round2.contains("BATCH-PICK-1"))
        assertTrue("带上第二题的结论", round2.contains("BATCH-PICK-2"))
        assertTrue("带上第三题的结论", round2.contains("BATCH-PICK-3"))
        assertEquals(ClarificationState.Result("ADJUSTED"), f.state)
    }

    @Test
    fun `第一题写其他想法时立即调查且没问到的两个方向回到待确认`() {
        val f = flow(firstBatchOfThree)
        f.advance(f.begin("REQ", null))
        val runId = f.answer(Answer.OwnWords("OWN"), null)
        assertTrue("其他想法提前结束这一批", runId != null)
        assertTrue(f.state is ClarificationState.Investigating)
        assertEquals("答过的方向等模型交代", DirectionStatus.ACTIVE, f.ledger.direction("d1")!!.status)
        assertEquals("第二题的方向回到待确认", DirectionStatus.OPEN, f.ledger.direction("d2")!!.status)
        assertEquals("第三题的方向回到待确认", DirectionStatus.OPEN, f.ledger.direction("d3")!!.status)
        assertEquals(listOf("d1"), f.ledger.awaitingReport)
    }

    @Test
    fun `第一题随回答纠正理解时立即调查`() {
        val f = flow(firstBatchOfThree, """{"understanding":"u2","answered":[{"id":"d1","status":"DECIDED","conclusion":"","openPoints":[]}],"notes":"n","next":{"type":"done","requirement":"ADJUSTED"}}""")
        f.advance(f.begin("REQ", null))
        val runId = f.answer(Answer.Picked("a"), correction = "FIX")
        assertTrue("纠正理解提前结束这一批", runId != null)
        assertEquals("第二题的方向回到待确认", DirectionStatus.OPEN, f.ledger.direction("d2")!!.status)
        assertEquals("第三题的方向回到待确认", DirectionStatus.OPEN, f.ledger.direction("d3")!!.status)
        f.advance(runId!!)
        assertTrue("下一轮按纠正后的阶段说明调用", prompts[1].contains("corrected your understanding"))
    }

    @Test
    fun `批中跳过当前一题时继续下一题而不调用 Agent`() {
        val f = flow(firstBatchOfThree)
        f.advance(f.begin("REQ", null))
        assertNull(f.skip("d1"))
        assertEquals(1, prompts.size)
        val asking = f.state as ClarificationState.Asking
        assertEquals("d2", asking.question.directionId)
        assertEquals(DirectionStatus.DEFAULTED, f.ledger.direction("d1")!!.status)
        assertTrue("跳过的不等模型交代", f.ledger.awaitingReport.isEmpty())
    }

    @Test
    fun `在清单里跳过这一批里还没问到的题时该题从这一批移除且按默认处理`() {
        val f = flow(firstBatchOfThree, answeredDone("d1", "d2"))
        f.advance(f.begin("REQ", null))
        assertNull(f.skip("d3"))
        val asking = f.state as ClarificationState.Asking
        assertEquals(listOf("d1", "d2"), asking.batch.map { it.directionId })
        assertEquals("仍停在当前一题", "d1", asking.question.directionId)
        assertEquals(DirectionStatus.DEFAULTED, f.ledger.direction("d3")!!.status)
        assertNull(f.answer(Answer.Picked("a"), null))
        val runId = f.answer(Answer.Picked("a"), null)
        assertTrue("剩下的最后一题答完即调查", runId != null)
        f.advance(runId!!)
        assertEquals(2, prompts.size)
        assertEquals(ClarificationState.Result("ADJUSTED"), f.state)
    }

    @Test
    fun `批中生成结果时已回答的纳入 confirmed 与兜底结果而没问到的不纳入且继续澄清后回到待确认`() {
        val resultPrompt = AtomicReference<String>()
        val calls = AtomicInteger(0)
        val f = ClarificationFlow(false, "G", runAgent = { prompt, _ ->
            prompts += prompt
            when (calls.incrementAndGet()) {
                1 -> firstBatchOfThree
                2 -> { resultPrompt.set(prompt); """{"requirement":"FINAL"}""" }
                else -> """{"answered":[{"id":"d1","status":"DECIDED","conclusion":"","openPoints":[]}],"notes":"n","next":{"type":"done","requirement":"ADJUSTED"}}"""
            }
        })
        f.advance(f.begin("REQ", null))
        assertNull(f.answer(Answer.Picked("a"), null))
        assertTrue("批中任何一题都能生成结果", f.canFinish)
        f.generateResult(f.beginResult()!!)

        val confirmed = resultPrompt.get().substring(resultPrompt.get().indexOf("\"confirmed\"")).substringBefore("\"constraints\"")
        assertTrue("已回答的第一题算已确认", confirmed.contains("BATCH-PICK-1"))
        assertFalse("当前没答的第二题不算", confirmed.contains("BATCH-PICK-2"))
        assertFalse("没问到的第三题不算", confirmed.contains("BATCH-PICK-3"))
        assertFalse("没问到的第三题的默认处理不算", confirmed.contains("BATCH-FB-3"))

        val fallback = composeFallback(f.ledger)
        assertTrue("兜底结果含已回答的第一题", fallback.contains("BATCH-PICK-1"))
        assertFalse("兜底结果不含第二题", fallback.contains("BATCH-PICK-2") || fallback.contains("BATCH-FB-2"))
        assertFalse("兜底结果不含第三题", fallback.contains("BATCH-PICK-3") || fallback.contains("BATCH-FB-3"))

        val runId = f.continueClarifying()!!
        assertEquals("第二题的方向回到待确认", DirectionStatus.OPEN, f.ledger.direction("d2")!!.status)
        assertEquals("第三题的方向回到待确认", DirectionStatus.OPEN, f.ledger.direction("d3")!!.status)
        assertEquals("已回答的方向仍等模型交代", listOf("d1"), f.ledger.awaitingReport)
        f.advance(runId)
        assertEquals(ClarificationState.Result("ADJUSTED"), f.state)
    }

    @Test
    fun `批中兜底结果含已回答的而不含没问到的`() {
        val f = flow(firstBatchOfThree)
        f.advance(f.begin("REQ", null))
        assertNull(f.answer(Answer.Picked("a"), null))
        f.fallbackResult()
        val result = (f.state as ClarificationState.Result).requirement
        assertTrue(result.contains("BATCH-PICK-1"))
        assertFalse(result.contains("BATCH-PICK-2"))
        assertFalse(result.contains("BATCH-PICK-3"))
        assertEquals("第二题的方向回到待确认", DirectionStatus.OPEN, f.ledger.direction("d2")!!.status)
        assertEquals("第三题的方向回到待确认", DirectionStatus.OPEN, f.ledger.direction("d3")!!.status)
    }

    @Test
    fun `先问这个在下一次调查生效且下一批第一题针对它`() {
        val pinnedSecond =
            """{"answered":[{"id":"d1","status":"DECIDED","conclusion":"","openPoints":[]}],"notes":"n",
               "next":{"type":"questions","questions":[${batchQuestion("d2", 4)},${batchQuestion("d3", 5)}]}}"""
        val pinnedFirst =
            """{"answered":[{"id":"d1","status":"DECIDED","conclusion":"","openPoints":[]}],"notes":"n",
               "next":{"type":"questions","questions":[${batchQuestion("d3", 5)},${batchQuestion("d2", 4)}]}}"""
        val f = flow(firstBatchOfThree, pinnedSecond, pinnedFirst)
        f.advance(f.begin("REQ", null))
        // OwnWords ends the batch: d2/d3 go back to OPEN, so d3 can be pinned before the next round runs
        val runId = f.answer(Answer.OwnWords("OWN"), null)!!
        f.pin("d3")
        assertEquals("d3", f.ledger.pinned)
        f.advance(runId)
        assertEquals("第一题没针对先问这个的方向时附错误重调", 3, prompts.size)
        assertTrue(prompts[2].contains("the first question must target it"))
        val asking = f.state as ClarificationState.Asking
        assertEquals("d3", asking.question.directionId)
        assertNull("出题后指定解除", f.ledger.pinned)
    }
}
