package com.github.izerui.imux.toolwindow

import com.github.izerui.imux.SourceCode
import com.intellij.testFramework.LightVirtualFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `CloseOtherEditorsAction` 和提取出来的纯函数的测试。
 *
 * 分两层：
 * 1. **行为测试**：直接调用 [closeNonSessionEditors] 和 [hasClosableEditors]，
 *    用可注入回调记录关闭调用，断言普通文件被关闭、会话文件没被关闭。
 * 2. **接线断言**：用 [SourceCode] 钉住 Action 把正确的判据（`AgentTerminalVirtualFile`）
 *    和回调传给了纯函数，以及 Action 确实被挂到了 `setTitleActions` 列表里。
 */
class CloseOtherEditorsActionSourceTest {
    private val source = SourceCode(
        "src/main/kotlin/com/github/izerui/imux/toolwindow/AgentToolWindowFactory.kt",
    )

    // ── 行为测试：用注入回调记录关闭调用 ──────────────────────────

    @Test
    fun `混合文件中只有普通文件被关闭，会话文件不被关闭`() {
        val regular1 = LightVirtualFile("Main.kt")
        val regular2 = LightVirtualFile("README.md")
        val session1 = LightVirtualFile("session-1")
        val session2 = LightVirtualFile("session-2")
        val sessionFiles = setOf(session1, session2)

        val closed = mutableListOf<LightVirtualFile>()
        closeNonSessionEditors(
            arrayOf(regular1, session1, regular2, session2),
            isSessionFile = { it in sessionFiles },
        ) { closed += it as LightVirtualFile }

        assertEquals(
            "只有非会话文件应该被关闭",
            listOf(regular1, regular2),
            closed,
        )
    }

    @Test
    fun `全部是会话文件时没有任何文件被关闭`() {
        val session1 = LightVirtualFile("session-1")
        val session2 = LightVirtualFile("session-2")

        val closed = mutableListOf<LightVirtualFile>()
        closeNonSessionEditors(
            arrayOf(session1, session2),
            isSessionFile = { true },
        ) { closed += it as LightVirtualFile }

        assertTrue("全是会话文件时不应有关闭调用", closed.isEmpty())
    }

    @Test
    fun `全部是普通文件时全部被关闭`() {
        val regular1 = LightVirtualFile("Main.kt")
        val regular2 = LightVirtualFile("Test.kt")
        val regular3 = LightVirtualFile("README.md")

        val closed = mutableListOf<LightVirtualFile>()
        closeNonSessionEditors(
            arrayOf(regular1, regular2, regular3),
            isSessionFile = { false },
        ) { closed += it as LightVirtualFile }

        assertEquals(
            "全是普通文件时应全部关闭",
            listOf(regular1, regular2, regular3),
            closed,
        )
    }

    @Test
    fun `空文件列表时没有关闭调用`() {
        val closed = mutableListOf<LightVirtualFile>()
        closeNonSessionEditors(
            emptyArray(),
            isSessionFile = { false },
        ) { closed += it as LightVirtualFile }

        assertTrue("空列表时不应有关闭调用", closed.isEmpty())
    }

    // ── hasClosableEditors 行为测试 ──────────────────────────────

    @Test
    fun `有普通文件时 hasClosableEditors 返回 true`() {
        val regular = LightVirtualFile("Main.kt")
        val session = LightVirtualFile("session-1")

        assertTrue(
            hasClosableEditors(arrayOf(regular, session)) { it === session },
        )
    }

    @Test
    fun `只剩会话文件时 hasClosableEditors 返回 false`() {
        val session1 = LightVirtualFile("session-1")
        val session2 = LightVirtualFile("session-2")

        assertFalse(
            hasClosableEditors(arrayOf(session1, session2)) { true },
        )
    }

    @Test
    fun `空文件列表时 hasClosableEditors 返回 false`() {
        assertFalse(
            hasClosableEditors(emptyArray()) { false },
        )
    }

    // ── 接线断言：整段核对 Action 方法体 ────────────────────────

