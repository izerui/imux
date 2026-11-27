package com.github.izerui.imux.bridge.terminal

import com.github.izerui.imux.bridge.core.CodexReviewMenu
import com.github.izerui.imux.bridge.core.StopResult
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexStopSequenceTest {
    private val preset = CodexReviewMenu("Select a review preset", listOf("base"), 0)
    private val custom = CodexReviewMenu("Custom review instructions", emptyList(), -1)

    @Test
    fun `逐层退出自定义审查和上级菜单后才中断前台并停止后台`() {
        val closed = mutableListOf<CodexReviewMenu>()
        val sequence = CodexStopSequence(true, closed::add)
        val keys = mutableListOf<Pair<CodexReviewMenu?, String>>()
        val screens = listOf(custom, custom, preset, preset, null, null, null)
        val results = screens.map { menu -> sequence.advance(menu) { keys.add(menu to it) } }
        assertEquals(listOf(custom to "\u001b", preset to "\u001b", null to "\u001b", null to "/stop", null to "\r"), keys)
        assertEquals(listOf(custom, preset), closed)
        assertEquals(StopResult.SENT, results.last())
    }

    @Test
    fun `输入后台停止命令之后新出现的菜单不接收回车`() {
        val sequence = CodexStopSequence(false) {}
        val keys = mutableListOf<String>()
        assertEquals(null, sequence.advance(null, keys::add))
        assertEquals(StopResult.NOT_READY, sequence.advance(preset, keys::add))
        assertEquals(listOf("/stop"), keys)
    }

    @Test
    fun `菜单不退出时仅按一次返回并报告停止未生效`() {
        val closed = mutableListOf<CodexReviewMenu>()
        val sequence = CodexStopSequence(false, closed::add)
        val keys = mutableListOf<String>()
        var result: StopResult? = null
        repeat(25) {
            if (result == null) result = sequence.advance(custom, keys::add)
        }
        assertEquals(StopResult.NOT_READY, result)
        assertEquals(listOf("\u001b"), keys)
        assertEquals(emptyList<CodexReviewMenu>(), closed)
    }
}
