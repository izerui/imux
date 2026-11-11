package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertTrue
import org.junit.Test

class RobotsConfigurableSourceTest {
    private val source = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/ui/RobotsConfigurable.kt")

    @Test
    fun `管理页向行模型传入实际会话可用性查询`() {
        val refresh = source.compact(source.bodyAfter("private fun refresh()", '{'))
        assertTrue(refresh.contains("sessionAvailable=::bindingSessionAvailable"))
    }

    @Test
    fun `重新连接按钮按状态启用`() {
        val createPanelBody = source.compact(source.bodyAfter("override fun createPanel()", '{'))
        assertTrue("createPanel 必须包含重连动作", createPanelBody.contains("bridge.settings.reconnect"))
        // 哪些状态可以重连见 RobotRowsTest 的 canReconnect 用例
        assertTrue(createPanelBody.contains("canReconnect(RobotManager.getInstance().statusOf(row.id))"))
    }
}
