package com.github.izerui.imux.mcp

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

private val random = SecureRandom()

/** 插件内置 MCP 服务按终端发放的凭证：32 字节随机数，URL 安全的 Base64（不带填充）。 */
internal fun newMcpToken(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

/**
 * 凭证 → 该终端的服务实例，`imux_code` 和 `imux_session` 共用。凭证的生成（[newMcpToken]）三个内置服务
 * 都走这里，长度、编码要改时只改一处；`imux_mobile` 的登记表另有一份，因为它撤销时还要关掉发送队列、
 * 查找时要跳过已关闭的调用方（见 `MobileCallerRegistry`），改撤销语义时那边要一起看。
 */
class McpTokenRegistry<T : Any> {
    private val entries = ConcurrentHashMap<String, T>()

    /** 发放新凭证并登记 [value]，返回凭证。 */
    fun register(value: T): String = newMcpToken().also { entries[it] = value }

    operator fun get(token: String): T? = entries[token]

    fun revoke(token: String) { entries.remove(token) }

    fun clear() = entries.clear()
}
