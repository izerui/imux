package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneToolSourceTest {
    private val phoneTool = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/ui/PhoneTool.kt")

    @Test
    fun `收发受阻时手机按钮菜单不提供重新连接`() {
        val showMenuBody = phoneTool.compact(phoneTool.bodyAfter("private fun showMenu(", '{'))
        // 菜单里必须有重连动作（按条件出现）
        assertTrue("showMenu 必须包含重连动作", showMenuBody.contains("bridge.settings.reconnect"))
        // 重连必须受 SendFailing 条件约束
        assertFalse(
            "SendFailing 时不应无条件提供重新连接——showMenu 必须按 problemKind 区分",
            showMenuBody.contains("bridge.settings.reconnect") && !showMenuBody.contains("SendFailing"),
        )
    }

    @Test
    fun `收发受阻时提示条不提供重新连接，只保留换绑`() {
        val refreshBody = phoneTool.compact(phoneTool.bodyAfter("private fun refresh()", '{'))
        // 换绑始终可用
        assertTrue("Problem 提示条必须包含换绑动作", refreshBody.contains("bridge.banner.rebind"))
        // 收发受阻时不应提供重新连接：需要根据 problemKind 条件判断
        // 重连动作必须存在（按条件出现），避免被移走时测试空过
        assertTrue("refresh 函数必须包含重连动作", refreshBody.contains("bridge.settings.reconnect"))
        assertFalse(
            "SendFailing 时不应无条件提供重新连接——必须按 problemKind 区分",
            // refresh 函数里出现了 reconnect 但没有按 SendFailing 条件判断
            refreshBody.contains("bridge.settings.reconnect") && !refreshBody.contains("SendFailing"),
        )
    }
}
