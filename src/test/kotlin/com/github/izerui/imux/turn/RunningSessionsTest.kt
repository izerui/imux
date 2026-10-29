package com.github.izerui.imux.turn

import com.github.izerui.imux.session.ClaudeRuntimeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunningSessionsTest {

    private fun session(id: String, status: String?, kind: String = "interactive") =
        ClaudeRuntimeSession(id, pid = 1, kind = kind, status = status, cwd = "/proj")

    private fun snapshot(vararg pairs: Pair<String, String?>) =
        pairs.associate { (id, status) -> id to session(id, status) }

    @Test
    fun `忙碌的 claude 会话计入执行中`() {
        val running = RunningSessions.of(snapshot("s1" to "busy"), emptySet())

        assertEquals(setOf("s1"), running)
    }

    @Test
    fun `后台命令运行期间持续计入执行中，结束后移除`() {
        assertEquals(setOf("s1"), RunningSessions.of(snapshot("s1" to "busy"), emptySet()))
        assertEquals(setOf("s1"), RunningSessions.of(snapshot("s1" to "shell"), emptySet()))
        assertTrue(RunningSessions.of(snapshot("s1" to "idle"), emptySet()).isEmpty())
    }

    @Test
    fun `等待用户确认时不计入执行中`() {
        assertTrue(RunningSessions.of(snapshot("s1" to "waiting"), emptySet()).isEmpty())
    }

    @Test
    fun `后台运行标记持续时仍能识别前台新一轮`() {
        val background = snapshot("s1" to "shell")
        val foreground = snapshot("s1" to "busy")
        assertEquals(setOf("s1"), RunningSessions.of(background, emptySet()))
        assertTrue(RunningSessions.workingOf(background, emptySet()).isEmpty())
        assertEquals(setOf("s1"), RunningSessions.of(foreground, emptySet()))
        assertEquals(setOf("s1"), RunningSessions.workingOf(foreground, emptySet()))
        assertEquals(
            setOf("s1"),
            RunningSessions.workingOf(foreground, emptySet()) - RunningSessions.workingOf(background, emptySet()),
        )
    }

    @Test
    fun `前台轮次集合保留文件推断的执行状态`() {
        assertEquals(setOf("c1"), RunningSessions.workingOf(snapshot("s1" to "shell"), setOf("c1")))
    }

    @Test
    fun `空闲的 claude 会话不计入`() {
        val running = RunningSessions.of(snapshot("s1" to "idle"), emptySet())

        assertTrue(running.isEmpty())
    }

    /** 运行态文件里没有 status 字段时，无从判断在跑，按不在跑处理。 */
    @Test
    fun `状态缺失时不计入`() {
        val running = RunningSessions.of(snapshot("s1" to null), emptySet())

        assertTrue(running.isEmpty())
    }

    @Test
    fun `后台 agent 忙碌时同样计入`() {
        val runtime = mapOf("s1" to session("s1", "busy", kind = "bg"))

        assertEquals(setOf("s1"), RunningSessions.of(runtime, emptySet()))
    }

    @Test
    fun `codex 执行中的会话计入`() {
        val running = RunningSessions.of(emptyMap(), setOf("c1"))

        assertEquals(setOf("c1"), running)
    }

    @Test
    fun `两侧取并集`() {
        val running = RunningSessions.of(snapshot("s1" to "busy", "s2" to "idle"), setOf("c1"))

        assertEquals(setOf("s1", "c1"), running)
    }

    /** 同一个会话不会既是 claude 又是 codex，但重合时不该出现重复。 */
    @Test
    fun `两侧重合时不重复`() {
        val running = RunningSessions.of(snapshot("s1" to "busy"), setOf("s1"))

        assertEquals(setOf("s1"), running)
    }
}
