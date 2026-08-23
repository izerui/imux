package com.github.izerui.imux.session

import org.junit.Assert.assertFalse
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
     * 这个 handler 只服务 pi 一条路径。
     *
     * 曾经并列过一条 `/imux/codex-session`（Windows 上 codex 的 hook 上报），
     * 已随整套 hook 机制删除——codex 改读它自己写的运行态 sqlite。
     * 重新认这条路径就意味着上报端点、令牌下发与那个 `.ps1` 又得整套回来。
     */
    @Test
    fun `不再受理已删除的 codex 上报路径`() {
        assertFalse(handlesPiReport("/imux/codex-session", isPost = true))
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
