package com.github.izerui.imux.terminal

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.icons.AgentIcons
import com.github.izerui.imux.settings.PluginLanguage
import com.intellij.icons.AllIcons
import com.intellij.ide.ui.laf.darcula.DarculaLaf
import com.intellij.mock.MockApplication
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.IconManager
import com.intellij.ui.icons.CoreIconManager
import com.intellij.util.ui.JBUI
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.LookAndFeel
import javax.swing.UIManager

/**
 * 以实际 Swing 布局验收窄分屏：真实的 [ToolPill]、[ResultChip]、[TerminalToolStrip] 与字体，所有语言的结果文案，
 * 「审查完」与「审查完＋手机在线」两种场景，宽度从很窄到宽逐档排一遍：四个按钮始终完整、整组不超出可用宽度、
 * chip 文字不被截成省略号，并且按对比页方案 A 的顺序收缩（伙伴名最后才收）。
 * 写出 build/ui-preview/tool-strip-narrow-*.png 供人看。
 *
 * 按钮、结果 chip、图标组都是真实组件，只有按钮背后的功能换成固定状态的假实现。
 */
class ToolStripNarrowPreviewTest {
    companion object {
        private val outDir = File("build/ui-preview")
        private val appDisposable = Disposer.newDisposable("ToolStripNarrowPreviewTest")
        private var originalLaf: LookAndFeel? = null
        private var activatedIcons = false

        @JvmStatic
        @BeforeClass
        fun installLaf() {
            originalLaf = UIManager.getLookAndFeel()
            UIManager.setLookAndFeel(DarculaLaf())
            activatedIcons = runCatching { IconManager.activate(CoreIconManager()) }.isSuccess
            val app = MockApplication(appDisposable)
            ApplicationManager.setApplication(app, appDisposable)
            app.registerService(com.intellij.ui.ExperimentalUI::class.java, object : com.intellij.ui.ExperimentalUI() {
                override fun saveCurrentValueAndReapplyDefaultLaf() = Unit
            })
            outDir.mkdirs()
        }

        @JvmStatic
        @AfterClass
        fun uninstall() {
            Disposer.dispose(appDisposable)
            if (activatedIcons) IconManager.deactivate()
            originalLaf?.let { UIManager.setLookAndFeel(it) }
        }
    }

    private enum class Result { PASSED, FEEDBACK }

    /** 审查完、结对开着；[phoneOnline] 时手机也在线，两段文字和结果一起抢空间。 */
    private class Scene(val result: Result, val phoneOnline: Boolean) {
        override fun toString() = "$result${if (phoneOnline) "+手机" else ""}"
    }

    private val scenes = Result.entries.flatMap { listOf(Scene(it, false), Scene(it, true)) }

    private fun tool(state: ToolState) =
        object : SessionTool {
            override fun state() = state

            override fun perform(anchor: JComponent) = Unit
        }

    private fun strip(language: PluginLanguage, scene: Scene): Pair<TerminalToolStrip, List<ToolPill>> {
        val chip = ResultChip {}
        val passed = scene.result == Result.PASSED
        chip.icon = if (passed) AllIcons.Status.Success else AgentIcons.review
        chip.setResult(
            true,
            full =
                if (passed) ImuxBundle.message(language, "action.peer.chip.passed", "14:32")
                else ImuxBundle.message(language, "action.peer.chip.feedback", "Codex", "14:32"),
            short = ImuxBundle.message(language, if (passed) "action.peer.chip.passed.short" else "action.peer.chip.feedback.short"),
        )
        val pills =
            listOf(
                ToolPill(tool(ToolState(AgentIcons.pair, "", "", label = "Codex", tone = NoticeTone.SUCCESS)), pinned = true),
                ToolPill(tool(ToolState(AgentIcons.talk, "", ""))),
                ToolPill(tool(ToolState(AgentIcons.trail, "", ""))),
                ToolPill(
                    tool(
                        if (scene.phoneOnline) ToolState(AgentIcons.phone, "", "", label = "研发助手", tone = NoticeTone.SUCCESS)
                        else ToolState(AgentIcons.phone, "", ""),
                    ),
                ),
            )
        return TerminalToolStrip(chip, pills) to pills
    }

