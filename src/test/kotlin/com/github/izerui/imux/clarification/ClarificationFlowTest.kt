package com.github.izerui.imux.clarification

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
           "next":{"type":"questions","questions":[{"directionId":"new-1","header":"H1","question":"Q1",
             "options":[{"id":"a","label":"A","recommended":true,"description":"CA"},{"id":"b","label":"B","recommended":false,"description":"PICKED-MARK-2B"}]}]}}"""

    private fun askD2(previousStatus: String = "DECIDED") =
        """{"answered":[{"id":"d1","status":"$previousStatus","conclusion":"","openPoints":[]}],"notes":"n",
           "next":{"type":"questions","questions":[{"directionId":"d2","header":"H2","question":"Q2",
             "options":[{"id":"a","label":"A","recommended":true,"description":"C2A"},{"id":"b","label":"B","recommended":false,"description":"C2B"}]}]}}"""

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
        val r2 = f.submitBatch(mapOf("d1" to Answer.Picked(listOf("b"))))!!
        f.advance(r2)
        // Round 2 prompt (prompts[1]) must carry requirement, background and the round-1 conclusion
        val round2 = prompts[1]
        assertTrue("round 2 should contain requirement", round2.contains("REQ-MARK-7Q"))
        assertTrue("round 2 should contain background", round2.contains("BG-MARK-3X"))
        assertTrue("round 2 should contain round-1 conclusion", round2.contains("PICKED-MARK-2B"))
        val r3 = f.submitBatch(mapOf("d2" to Answer.OwnWords("OWN-MARK-5K")))!!
        f.advance(r3)
        // Round 3 prompt (prompts[2]) must carry everything above plus the own-words marker
        val round3 = prompts[2]
        assertTrue("round 3 should contain requirement", round3.contains("REQ-MARK-7Q"))
        assertTrue("round 3 should contain background", round3.contains("BG-MARK-3X"))
        assertTrue("round 3 should contain round-1 conclusion", round3.contains("PICKED-MARK-2B"))
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
        """{"notes":"n","next":{"type":"questions","questions":[{"directionId":"d1","header":"H1","question":"Q1 again",
             "options":[{"id":"a","label":"A","recommended":true,"description":"CA"},{"id":"b","label":"B","recommended":false,"description":"CB"}]}]}}"""

    @Test
    fun `问题页直接生成结果后继续澄清仍能再问同一方向`() {
        val f = flow(first, """{"requirement":"FINAL"}""", askD1Again())
        f.advance(f.begin("REQ", null))
        assertEquals("d1", (f.state as ClarificationState.Asking).batch.single().directionId)
        f.generateResult(f.beginResult()!!)
        assertEquals(DirectionStatus.OPEN, f.ledger.direction("d1")!!.status)
        f.advance(f.continueClarifying()!!)
        val asking = f.state as ClarificationState.Asking
        assertEquals("d1", asking.batch.single().directionId)
        assertEquals("Q1 again", asking.batch.single().text)
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
           "next":{"type":"questions","questions":[{"directionId":"new-1","header":"H1","question":"Q1",
             "options":[{"id":"a","label":"A","recommended":true,"description":"CONFIRMED-MARK-4R"},
                        {"id":"b","label":"B","recommended":false,"description":"CB"}]}]}}"""

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
        val r2 = f.submitBatch(mapOf("d1" to Answer.Picked(listOf("a"))))!!
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
        val r2 = f.submitBatch(mapOf("d1" to Answer.Picked(listOf("a"))))!!
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
               "next":{"type":"questions","questions":[{"directionId":"new-1","header":"H1","question":"Q1",
                 "options":[{"id":"a","label":"A","recommended":true,"description":"CONFIRMED-MARK-4R"},
                            {"id":"b","label":"B","recommended":false,"description":"CB"}]}]}}"""
        val round2WithPartialD1 =
            """{"answered":[{"id":"d1","status":"OPEN","conclusion":"PARTIAL-MARK-6W","openPoints":["open"]}],"notes":"n",
               "next":{"type":"questions","questions":[{"directionId":"d2","header":"H2","question":"Q2",
                 "options":[{"id":"a","label":"A","recommended":true,"description":"C2A"},{"id":"b","label":"B","recommended":false,"description":"C2B"}]}]}}"""
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
        val r2 = f.submitBatch(mapOf("d1" to Answer.Picked(listOf("a"))))!!
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
        ff.advance(ff.submitBatch(mapOf("d1" to Answer.Picked(listOf("a"))))!!)
        ff.beginResult()
        ff.fallbackResult()
        val fallbackText = (ff.state as ClarificationState.Result).requirement
        assertFalse("fallback must NOT contain model's partial conclusion", fallbackText.contains("PARTIAL-MARK-6W"))
        assertTrue("fallback must contain user's confirmed answer", fallbackText.contains("CONFIRMED-MARK-4R"))
    }

    // ---- 每轮 1～4 题，整批提交 ----

    private fun batchQuestion(dir: String, n: Int, multi: Boolean = false) =
        """{"directionId":"$dir","header":"BH$n","question":"BQ$n","multiSelect":$multi,
             "options":[{"id":"a","label":"A","description":"BATCH-PICK-$n","recommended":true},{"id":"b","label":"B","description":"BATCH-OTHER-$n","recommended":false}]}"""

    /** 首轮三个方向、一批三题，每题针对一个方向；第三题是多选。 */
    private val firstBatchOfThree =
        """{"understanding":"u","directions":{"add":[
           {"tempId":"new-1","title":"T1","why":"w","source":"s","fallback":"BATCH-FB-1"},
           {"tempId":"new-2","title":"T2","why":"w","source":"s","fallback":"BATCH-FB-2"},
           {"tempId":"new-3","title":"T3","why":"w","source":"s","fallback":"BATCH-FB-3"}]},"notes":"n",
           "next":{"type":"questions","questions":[${batchQuestion("new-1", 1)},${batchQuestion("new-2", 2)},${batchQuestion("new-3", 3, multi = true)}]}}"""

    private fun answeredDone(vararg ids: String) =
        """{"answered":[${ids.joinToString(",") { """{"id":"$it","status":"DECIDED","conclusion":"","openPoints":[]}""" }}],"notes":"n",
           "next":{"type":"done","requirement":"ADJUSTED"}}"""

    @Test
    fun `提交前不调用 Agent，提交一批只发起一次调查且提示词带上各题的结论`() {
        val f = flow(firstBatchOfThree, answeredDone("d1", "d2", "d3"))
        f.advance(f.begin("REQ", null))
        assertEquals(3, (f.state as ClarificationState.Asking).batch.size)
        assertEquals("出题之后、提交之前没有再调用", 1, prompts.size)
        assertTrue("提交之前台账里没有任何回答", f.ledger.history.isEmpty())

        val runId = f.submitBatch(mapOf("d1" to Answer.Picked(listOf("a")), "d2" to Answer.OwnWords("OWN-2"), "d3" to Answer.Picked(listOf("b", "a"))))
        assertNotNull(runId)
        assertTrue(f.state is ClarificationState.Investigating)
        assertNull("同一批不能再提交一次", f.submitBatch(emptyMap()))
        f.advance(runId!!)
        assertEquals("整批只调用一次", 2, prompts.size)
        val round2 = prompts[1]
        assertTrue("带上第一题的结论", round2.contains("BATCH-PICK-1"))
        assertTrue("带上第二题的原话", round2.contains("OWN-2"))
        assertTrue("带上第三题（多选）的结论", round2.contains("BATCH-PICK-3；BATCH-OTHER-3"))
        assertEquals(ClarificationState.Result("ADJUSTED"), f.state)
    }

    @Test
    fun `提交时按这一批的顺序记下回答`() {
        val f = flow(firstBatchOfThree)
        f.advance(f.begin("REQ", null))
        f.submitBatch(linkedMapOf("d3" to Answer.Picked(listOf("a")), "d1" to Answer.Picked(listOf("a")), "d2" to Answer.Picked(listOf("a"))))
        assertEquals(listOf("d1", "d2", "d3"), f.ledger.history.map { it.question.directionId })
        assertEquals(listOf("d1", "d2", "d3"), f.ledger.awaitingReport)
    }

    @Test
    fun `多选题记下所选各项的说明，按选项顺序以分号连接`() {
        val f = flow(firstBatchOfThree)
        f.advance(f.begin("REQ", null))
        f.submitBatch(mapOf("d3" to Answer.Picked(listOf("b", "a"), other = "OWN-3")))
        assertEquals("BATCH-PICK-3；BATCH-OTHER-3；OWN-3", f.ledger.history.single { it.question.directionId == "d3" }.recorded)
    }

    @Test
    fun `提交时没回答的题按不回答处理，方向按默认且不等交代`() {
        val f = flow(firstBatchOfThree, answeredDone("d1"))
        f.advance(f.begin("REQ", null))
        f.advance(f.submitBatch(mapOf("d1" to Answer.Picked(listOf("a"))))!!)
        assertEquals(DirectionStatus.DEFAULTED, f.ledger.direction("d2")!!.status)
        assertEquals("BATCH-FB-2", f.ledger.direction("d2")!!.conclusion)
        assertEquals(DirectionStatus.DEFAULTED, f.ledger.direction("d3")!!.status)
        assertEquals("BATCH-FB-3", f.ledger.direction("d3")!!.conclusion)
        assertTrue("下一轮只需交代回答过的 d1", prompts[1].contains("\"reportAnswered\": [\n      \"d1\"\n    ]"))
        assertEquals(ClarificationState.Result("ADJUSTED"), f.state)
    }

    @Test
    fun `不在提问页时提交无效`() {
        val f = flow()
        assertNull(f.submitBatch(mapOf("d1" to Answer.Picked(listOf("a")))))
        assertEquals(ClarificationState.Input, f.state)
    }

    @Test
    fun `批中够了：已填写的回答纳入结果，没填写的不记录且回到待确认，继续澄清后等交代`() {
        val resultPrompt = AtomicReference<String>()
        val calls = AtomicInteger(0)
        val f = ClarificationFlow(false, "G", runAgent = { prompt, _ ->
            prompts += prompt
            when (calls.incrementAndGet()) {
                1 -> firstBatchOfThree
                2 -> { resultPrompt.set(prompt); """{"requirement":"FINAL"}""" }
                else -> answeredDone("d1")
            }
        })
        f.advance(f.begin("REQ", null))
        assertTrue("提问页可以生成结果", f.canFinish)
        f.generateResult(f.beginResult(mapOf("d1" to Answer.Picked(listOf("a"))))!!)

        val confirmed = resultPrompt.get().substring(resultPrompt.get().indexOf("\"confirmed\"")).substringBefore("\"constraints\"")
        assertTrue("已填写的第一题算已确认", confirmed.contains("BATCH-PICK-1"))
        assertFalse("没填写的第二题不算", confirmed.contains("BATCH-PICK-2") || confirmed.contains("BATCH-FB-2"))
        assertFalse("没填写的第三题不算", confirmed.contains("BATCH-PICK-3") || confirmed.contains("BATCH-FB-3"))
        assertEquals("只记下已填写的一题", listOf("d1"), f.ledger.history.map { it.question.directionId })
        assertEquals("第二题的方向回到待确认", DirectionStatus.OPEN, f.ledger.direction("d2")!!.status)
        assertEquals("第三题的方向回到待确认", DirectionStatus.OPEN, f.ledger.direction("d3")!!.status)

        val runId = f.continueClarifying()!!
        assertEquals("已填写的方向仍等模型交代", listOf("d1"), f.ledger.awaitingReport)
        f.advance(runId)
        assertEquals(ClarificationState.Result("ADJUSTED"), f.state)
    }

    @Test
    fun `调查中够了不记录任何回答`() {
        val f = flow(firstBatchOfThree)
        val r1 = f.begin("REQ", null)
        assertNotNull(f.beginResult(mapOf("d1" to Answer.Picked(listOf("a")))))
        f.advance(r1)
        assertTrue("在途一轮作废", f.ledger.history.isEmpty() && f.ledger.directions.isEmpty())
    }
}
