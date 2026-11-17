package com.github.izerui.imux.bridge

import com.github.izerui.imux.SourceCode
import com.github.izerui.imux.bridge.core.BindResult
import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.core.BindingRegistry
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.core.SessionTarget
import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 删除机器人原先只改绑定表，不走断开的清理：推给这台手机还没答的确认一直挂着，CLI 等一台已经不存在的手机作答。
 * 清理本身（onReleased 把挂着的确认交还电脑）见 HookPromptRouterTest「解绑时挂起的请求都放行给电脑」。
 */
class BridgeHubUnregisterTest {
    private val robot = RobotId("r1")
    private val target = SessionTarget("/p", "tab-1", AgentType.CLAUDE, "s1")
    private val bindings = BindingRegistry()
    private val released = mutableListOf<Binding>()

    @Test
    fun `删除有绑定的机器人时解绑，并把原绑定交给断开清理`() {
        val binding = (bindings.bind(robot, target) as BindResult.Bound).binding

        releaseDeletedRobot(robot, bindings) { released += it }

        assertNull(bindings.bindingOf(robot))
        assertEquals(listOf(binding), released)
    }

    @Test
    fun `删除没有绑定的机器人时不做清理`() {
        releaseDeletedRobot(robot, bindings) { released += it }

        assertEquals(emptyList<Binding>(), released)
    }

    /** 上面只验了函数；删除入口不接它、或者先清理后摘渠道（会给已删的机器人发「已断开」），都要拦下。 */
    @Test
    fun `删除入口先摘渠道与本人，再经断开清理解绑`() {
        val hub = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/BridgeHub.kt")
        val body = hub.compact(hub.bodyAfter("fun unregisterRobot(robotId: RobotId)", '{'))

        val release = body.indexOf("releaseDeletedRobot(robotId,bindings){released->scope.launch{router.onReleased(released,\"\")}}")
        assertTrue(body, release >= 0)
        assertTrue(body, body.indexOf("channels.remove(robotId)") in 0 until release)
        assertTrue(body, body.indexOf("owners.remove(robotId)") in 0 until release)
    }
}
