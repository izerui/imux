package com.github.izerui.imux.code

import com.github.izerui.imux.settings.GuidanceCapability
import com.github.izerui.imux.terminal.TerminalMcpServer
import org.junit.Assert.assertEquals
import org.junit.Test

class CodeToolsLeaseTest {
    private val lease = CodeToolsLease(TerminalMcpServer("imux_code", "http://127.0.0.1:1/x", "IMUX_CODE_TOKEN"), emptyMap(), null) {}

    @Test
    fun `挂上查调用链工具时声明引导词要带上查调用链那部分`() {
        assertEquals(setOf(GuidanceCapability.CODE_TOOLS), lease.guidance)
    }
}
