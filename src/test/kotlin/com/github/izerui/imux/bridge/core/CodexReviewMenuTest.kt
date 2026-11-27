package com.github.izerui.imux.bridge.core

import org.junit.Assert.*
import org.junit.Test

class CodexReviewMenuTest {
    // Layouts captured with tmux capture-pane from codex-cli 0.171.0.
    private val footer = "  enter select · esc back"
    private fun presetScreen(rows: String) = "\n\n  Select a review preset\n$rows\n$footer"

    @Test fun `窄屏滚动后手机编号仍对应四个固定预设`() {
        val labels = listOf("Review against a base branch", "Review uncommitted changes", "Review a commit", "Custom review instructions")
        val top32 = "  1. Review against a base\n     branch\n  2. Review uncommitted changes"
        val bottom = "  2. Review uncommitted changes\n  3. Review a commit\n  4. Custom review instructions"
        val top40 = "  1. Review against a base branch\n  2. Review uncommitted changes\n  3. Review a commit"
        for (rows in listOf(top32, bottom, top40, "$top40\n  4. Custom review instructions")) {
            for (from in 1..4) {
                if (!rows.lines().any { it.startsWith("  $from. ") }) continue
                val menu = CodexReviewMenu.parse(presetScreen(rows.replace("  $from. ", "› $from. ")))!!
                assertEquals(labels, menu.options)
                assertEquals(from - 1, menu.selected)
                for (to in 1..4) {
                    val moves = List(kotlin.math.abs(to - from)) { if (to < from) "\u001b[A" else "\u001b[B" }
                    assertEquals("$rows: $from -> $to", moves + "\r", menu.keysFor("$to"))
                }
            }
        }
    }

    @Test fun `无法确认编号或标签时拒绝预设菜单`() {
        for (rows in listOf(
            "› 1. Unknown",
            "› 2. Review a commit",
            "› 1. Review against a base\n  branch",
            "     branch\n› 2. Review uncommitted changes",
            "› 2. Review uncommitted changes\n  4. Custom review instructions",
            "› 2. Review uncommitted changes\n› 3. Review a commit",
        )) assertNull(rows, CodexReviewMenu.parse(presetScreen(rows)))
    }

    @Test fun `自定义占位符完整换行可输入而截断或草稿只能取消`() {
        fun parse(body: String) = CodexReviewMenu.parse("  Custom review instructions\n\n› $body\n\n  enter submit · esc back")!!
        assertEquals(listOf("\u001b[200~review\u001b[201~", "\r"), parse("Type instructions and press\n  Enter").keysFor("review"))
        for (body in listOf("Type instructions and press", "Type instructions and press\n  later")) {
            val menu = parse(body)
            assertNull(menu.keysFor("review"))
            assertEquals(listOf("\u001b"), menu.keysFor("0"))
        }
    }

    @Test fun `七行终端首屏可选四且滚到底时原编号仍指向自定义审查`() {
        val first = CodexReviewMenu.parse("  Select a review preset\n› 1. Review against a base branch\n  2. Review uncommitted changes\n  3. Review a commit\n$footer")!!
        val last = CodexReviewMenu.parse("  Select a review preset\n  2. Review uncommitted changes\n  3. Review a commit\n› 4. Custom review instructions\n$footer")!!
        assertEquals("Custom review instructions", first.options[3])
        assertEquals(listOf("\u001b[B", "\u001b[B", "\u001b[B", "\r"), first.keysFor("4"))
        assertTrue(first.sameChoices(last))
        assertEquals(listOf("\r"), last.keysFor("4"))
        assertEquals(listOf("\u001b[A", "\u001b[A", "\r"), last.keysFor("2"))
    }
    private val preset = """
          Select a review preset


        › 1. Review against a base branch  (PR Style)
          2. Review uncommitted changes
          3. Review a commit
          4. Custom review instructions

    """.trimIndent() + "\n$footer"

    @Test fun `预设保留说明并从当前行移动到手机选择`() {
        val menu = CodexReviewMenu.parse(preset.replace("› 1.", "\u001b[1;7m› 1.\u001b[0m"))!!
        assertEquals("Review against a base branch  (PR Style)", menu.options.first())
        assertEquals(4, menu.options.size)
        assertEquals(0, menu.selected)
        assertEquals(listOf("\u001b[B", "\u001b[B", "\r"), menu.keysFor("3"))
        val moved = menu.copy(selected = 2)
        assertTrue(menu.sameChoices(moved))
        assertEquals(listOf("\u001b[A", "\r"), moved.keysFor("２。"))
        assertEquals(listOf("\r"), moved.keysFor("3"))
        assertNull(menu.keysFor("5"))
        assertNull(menu.keysFor("next"))
    }

    @Test fun `提交列表保留重名与编号状标题并识别翻页箭头`() {
        val menu = CodexReviewMenu.parse("  Select a commit to review\n\n  Type to search commits\n↑\n  1. Fix\n› 1. Fix\n↓\n$footer")!!
        assertEquals(listOf("1. Fix", "1. Fix"), menu.options)
        assertEquals(1, menu.selected)
        assertEquals(listOf("\u001b[5~"), menu.keysFor("prev"))
        assertEquals(listOf("\u001b[6~"), menu.keysFor("next"))
        assertFalse(menu.sameChoices(menu.copy(hasPreviousPage = false)))
    }

    @Test fun `分支搜索无结果仍可清除或更换条件`() {
        val menu = CodexReviewMenu.parse("  Select a base branch\n  Current branch: main\n\n  zz\n\nno matches\n\n$footer")!!
        assertEquals(emptyList<String>(), menu.options)
        assertEquals(-1, menu.selected)
        assertEquals("zz", menu.searchQuery)
        assertEquals(listOf("\u007f", "\u007f", "\u001b[200~feat\u001b[201~"), menu.keysFor("/feat"))
        assertEquals(listOf("\u007f", "\u007f"), menu.keysFor("/"))
        assertEquals(listOf("\u001b"), menu.keysFor("0"))
        assertNull(menu.keysFor("1"))
        assertNull(menu.keysFor("/bad\nsearch"))
        assertFalse(menu.sameChoices(menu.copy(searchQuery = "yy")))
    }

    @Test fun `自定义正文以括号粘贴再回车并拒绝控制序列`() {
        val screen = "  Custom review instructions\n\n› Type instructions and press Enter\n\n  enter submit · esc back"
        val menu = CodexReviewMenu.parse(screen)!!
        assertTrue(menu.isCustom)
        assertEquals(listOf("\u001b[200~Check\n tests!\u001b[201~", "\r"), menu.keysFor("Check\n tests!"))
        assertNull(menu.keysFor("a\u001b[201~b"))
        assertNull(menu.keysFor("\u009b201~"))
        assertNull(menu.keysFor("  "))
        val draft = CodexReviewMenu.parse(screen.replace("Type instructions and press Enter", "desktop draft"))!!
        assertFalse(menu.sameChoices(draft))
        assertNull(draft.keysFor("replace it"))
        assertEquals(listOf("\u001b"), draft.keysFor("0"))
    }

    @Test fun `只识别屏幕底部有对应页脚与光标的菜单`() {
        assertNull(CodexReviewMenu.parse("$preset\n\n› Ask Codex to do anything"))
        assertNull(CodexReviewMenu.parse(preset.replace("› 1.", "  1.")))
        assertNull(CodexReviewMenu.parse(preset.replace("enter select", "enter submit")))
    }
}
