package com.github.izerui.imux.bridge.channel.wecom

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertTrue
import org.junit.Test

class WecomConnectionSourceTest {
    private val code = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/channel/wecom/WecomConnection.kt")

    @Test
    fun `发布会话前确认本次运行仍然有效`() {
        assertTrue(code.compact(code.normalized).contains("coroutineContext.ensureActive()this@WecomConnection.session=state"))
    }
}
