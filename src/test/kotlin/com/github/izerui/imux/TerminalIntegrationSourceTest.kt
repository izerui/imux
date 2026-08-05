package com.github.izerui.imux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TerminalIntegrationSourceTest {

    @Test
    fun `只支持 262 并使用官方 detached tab 生命周期`() {
        val buildFile = File("build.gradle.kts").readText()
        val terminalHost = File(
            "src/main/kotlin/com/github/izerui/imux/terminal/TerminalHost.kt",
        ).readText()

        assertTrue("插件最低版本必须是 IDEA 2026.2 / build 262", buildFile.contains("""sinceBuild = "262""""))
        assertTrue(
            "隐藏终端创建后必须通过 detachTab 脱离工具窗口，避免被 backend 持久化恢复",
            terminalHost.contains("manager.detachTab(tab)"),
        )
        assertFalse(
            "不能直接取 createTab().view，否则 backend 会把会话当作可恢复终端标签",
            terminalHost.contains(".createTab()\n            .view"),
        )
    }
}
