package com.github.liuyuhua.imux.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ClaudeHistoryIndexTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun index() = ClaudeHistoryIndex(tmp.root.toPath())

    private fun writeHistory(vararg lines: String) {
        File(tmp.root, "history.jsonl").writeText(lines.joinToString("\n"))
    }

    private fun entry(sessionId: String, display: String, project: String, timestamp: Long) =
        """{"display":"$display","pastedContents":"{}","timestamp":"$timestamp","project":"$project","sessionId":"$sessionId"}"""

    @Test
    fun `按会话 id 取到标题`() {
        writeHistory(entry("s1", "咚咚咚", "/Users/demo/proj", 1_000))

        assertEquals("咚咚咚", index().load("/Users/demo/proj")["s1"]?.display)
    }

    /** 同一会话每个 prompt 一条记录，标题取最早那条——它才是这次对话的由头。 */
    @Test
    fun `同一会话取最早一条作为标题`() {
        writeHistory(
            entry("s1", "第二句", "/Users/demo/proj", 2_000),
            entry("s1", "第一句", "/Users/demo/proj", 1_000),
            entry("s1", "第三句", "/Users/demo/proj", 3_000),
        )

        assertEquals("第一句", index().load("/Users/demo/proj")["s1"]?.display)
    }

    /** 最后活动时间取最晚那条：这是「你最后一次说话」的时刻，比文件 mtime 更贴切。 */
    @Test
    fun `最后活动时间取最晚一条`() {
        writeHistory(
            entry("s1", "第一句", "/Users/demo/proj", 1_000),
            entry("s1", "第三句", "/Users/demo/proj", 3_000),
        )

        assertEquals(3_000L, index().load("/Users/demo/proj")["s1"]?.lastPromptAtMillis)
    }

    @Test
    fun `只返回本项目的会话`() {
        writeHistory(
            entry("mine", "本项目", "/Users/demo/proj", 1_000),
            entry("other", "别的项目", "/Users/demo/other", 1_000),
        )

        val loaded = index().load("/Users/demo/proj")
        assertEquals(setOf("mine"), loaded.keys)
    }

    @Test
    fun `文件不存在时返回空表`() {
        assertTrue(index().load("/Users/demo/proj").isEmpty())
    }

    @Test
    fun `损坏行被跳过`() {
        writeHistory(
            "这不是 json",
            entry("s1", "正常记录", "/Users/demo/proj", 1_000),
        )

        assertEquals("正常记录", index().load("/Users/demo/proj")["s1"]?.display)
    }

    @Test
    fun `缺字段的记录被跳过`() {
        writeHistory(
            """{"display":"没有会话id","project":"/Users/demo/proj","timestamp":"1000"}""",
            entry("s1", "正常记录", "/Users/demo/proj", 1_000),
        )

        val loaded = index().load("/Users/demo/proj")
        assertEquals(1, loaded.size)
        assertNull(loaded["没有会话id"])
    }

    @Test
    fun `空白标题不参与`() {
        writeHistory(
            entry("s1", "   ", "/Users/demo/proj", 1_000),
            entry("s1", "有内容的", "/Users/demo/proj", 2_000),
        )

        assertEquals("有内容的", index().load("/Users/demo/proj")["s1"]?.display)
    }

    /**
     * 真实文件里 timestamp 两种形式并存：`"timestamp":123` 与 `"timestamp":"123"`。
     * 早先只认字符串形式，数字形式的记录被整条丢弃，索引直接空掉。
     */
    @Test
    fun `数字形式的时间戳同样能解析`() {
        File(tmp.root, "history.jsonl").writeText(
            """{"display":"数字时间戳","pastedContents":{},"timestamp":1785211438000,"project":"/Users/demo/proj","sessionId":"s1"}""",
        )

        val entry = index().load("/Users/demo/proj")["s1"]
        assertEquals("数字时间戳", entry?.display)
        assertEquals(1785211438000L, entry?.lastPromptAtMillis)
    }

    @Test
    fun `两种时间戳形式混排时排序正确`() {
        File(tmp.root, "history.jsonl").writeText(
            listOf(
                """{"display":"晚的","timestamp":3000,"project":"/Users/demo/proj","sessionId":"s1"}""",
                """{"display":"早的","timestamp":"1000","project":"/Users/demo/proj","sessionId":"s1"}""",
            ).joinToString("\n"),
        )

        val entry = index().load("/Users/demo/proj")["s1"]
        assertEquals("早的", entry?.display)
        assertEquals(3000L, entry?.lastPromptAtMillis)
    }

    /** 时间戳缺失或无法解析时，不该把整条记录连同标题一起丢掉。 */
    @Test
    fun `时间戳无法解析时仍保留标题`() {
        File(tmp.root, "history.jsonl").writeText(
            """{"display":"仍然可用","timestamp":"不是数字","project":"/Users/demo/proj","sessionId":"s1"}""",
        )

        assertEquals("仍然可用", index().load("/Users/demo/proj")["s1"]?.display)
    }
}
