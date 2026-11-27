package com.github.izerui.imux.bridge.core

import org.junit.Assert.*
import org.junit.Test

class CodexCommitPickerTest {
    private val header = "prior output\n  Select a commit to review\n\n  fix\n\n"
    private val footer = "\n\n  enter select · esc back\n"
    private fun screen(rows: String) = header + rows + footer
    private fun normalize(rows: String, vararg subjects: String) =
        normalizeCommitPickerScreen(screen(rows), subjects.toList())

    @Test fun `提交标题与审查范围菜单同名时仍可选中对应提交`() {
        val names = listOf("update details", "Select a review preset", "Select a commit to review", "Custom review instructions", "Select a base branch")
        for (name in names.drop(1)) {
            for (selected in 0..2) {
                val labels = listOf(name, "update details", name)
                val rows = labels.mapIndexed { i, label ->
                    (if (i == selected) "› " else "  ") + label.replace("update details", "update\n  details")
                }.joinToString("\n")
                val menu = CodexReviewMenu.parse(normalizeGitPickerScreen(screen(rows), labels))!!
                assertEquals("Select a commit to review", menu.title)
                assertEquals(labels, menu.options)
                assertEquals(selected, menu.selected)
            }
        }
    }

    @Test fun `窄终端的分支续行不占编号且能选中最后一个分支`() {
        val names = listOf("main", "feat/requirement-clarifier", "fix/headless-runtime-unread", "fix/trail-generate-on-open")
        val raw = "  Select a base branch\n  Current branch: main\n\n  Type to search branche\n\n" +
            "› main\n  feat/requirement-\n  clarifier\n  fix/headless-runtime-\n  unread\n  fix/trail-generate-on-\n  open$footer"
        val menu = CodexReviewMenu.parse(normalizeGitPickerScreen(raw, names))!!
        assertEquals(names, menu.options)
        assertEquals(listOf("\u001b[B", "\u001b[B", "\u001b[B", "\r"), menu.keysFor("4"))
        assertNull(menu.keysFor("5"))
    }

    @Test fun `按屏幕搜索顺序还原中英文折行和重复提交`() {
        val rows = "↑\n  修复🙂\n  界面\n› split\n  English  words\n  split\n  English  words\n↓"
        val english = "split English  words"
        val result = normalize(rows, english, english, "修复🙂界面")
        assertEquals(screen("↑\n  修复🙂界面\n› $english\n  $english\n↓"), result)
        val menu = CodexReviewMenu.parse(result)!!
        assertEquals(1, menu.selected)
        assertEquals(listOf("\u001b[B", "\r"), menu.keysFor("3"))
        assertTrue(menu.hasPreviousPage)
        assertTrue(menu.hasNextPage)
    }

    @Test fun `不同分组都符合提交标题时只保留搜索取消`() {
        val menu = CodexReviewMenu.parse(normalize("› first\n  second", "first second", "first", "second"))!!
        assertEquals(emptyList<String>(), menu.options)
        assertEquals("fix", menu.searchQuery)
        assertNull(menu.keysFor("1"))
        assertNotNull(menu.keysFor("/new"))
        assertNotNull(menu.keysFor("0"))
    }

    @Test fun `选中箭头强制开启新选项`() {
        assertEquals(screen("  first\n› second"), normalize("  first\n› second", "first second", "first", "second"))
        assertEquals(screen("no matches"), normalize("  first\n› second", "first second"))
    }

    @Test fun `缺少历史或遇到截断标题时关闭数字选择`() {
        assertEquals(screen("no matches"), normalize("↑\n› known\n↓"))
        assertEquals(screen("no matches"), normalize("› truncated", "truncated subject"))
        assertEquals(screen("no matches"), normalize("› repeated\n  repeated", "repeated"))
    }

    @Test fun `折行边缘空格来源不明确时不猜标题`() {
        assertEquals(screen("no matches"), normalize("› abc\n  def", "abcdef", "abc def"))
    }

    @Test fun `其他菜单保留原屏`() {
        val branch = screen("› main").replace("Select a commit to review", "Select a base branch")
        assertEquals(branch, normalizeCommitPickerScreen(branch, emptyList()))
    }
}
