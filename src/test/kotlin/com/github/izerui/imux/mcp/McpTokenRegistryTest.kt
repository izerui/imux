package com.github.izerui.imux.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpTokenRegistryTest {
    @Test
    fun `凭证是 32 字节随机数的 URL 安全 Base64，每次都不一样`() {
        val tokens = List(50) { newMcpToken() }
        tokens.forEach { assertTrue(it, it.matches(Regex("[A-Za-z0-9_-]{43}"))) }
        assertEquals(50, tokens.toSet().size)
    }

    @Test
    fun `登记后能按凭证取回，撤销后取不到，别的凭证不受影响`() {
        val registry = McpTokenRegistry<String>()
        val a = registry.register("a")
        val b = registry.register("b")
        assertNotEquals(a, b)
        assertEquals("a", registry[a])
        registry.revoke(a)
        assertNull(registry[a])
        assertEquals("b", registry[b])
    }

    @Test
    fun `清空后所有凭证都取不到`() {
        val registry = McpTokenRegistry<String>()
        val a = registry.register("a")
        val b = registry.register("b")
        registry.clear()
        assertNull(registry[a])
        assertNull(registry[b])
    }
}
