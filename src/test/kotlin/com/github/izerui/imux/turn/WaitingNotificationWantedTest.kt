package com.github.izerui.imux.turn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 等待提醒的抑制规则，与「轮次完成」刻意不同。
 *
 * 完成时即使用户正看着该标签页也要提醒——tab 选中不等于人在屏幕前。
 * 但等待不一样：CLI 的选择框就占在屏幕上，再弹一个 IDE 气泡是重复告知。
 * 它顺带压掉了连环气泡：一轮里连续几次权限确认会产生
 * `waiting -> busy -> waiting`，用户正逐个确认，不该每次都被打扰。
 */
class WaitingNotificationWantedTest {

    @Test
    fun `正在查看该会话时不弹气泡`() {
        assertFalse(waitingNotificationWanted("s1", selectedSessionKeys = setOf("s1")))
    }

    @Test
    fun `看着别的会话时要弹`() {
        assertTrue(waitingNotificationWanted("s1", selectedSessionKeys = setOf("s2")))
    }

    @Test
    fun `没有打开任何会话标签页时要弹`() {
        assertTrue(waitingNotificationWanted("s1", selectedSessionKeys = emptySet()))
    }

    /** 分屏时多个标签页同时处于选中态，只要其中之一是它就算正在看。 */
    @Test
    fun `分屏中该会话是选中之一时不弹`() {
        assertFalse(waitingNotificationWanted("s1", selectedSessionKeys = setOf("s2", "s1")))
    }
}
