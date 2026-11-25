package com.github.izerui.imux.peer

import com.github.izerui.imux.session.isPeerFeedbackMessage
import org.junit.Assert.assertTrue

/**
 * 发进主会话（或交给「没送达」兜底）的审查意见都带固定开头。各用例比对的是意见正文：
 * 这里先核对开头确实在，再把正文取出来——于是每条用到它的用例都顺带守住了「发出去的一定带标记」。
 */
internal fun peerBody(sent: String): String {
    assertTrue("发进主会话的审查意见应带固定开头：$sent", isPeerFeedbackMessage(sent))
    return sent.substringAfter("\n\n")
}
