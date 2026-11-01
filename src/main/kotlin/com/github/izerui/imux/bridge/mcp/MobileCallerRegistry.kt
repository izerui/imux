package com.github.izerui.imux.bridge.mcp

import com.github.izerui.imux.bridge.media.MediaSendCoordinator
import kotlinx.coroutines.CoroutineScope
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class MobileCaller(
    val projectPath: String,
    val tabId: String,
    scope: CoroutineScope,
) : AutoCloseable {
    private val alive = AtomicBoolean(true)
    val active: Boolean get() = alive.get()
    val queue = MediaSendCoordinator(scope)
    override fun close() { if (alive.compareAndSet(true, false)) queue.close() }
}

class MobileCallerRegistry(private val scope: CoroutineScope) : AutoCloseable {
    private val callers = ConcurrentHashMap<String, MobileCaller>()
    fun register(projectPath: String, tabId: String): Pair<String, MobileCaller> {
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val caller = MobileCaller(projectPath, tabId, scope)
        callers[token] = caller
        return token to caller
    }
    fun get(token: String): MobileCaller? = callers[token]?.takeIf { it.active }
    fun revoke(token: String) { callers.remove(token)?.close() }
    fun invalidateRoutes() { callers.values.forEach { it.queue.invalidate() } }
    override fun close() { callers.values.forEach { it.close() }; callers.clear() }
}
