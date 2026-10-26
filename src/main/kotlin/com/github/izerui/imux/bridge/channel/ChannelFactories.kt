package com.github.izerui.imux.bridge.channel

import com.github.izerui.imux.bridge.channel.wecom.WecomChannelFactory
import com.github.izerui.imux.bridge.robot.ChannelKind

/** 已实现的渠道。新增渠道只需在这里登记一个工厂。 */
object ChannelFactories {
    fun all(): List<ChannelFactory> = listOf(WecomChannelFactory)

    fun forKind(kind: ChannelKind): ChannelFactory? = all().firstOrNull { it.kind == kind }
}
