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
}
