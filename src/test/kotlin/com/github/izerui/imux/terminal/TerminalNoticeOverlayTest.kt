package com.github.izerui.imux.terminal

import com.github.izerui.imux.SourceCode
import com.intellij.ide.ui.laf.darcula.DarculaLaf
import com.intellij.mock.MockApplication
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import javax.swing.LookAndFeel
import javax.swing.UIManager

/**
 * 重建编辑器时轨迹已是失败状态：轨迹在编辑器字段初始化途中就同步发出失败提示。
 * 提示层在 start 之前只能记下，不能回调编辑器——回调里用到的字段那时还是 null。
 */
class TerminalNoticeOverlayTest {
    companion object {
        private val appDisposable = Disposer.newDisposable("TerminalNoticeOverlayTest")
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

    private val parent: Disposable = Disposer.newDisposable("editor")

    @After
    fun dispose() = Disposer.dispose(parent)

    /** 与 AgentTerminalFileEditor 相同的初始化顺序：提示层 → 轨迹（init 里立即刷新）→ 回调要用的字段 → start。 */
    private class FakeEditor(parent: Disposable, trailFailed: () -> Boolean) {
        val overlay = TerminalNoticeOverlay(parent, { onNoticesChanged() }, ::FakeNoticeView)

        // 相当于 SessionTrailOverlay：构造时立刻 refresh，失败状态同步发常驻提示
        init {
            overlay.sink(NoticeSource.TRAIL).persistent(
                if (trailFailed()) Notice("trail:failed:超时", NoticeTone.WARNING, "会话轨迹更新失败：超时") else null,
            )
        }

        // 相当于 resultChip：声明在轨迹之后
        private val chipRefreshes = mutableListOf<Boolean>()

        init {
            overlay.start()
        }

        val refreshCount get() = chipRefreshes.size

        private fun onNoticesChanged() {
            chipRefreshes += overlay.isShowing(NoticeSource.PAIR)
        }
    }

    @Test
    fun `轨迹已失败时打开终端不会在初始化途中回调编辑器`() {
        val editor = FakeEditor(parent) { true }
        assertEquals("start 之后才画出失败提示并回调一次", 1, editor.refreshCount)
        assertEquals(1, editor.overlay.component.componentCount)
        assertTrue(editor.overlay.component.isVisible)
    }

    @Test
    fun `轨迹失败后重建编辑器，新编辑器照样显示失败提示`() {
        val failed = { true }
        val first = FakeEditor(parent, failed)
        // 拖动标签页、分屏：旧编辑器销毁，新编辑器在同一失败状态下重建
        Disposer.dispose(first.overlay)
        val rebuilt = FakeEditor(parent, failed)
        assertEquals(1, rebuilt.overlay.component.componentCount)
        assertEquals(1, rebuilt.refreshCount)
    }

    @Test
    fun `没有提示时 start 不回调`() {
        val editor = FakeEditor(parent) { false }
        assertEquals(0, editor.refreshCount)
        assertEquals(0, editor.overlay.component.componentCount)
    }

    @Test
    fun `失败提示收起、重试、再次同样失败时重新出现`() {
        val editor = FakeEditor(parent) { true }
        val trail = editor.overlay.sink(NoticeSource.TRAIL)
        val failed = Notice("trail:failed:超时", NoticeTone.WARNING, "会话轨迹更新失败：超时")
        val row = editor.overlay.component.getComponent(0) as javax.swing.JComponent
        findClose(row).doClick()
        assertEquals("× 之后收起", 0, editor.overlay.component.componentCount)
        trail.persistent(failed)
        assertEquals("同一状态不再出现", 0, editor.overlay.component.componentCount)
        trail.persistent(null)
        trail.persistent(failed)
        assertEquals("重试后同样的错误重新提示", 1, editor.overlay.component.componentCount)
    }

    private fun findClose(root: java.awt.Container): javax.swing.JButton =
        root.components.firstNotNullOfOrNull { child ->
            when (child) {
                is javax.swing.JButton -> child.takeIf { it.text == "×" }
                is java.awt.Container -> runCatching { findClose(child) }.getOrNull()
                else -> null
            }
        } ?: error("没找到关闭按钮")
}

/** 平台通知条依赖编辑器配色服务，单元测试里用普通组件代替，行为（文字、链接、×）照样走提示层。 */
private class FakeNoticeView(tone: NoticeTone) : NoticeView {
    override val component = javax.swing.JPanel()
    private val label = javax.swing.JLabel()

    init {
        component.add(label)
    }

    override fun setText(text: String) {
        label.text = text
    }

    override fun setIcon(icon: javax.swing.Icon) {
        label.icon = icon
    }

    override fun addAction(text: String, run: (javax.swing.JComponent) -> Unit) {
        lateinit var button: javax.swing.JButton
        button = javax.swing.JButton(text).apply { addActionListener { run(button) } }
        component.add(button)
    }

    override fun setCloseAction(run: () -> Unit) {
        component.add(javax.swing.JButton("×").apply { addActionListener { run() } })
    }
}

class TerminalNoticeStartOrderSourceTest {
    private val editor = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt").normalized

    @Test
    fun `编辑器在 init 最后才启动提示层，回调用到的字段都在它之前初始化`() {
        // 编辑器自己的 init（不是 terminalLayer 匿名对象里的那个），以注册语言监听开头
        val firstInit = editor.indexOf("init { ImuxSettings.getInstance().addLanguageListener(this)")
        assertTrue(firstInit >= 0)
        val open = editor.indexOf('{', firstInit)
        var depth = 0
        var close = -1
        for (i in open until editor.length) {
            if (editor[i] == '{') depth++
            if (editor[i] == '}' && --depth == 0) {
                close = i
                break
            }
        }
        val initBody = editor.substring(open + 1, close).trim()
        assertTrue("start 是 init 的最后一步：…${initBody.takeLast(80)}", initBody.endsWith("noticeOverlay.start()"))
        for (field in listOf("private val noticeOverlay", "private val trailOverlay", "private val resultChip", "private var toolStrip")) {
            val at = editor.indexOf(field)
            assertTrue("$field 必须在 init 之前声明", at in 0 until firstInit)
        }
    }
}