    /**
     * 与编辑器 doLayout 相同：按可用宽度 fit，用 fit 返回的尺寸。
     * 比按钮本身还窄时整组保持按钮宽度（盖住一点导航轨道），不压按钮。
     */
    private fun layOut(strip: TerminalToolStrip, room: Int) {
        val size = strip.fit(room)
        strip.component.setSize(size)
        strip.component.doLayout()
    }

    private val rooms = (60..460 step 10).toList()

    private fun labelled(pill: ToolPill) = pill.width > pill.iconWidth()

    @Test
    fun `任何语言和宽度下四个按钮都完整可见，整组不超出可用宽度`() {
        for (language in PluginLanguage.entries) for (scene in scenes) {
            for (room in rooms) {
                val (strip, pills) = strip(language, scene)
                layOut(strip, room)
                val label = "$language $scene room=$room"
                val minimum = strip.component.insets.let { it.left + it.right } + pills.sumOf { it.iconWidth() } + JBUI.scale(4) * (pills.size - 1)
                if (room >= minimum) assertTrue("$label 整组宽 ${strip.component.width}", strip.component.width <= room)
                for (pill in pills) {
                    assertEquals("$label 按钮宽度应等于它要的宽度", pill.preferredSize.width, pill.width)
                    assertTrue("$label 按钮不能超出图标组", pill.x >= 0 && pill.x + pill.width <= strip.component.width)
                }
            }
        }
    }

    @Test
    fun `收缩顺序：先缩结果，再收其它功能的字，伙伴名最后才收`() {
        for (language in PluginLanguage.entries) for (scene in scenes) {
            for (room in rooms) {
                val (strip, pills) = strip(language, scene)
                layOut(strip, room)
                val label = "$language $scene room=$room"
                val chip = strip.chip
                val pairLabelled = labelled(pills[0])
                if (labelled(pills[3])) assertTrue("$label 手机名还在时伙伴名必须在", pairLabelled)
                if (chip.isVisible) assertTrue("$label 结果还在时伙伴名必须在", pairLabelled)
                if (chip.isVisible && chip.text.isNotEmpty()) {
                    assertTrue("$label chip 宽 ${chip.width} < 需要 ${chip.preferredSize.width}", chip.width >= chip.preferredSize.width)
                }
                if (labelled(pills[3])) assertTrue("$label 手机名还在时结果不能只剩图标或收起", chip.isVisible && chip.text.isNotEmpty())
            }
            val (wide, widePills) = strip(language, scene)
            layOut(wide, 700)
            assertTrue("$language $scene 宽屏应放完整文案：${wide.chip.text}", wide.chip.text.contains("14:32") || scene.phoneOnline)
            assertTrue("$language $scene 宽屏应显示伙伴名", labelled(widePills[0]))
        }
    }

    @Test
    fun `输出窄分屏预览图`() {
        for (language in listOf(PluginLanguage.SIMPLIFIED_CHINESE, PluginLanguage.GERMAN, PluginLanguage.RUSSIAN)) {
            val widths = listOf(120, 170, 220, 260, 300, 340, 400, 460)
            val rowHeight = 44
            val canvasWidth = 520
            val rows = listOf(Scene(Result.PASSED, false), Scene(Result.PASSED, true))
            val image = BufferedImage(canvasWidth, rowHeight * widths.size * rows.size, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            g.color = Color(0x1E1F22)
            g.fillRect(0, 0, image.width, image.height)
            var y = 0
            for (scene in rows) for (room in widths) {
                val (strip, _) = strip(language, scene)
                layOut(strip, room)
                g.color = Color(0x3C3F41)
                g.drawRect(canvasWidth - room - 8, y + 4, room, rowHeight - 8)
                g.color = Color(0x8C8F96)
                g.drawString("$room", 6, y + 26)
                val x = canvasWidth - 8 - strip.component.width
                val sub = g.create(x, y + 6, strip.component.width, strip.component.height)
                paintTree(strip.component, sub)
                sub.dispose()
                y += rowHeight
            }
            g.dispose()
            ImageIO.write(image, "png", File(outDir, "tool-strip-narrow-${language.id}.png"))
        }
    }

    /** 未挂到窗口上的组件 paint 不会递归排版，这里先把子组件排好再画。 */
    private fun paintTree(component: JComponent, g: java.awt.Graphics) {
        component.doLayout()
        component.paint(g)
    }
}
