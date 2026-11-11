package com.github.izerui.imux.bridge

import org.junit.Assert.assertTrue
import org.junit.Test

/** 署名之外的手机提示与署名一样，只在打头带 💬，不再给工程、智能体加图标。 */
class PhoneTextIconsTest {
    @Test
    fun `已连接提示只在打头带 💬`() {
        val text = BundleBridgeTexts.connected("maas-api", "Claude Code", "UAT 临时 API key")
        assertTrue(text, text.contains("💬 maas-api · Claude Code · UAT 临时 API key"))
        assertTrue(text, listOf("📂", "✴️").none(text::contains))
    }

    @Test
    fun `已断开、会话已离线提示与已连接同一写法，💬 只出现一次`() {
        val session = sessionLabel("maas-api", "Claude Code", "UAT 临时 API key")
        for (text in listOf(BundleBridgeTexts.released(session), BundleBridgeTexts.suspended(session))) {
            assertTrue(text, text.contains("💬 maas-api · Claude Code · UAT 临时 API key"))
            assertTrue(text, text.split("💬").size == 2)
        }
    }
}
