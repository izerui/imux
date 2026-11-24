package com.github.izerui.imux.terminal

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 新建 / 续聊打开的会话标签放在最后一个，而不是插在当前选中标签的右边。 */
class NewSessionTabAtEndTest {
    private val host = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/TerminalHost.kt").normalized

    @Test
    fun `打开期间设置为真，结束后恢复原值`() {
        var setting = false
        val seen = withTemporarySetting({ setting }, { setting = it }, true) { setting }
        assertTrue("打开标签的那一刻应已切到放在最后", seen)
        assertEquals("打开完要恢复用户原来的设置", false, setting)
    }

    @Test
    fun `打开时抛异常也恢复原值`() {
        var setting = false
        try {
            withTemporarySetting({ setting }, { setting = it }, true) { error("open failed") }
            fail("异常应原样抛出")
        } catch (_: IllegalStateException) {
        }
        assertEquals(false, setting)
    }

    @Test
    fun `用户本来就开着时不写设置`() {
        var writes = 0
        withTemporarySetting({ true }, { writes++ }, true) { }
        assertEquals(0, writes)
    }

    @Test
    fun `前台打开会话标签时临时启用放在最后`() {
        val openSelected = host.substringAfter("private fun openSelected(").substringBefore("private fun openWithoutSelecting(")
        val openLambda = openSelected.substringAfter("open = {")
        assertTrue(
            "前台打开应包在临时设置里",
            openLambda.contains("withTemporarySetting(") &&
                openLambda.contains("ui.openTabsAtTheEnd = it") &&
                openLambda.contains("value = true") &&
                openLambda.contains("manager.openFile(file, true)"),
        )
    }

    @Test
    fun `后台恢复标签不改设置`() {
        val openWithoutSelecting = host.substringAfter("private fun openWithoutSelecting(").substringBefore("internal fun scrollToBottom(")
        assertTrue(!openWithoutSelecting.contains("withTemporarySetting("))
    }
}
