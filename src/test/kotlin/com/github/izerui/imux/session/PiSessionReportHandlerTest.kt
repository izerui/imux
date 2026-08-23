package com.github.izerui.imux.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiSessionReportHandlerTest {

    /**
     * 扩展点是全局共享链，`findFirstSafe` 先到先得，而且平台会把上次命中的 handler
     * 缓存在 channel 上优先重试。对不属于自己的 URI 必须老实返回 false，
     * 否则会吞掉别的插件的请求。
     */
    @Test
    fun `只认自己的路径`() {
        assertTrue(handlesPiReport("/imux/pi-session", isPost = true))
        assertTrue("带查询串也要认", handlesPiReport("/imux/pi-session?x=1", isPost = true))

        assertFalse(handlesPiReport("/api/about/", isPost = true))
        assertFalse(handlesPiReport("/imux/pi-session-other", isPost = true))
        assertFalse(handlesPiReport("/", isPost = true))
    }

    /** 平台默认只放行 GET/HEAD，这里必须自己认 POST；反过来也不该受理 GET。 */
    @Test
    fun `只受理 POST`() {
        assertFalse(handlesPiReport("/imux/pi-session", isPost = false))
    }

    /**
     * codex 的上报走一条**并列**的新路径，而不是在 pi 的报文体里加判别字段。
     *
     * `handlesPiReport` 与 `parsePiReport` 都被一整批用例钉住，加判别字段要改它们
     * 全部；并列一条新路径则 pi 那一侧一个字节都不用动。
     */
    @Test
    fun `codex 只认自己的路径`() {
        assertTrue(handlesCodexReport("/imux/codex-session", isPost = true))
        assertTrue("带查询串也要认", handlesCodexReport("/imux/codex-session?x=1", isPost = true))

        assertFalse(handlesCodexReport("/api/about/", isPost = true))
        assertFalse(handlesCodexReport("/imux/codex-session-other", isPost = true))
        assertFalse(handlesCodexReport("/", isPost = true))
    }

    /** 两条路径必须互不相认，否则新增一条等于什么都没隔开。 */
    @Test
    fun `两条上报路径互不相认`() {
        assertFalse(handlesPiReport("/imux/codex-session", isPost = true))
        assertFalse(handlesCodexReport("/imux/pi-session", isPost = true))
    }

    @Test
    fun `codex 也只受理 POST`() {
        assertFalse(handlesCodexReport("/imux/codex-session", isPost = false))
    }

    /**
     * codex 报上来的 cwd 是 Windows 写法（`C:\a\b`），而 `Project.getBasePath()`
     * 标着 `@SystemIndependent`，在 Windows 上返回的是 `C:/a/b`。
     *
     * `piReportBelongsToProject` 做的是精确字符串比较——不归一化分隔符，
     * 这条上报**永远**匹配不上任何项目，被整条丢弃。症状与「Windows 上 codex
     * 漂移探测没做」完全一样，且不报错。
     */
    @Test
    fun `codex 上报的反斜杠 cwd 归一化成 IDE 写法`() {
        val body =
            """{"type":"session_start","tabId":"t1","sessionId":"01a02ac9-401b-7d00-9b38-e4f85392ccfd",""" +
                """"cwd":"C:\\Users\\me\\proj"}"""

        assertEquals("C:/Users/me/proj", parseCodexReport(body, isWindows = true)?.cwd)
    }

    /** POSIX 上的 cwd 里没有反斜杠，归一化必须是恒等变换。 */
    @Test
    fun `POSIX 写法的 cwd 原样通过`() {
        val body =
            """{"type":"session_start","tabId":"t1","sessionId":"01a02ac9-401b-7d00-9b38-e4f85392ccfd",""" +
                """"cwd":"/Users/me/proj"}"""

        assertEquals("/Users/me/proj", parseCodexReport(body, isWindows = false)?.cwd)
        // Windows 上同样恒等：POSIX 写法里没有反斜杠可换
        assertEquals("/Users/me/proj", parseCodexReport(body, isWindows = true)?.cwd)
    }

    /** 报文不合形状时与 pi 一样返回 null：上报来自另一个进程，不能假定它的内容。 */
    @Test
    fun `codex 报文不合形状时返回 null`() {
        assertNull(parseCodexReport("{}", isWindows = true))
        assertNull(parseCodexReport("""{"type":"session_start","tabId":"t1"}""", isWindows = true))
    }

    /**
     * codex 端点与 pi 端点共用端口和令牌，只换路径。
     *
     * 认不出的形状一律返回 null（退回「不上报」），不猜——这条链路的铁律。
     */
    @Test
    fun `codex 端点由 pi 端点派生`() {
        val pi = PiReportEndpoint("http://127.0.0.1:63342/imux/pi-session", "tok-1")

        assertEquals(
            PiReportEndpoint("http://127.0.0.1:63342/imux/codex-session", "tok-1"),
            codexEndpointOf(pi),
        )
        assertNull(codexEndpointOf(null))
        assertNull(
            "认不出的 url 形状一律跳过",
            codexEndpointOf(PiReportEndpoint("http://127.0.0.1:63342/somewhere", "tok-1")),
        )
    }

    /** 正常路径：令牌一字不差才算通过。 */
    @Test
    fun `令牌完全一致才放行`() {
        assertTrue(piReportTokenMatches("s3cr3t-token", "s3cr3t-token"))
    }

    /**
     * header 缺失时 `headers().get()` 返回 null，必须拒绝。
     *
     * 如果实现把 null 归一化后再比（`actual.orEmpty() == expected`）而 expected
     * 又恰好是空串，不带任何凭据的请求就能进来。这条钉住 null 分支本身。
     */
    @Test
    fun `缺少令牌 header 时拒绝`() {
        assertFalse("不带 x-imux-token 的请求必须被拒", piReportTokenMatches(null, "s3cr3t-token"))
        assertFalse(
            "即使期望值为空串，缺少 header 也不能放行",
            piReportTokenMatches(null, ""),
        )
    }

    /**
     * 空串必须拒绝。
     *
     * 如果实现写成 `expected.startsWith(actual)` 或 `actual.isNullOrEmpty() || ...`
     * 这类宽松比较，空令牌会被判为通过——等于完全没有门禁，这条会失败。
     */
    @Test
    fun `空令牌被拒绝`() {
        assertFalse("空令牌等于没有门禁", piReportTokenMatches("", "s3cr3t-token"))
    }

    /**
     * 令牌是随机串，比较必须区分大小写。
     *
     * 如果实现误用 `equals(other, ignoreCase = true)`，这条会失败。
     * 大小写不敏感会让暴力搜索空间大幅缩小。
     */
    @Test
    fun `令牌大小写敏感`() {
        assertFalse(
            "令牌比较不能忽略大小写",
            piReportTokenMatches("S3CR3T-TOKEN", "s3cr3t-token"),
        )
    }

    /**
     * 前缀、后缀被截断或多出内容都必须拒绝。
     *
     * 如果实现误用 `startsWith` / `endsWith` / `contains` 做匹配，
     * 攻击者就能逐字符试探出完整令牌，对应的断言会失败。
     */
    @Test
    fun `令牌被截断或多出内容时拒绝`() {
        assertFalse("前缀不算匹配", piReportTokenMatches("s3cr3t", "s3cr3t-token"))
        assertFalse("后缀不算匹配", piReportTokenMatches("token", "s3cr3t-token"))
        assertFalse(
            "多出后缀也不算匹配",
            piReportTokenMatches("s3cr3t-token-extra", "s3cr3t-token"),
        )
    }
}
