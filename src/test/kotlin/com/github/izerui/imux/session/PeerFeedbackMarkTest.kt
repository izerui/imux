package com.github.izerui.imux.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerFeedbackMarkTest {
    @Test
    fun `中文和英文的审查意见都只以一个短标记起头，正文原样跟在后面`() {
        assertEquals("【副驾驶审查意见】\n\n这里空列表会炸", peerFeedbackMessage("这里空列表会炸", chinese = true))
        assertEquals("[Copilot review]\n\nempty list crashes", peerFeedbackMessage("empty list crashes", chinese = false))
    }

    /** 早先的开头后面还带一段说明，旧会话记录里留着那种写法，标题、报告、副驾驶照样要认得出。 */
    @Test
    fun `早先带说明的中文和英文开头也都认得出`() {
        assertTrue(isPeerFeedbackMessage("【副驾驶审查意见｜不是用户的要求：先核实再处理，与用户已定的事冲突时以用户为准】\n\nx"))
        assertTrue(isPeerFeedbackMessage("[Copilot review — not a user request: verify before acting]\n\nx"))
    }

    @Test
    fun `两种开头都认得出，用户自己的话认不成审查意见`() {
        assertTrue(isPeerFeedbackMessage(peerFeedbackMessage("x", chinese = true)))
        assertTrue(isPeerFeedbackMessage(peerFeedbackMessage("x", chinese = false)))
        assertTrue("记录里前面带空白也认", isPeerFeedbackMessage("  \n【副驾驶审查意见｜…】\n\nx"))
        assertTrue("被截断到只剩开头也认", isPeerFeedbackMessage("[Copilot review — not a user"))
        assertFalse(isPeerFeedbackMessage("帮我看看副驾驶审查意见对不对"))
        assertFalse(isPeerFeedbackMessage("please do a Copilot review"))
        assertFalse(isPeerFeedbackMessage(""))
    }

    /** 开头会显示在终端里，也会作为「电脑端输入」转到手机上：只能占一行。 */
    @Test
    fun `中文和英文的开头都只有一行`() {
        assertEquals(1, peerFeedbackMessage("x", chinese = true).substringBefore("\n\n").lines().size)
        assertEquals(1, peerFeedbackMessage("x", chinese = false).substringBefore("\n\n").lines().size)
    }
}
