package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.core.SessionTarget
import com.github.izerui.imux.bridge.robot.ChannelKind
import com.github.izerui.imux.bridge.robot.RobotRecord
import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneBannerTest {
    private val robot = RobotRecord("r", "开发助手 1", ChannelKind.WECOM, ownerSenderId = "u", ownerChatId = "u")
    private val binding = Binding(RobotId("r"), SessionTarget("/p", "t", AgentType.CODEX, "s"), 1)

    private fun model(
        enabled: Boolean = true,
        binding: Binding? = this.binding,
        robot: RobotRecord? = this.robot,
        status: ConnectionStatus? = ConnectionStatus.Online,
    ) = phoneBannerModel(enabled, binding, robot, status, channel = { it.id }, statusText = { "S:$it" })

    @Test
    fun `在线时只显示渠道与名称`() {
        assertEquals(PhoneBannerModel("r", "wecom", "开发助手 1", null), model())
    }

    @Test
    fun `不在线时带上状态`() {
        assertEquals("S:Kicked", model(status = ConnectionStatus.Kicked)!!.problem)
    }

    @Test
    fun `状态未知时不视为问题`() {
        assertNull(model(status = null)!!.problem)
    }

    @Test
    fun `未开启、未绑定或机器人不存在时不显示`() {
        assertNull(model(enabled = false))
        assertNull(model(binding = null))
        assertNull(model(robot = null))
    }

    private val label = { channel: String, name: String -> "$channel「$name」" }

    @Test
    fun `宽横幅显示渠道与名称，出问题时追加状态`() {
        assertEquals("wecom「开发助手 1」", phoneBannerText(model()!!, 1100, label))
        assertEquals("wecom「开发助手 1」 · S:Kicked", phoneBannerText(model(status = ConnectionStatus.Kicked)!!, 900, label))
    }

    @Test
    fun `中等宽度只留渠道，出问题时换成状态`() {
        assertEquals("wecom", phoneBannerText(model()!!, 760, label))
        assertEquals("S:Kicked", phoneBannerText(model(status = ConnectionStatus.Kicked)!!, 600, label))
    }

    @Test
    fun `窄横幅只留图标`() {
        assertEquals("", phoneBannerText(model()!!, 599, label))
    }

    @Test
    fun `开关关闭时手机段仍显示为未连接，不会消失`() {
        assertEquals(PhoneSegmentState.Unbound(bridgeEnabled = false), phoneSegmentState(false, null))
        // 即使残留了绑定模型，开关关着也不当作已连接
        assertEquals(PhoneSegmentState.Unbound(bridgeEnabled = false), phoneSegmentState(false, model(enabled = true)))
    }

    @Test
    fun `开关打开时按是否绑定区分未连接与已连接`() {
        assertEquals(PhoneSegmentState.Unbound(bridgeEnabled = true), phoneSegmentState(true, null))
        assertEquals(PhoneSegmentState.Bound(model()!!), phoneSegmentState(true, model()))
    }
}
