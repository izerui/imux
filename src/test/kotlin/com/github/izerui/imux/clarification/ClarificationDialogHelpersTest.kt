package com.github.izerui.imux.clarification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClarificationDialogHelpersTest {
    private val direction = Direction("d1", "A", "", "", "")
    private val laterLedger = Ledger("r", null, directions = listOf(direction))

    private fun q(id: String, multi: Boolean = false) =
        Question(id, "H$id", "Q$id", multi, listOf(ChoiceOption("a", "A", "DA", true), ChoiceOption("b", "B", "DB", false)))

    // ---- requirementTooltip ----

    @Test
    fun `空白需求没有 tooltip`() {
        assertNull(requirementTooltip(""))
        assertNull(requirementTooltip("   "))
    }

    @Test
    fun `tooltip 用 HTML 包裹、保留换行且限宽`() {
        val tip = requirementTooltip("hello\nworld")
        assertNotNull(tip)
        assertTrue("以 <html> 开头", tip!!.startsWith("<html>"))
        assertTrue("换行变 <br>", tip.contains("<br>"))
        assertTrue("包含 width 限宽", tip.contains("width:"))
    }

    @Test
    fun `tooltip 截断超长文本`() {
        val tip = requirementTooltip("x".repeat(3000))!!
        assertFalse("不含第 2001 个字符之后的内容", tip.contains("x".repeat(2001)))
    }

    // ---- needsCenterRebuild ----

    @Test
    fun `同一次调查只是进度变化不重建，新一次调查重建`() {
        val old = ClarificationState.Investigating(null, 100, run = 1)
        assertFalse("进度变了 → 不重建", needsCenterRebuild(old, ClarificationState.Investigating("读取文件", 100, run = 1)))
        assertTrue("runId 变了（同一毫秒开始的新一次调查）→ 重建", needsCenterRebuild(old, ClarificationState.Investigating(null, 100, run = 2)))
        assertTrue("startedAt 变了 → 重建", needsCenterRebuild(old, ClarificationState.Investigating(null, 200, run = 1)))
    }

    @Test
    fun `同一次发送只是等待原因变化不重建`() {
        val old = ClarificationState.Sending("需求", waitingForTurn = true)
        assertFalse("等待原因变了 → 不重建", needsCenterRebuild(old, ClarificationState.Sending("需求", waitingForTurn = false)))
        assertTrue("需求文字变了 → 重建", needsCenterRebuild(old, ClarificationState.Sending("另一段", waitingForTurn = true)))
    }

    @Test
    fun `同一批问题不重建，换了一批重建`() {
        val batch = ClarificationState.Asking(listOf(q("d1"), q("d2")))
        assertFalse(needsCenterRebuild(batch, ClarificationState.Asking(listOf(q("d1"), q("d2")))))
        assertTrue(needsCenterRebuild(batch, ClarificationState.Asking(listOf(q("d3")))))
    }

    @Test
    fun `状态类型不同时必须重建`() {
        assertTrue(needsCenterRebuild(null, ClarificationState.Input))
        assertTrue(needsCenterRebuild(ClarificationState.Input, ClarificationState.Investigating(null, 1)))
    }

    // ---- investigationKind ----

    @Test
    fun `调查种类按能否生成结果与台账区分三种`() {
        assertEquals("不能生成结果 → 正在生成结果", InvestigationKind.RESULT, investigationKind(false, laterLedger))
        assertEquals("没有方向也没有问答 → 第一轮", InvestigationKind.FIRST_ROUND, investigationKind(true, Ledger("r", null)))
        assertEquals("已有方向 → 后续一轮", InvestigationKind.ROUND, investigationKind(true, laterLedger))
    }

    // ---- buttonBar ----

    @Test
    fun `输入页只有关闭与开始澄清，开始澄清是默认按钮`() {
        val bar = buttonBar(ClarificationState.Input, false, Ledger("", null), null)
        assertEquals(emptyList<BarButton>(), bar.left)
        assertEquals(listOf(BarButton.CLOSE, BarButton.START), bar.right)
        assertEquals(BarButton.START, bar.default)
    }

    @Test
    fun `调查中第一轮不出现够了生成结果，后续一轮出现在左侧`() {
        val first = buttonBar(ClarificationState.Investigating(null, 0), true, Ledger("r", null), null)
        assertEquals(emptyList<BarButton>(), first.left)
        val later = buttonBar(ClarificationState.Investigating(null, 0), true, laterLedger, null)
        assertEquals(listOf(BarButton.FINISH), later.left)
        assertEquals(listOf(BarButton.CLOSE, BarButton.CANCEL_INVESTIGATION), later.right)
        val result = buttonBar(ClarificationState.Investigating(null, 0), false, laterLedger, null)
        assertEquals("正在生成结果时没有够了", emptyList<BarButton>(), result.left)
    }

    @Test
    fun `提问页的主按钮按所在页分四种`() {
        val asking = ClarificationState.Asking(listOf(q("d1")))
        fun bar(page: AskingPage) = buttonBar(asking, true, laterLedger, page)
        assertEquals("只有一题：提交", BarButton.SUBMIT, bar(AskingPage.SINGLE).default)
        assertEquals("中间的题：下一题", BarButton.NEXT, bar(AskingPage.QUESTION).default)
        assertEquals("最后一题：查看并提交", BarButton.REVIEW, bar(AskingPage.LAST_QUESTION).default)
        assertEquals("提交页：提交", BarButton.SUBMIT, bar(AskingPage.REVIEW).default)
        for (page in AskingPage.entries) {
            assertEquals("$page：左侧是够了", listOf(BarButton.FINISH), bar(page).left)
            assertEquals("$page：右侧是关闭与主按钮", listOf(BarButton.CLOSE, bar(page).default), bar(page).right)
        }
    }

    @Test
    fun `结果页、发送中、失败页各自的按钮栏`() {
        val result = buttonBar(ClarificationState.Result("x"), false, laterLedger, null)
        assertEquals(listOf(BarButton.CONTINUE, BarButton.COPY), result.left)
        assertEquals(listOf(BarButton.CLOSE, BarButton.SEND), result.right)
        assertEquals(BarButton.SEND, result.default)

        val sending = buttonBar(ClarificationState.Sending("x", true), false, laterLedger, null)
        assertEquals(emptyList<BarButton>(), sending.left)
        assertEquals(listOf(BarButton.CANCEL_SEND), sending.right)

        val failed = buttonBar(ClarificationState.Failed(FailureReason.TIMEOUT, "600", null), false, laterLedger, null)
        assertEquals(listOf(BarButton.FALLBACK), failed.left)
        assertEquals(listOf(BarButton.CLOSE, BarButton.RETRY), failed.right)
        assertEquals(BarButton.RETRY, failed.default)
    }

    @Test
    fun `每个状态的默认按钮都在右侧最后`() {
        val states =
            listOf(
                ClarificationState.Input,
                ClarificationState.Investigating(null, 0),
                ClarificationState.Asking(listOf(q("d1"))),
                ClarificationState.Result("x"),
                ClarificationState.Sending("x", false),
                ClarificationState.Failed(FailureReason.CANCELLED, "", null),
            )
        for (state in states) {
            val bar = buttonBar(state, true, laterLedger, AskingPage.QUESTION)
            assertEquals("$state", bar.default, bar.right.last())
        }
    }

    // ---- RenderCoalescer ----

    @Test
    fun `一百次通知只排一次渲染，渲染后再来的通知再排一次`() {
        val scheduled = ArrayDeque<Runnable>()
        var renders = 0
        val coalescer = RenderCoalescer({ scheduled.addLast(it) }) { renders++ }
        repeat(100) { coalescer.request() }
        assertEquals("只排了一次", 1, scheduled.size)
        scheduled.removeFirst().run()
        assertEquals("渲染一次", 1, renders)
        coalescer.request()
        assertEquals("渲染之后的通知再排一次", 1, scheduled.size)
    }

    @Test
    fun `渲染期间到达的通知不丢`() {
        val scheduled = ArrayDeque<Runnable>()
        lateinit var coalescer: RenderCoalescer
        var renders = 0
        coalescer = RenderCoalescer({ scheduled.addLast(it) }) {
            renders++
            if (renders == 1) coalescer.request()
        }
        coalescer.request()
        scheduled.removeFirst().run()
        assertEquals("渲染中的通知又排了一次", 1, scheduled.size)
    }

    // ---- 草稿 ----

    @Test
    fun `单选草稿：选了选项、写了其他、什么都没选三种情况`() {
        val question = q("d1")
        val picked = DraftAnswer().apply { picked += "b" }
        assertEquals(Answer.Picked(listOf("b")), picked.toAnswer(question))
        val own = DraftAnswer().apply { other = true; otherText = " 我的想法 " }
        assertEquals(Answer.OwnWords("我的想法"), own.toAnswer(question))
        assertNull("其他没写字", DraftAnswer().apply { other = true }.toAnswer(question))
        assertNull("什么都没选", DraftAnswer().toAnswer(question))
    }

    @Test
    fun `多选草稿：按选项顺序给出所选各项并带上其他原话`() {
        val question = q("d1", multi = true)
        val draft = DraftAnswer().apply { picked += listOf("b", "a"); other = true; otherText = "还有" }
        assertEquals(Answer.Picked(listOf("a", "b"), "还有"), draft.toAnswer(question))
        assertEquals("只写了其他", Answer.OwnWords("还有"), DraftAnswer().apply { other = true; otherText = "还有" }.toAnswer(question))
    }

    @Test
    fun `一批草稿只给出填写了的回答，所在页按标签判断`() {
        val drafts = BatchDrafts(listOf(q("d1"), q("d2"), q("d3")))
        drafts.draftOf(drafts.batch[1]).picked += "a"
        assertEquals(mapOf("d2" to Answer.Picked(listOf("a"))), drafts.filled())
        assertEquals(AskingPage.QUESTION, drafts.page)
        drafts.tab = 2
        assertEquals(AskingPage.LAST_QUESTION, drafts.page)
        drafts.tab = 3
        assertEquals(AskingPage.REVIEW, drafts.page)
        assertEquals(AskingPage.SINGLE, BatchDrafts(listOf(q("d1"))).page)
    }

    @Test
    fun `进度里的长路径从中间省略`() {
        val path = "src/main/kotlin/com/github/izerui/imux/terminal/TerminalSessionReconnector.kt"
        val shortened = shortenPaths("读取 $path")
        assertTrue(shortened.startsWith("读取 src"))
        assertTrue("保留文件名", shortened.endsWith("TerminalSessionReconnector.kt"))
        assertTrue("变短了", shortened.length < "读取 $path".length)
        assertEquals("短文字不动", "读取 a/b.kt", shortenPaths("读取 a/b.kt"))
    }
}
