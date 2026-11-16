package com.github.izerui.imux.overview.report

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** 样例行的字段与本机真实会话记录一致（2026-11 核对过）。 */
class TranscriptFactsTest {
    private fun file(vararg lines: String): Path =
        Files.createTempFile("transcript", ".jsonl").also { Files.writeString(it, lines.joinToString("\n", postfix = "\n")) }

    private fun facts(agent: AgentType, path: Path, root: Path? = null) = transcriptFacts(agent, path, Files.size(path), root)

    private fun claudeTool(name: String, input: String, ts: String = "2026-11-16T09:00:00Z") =
        """{"type":"assistant","timestamp":"$ts","message":{"role":"assistant","content":[{"type":"tool_use","id":"t","name":"$name","input":$input}]}}"""

    private fun piTool(name: String, args: String, ts: String = "2026-11-16T09:00:00Z") =
        """{"type":"message","timestamp":"$ts","message":{"role":"assistant","content":[{"type":"toolCall","id":"t","name":"$name","arguments":$args}]}}"""

    private fun codexPatch(patch: String, ts: String = "2026-11-16T09:00:00Z") =
        """{"timestamp":"$ts","type":"response_item","payload":{"type":"custom_tool_call","call_id":"c","name":"apply_patch","input":${com.google.gson.JsonPrimitive(patch)}}}"""

    @Test
    fun `Claude 的 Edit Write MultiEdit NotebookEdit 都计入改动文件`() {
        val path = file(
            claudeTool("Edit", """{"file_path":"/p/a.kt","old_string":"x","new_string":"y"}"""),
            claudeTool("Write", """{"file_path":"/p/b.kt","content":"c"}"""),
            claudeTool("MultiEdit", """{"file_path":"/p/c.kt","edits":[]}"""),
            claudeTool("NotebookEdit", """{"notebook_path":"/p/d.ipynb","new_source":"s"}"""),
            claudeTool("Read", """{"file_path":"/p/e.kt"}"""),
        )
        assertEquals(listOf("/p/a.kt", "/p/b.kt", "/p/c.kt", "/p/d.ipynb"), facts(AgentType.CLAUDE, path).files)
    }

    @Test
    fun `Codex 的 apply_patch 新增 修改 删除 移动都计入`() {
        val patch = "*** Begin Patch\n*** Add File: /p/new.md\n+hi\n*** Update File: /p/old.kt\n*** Move to: /p/moved.kt\n@@\n-a\n+b\n*** Delete File: /p/gone.kt\n*** End Patch"
        val functionCall =
            """{"timestamp":"2026-11-16T09:01:00Z","type":"response_item","payload":{"type":"function_call","name":"apply_patch","arguments":${com.google.gson.JsonPrimitive("""{"input":"*** Begin Patch\n*** Update File: /p/fn.kt\n*** End Patch"}""")}}}"""
        val path = file(codexPatch(patch), functionCall)
        assertEquals(listOf("/p/new.md", "/p/old.kt", "/p/moved.kt", "/p/gone.kt", "/p/fn.kt"), facts(AgentType.CODEX, path).files)
    }

    @Test
    fun `Pi 的 edit 和 write 计入，bash 不计入`() {
        val path = file(
            piTool("edit", """{"path":"/p/a.kt","edits":[]}"""),
            piTool("write", """{"path":"/p/b.kt","content":"c"}"""),
            piTool("bash", """{"command":"rm /p/c.kt"}"""),
        )
        assertEquals(listOf("/p/a.kt", "/p/b.kt"), facts(AgentType.PI, path).files)
    }

    @Test
    fun `同一文件只记一次并保留首次出现顺序`() {
        val path = file(
            claudeTool("Edit", """{"file_path":"/p/b.kt"}"""),
            claudeTool("Edit", """{"file_path":"/p/a.kt"}"""),
            claudeTool("Write", """{"file_path":"/p/b.kt"}"""),
        )
        assertEquals(listOf("/p/b.kt", "/p/a.kt"), facts(AgentType.CLAUDE, path).files)
    }

    @Test
    fun `工程目录内的写相对路径，目录外的保留绝对路径`() {
        val path = file(
            claudeTool("Edit", """{"file_path":"/p/src/A.kt"}"""),
            claudeTool("Edit", """{"file_path":"/tmp/x.md"}"""),
        )
        assertEquals(listOf("src/A.kt", "/tmp/x.md"), facts(AgentType.CLAUDE, path, Path.of("/p")).files)
        assertEquals(listOf("/p/src/A.kt", "/tmp/x.md"), facts(AgentType.CLAUDE, path, null).files)
    }

    @Test
    fun `截止位置之后的行不统计`() {
        val first = claudeTool("Edit", """{"file_path":"/p/a.kt"}""", "2026-11-16T09:00:00Z")
        val second = claudeTool("Edit", """{"file_path":"/p/b.kt"}""", "2026-11-16T09:10:00Z")
        val third = claudeTool("Edit", """{"file_path":"/p/c.kt"}""", "2026-11-16T10:00:00Z")
        val path = file(first, second, third)
        val limit = (first + "\n" + second + "\n").toByteArray().size.toLong()
        val result = transcriptFacts(AgentType.CLAUDE, path, limit, null)
        assertEquals(listOf("/p/a.kt", "/p/b.kt"), result.files)
        assertEquals(10L, result.durationMinutes)
    }

    @Test
    fun `时长按首末时间戳向下取整到分钟`() {
        val path = file(
            """{"type":"user","timestamp":"2026-11-16T09:00:00Z","message":{"role":"user","content":"hi"}}""",
            """{"type":"assistant","timestamp":"2026-11-16T11:15:59Z","message":{"role":"assistant","content":[]}}""",
        )
        assertEquals(135L, facts(AgentType.CLAUDE, path).durationMinutes)
    }

    @Test
    fun `没有可解析时间戳时时长为空`() {
        val path = file("""{"type":"user","message":{"role":"user","content":"hi"}}""", """{"timestamp":"不是时间"}""")
        assertNull(facts(AgentType.CLAUDE, path).durationMinutes)
    }

    @Test
    fun `坏行跳过不影响其他行`() {
        val path = file("这不是 JSON", claudeTool("Edit", """{"file_path":"/p/a.kt"}"""), "{\"半截\":")
        assertEquals(listOf("/p/a.kt"), facts(AgentType.CLAUDE, path).files)
    }
}
