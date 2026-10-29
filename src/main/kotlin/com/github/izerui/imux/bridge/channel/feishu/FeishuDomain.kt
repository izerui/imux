package com.github.izerui.imux.bridge.channel.feishu

/** 飞书与 Lark 两套地址；扫码时按返回的 tenant_brand 确定，[id] 写入凭据。 */
enum class FeishuDomain(
    val id: String,
    val accountsBase: String,
    val openBase: String,
) {
    FEISHU("feishu", "https://accounts.feishu.cn", "https://open.feishu.cn"),
    LARK("lark", "https://accounts.larksuite.com", "https://open.larksuite.com"),
    ;

    companion object {
        fun of(id: String?): FeishuDomain = entries.firstOrNull { it.id == id } ?: FEISHU
    }
}

/** 飞书机器人凭据在凭据库里的键名。 */
object FeishuCredentialKeys {
    const val APP_ID = "app_id"
    const val APP_SECRET = "app_secret"
    const val DOMAIN = "domain"
}
