package com.github.izerui.imux.bridge.channel.feishu

import com.github.izerui.imux.bridge.channel.ChannelConnection
import com.github.izerui.imux.bridge.channel.ChannelFactory
import com.github.izerui.imux.bridge.channel.ChannelKind
import com.github.izerui.imux.bridge.channel.ChannelOnboarding
import com.github.izerui.imux.bridge.core.RobotId
import kotlinx.coroutines.CoroutineScope

object FeishuChannelFactory : ChannelFactory {
    override val kind: ChannelKind = ChannelKind.FEISHU

    /** 扫码直接认主，不需要配对码，说明文字与企业微信不同。 */
    override val scanStepsKey: String = "bridge.add.scan.steps.feishu"

    override fun onboarding(): ChannelOnboarding = FeishuOnboarding()

    override fun connect(
        robotId: RobotId,
        credentials: Map<String, String>,
        scope: CoroutineScope,
    ): ChannelConnection =
        FeishuConnection(
            robotId = robotId,
            api =
                FeishuApi(
                    appId = credentials.getValue(FeishuCredentialKeys.APP_ID),
                    appSecret = credentials.getValue(FeishuCredentialKeys.APP_SECRET),
                    domain = FeishuDomain.of(credentials[FeishuCredentialKeys.DOMAIN]),
                ),
            scope = scope,
        )
}
