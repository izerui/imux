package com.github.izerui.imux.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 上报端点的计算不能落在 EDT 上。
 *
 * `BuiltInServerManager.waitForStart()` 会**阻塞**到内置 HTTP 服务起来，而唯一的
 * 调用点 `TerminalHost.createView` 走在 `open()` 的 EDT 路径上。绝大多数时候它立即
 * 返回，因此这个缺陷在日常使用中看不出来，只有 IDE 刚启动就点开 pi 会话时才卡——
 * 属于「测不到就一定会被改回去」的那类约束，只能锁住源码形态。
 */
class PiReportEndpointCachingTest {

    private val endpoint =
        File("src/main/kotlin/com/github/izerui/imux/session/PiReportEndpoint.kt").readText()

    @Test
    fun `阻塞的启动等待只出现在后台协程里`() {
        assertTrue(
            "端点必须缓存，不能每次开标签页都现算",
            endpoint.contains("@Volatile") && endpoint.contains("private var cached: PiReportEndpoint?"),
        )
        assertTrue(
            "缓存的计算本身也不能阻塞调用它的线程（服务可能在 EDT 上被首次实例化）",
            endpoint.contains("scope.async(Dispatchers.IO)") &&
                endpoint.contains("compute().also { cached = it }"),
        )
        assertTrue(
            "waitForStart 只允许出现在后台计算里",
            endpoint.contains("waitForStart()"),
        )
    }

    @Test
    fun `读取端点绝不等待`() {
        assertTrue(
            "读取必须是纯字段读，任何形式的等待都会把阻塞搬回 EDT",
            Regex("""fun endpoint\(\): PiReportEndpoint\? = cached""").containsMatchIn(endpoint),
        )
        assertFalse(endpoint.contains("fun endpoint(): PiReportEndpoint? = computation.await()"))
    }

    @Test
    fun `current 只取缓存`() {
        assertTrue(
            "TerminalHost 在 EDT 上调 current()，它必须是取现成的",
            Regex("""fun current\(\): PiReportEndpoint\? =\s*\n?\s*ApplicationManager[\s\S]{0,120}?\.endpoint\(\)""")
                .containsMatchIn(endpoint),
        )
    }

    @Test
    fun `启动时预热缓存`() {
        val monitor = File(
            "src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt",
        ).readText()

        assertTrue(
            "服务是懒加载的：不主动碰一下，它要等第一次 current() 才实例化，" +
                "而那时已经在 EDT 上，等于把整段延迟原样搬到用户点击的那一刻",
            monitor.contains("PiReportEndpointCache.warmUp()"),
        )
    }

    @Test
    fun `恢复 pi 标签前等待同一份后台计算`() {
        val monitor = File(
            "src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt",
        ).readText()

        assertTrue(endpoint.contains("private val computation: Deferred<PiReportEndpoint?>"))
        assertTrue(endpoint.contains("awaitEndpoint(): PiReportEndpoint? = computation.await()"))
        assertTrue(monitor.contains("saved.any { it.agentId == AgentType.PI.cli }"))
        assertTrue(monitor.contains("PiReportEndpointCache.awaitReady()"))
    }
}
