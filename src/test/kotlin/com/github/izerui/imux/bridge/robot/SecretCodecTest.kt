package com.github.izerui.imux.bridge.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecretCodecTest {
    @Test
    fun `凭据编码往返`() {
        val values = mapOf("bot_id" to "B1", "secret" to "s\"e:c")
        assertEquals(values, decodeSecret(encodeSecret(values)))
    }

    @Test
    fun `损坏的内容返回 null`() {
        assertNull(decodeSecret("not json"))
    }
}
