package com.github.izerui.imux.bridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabStateTest {
    private fun PendingReply?.text() = this?.text?.toString()

    @Test
    fun `工具调用之前的文字丢弃，只留最后一次工具调用之后的回复`() {
        val tab = TabState()
        tab.startTurn()
        tab.appendReply(1, "我先看看")
        tab.toolStarted()
        tab.appendReply(1, "改好了")
        tab.appendReply(1, "总结")
        assertEquals("改好了\n\n总结", tab.endByMarker().text())
    }

    @Test
    fun `未结算时不交出回复；结束标记结算后迟到的回复立即交出`() {
        val tab = TabState()
        tab.startTurn()
        tab.appendReply(1, "结论")
        assertNull(tab.takeIfSettled())
        assertEquals("结论", tab.endByMarker().text())
        tab.appendReply(1, "迟到的")
        assertEquals("迟到的", tab.takeIfSettled().text())
    }

    @Test
    fun `IDE 完成信号只结算所属的当前轮，且这一轮没有结束标记`() {
        val tab = TabState()
        tab.startTurn()
        val first = tab.turn
        tab.endByMarker()
        assertFalse("已由结束标记结算", tab.completeByIde(first))
        tab.startTurn()
        assertFalse("信号属于上一轮，迟到作废", tab.completeByIde(first))
        assertTrue(tab.completeByIde(tab.turn))
    }

    @Test
    fun `新一轮开始丢弃没结算的过程说明`() {
        val tab = TabState()
        tab.startTurn()
        tab.appendReply(1, "过程")
        tab.startTurn()
        assertNull(tab.takeReply())
    }

    @Test
    fun `换绑后旧代次攒下的回复作废，从新代次重新开始`() {
        val tab = TabState()
        tab.appendReply(1, "旧")
        tab.appendReply(2, "新")
        val reply = tab.takeReply()
        assertEquals(2L, reply?.epoch)
        assertEquals("新", reply.text())
    }

    @Test
    fun `远程输入按空白归一匹配，消耗一次即失效`() {
        val tab = TabState()
        tab.expectEcho("跑  一下\n测试")
        assertTrue(tab.consumeEcho("跑 一下 测试"))
        assertFalse(tab.consumeEcho("跑 一下 测试"))
        tab.expectEcho("撤回的")
        tab.forgetEcho("撤回的")
        assertFalse(tab.consumeEcho("撤回的"))
    }
}
