package com.github.izerui.imux.bridge.channel.weixin

import com.github.izerui.imux.bridge.channel.ChannelConnection
import com.github.izerui.imux.bridge.channel.ChannelFactory
import com.github.izerui.imux.bridge.channel.ChannelKind
import com.github.izerui.imux.bridge.channel.ChannelOnboarding
import com.github.izerui.imux.bridge.core.RobotId
import kotlinx.coroutines.CoroutineScope

object WeixinChannelFactory : ChannelFactory {
    override val kind: ChannelKind = ChannelKind.WEIXIN

    /** 扫码人即本人，不需要配对码，说明文字与企业微信不同。 */
    override val scanStepsKey: String = "bridge.add.scan.steps.weixin"

    override fun onboarding(): ChannelOnboarding = WeixinOnboarding()

    override fun connect(
        robotId: RobotId,
        credentials: Map<String, String>,
        scope: CoroutineScope,
    ): ChannelConnection =
        WeixinConnection(
            robotId = robotId,
            api = WeixinApi(),
            baseUrl = WeixinApi.normalizeBaseUrl(credentials[WeixinCredentialKeys.BASE_URL]),
            token = credentials.getValue(WeixinCredentialKeys.BOT_TOKEN),
            scope = scope,
        )
}