    /**
     * `actionPerformed` 整段核对。
     *
     * 钉住三件事，每一件对应一种让行为测试仍绿但按钮失效的变异：
     * - `manager.openFiles` → 换成 `emptyArray()` 则什么都不关
     * - `{ it is AgentTerminalVirtualFile }` → 反转判据则关的全是会话
     * - `{ manager.closeFile(it) }` → 换成 `{ }` 则一个文件也关不了
     */
    @Test
    fun `actionPerformed 整段钉住数据源、会话判据和关闭回调`() {
        source.assertSameCode(
            "actionPerformed 必须用 manager.openFiles 取文件、用 AgentTerminalVirtualFile " +
                "做判据、用 manager.closeFile(it) 关闭。三者少一个，行为测试可能仍绿但按钮失效。",
            """
            {
                val project = event.project ?: return
                val manager = FileEditorManager.getInstance(project)
                closeNonSessionEditors(
                    manager.openFiles,
                    { it is AgentTerminalVirtualFile },
                ) { manager.closeFile(it) }
            }
            """,
            actionPerformedBody(),
        )
    }

    /**
     * `update` 整段核对。
     *
     * 钉住 `isEnabled` 赋值的完整表达式：`hasClosableEditors` 的结果必须
     * 赋给 `event.presentation.isEnabled`，且判据是 `AgentTerminalVirtualFile`。
     * 删掉赋值（结果被丢弃）或反转判据都会让测试变红。
     */
    @Test
    fun `update 整段钉住 isEnabled 赋值、数据源和会话判据`() {
        source.assertSameCode(
            "update 必须把 hasClosableEditors 的结果赋给 isEnabled，" +
                "且判据是 AgentTerminalVirtualFile。删掉赋值或反转判据都不行。",
            """
            {
                event.presentation.text = ImuxBundle.message("action.close.other.editors.text")
                event.presentation.description = ImuxBundle.message("action.close.other.editors.description")
                val project = event.project
                event.presentation.isEnabled = project != null &&
                    hasClosableEditors(
                        FileEditorManager.getInstance(project).openFiles,
                    ) { it is AgentTerminalVirtualFile }
            }
            """,
            updateBody(),
        )
    }

    /**
     * `closeOtherEditorsAction` 必须出现在 `setTitleActions` 的参数列表里。
     *
     * 从列表里删掉但保留变量声明和 `refreshPresentation()` 调用，
     * 只检查符号存在于文件就能绕过——这里精确提取列表内容做核对。
     */
    @Test
    fun `closeOtherEditorsAction 在 setTitleActions 的列表中`() {
        val body = source.bodyAfter(
            "private fun doCreateContent(project: Project, toolWindow: ToolWindow)",
            '{',
        )
        val compact = source.compactArgs(body)

        assertTrue(
            "closeOtherEditorsAction 必须作为实参出现在 setTitleActions(listOf(...)) 内：$body",
            compact.contains("setTitleActions(listOf(") &&
                compact.substringAfter("setTitleActions(listOf(")
                    .substringBefore("))")
                    .contains("closeOtherEditorsAction"),
        )
    }

    // ── 锚点提取 ────────────────────────────────────────────────

    /**
     * 从 CloseOtherEditorsAction 类体中精确提取 actionPerformed 的方法体。
     *
     * 不能直接用 `bodyAfter("override fun actionPerformed")` 因为文件中
     * 其他 Action 类也有同名方法。先取整个类体再在里面定位。
     */
    private fun actionPerformedBody(): String {
        val classBody = source.bodyAfter("private class CloseOtherEditorsAction", '{')
        val classSource = SourceCode.fromText(classBody)
        return classSource.bodyAfter("override fun actionPerformed(event: AnActionEvent)", '{')
    }

    private fun updateBody(): String {
        val classBody = source.bodyAfter("private class CloseOtherEditorsAction", '{')
        val classSource = SourceCode.fromText(classBody)
        return classSource.bodyAfter("override fun update(event: AnActionEvent)", '{')
    }
}
