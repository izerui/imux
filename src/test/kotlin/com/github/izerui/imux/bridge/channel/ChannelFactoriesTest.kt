package com.github.izerui.imux.bridge.channel

import com.github.izerui.imux.bridge.channel.feishu.FeishuChannelFactory
import com.github.izerui.imux.bridge.channel.feishu.FeishuConnection
import com.github.izerui.imux.bridge.channel.feishu.FeishuOnboarding
import com.github.izerui.imux.bridge.channel.wecom.WecomChannelFactory
import com.github.izerui.imux.bridge.core.RobotId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelFactoriesTest {
    @Test
    fun `飞书已登记，扫码说明用自己的文案`() {
        assertEquals(FeishuChannelFactory, ChannelFactories.forKind(ChannelKind.FEISHU))
        assertEquals("bridge.add.scan.steps.feishu", FeishuChannelFactory.scanStepsKey)
        assertEquals("bridge.add.scan.steps", WecomChannelFactory.scanStepsKey)
        assertTrue(FeishuChannelFactory.onboarding() is FeishuOnboarding)
    }

    @Test
    fun `按凭据建立飞书连接`() {
        val connection =
            FeishuChannelFactory.connect(
                RobotId("r"),
                mapOf("app_id" to "cli", "app_secret" to "s", "domain" to "lark"),
                CoroutineScope(Dispatchers.Default)
            )
        assertTrue(connection is FeishuConnection)
        assertEquals(RobotId("r"), connection.robotId)
    }
}
