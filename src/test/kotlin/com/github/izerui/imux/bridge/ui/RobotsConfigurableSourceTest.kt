package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RobotsConfigurableSourceTest {
    private val source = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/ui/RobotsConfigurable.kt")

    @Test
    fun `收发受阻时设置页重新连接按钮禁用`() {
        val createPanelBody = source.compact(source.bodyAfter("override fun createPanel()", '{'))
        // 重连按钮必须存在
        assertTrue("createPanel 必须包含重连动作", createPanelBody.contains("bridge.settings.reconnect"))
        // 重连按钮的启用逻辑必须受 SendFailing 条件约束
        assertFalse(
            "SendFailing 时不应无条件启用重新连接——必须按状态区分",
            createPanelBody.contains("bridge.settings.reconnect") && !createPanelBody.contains("SendFailing"),
        )
    }
}
