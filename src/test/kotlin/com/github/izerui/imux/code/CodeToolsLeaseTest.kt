package com.github.izerui.imux.code

import com.github.izerui.imux.settings.GuidanceCapability
import com.github.izerui.imux.settings.PluginLanguage
import com.github.izerui.imux.terminal.TerminalMcpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeToolsLeaseTest {
    private val lease = CodeToolsLease(TerminalMcpServer("imux_code", "http://127.0.0.1:1/x", "IMUX_CODE_TOKEN"), emptyMap(), null) {}

    @Test
    fun `挂上查调用链工具时声明引导词要带上查调用链那部分`() {
        assertEquals(setOf(GuidanceCapability.CODE_TOOLS), lease.guidance)
    }

    @Test
    fun `审查要求由工具自己提供，中文和英文都写明三个工具且只在有这些工具时才用`() {
        val zh = CodeToolsLease.reviewHint(PluginLanguage.SIMPLIFIED_CHINESE)
        assertTrue(zh.contains("如果你有 `imux_code` 的工具"))
        assertTrue(zh.contains("`call_hierarchy`") && zh.contains("`find_references`") && zh.contains("`type_hierarchy`"))
        val tw = CodeToolsLease.reviewHint(PluginLanguage.TRADITIONAL_CHINESE)
        assertEquals(zh, tw)
        val en = CodeToolsLease.reviewHint(PluginLanguage.ENGLISH)
        assertTrue(en.contains("you have the `imux_code` tools"))
        assertTrue(en.contains("`call_hierarchy`") && en.contains("`find_references`") && en.contains("`type_hierarchy`"))
    }
}
