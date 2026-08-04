package com.github.izerui.imux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
}
