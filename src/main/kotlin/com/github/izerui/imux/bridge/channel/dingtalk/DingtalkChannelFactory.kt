package com.github.izerui.imux.bridge.channel.dingtalk

import com.github.izerui.imux.bridge.channel.ChannelConnection
import com.github.izerui.imux.bridge.channel.ChannelFactory
import com.github.izerui.imux.bridge.channel.ChannelKind
import com.github.izerui.imux.bridge.channel.ChannelOnboarding
import com.github.izerui.imux.bridge.core.RobotId
import kotlinx.coroutines.CoroutineScope

object DingtalkChannelFactory : ChannelFactory {
    override val kind: ChannelKind = ChannelKind.DINGTALK

    /** 扫码后要配对码认主，且可能要求组织管理员，说明文字与企业微信不同。 */
    override val scanStepsKey: String = "bridge.add.scan.steps.dingtalk"

    override fun onboarding(): ChannelOnboarding = DingtalkOnboarding()

    override fun connect(
        robotId: RobotId,
        credentials: Map<String, String>,
        scope: CoroutineScope,
    ): ChannelConnection =
        DingtalkConnection(
            robotId = robotId,
            api =
                DingtalkApi(
                    clientId = credentials.getValue(DingtalkCredentialKeys.CLIENT_ID),
                    clientSecret = credentials.getValue(DingtalkCredentialKeys.CLIENT_SECRET),
                ),
            scope = scope,
        )
}
