package com.github.izerui.imux.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PiSessionReportTest {

    @Test
    fun `解析会话切换上报体`() {
        val body =
            """{"type":"session_start","tabId":"imux-1","sessionId":"team.custom_1","cwd":"/Users/demo/proj"}"""

        assertEquals(
            PiSessionReport(
                PiReportType.SESSION_START,
                "imux-1",
                "team.custom_1",
                "/Users/demo/proj",
            ),
            parsePiReport(body),
        )
    }

    @Test
    fun `解析自动重试后的最终失败`() {
        val body =
            """{"type":"agent_settled","tabId":"imux-1","sessionId":"abc-123","cwd":"/Users/demo/proj","stopReason":"error","messageId":"msg-1"}"""

        assertEquals(
            PiSessionReport(
                PiReportType.AGENT_SETTLED,
                "imux-1",
                "abc-123",
                "/Users/demo/proj",
                PiStopReason.ERROR,
                "msg-1",
            ),
            parsePiReport(body),
        )
    }

    /** 上报来自另一个进程，任何字段都不能假定存在——损坏的报文只该被丢弃。 */
    @Test
    fun `缺字段或损坏的上报体解析为 null`() {
        assertNull(parsePiReport(""))
        assertNull(parsePiReport("这不是 json"))
        assertNull(parsePiReport("""{"tabId":"imux-1","sessionId":"abc","cwd":"/tmp"}"""))
        assertNull(parsePiReport("""{"type":"session_start","sessionId":"abc","cwd":"/tmp"}"""))
        assertNull(parsePiReport("""{"type":"session_start","tabId":"","sessionId":"abc","cwd":"/tmp"}"""))
        assertNull(parsePiReport("""{"type":"session_start","tabId":"imux-1","sessionId":"abc","cwd":""}"""))
    }

    @Test
    fun `会话 id 只接受 pi 官方允许的字符与边界`() {
        fun report(id: String) =
            """{"type":"session_start","tabId":"imux-1","sessionId":"$id","cwd":"/tmp"}"""

        assertEquals("a", parsePiReport(report("a"))?.sessionId)
        assertEquals("A0._-z", parsePiReport(report("A0._-z"))?.sessionId)
        assertNull(parsePiReport(report("-abc")))
        assertNull(parsePiReport(report("abc_")))
        assertNull(parsePiReport(report("a/b")))
        assertNull(parsePiReport(report("中文")))
    }

    @Test
    fun `settled 只接受 error 或 length`() {
        fun report(reason: String) =
            """{"type":"agent_settled","tabId":"imux-1","sessionId":"abc","cwd":"/tmp","stopReason":"$reason","messageId":"msg-1"}"""

        assertEquals(PiStopReason.LENGTH, parsePiReport(report("length"))?.stopReason)
        assertNull(parsePiReport(report("stop")))
        assertNull(parsePiReport(report("unknown")))
        assertNull(
            parsePiReport(
                """{"type":"agent_settled","tabId":"imux-1","sessionId":"abc","cwd":"/tmp"}""",
            ),
        )
        assertNull(
            parsePiReport(
                """{"type":"agent_settled","tabId":"imux-1","sessionId":"abc","cwd":"/tmp","stopReason":"error"}""",
            ),
        )
    }
}
