package com.github.izerui.imux.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PiSessionReportTest {

    private val file =
        "/Users/demo/.pi/agent/sessions/--Users-demo-proj--/2026-08-13T09-45-00-045Z_019ffa82-bd8d-7edd-a9aa-e5e711524a7a.jsonl"

    @Test
    fun `从会话文件路径解析出会话 id`() {
        assertEquals("019ffa82-bd8d-7edd-a9aa-e5e711524a7a", piSessionIdOf(file))
    }

    /** 文件名是 <时间戳>_<uuid>.jsonl，时间戳里也有横杠，不能按横杠切。 */
    @Test
    fun `时间戳不会被误当成 id`() {
        assertEquals(36, piSessionIdOf(file)?.length)
    }

    @Test
    fun `形状不对的路径解析为 null`() {
        assertNull(piSessionIdOf("/tmp/notasession.jsonl"))
        assertNull(piSessionIdOf("/tmp/2026-08-13T09-45-00-045Z_短id.jsonl"))
        assertNull(piSessionIdOf(""))
    }

    @Test
    fun `解析上报体`() {
        val body = """{"tabId":"imux-1","sessionFile":"$file"}"""

        assertEquals(
            PiSessionReport("imux-1", "019ffa82-bd8d-7edd-a9aa-e5e711524a7a"),
            parsePiReport(body),
        )
    }

    /** 上报来自另一个进程，任何字段都不能假定存在——损坏的报文只该被丢弃。 */
    @Test
    fun `缺字段或损坏的上报体解析为 null`() {
        assertNull(parsePiReport(""))
        assertNull(parsePiReport("这不是 json"))
        assertNull(parsePiReport("""{"tabId":"imux-1"}"""))
        assertNull(parsePiReport("""{"sessionFile":"$file"}"""))
        assertNull(parsePiReport("""{"tabId":"","sessionFile":"$file"}"""))
        assertNull(parsePiReport("""{"tabId":"imux-1","sessionFile":"/tmp/x.jsonl"}"""))
    }
}
