package com.github.liuyuhua.imux.toolwindow

import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AgentToolWindowRegistrationTest : BasePlatformTestCase() {

    fun testToolWindowIsRegisteredOnLeftAnchor() {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("imux")
        assertNotNull("工具窗口 imux 未注册", toolWindow)
        assertEquals(ToolWindowAnchor.LEFT, toolWindow!!.anchor)
    }
}
