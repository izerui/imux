package com.github.izerui.imux.terminal

import com.github.izerui.imux.icons.AgentIcons
import com.intellij.ide.ui.laf.darcula.DarculaLaf
import com.intellij.mock.MockApplication
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.awt.Container
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.LookAndFeel
import javax.swing.UIManager

/**
 * 收起蓝条后仍能悬停看实时进度：审查进行时说明每秒都在变（工具调用、秒数），
 * 正在显示的说明必须原地刷新，弹窗不能被关掉或重建。
 */
class ToolHoverTipTest {
    companion object {
        private val appDisposable = Disposer.newDisposable("ToolHoverTipTest")
        private var originalLaf: LookAndFeel? = null

        @JvmStatic
        @BeforeClass
        fun installApp() {
            originalLaf = UIManager.getLookAndFeel()
            UIManager.setLookAndFeel(DarculaLaf())
            val app = MockApplication(appDisposable)
            ApplicationManager.setApplication(app, appDisposable)
            app.registerService(com.intellij.ui.ExperimentalUI::class.java, object : com.intellij.ui.ExperimentalUI() {
                override fun saveCurrentValueAndReapplyDefaultLaf() = Unit
            })
        }

        @JvmStatic
        @AfterClass
        fun uninstall() {
            Disposer.dispose(appDisposable)
            originalLaf?.let { UIManager.setLookAndFeel(it) }
        }
    }

    private class FakeWindow : TipWindow {
        var shows = 0
        var closes = 0
        var repacks = 0
        var content: JComponent? = null
        override var isVisible = false

        override fun show(content: JComponent, owner: JComponent) {
            shows++
            this.content = content
            isVisible = true
        }

        override fun repack() {
            repacks++
        }

        override fun close() {
            closes++
            isVisible = false
        }
    }

    private class MutableTool(var state: ToolState) : SessionTool {
        override fun state() = state

        override fun perform(anchor: JComponent) = Unit
    }

    private fun texts(root: Container): List<String> =
        root.components.flatMap { child ->
            when (child) {
                is JLabel -> listOf(child.text)
                is Container -> texts(child)
                else -> emptyList()
            }
        }

    private fun running(seconds: Int, tool: String) =
        ToolState(
            AgentIcons.pair, "结对编程",
            "Codex 正在审查第 1 轮 · 已完成 ${seconds / 2} 项 · 正在执行 $tool · $seconds 秒",
            label = "审查中 ${seconds}s", tone = NoticeTone.INFO,
        )

    /** 持续结对与审查一次在协调器里是同一条进度路径（见 PeerCoordinatorDispatchTest），这里按两种文案各走一遍。 */
    private fun hoverForSeconds(firstTool: String, laterTool: String) {
        val window = FakeWindow()
        val tool = MutableTool(running(1, firstTool))
        val pill = ToolPill(tool, pinned = true, tipWindow = window)

        pill.hoverTip.hoverStarted()
        pill.hoverTip.showNow()
        assertEquals(1, window.shows)

        // 鼠标停着不动，状态每秒刷新一次
        for (second in 2..6) {
            tool.state = running(second, if (second < 4) firstTool else laterTool)
            pill.update()
        }

        assertEquals("说明不能被重建", 1, window.shows)
        assertEquals("说明不能被关掉", 0, window.closes)
        assertTrue("一直开着", window.isVisible)
        assertEquals("每次变化都按新内容调整大小", 5, window.repacks)
        val shown = texts(window.content!!).joinToString(" ")
        assertTrue("能看到最新的工具调用：$shown", shown.contains(laterTool))
        assertTrue("能看到最新的时间：$shown", shown.contains("6 秒"))
    }

    @Test
    fun `持续结对审查中悬停几秒，说明原地刷新工具调用与时间且不消失`() {
        hoverForSeconds("读取 PhoneTool.kt", "./gradlew test")
    }

    @Test
    fun `审查一次进行中悬停几秒，说明原地刷新工具调用与时间且不消失`() {
        hoverForSeconds("git diff --stat", "读取 AgentTerminalFileEditor.kt")
    }

    @Test
    fun `按钮状态没变时不要求重新布局，字变长时才要求`() {
        val tool = MutableTool(running(1, "git diff"))
        val pill = ToolPill(tool, tipWindow = FakeWindow())
        assertFalse("没变", pill.update())
        tool.state = tool.state.copy(description = "说明变了，宽度不变")
        assertFalse("只有说明变化不影响宽度", pill.update())
        tool.state = tool.state.copy(label = "审查中 1234567s")
        assertTrue("字变长了", pill.update())
    }

    @Test
    fun `内容没变时不调整大小`() {
        val window = FakeWindow()
        val tool = MutableTool(running(1, "git diff"))
        val pill = ToolPill(tool, tipWindow = window)
        pill.hoverTip.showNow()
        pill.update()
        assertEquals(0, window.repacks)
    }

    @Test
    fun `鼠标移开或按下时收起，未显示时不弹出`() {
        val window = FakeWindow()
        val pill = ToolPill(MutableTool(running(1, "git diff")), tipWindow = window)
        pill.hoverTip.hoverEnded()
        assertEquals("没显示时不需要关", 0, window.closes)
        pill.hoverTip.showNow()
        pill.hoverTip.hoverEnded()
        assertEquals(1, window.closes)
        assertFalse(window.isVisible)
    }
}
