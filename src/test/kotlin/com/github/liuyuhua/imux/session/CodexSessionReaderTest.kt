package com.github.liuyuhua.imux.session

import com.github.liuyuhua.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CodexSessionReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun reader() = CodexSessionReader(tmp.root.toPath())

    private fun writeRollout(uuid: String, cwd: String, body: String = "") {
        val dir = File(tmp.root, "sessions/2026/08/03").apply { mkdirs() }
        val meta =
            """{"timestamp":"2026-08-03T11:31:27.000Z","type":"session_meta","payload":{"id":"$uuid","cwd":"$cwd"}}"""
        File(dir, "rollout-2026-08-03T11-31-27-$uuid.jsonl")
            .writeText(if (body.isEmpty()) meta else "$meta\n$body")
    }

    private fun userMessage(text: String) =
        """{"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"$text"}]}}"""

    @Test
    fun `只返回 cwd 与项目匹配的会话`() {
        writeRollout("uuid-mine", "/Users/demo/proj")
        writeRollout("uuid-other", "/Users/demo/other")

        val sessions = reader().read("/Users/demo/proj")

        assertEquals(1, sessions.size)
        assertEquals("uuid-mine", sessions[0].id)
        assertEquals(AgentType.CODEX, sessions[0].agentType)
    }

    @Test
    fun `标题回退为首条用户消息`() {
        writeRollout("uuid-msg", "/Users/demo/proj", userMessage("帮我重构这个函数"))

        assertEquals("帮我重构这个函数", reader().read("/Users/demo/proj")[0].title)
    }

    @Test
    fun `过长的用户消息被截断并加省略号`() {
        writeRollout("uuid-long", "/Users/demo/proj", userMessage("很".repeat(120)))

        val title = reader().read("/Users/demo/proj")[0].title
        assertEquals(61, title.length)
        assertTrue(title.endsWith("…"))
    }

    @Test
    fun `没有用户消息时回退为会话 id 短码`() {
        writeRollout("01abcdef-2222", "/Users/demo/proj")

        assertEquals("会话 01abcdef", reader().read("/Users/demo/proj")[0].title)
    }

    @Test
    fun `首行损坏的文件被跳过而不影响其他会话`() {
        writeRollout("uuid-ok", "/Users/demo/proj")
        File(tmp.root, "sessions/2026/08/03/rollout-2026-08-03T09-00-00-uuid-bad.jsonl")
            .writeText("这不是 json")

        assertEquals(1, reader().read("/Users/demo/proj").size)
    }

    @Test
    fun `会话根目录不存在时返回空列表`() {
        assertTrue(reader().read("/Users/demo/proj").isEmpty())
    }

    @Test
    fun `跨日期目录的会话都能读到`() {
        writeRollout("uuid-day3", "/Users/demo/proj")
        File(tmp.root, "sessions/2026/07/30").mkdirs()
        File(tmp.root, "sessions/2026/07/30/rollout-2026-07-30T10-00-00-uuid-day30.jsonl")
            .writeText("""{"type":"session_meta","payload":{"id":"uuid-day30","cwd":"/Users/demo/proj"}}""")

        assertEquals(2, reader().read("/Users/demo/proj").size)
    }
}
