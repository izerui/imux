package com.github.izerui.imux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 守住「只画了一份图标，忘了深色主题那份」这个坑。
 *
 * 真实故障：工具窗口条上 imux 的图标明显比旁边的平台图标暗一档。
 * 平台按 `<名字>_dark.svg` 的约定自动挑选，缺了这份就只能拿浅色那份顶上，
 * 而浅色主色（#6C707E）放在深色背景上就是发灰。
 *
 * 编译器查不出、单元测试也碰不到，只能断言资源本身。
 */
class IconResourceTest {

    /** 新 UI 图标的主色，取自平台 expui 图标集里出现次数最多的那个。 */
    private val lightColor = "#6C707E"
    private val darkColor = "#CED0D6"

    private fun icon(name: String): String {
        val file = File("src/main/resources/icons/$name")
        assertTrue("缺少图标资源：${file.absolutePath}", file.exists())
        return file.readText()
    }

    private fun rasterIcon(name: String): BufferedImage {
        val file = File("src/main/resources/icons/$name")
        assertTrue("缺少图标资源：${file.absolutePath}", file.exists())
        return ImageIO.read(file)
    }

    @Test
    fun `工具窗口图标提供了深色主题变体`() {
        val light = icon("agent.svg")
        val dark = icon("agent_dark.svg")

        assertTrue("浅色图标应使用新 UI 的浅色主色 $lightColor", light.contains(lightColor))
        assertTrue("深色图标应使用新 UI 的深色主色 $darkColor", dark.contains(darkColor))
        assertTrue("深色图标里不该残留浅色主色", !dark.contains(lightColor))
    }

    @Test
    fun `两份图标形状一致，只有颜色不同`() {
        // 只换色、不改形，否则切换主题时图标会变样
        val light = icon("agent.svg")
        val dark = icon("agent_dark.svg")

        assertEquals(light.replace(lightColor, "COLOR"), dark.replace(darkColor, "COLOR"))
    }

    @Test
    fun `Agent 标签图标提供标准与高分辨率资源`() {
        listOf("codex.png", "codex_dark.png", "claude.png", "pi.png", "pi_dark.png").forEach { name ->
            val image = rasterIcon(name)
            assertEquals("$name 宽度错误", 16, image.width)
            assertEquals("$name 高度错误", 16, image.height)
        }
        listOf("codex@2x.png", "codex@2x_dark.png", "claude@2x.png", "pi@2x.png", "pi@2x_dark.png").forEach { name ->

            val image = rasterIcon(name)
            assertEquals("$name 宽度错误", 32, image.width)
            assertEquals("$name 高度错误", 32, image.height)
        }
    }

    @Test
    fun `Codex 图标透明且按主题切换线条颜色`() {
        val light = rasterIcon("codex.png")
        val dark = rasterIcon("codex_dark.png")

        assertEquals("Codex 图标四角必须透明", 0, light.getRGB(0, 0).ushr(24))
        assertEquals("深色 Codex 图标四角必须透明", 0, dark.getRGB(0, 0).ushr(24))
        assertNotEquals(
            "浅色与深色 Codex 图标不能完全相同",
            light.getRGB(8, 2),
            dark.getRGB(8, 2),
        )
    }

    @Test
    fun `Pi 图标透明且按主题切换填充色`() {
        val light = rasterIcon("pi.png")
        val dark = rasterIcon("pi_dark.png")

        assertEquals("Pi 图标四角必须透明", 0, light.getRGB(0, 0).ushr(24))
        assertEquals("深色 Pi 图标四角必须透明", 0, dark.getRGB(0, 0).ushr(24))
        assertNotEquals(
            "浅色与深色 Pi 图标不能完全相同",
            light.getRGB(4, 4),
            dark.getRGB(4, 4),
        )
    }

    /**
     * 忙碌时品牌图标要原地转一整圈（见 AgentIcons.spinning），转到 45° 时占用的是
     * 图案的**外接圆**。pi 的图案是直角阶梯，四角离中心最远——画得太满，转起来就削角。
     *
     * 外接圆直径 = 图案边长 × √2，要 ≤ 16px，图案边长就不能超过 11px。
     *
     * 量的是实心部分（不透明度 ≥ 200），不含抗锯齿羽化出来的那圈半透明像素：
     * 羽化边缘转出去削掉半个像素肉眼无感，实心笔画被切才看得出来。
     */
    @Test
    fun `Pi 图标留出旋转所需的余量`() {
        val image = rasterIcon("pi.png")

        var minX = image.width
        var minY = image.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (image.getRGB(x, y).ushr(24) < 200) continue
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
        }

        val width = maxX - minX + 1
        val height = maxY - minY + 1
        assertTrue("图案宽 $width px，旋转到 45° 会被裁角", width <= 11)
        assertTrue("图案高 $height px，旋转到 45° 会被裁角", height <= 11)
        // 也不能小得没边——太小的话与 claude、codex 摆在一起会明显轻一档
        assertTrue("图案宽 $width px，比另外两个 agent 的图标小太多", width >= 9)
    }

    @Test
    fun `Claude 图标保留透明圆角`() {
        val image = rasterIcon("claude.png")

        assertEquals("Claude 图标四角必须透明", 0, image.getRGB(0, 0).ushr(24))
    }
}
