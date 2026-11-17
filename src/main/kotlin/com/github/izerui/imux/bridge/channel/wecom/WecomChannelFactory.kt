package com.github.izerui.imux.bridge.channel.wecom

import com.github.izerui.imux.bridge.channel.ChannelConnection
import com.github.izerui.imux.bridge.channel.ChannelFactory
import com.github.izerui.imux.bridge.channel.ChannelKind
import com.github.izerui.imux.bridge.channel.ChannelOnboarding
import com.github.izerui.imux.bridge.core.RobotId
import kotlinx.coroutines.CoroutineScope

object WecomChannelFactory : ChannelFactory {
    override val kind: ChannelKind = ChannelKind.WECOM

    /** 步骤后附建议：自建一个只有自己的组织再扫码，免得公司组织的管理员策略拦下创建（见 docs/channel-comparison.md 权限前提）。 */
    override val scanStepsKey: String = "bridge.add.scan.steps.wecom"

    override fun onboarding(): ChannelOnboarding = WecomOnboarding()

    override fun connect(
        robotId: RobotId,
        credentials: Map<String, String>,
        scope: CoroutineScope,
    ): ChannelConnection =
        WecomConnection(
            robotId = robotId,
            botId = credentials.getValue("bot_id"),
            secret = credentials.getValue("secret"),
            scope = scope,
        )
}
