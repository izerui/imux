package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhoneStatusWidgetTest {
    private fun text(
        enabled: Boolean,
        robots: Int,
        bound: Int,
    ) = statusBarText(enabled, robots, bound, connected = { "C$it" }, idle = "IDLE")

    @Test
    fun `关闭或没有机器人时不显示`() {
        assertEquals("", text(enabled = false, robots = 2, bound = 1))
        assertEquals("", text(enabled = true, robots = 0, bound = 0))
    }

    @Test
    fun `显示本项目连接数或空闲`() {
        assertEquals("C2", text(enabled = true, robots = 3, bound = 2))
        assertEquals("IDLE", text(enabled = true, robots = 1, bound = 0))
    }

    @Test
    fun `连着会话的机器人有异常时先报异常`() {
        assertEquals("BAD", statusBarText(true, 2, 1, connected = { "C$it" }, idle = "IDLE", problems = 1, problem = "BAD"))
    }

    @Test
    fun `注册了状态栏组件并在装饰变化时刷新`() {
        val xml = File("src/main/resources/META-INF/plugin.xml").readText()
        assertTrue(xml.contains("com.github.izerui.imux.bridge.ui.PhoneStatusWidgetFactory"))
        val code = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/ui/PhoneStatusWidgetFactory.kt").normalized
        assertTrue(code.contains("SessionDecorationsListener.TOPIC"))
        assertTrue(code.contains("updateWidget("))
    }
}
