package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.bridge.channel.ChannelKind
import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.core.SessionTarget
import com.github.izerui.imux.bridge.robot.RobotRecord
import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneToolTest {
    private val robot = RobotRecord(RobotId("r"), "开发助手 1", ChannelKind.WECOM, ownerSenderId = "u", ownerChatId = "u")
    private val binding = Binding(RobotId("r"), SessionTarget("/p", "t", AgentType.CODEX, "s"), 1)

    private fun model(
        enabled: Boolean = true,
        binding: Binding? = this.binding,
        robot: RobotRecord? = this.robot,
        status: ConnectionStatus? = ConnectionStatus.Online,
    ) = phoneBannerModel(enabled, binding, robot, status, channel = { it.id }, statusText = { "S:$it" })

    @Test
    fun `在线时只显示渠道与名称`() {
        assertEquals(PhoneBannerModel(RobotId("r"), "wecom", "开发助手 1", null), model())
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

    @Test
    fun `问题提示的身份只看问题种类，不随原因文字变化`() {
        val first = model(status = ConnectionStatus.Retrying("timeout"))!!
        val second = model(status = ConnectionStatus.Retrying("connection reset"))!!
        assertEquals(first.problemKind, second.problemKind)
        assertTrue(first.problemKind != model(status = ConnectionStatus.Kicked)!!.problemKind)
        assertNull("在线时没有问题种类", model()!!.problemKind)
    }

    @Test
    fun `连接中单独标出，不算问题`() {
        val connecting = model(status = ConnectionStatus.Connecting)!!
        assertTrue(connecting.connecting)
        assertNull(connecting.problem)
    }

    @Test
    fun `顶部提示按连接中、出问题、在线、未连接区分`() {
        assertEquals(PhoneNoticeState.None, phoneNoticeState(phoneSegmentState(true, null)))
        assertTrue(phoneNoticeState(phoneSegmentState(true, model(status = ConnectionStatus.Connecting))) is PhoneNoticeState.Connecting)
        assertEquals(
            PhoneNoticeState.Problem(model(status = ConnectionStatus.Kicked)!!, "S:Kicked"),
            phoneNoticeState(phoneSegmentState(true, model(status = ConnectionStatus.Kicked))),
        )
        assertEquals(PhoneNoticeState.Online(model()!!), phoneNoticeState(phoneSegmentState(true, model())))
    }

    @Test
    fun `刚连上、重连成功、换了机器人时提示一次`() {
        val online = PhoneNoticeState.Online(model()!!)
        assertTrue("刚连上", phoneJustConnected(PhoneNoticeState.None, online))
        assertTrue("重连成功", phoneJustConnected(PhoneNoticeState.Connecting(model(status = ConnectionStatus.Connecting)!!), online))
        assertTrue("断线后恢复", phoneJustConnected(PhoneNoticeState.Problem(model(status = ConnectionStatus.Kicked)!!, "S:Kicked"), online))
        val other = PhoneNoticeState.Online(model(robot = robot.copy(id = RobotId("r2")))!!)
        assertTrue("换了机器人", phoneJustConnected(online, other))
    }

    @Test
    fun `打开标签页时已在线、一直在线、未连上时不提示`() {
        val online = PhoneNoticeState.Online(model()!!)
        assertFalse("打开标签页时已在线", phoneJustConnected(null, online))
        assertFalse("一直在线", phoneJustConnected(online, online))
        assertFalse("未连上", phoneJustConnected(PhoneNoticeState.None, PhoneNoticeState.None))
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
