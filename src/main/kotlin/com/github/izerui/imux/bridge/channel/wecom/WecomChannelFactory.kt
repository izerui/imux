package com.github.izerui.imux.bridge.channel.wecom

import com.github.izerui.imux.bridge.channel.ChannelConnection
import com.github.izerui.imux.bridge.channel.ChannelFactory
import com.github.izerui.imux.bridge.channel.ChannelKind
import com.github.izerui.imux.bridge.channel.ChannelOnboarding
import com.github.izerui.imux.bridge.core.RobotId
import kotlinx.coroutines.CoroutineScope

object WecomChannelFactory : ChannelFactory {
    override val kind: ChannelKind = ChannelKind.WECOM

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
