package com.github.izerui.imux.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class IdeaMcpSupportTest {
    private fun decide(
        injectEnabled: Boolean = true,
        configuredPort: Int = 64342,
        probe: (Int) -> Boolean = { true },
        shouldNotify: (Int) -> Boolean = { true },
    ) = decideIdeaMcp(injectEnabled, configuredPort, "/workspace", probe, shouldNotify)

    @Test
    fun `端口不可达时仍使用 Imux 设置并提示`() {
        val asked = mutableListOf<Int>()
        val decision = decide(configuredPort = 64355, probe = { asked += it; false })

        assertEquals(
            IdeaMcpDecision.Inject(
                endpoint = IdeaMcpEndpoint(64355, "/workspace"),
                notifyUnavailable = true,
            ),
            decision,
        )
        assertEquals("探测也必须使用 Imux 设置的端口", listOf(64355), asked)
    }

    @Test
    fun `端口可达时使用 Imux 设置且不提示`() {
        assertEquals(
            IdeaMcpDecision.Inject(
                endpoint = IdeaMcpEndpoint(64342, "/workspace"),
                notifyUnavailable = false,
            ),
            decide(probe = { true }),
        )
    }

    /** imux 自己的开关是用户的显式选择：不注入，也不该拿通知去烦他。 */
    @Test
    fun `关掉注入开关时既不注入也不提示`() {
        var notified = false

        var probed = false
        val decision =
            decide(
                injectEnabled = false,
                probe = { probed = true; false },
                shouldNotify = { notified = true; true },
            )

        assertEquals(IdeaMcpDecision.Disabled, decision)
        assertFalse("关闭注入后不该探测端口", probed)
        assertFalse("用户自己关的，不该再弹通知", notified)
    }

    /**
     * 同一次会话启动里几处调用必须拿到**同一个**答案。
     *
     * `createView` 决定注不注入环境变量、`newCommand` / `resumeCommand` 决定加不加 CLI
     * 参数，各问一次。若两次答案不同，拼出来的是「命令行带 `--mcp-config` 但 pi 没有端点」
     * 这类半截状态——而它不报错。断言探测函数的**实际调用次数**，而不是返回值：
     * 两次返回值本来就相同，只看返回值分不清「缓存生效」与「探测了两遍」。
     */
    @Test
    fun `同一端口在缓存期内只探测一次`() {
        var probes = 0
        val probe = IdeaMcpProbe(ttlMillis = 3_000L, now = { 0L }, connect = { probes++; true })

        assertTrue(probe.reachable(64342))
        assertTrue(probe.reachable(64342))

        assertEquals("缓存期内重复探测会让同一次启动的几处调用可能拿到不同答案", 1, probes)
    }

    /** 端口改了就必须重新探测：缓存是按端口的，否则改端口后一直沿用旧答案。 */
    @Test
    fun `换端口立即重新探测`() {
        val asked = mutableListOf<Int>()
        val probe = IdeaMcpProbe(ttlMillis = 3_000L, now = { 0L }, connect = { asked += it; true })

        probe.reachable(64342)
        probe.reachable(64343)

        assertEquals(listOf(64342, 64343), asked)
    }

    /** 缓存过期后重新探测，否则用户在设置里开了 MCP Server 也要等到重启 IDE 才生效。 */
    @Test
    fun `缓存过期后重新探测`() {
        var clock = 0L
        var probes = 0
        val probe = IdeaMcpProbe(ttlMillis = 3_000L, now = { clock }, connect = { probes++; true })

        probe.reachable(64342)
        clock = 3_000L
        probe.reachable(64342)

        assertEquals(2, probes)
    }

    /**
     * 同一端口连续不可达只提示一次；**恢复过一次之后再次不可达要重新提示**。
     *
     * 原实现用一个永不复位的 `AtomicBoolean`：用户照着提示去改端口、又改错了，
     * 之后就再也收不到任何反馈，只能靠「IDE 能力莫名其妙没了」自己猜。
     */
    @Test
    fun `不可达提示按连续不可达去重且恢复后可再次提示`() {
        var clock = 0L
        var reachable = false
        val probe = IdeaMcpProbe(ttlMillis = 1L, now = { clock++ }, connect = { reachable })

        assertFalse(probe.reachable(64342))
        assertTrue("第一次不可达必须提示", probe.shouldNotify(64342))
        assertFalse(probe.reachable(64342))
        assertFalse("连续不可达不该反复提示", probe.shouldNotify(64342))

        reachable = true
        assertTrue(probe.reachable(64342))
        reachable = false
        assertFalse(probe.reachable(64342))
        assertTrue("恢复过之后再次不可达必须重新提示", probe.shouldNotify(64342))
    }

    @Test
    fun `端口有监听时判定 IDEA MCP 可连接`() {
        ServerSocket(0).use { server ->
            assertTrue(canConnectToIdeaMcp(server.localPort))
        }
    }

    @Test
    fun `端口没有监听时判定 IDEA MCP 不可连接`() {
        val port = ServerSocket(0).use { it.localPort }

        assertFalse(canConnectToIdeaMcp(port))
    }
}
