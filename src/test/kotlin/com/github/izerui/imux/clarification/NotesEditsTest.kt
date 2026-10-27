package com.github.izerui.imux.clarification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NotesEditsTest {
    private fun apply(original: String, edits: List<NotesEdit>): String =
        StringBuilder(original).also { text ->
            edits.asReversed().forEach { text.replace(it.startOffset, it.startOffset + it.deletedLength, it.insertedText) }
        }.toString()

    @Test
    fun `追加替换删除换行和中文字符的差异可以无损应用`() {
        val cases = listOf(
            "" to "新增",
            "原文" to "",
            "原文" to "原文\n",
            "原文\n" to "原文",
            "a\r\nb\r\n" to "a\r\n新行\r\nb\r\n",
            "通知管理员" to "通知用户",
            "before\nsame\nafter" to "BEFORE\nsame\nAFTER",
            "same\nsame\nlast" to "same\nlast",
            "甲😀乙" to "甲😁乙",
            "甲😀乙" to "甲😀新增乙",
            "不改变" to "不改变",
        )
        for ((before, after) in cases) {
            val edits = notesEdits(before, after)
            assertEquals("$before → $after", after, apply(before, edits))
            edits.forEach { edit ->
                for (text in listOf(edit.removedPreview, edit.insertedText, edit.beforeContext, edit.afterContext)) {
                    assertEquals("不产生孤立代理字符", text, String(text.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
                }
            }
        }
    }

    @Test
    fun `分散修改不保存中间未变的大段材料`() {
        val middle = (1..2000).joinToString("\n") { "UNCHANGED-$it" }
        val before = "旧通知方式\n$middle\n旧统计口径"
        val after = "新通知方式\n$middle\n新统计口径"
        val draft = ReviewedDraft(after, before, ContentOrigin.NOTES, 0)
        assertEquals("", draft.originalText)
        assertEquals("", draft.text)
        assertEquals(after, apply(before, draft.notesChanges))
        assertTrue("不把整个中段当成单个替换块保存", payloadSize(draft) < 1000)
    }

    @Test
    fun `整份材料替换为任务时不复制删除的整份历史`() {
        val before = "history\n".repeat(5000)
        val draft = ReviewedDraft("只增加查询功能", before, ContentOrigin.NOTES, 0)
        assertEquals("只增加查询功能", apply(before, draft.notesChanges))
        assertTrue("删除内容只留预览", draft.notesChanges.any { it.removalTruncated })
        assertTrue("旧历史没有再次存储", payloadSize(draft) < 1000)
        assertTrue(draft.notesChanges.all { it.removedPreview.codePointCount(0, it.removedPreview.length) <= NOTES_EDIT_PREVIEW_POINTS })
    }

    @Test
    fun `长行只改末尾一个字符不复制整行历史`() {
        val before = "很长的旧记录".repeat(5000)
        val draft = ReviewedDraft(before + "新", before, ContentOrigin.NOTES, 0)
        assertEquals(before + "新", apply(before, draft.notesChanges))
        assertEquals("新", draft.notesChanges.joinToString("") { it.insertedText })
        assertTrue(payloadSize(draft) < 300)
    }

    @Test
    fun `未编辑材料不保存副本而模型草稿仍保留前后原文`() {
        val notes = ReviewedDraft("notes", "notes", ContentOrigin.NOTES, 0)
        assertFalse(notes.edited)
        assertEquals(0, payloadSize(notes))
        val candidate = ReviewedDraft("用户修订", "模型原稿", ContentOrigin.AGENT, 0)
        assertTrue(candidate.edited)
        assertEquals("用户修订", candidate.text)
        assertEquals("模型原稿", candidate.originalText)
        assertTrue(candidate.notesChanges.isEmpty())
    }

    @Test
    fun `固定种子的多处增删改保留完整文字`() {
        val random = Random(24)
        repeat(120) { sample ->
            val before = List(40) { "行-${random.nextInt(12)}-中文😀\n" }.joinToString("")
            val lines = before.split('\n').toMutableList()
            repeat(8) {
                when (random.nextInt(3)) {
                    0 -> lines.add(random.nextInt(lines.size + 1), "新增-${random.nextInt(100)}")
                    1 -> if (lines.isNotEmpty()) lines.removeAt(random.nextInt(lines.size))
                    else -> if (lines.isNotEmpty()) lines[random.nextInt(lines.size)] = "替换😁"
                }
            }
            val after = lines.joinToString("\n")
            assertEquals("样本 $sample", after, apply(before, notesEdits(before, after)))
        }
    }

    private fun payloadSize(draft: ReviewedDraft): Int =
        draft.text.length + draft.originalText.length + draft.notesChanges.sumOf {
            it.removedPreview.length + it.insertedText.length + it.beforeContext.length + it.afterContext.length
        }
}
