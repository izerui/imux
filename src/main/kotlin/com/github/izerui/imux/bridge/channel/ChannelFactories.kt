package com.github.izerui.imux.bridge.channel

import com.github.izerui.imux.bridge.channel.feishu.FeishuChannelFactory
import com.github.izerui.imux.bridge.channel.wecom.WecomChannelFactory

/** 已实现的渠道。新增渠道只需在这里登记一个工厂。 */
object ChannelFactories {
    fun all(): List<ChannelFactory> = listOf(WecomChannelFactory, FeishuChannelFactory)

    fun forKind(kind: ChannelKind): ChannelFactory? = all().firstOrNull { it.kind == kind }
}
