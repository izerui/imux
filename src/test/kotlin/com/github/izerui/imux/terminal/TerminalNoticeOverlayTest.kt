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
import org.junit.Assert.assertSame
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
        private var originalDark = false

        @JvmStatic
        @BeforeClass
        fun installApp() {
            originalLaf = UIManager.getLookAndFeel()
            originalDark = !com.intellij.ui.JBColor.isBright()
            UIManager.setLookAndFeel(DarculaLaf())
            com.intellij.ui.JBColor.setDark(true)
            val app = MockApplication(appDisposable)
            ApplicationManager.setApplication(app, appDisposable)
            app.registerService(com.intellij.ui.ExperimentalUI::class.java, object : com.intellij.ui.ExperimentalUI() {
                override fun saveCurrentValueAndReapplyDefaultLaf() = Unit
                override fun earlyInitValue() = true
            })
            app.registerService(com.intellij.openapi.editor.colors.EditorColorsManager::class.java, PreviewColors())
        }

        @JvmStatic
        @AfterClass
        fun uninstall() {
            Disposer.dispose(appDisposable)
            originalLaf?.let { UIManager.setLookAndFeel(it) }
            com.intellij.ui.JBColor.setDark(originalDark)
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
    fun `提示连续更新后复用按钮只执行最新动作一次`() {
        val views = mutableListOf<FakeNoticeView>()
        val calls = mutableListOf<String>()
        var anchor: javax.swing.JComponent? = null
        val overlay = TerminalNoticeOverlay(parent, {}, { tone ->
            FakeNoticeView(tone).also { views += it }
        })
        overlay.start()
        val sink = overlay.sink(NoticeSource.PAIR)
        sink.persistent(Notice("pair:scheduled", NoticeTone.INFO, "old",
            actions = listOf(NoticeAction("cancel") { calls += "old" })))
        val button = views.single().actionButtons.single()

        repeat(3) { version ->
            sink.persistent(Notice("pair:scheduled", NoticeTone.INFO, "new-$version",
                actions = listOf(NoticeAction("cancel") {
                    calls += "new-$version"
                    anchor = it
                })))
        }
        button.doClick()

        assertEquals(listOf("new-2"), calls)
        assertSame("弹出菜单等动作仍收到实际点击的按钮", button, anchor)
        assertEquals("只更新动作，无需重建提示条", 1, views.size)
    }

    @Test
    fun `提示更新后两个按钮各自执行对应的新动作`() {
        val view = FakeNoticeView(NoticeTone.INFO)
        val calls = mutableListOf<String>()
        val overlay = TerminalNoticeOverlay(parent, {}, { view })
        overlay.start()
        val sink = overlay.sink(NoticeSource.PAIR)
        sink.persistent(Notice("pair", NoticeTone.INFO, "old", actions = listOf(
            NoticeAction("retry") { calls += "old-retry" },
            NoticeAction("cancel") { calls += "old-cancel" },
        )))
        sink.persistent(Notice("pair", NoticeTone.INFO, "new", actions = listOf(
            NoticeAction("retry") { calls += "new-retry" },
            NoticeAction("cancel") { calls += "new-cancel" },
        )))

        view.actionButtons[1].doClick()
        assertEquals(listOf("new-cancel"), calls)
        calls.clear()
        view.actionButtons[0].doClick()
        assertEquals(listOf("new-retry"), calls)
    }

    @Test
    fun `顶部进度从未知转为百分比时原地更新且结束移除`() {
        javax.swing.SwingUtilities.invokeAndWait {
            val views = mutableListOf<FakeNoticeView>()
            val overlay = TerminalNoticeOverlay(parent, {}, { tone ->
                FakeNoticeView(tone).also { views += it }
            })
            overlay.start()
            val sink = overlay.sink(NoticeSource.PROGRESS)
            sink.persistent(sessionProgressNotice("session",
                com.github.izerui.imux.progress.ProgressSnapshot(active = true)))
            val row = overlay.component.getComponent(0) as javax.swing.JPanel
            val bar = row.components.filterIsInstance<javax.swing.JProgressBar>().single()
            assertTrue(bar.isIndeterminate)
            assertEquals(com.github.izerui.imux.ImuxBundle.message("progress.unknown"), views.single().text)

            sink.persistent(sessionProgressNotice("session",
                com.github.izerui.imux.progress.ProgressSnapshot(active = true,
                    progress = com.github.izerui.imux.progress.TurnProgress(65, "核对手机连接"))))
            assertSame(row, overlay.component.getComponent(0))
            assertSame(bar, row.components.filterIsInstance<javax.swing.JProgressBar>().single())
            assertEquals(1, views.size)
            org.junit.Assert.assertFalse(bar.isIndeterminate)
            assertEquals(65, bar.value)
            assertEquals(com.github.izerui.imux.ImuxBundle.message("progress.running") +
                " 65% · 核对手机连接", views.single().text)
            assertEquals(1, bar.componentCount)
            org.junit.Assert.assertFalse(views.single().component.isOpaque)
            for (width in listOf(280, 800)) {
                row.setSize(width, 36)
                row.doLayout()
                bar.doLayout()
                assertEquals("进度覆盖整条高度", row.height, bar.height)
                assertEquals("状态和百分比共用整行", width, views.single().component.width)
            }

            sink.persistent(null)
            assertEquals(0, overlay.component.componentCount)
            org.junit.Assert.assertFalse(overlay.component.isVisible)
        }
    }

    @Test
    fun `顶部手机审查轨迹和进度提示使用一致的紧凑高度`() {
        javax.swing.SwingUtilities.invokeAndWait {
            val overlay = TerminalNoticeOverlay(parent, {})
            overlay.start()
            val fixtures = listOf(
                NoticeSource.PHONE to Notice("phone", NoticeTone.WARNING, "手机连接异常",
                    actions = listOf(NoticeAction("重新连接") {}, NoticeAction("换绑") {})),
                NoticeSource.PAIR to Notice("pair", NoticeTone.INFO, "正在审查",
                    actions = listOf(NoticeAction("取消") {})),
                NoticeSource.TRAIL to Notice("trail", NoticeTone.SUCCESS, "会话轨迹已更新",
                    actions = listOf(NoticeAction("查看") {})),
                NoticeSource.PROGRESS to checkNotNull(sessionProgressNotice("session",
                    com.github.izerui.imux.progress.ProgressSnapshot(active = true,
                        progress = com.github.izerui.imux.progress.TurnProgress(65, "检查修改")))),
            )
            val heights = fixtures.associate { (source, notice) ->
                overlay.sink(source).persistent(notice)
                // Inspect this source on its own so priority order cannot affect the assertion.
                val single = TerminalNoticeOverlay(parent, {})
                single.start()
                single.sink(source).persistent(notice)
                source to single.component.preferredSize.height
            }
            heights.forEach { (source, height) ->
                assertEquals("$source 提示不应被原生上下留白撑高，实际高度：$heights", com.intellij.util.ui.JBUI.scale(28), height)
            }
            assertEquals(4 * com.intellij.util.ui.JBUI.scale(28), overlay.component.preferredSize.height)
            for (width in listOf(280, 800)) {
                val component = overlay.component
                component.setSize(width, component.preferredSize.height)
                fun layoutTree(root: java.awt.Container) {
                    root.doLayout()
                    root.components.filterIsInstance<java.awt.Container>().forEach(::layoutTree)
                    root.components.filterIsInstance<javax.swing.JComponent>().forEach { child ->
                        if (child is com.intellij.ui.HyperlinkLabel || child is com.intellij.ui.InplaceButton) {
                            assertTrue("操作控件不能被上下裁剪", child.height >= child.preferredSize.height)
                            assertTrue("操作控件必须留在提示行内", child.y >= 0 && child.y + child.height <= root.height)
                        }
                    }
                }
                layoutTree(component)
                component.components.forEach { row ->
                    assertEquals("叠放后每行宽度与终端一致", width, row.width)
                    assertEquals("叠放后每行仍为紧凑高度", com.intellij.util.ui.JBUI.scale(28), row.height)
                }
                val image = java.awt.image.BufferedImage(width, component.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
                image.createGraphics().let { g -> try { component.paint(g) } finally { g.dispose() } }
                val file = java.io.File("build/reports/notice-stack-$width.png")
                file.parentFile.mkdirs()
                javax.imageio.ImageIO.write(image, "png", file)
            }
            overlay.sink(NoticeSource.PROGRESS).persistent(sessionProgressNotice("session",
                com.github.izerui.imux.progress.ProgressSnapshot(active = true)))
            assertEquals("未知进度动画不应改变提示行高度",
                4 * com.intellij.util.ui.JBUI.scale(28), overlay.component.preferredSize.height)
        }
    }

    @Test
    fun `放大字体时提示高度随文字增长且悬停文字可读完整说明`() {
        javax.swing.SwingUtilities.invokeAndWait {
            val originalFont = UIManager.getFont("Label.font")
            try {
                UIManager.put("Label.font", originalFont.deriveFont(28f))
                val view = platformNoticeView(NoticeTone.INFO)
                val message = "较长的提示文字".repeat(20)
                view.setText(message)
                val component = view.component
                assertTrue("大字体不能被固定行高裁掉",
                    component.preferredSize.height >= component.getFontMetrics(UIManager.getFont("Label.font")).height)
                fun editor(root: java.awt.Container): javax.swing.JEditorPane? =
                    root.components.firstNotNullOfOrNull {
                        when (it) {
                            is javax.swing.JEditorPane -> it
                            is java.awt.Container -> editor(it)
                            else -> null
                        }
                    }
                assertEquals(message, checkNotNull(editor(component)).toolTipText)
            } finally {
                UIManager.put("Label.font", originalFont)
            }
        }
    }

    @Test
    fun `原生通知显示底部细线且百分比紧跟状态`() {
        javax.swing.SwingUtilities.invokeAndWait {
            val foreground = UIManager.get("Label.foreground")
            val bannerForeground = UIManager.get("Banner.foreground")
            val panelBackground = UIManager.get("Panel.background")
            val wasDark = !com.intellij.ui.JBColor.isBright()
            try {
                for (dark in listOf(false, true)) {
                    com.intellij.ui.JBColor.setDark(dark)
                    val textColor = java.awt.Color(if (dark) 0xBBBBBB else 0x333333)
                    UIManager.put("Label.foreground", textColor)
                    UIManager.put("Banner.foreground", textColor)
                    UIManager.put("Panel.background", java.awt.Color(if (dark) 0x2B2D30 else 0xF7F8FA))
                    renderNativePreview(if (dark) "dark" else "light")
                }
            } finally {
                com.intellij.ui.JBColor.setDark(wasDark)
                UIManager.put("Label.foreground", foreground)
                UIManager.put("Banner.foreground", bannerForeground)
                UIManager.put("Panel.background", panelBackground)
            }
        }
    }

    private fun renderNativePreview(theme: String) {
        val overlay = TerminalNoticeOverlay(parent, {})
        overlay.start()
        overlay.sink(NoticeSource.PROGRESS).persistent(sessionProgressNotice("session",
            com.github.izerui.imux.progress.ProgressSnapshot(active = true,
                progress = com.github.izerui.imux.progress.TurnProgress(65, "核对手机连接与审查结果"))))
        val row = overlay.component.getComponent(0) as javax.swing.JPanel
        fun layoutTree(root: java.awt.Container) {
            root.doLayout()
            root.components.filterIsInstance<java.awt.Container>().forEach(::layoutTree)
        }
        for (width in listOf(280, 800)) {
            row.setSize(width, row.preferredSize.height)
            layoutTree(row)
            val image = java.awt.image.BufferedImage(width, row.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
            image.createGraphics().let { g -> try { row.paint(g) } finally { g.dispose() } }
            org.junit.Assert.assertNotEquals("原生通知不能盖住底部进度线",
                image.getRGB(2, row.height - 1), image.getRGB(width - 2, row.height - 1))
            val expectedBlue = if (theme == "dark") 0x548AF7 else 0x3574F0
            val expectedBackground = if (theme == "dark") 0x2B2D30 else 0xF7F8FA
            val progressEnd = (width * 0.65).toInt()
            assertEquals("完整提示条中的蓝线颜色", expectedBlue,
                image.getRGB(progressEnd - 1, row.height - 1) and 0xFFFFFF)
            assertEquals("65%之后不画进度线", expectedBackground,
                image.getRGB(progressEnd, row.height - 1) and 0xFFFFFF)
            assertEquals("蓝线不覆盖文字背景", expectedBackground,
                image.getRGB(2, row.height - com.intellij.util.ui.JBUI.scale(2) - 1) and 0xFFFFFF)
            assertEquals("文字区域保持统一背景",
                image.getRGB(2, row.height / 2), image.getRGB(width - 2, row.height / 2))
            val bar = row.components.filterIsInstance<javax.swing.JProgressBar>().single()
            assertEquals(1, bar.componentCount)
            fun editor(root: java.awt.Container): javax.swing.JEditorPane? =
                root.components.firstNotNullOfOrNull {
                    when (it) {
                        is javax.swing.JEditorPane -> it
                        is java.awt.Container -> editor(it)
                        else -> null
                    }
                }
            assertEquals(com.github.izerui.imux.ImuxBundle.message("progress.running") +
                " 65% · 核对手机连接与审查结果", checkNotNull(editor(bar)).toolTipText)
            val file = java.io.File("build/reports/progress-preview-$theme-$width.png")
            file.parentFile.mkdirs()
            javax.imageio.ImageIO.write(image, "png", file)
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

/** Only the color service is stubbed; the preview uses the actual IDE notification component. */
private class PreviewColors : com.intellij.openapi.editor.colors.EditorColorsManager() {
    private val scheme = java.lang.reflect.Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(com.intellij.openapi.editor.colors.EditorColorsScheme::class.java)
    ) { _, method, _ ->
        when (method.name) {
            "getDefaultForeground" -> com.intellij.util.ui.UIUtil.getLabelForeground()
            "getDefaultBackground" -> com.intellij.util.ui.UIUtil.getPanelBackground()
            "getName" -> "Preview"
            "getColor", "getAttributes" -> null
            else -> error("Unexpected scheme call: ${method.name}")
        }
    } as com.intellij.openapi.editor.colors.EditorColorsScheme
    override fun getGlobalScheme() = scheme
    override fun getActiveVisibleScheme() = scheme
    override fun getAllSchemes() = arrayOf(scheme)
    override fun getScheme(name: String) = scheme
    override fun isDefaultScheme(scheme: com.intellij.openapi.editor.colors.EditorColorsScheme) = true
    override fun isUseOnlyMonospacedFonts() = false
    override fun getSchemeModificationCounter() = 0L
    override fun addColorScheme(scheme: com.intellij.openapi.editor.colors.EditorColorsScheme) = Unit
    override fun setGlobalScheme(scheme: com.intellij.openapi.editor.colors.EditorColorsScheme?) = Unit
    override fun setCurrentSchemeOnLafChange(scheme: com.intellij.openapi.editor.colors.EditorColorsScheme) = Unit
    override fun setUseOnlyMonospacedFonts(value: Boolean) = Unit
}

/** 平台通知条依赖编辑器配色服务，单元测试里用普通组件代替，行为（文字、链接、×）照样走提示层。 */
private class FakeNoticeView(tone: NoticeTone) : NoticeView {
    override val component = javax.swing.JPanel()
    val actionButtons = mutableListOf<javax.swing.JButton>()
    private val label = javax.swing.JLabel()
    val text: String get() = label.text

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
        actionButtons += button
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
