package com.github.izerui.imux.bridge.channel

import com.github.izerui.imux.bridge.ui.DriverState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialMaskingTest {
    private val step = OnboardingStep.Succeeded(mapOf("botId" to "BOT-123", "secret" to "S3CRET"), "owner-1", "My Bot")

    @Test
    fun `接入成功的 toString 不暴露凭据`() {
        val text = step.toString()
        assertFalse(text.contains("BOT-123"))
        assertFalse(text.contains("S3CRET"))
        assertTrue(text.contains("botId"))
        assertTrue(text.contains("owner-1"))
    }

    @Test
    fun `驱动完成状态的 toString 不暴露凭据`() {
        val text = DriverState.Done(step).toString()
        assertFalse(text.contains("BOT-123"))
        assertFalse(text.contains("S3CRET"))
        assertTrue(text.startsWith("Done("))
    }
}
