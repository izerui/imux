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
    fun `已断开提示里会话标题带图标`() {
        val text = BundleBridgeTexts.released("UAT 临时 API key")
        assertTrue(text, text.contains("💬 UAT 临时 API key"))
    }
}
