package com.github.izerui.imux.bridge.channel

/** 渠道种类。[id] 写入持久化，不能改名。 */
enum class ChannelKind(val id: String) {
    WECOM("wecom"),
    FEISHU("feishu"),
    DINGTALK("dingtalk"),
    WEIXIN("weixin"),
    ;

    companion object {
        fun of(id: String): ChannelKind? = entries.firstOrNull { it.id == id }
    }
}
