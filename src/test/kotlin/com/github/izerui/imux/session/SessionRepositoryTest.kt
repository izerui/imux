package com.github.izerui.imux.session

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class SessionRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun repository(): SessionRepository {
        val claudeHome = File(tmp.root, "claude").apply { mkdirs() }
        val codexHome = File(tmp.root, "codex").apply { mkdirs() }
        val piHome = File(tmp.root, "pi").apply { mkdirs() }
        return SessionRepository(
            ClaudeSessionReader(claudeHome.toPath()),
            CodexSessionReader(codexHome.toPath()),
            PiSessionReader(piHome.toPath()),
        )
    }

    private fun claudeSession(name: String, lastModified: Long) {
        val dir = File(tmp.root, "claude/projects/-Users-demo-proj").apply { mkdirs() }
        File(dir, "$name.jsonl").apply {
            writeText("""{"type":"ai-title","aiTitle":"C-$name","sessionId":"$name"}""")
            setLastModified(lastModified)
        }
    }

    private fun claudeSessionWithActivity(name: String, activityAt: Instant) {
        val dir = File(tmp.root, "claude/projects/-Users-demo-proj").apply { mkdirs() }
        File(dir, "$name.jsonl").writeText(
            """
            {"type":"user","timestamp":"$activityAt","message":{"content":"消息-$name"}}
            {"type":"ai-title","aiTitle":"C-$name","sessionId":"$name"}
            """.trimIndent(),
        )
    }

    private fun codexSession(uuid: String, lastModified: Long) {
        val dir = File(tmp.root, "codex/sessions/2026/08/03").apply { mkdirs() }
        File(dir, "rollout-2026-08-03T10-00-00-$uuid.jsonl").apply {
            writeText("""{"type":"session_meta","payload":{"id":"$uuid","cwd":"/Users/demo/proj"}}""")
            setLastModified(lastModified)
        }
    }

    private fun piSession(uuid: String, lastModified: Long) {
        val dir = File(tmp.root, "pi/agent/sessions/--Users-demo-proj--").apply { mkdirs() }
        File(dir, "2026-08-13T10-00-00-000Z_$uuid.jsonl").apply {
            writeText(
                """
                {"type":"session","version":3,"id":"$uuid","cwd":"/Users/demo/proj"}
                {"type":"message","message":{"role":"user","content":"消息-$uuid"}}
                """.trimIndent(),
            )
            setLastModified(lastModified)
        }
    }

    @Test
    fun `三个会话库的结果被合并`() {
        claudeSession("aaa", 1_000_000)
        codexSession("bbb", 2_000_000)
        piSession("ccc", 3_000_000)

        val sessions = repository().scan("/Users/demo/proj")

        assertEquals(3, sessions.size)
        assertEquals(
            setOf(AgentType.CLAUDE, AgentType.CODEX, AgentType.PI),
            sessions.map { it.agentType }.toSet(),
        )
    }

    @Test
    fun `按最后活动时间倒序排列`() {
        claudeSession("old", 1_000_000)
        codexSession("new", 3_000_000)
        claudeSession("mid", 2_000_000)
        piSession("newest", 4_000_000)

        val ids = repository().scan("/Users/demo/proj").map { it.id }

        assertEquals(listOf("newest", "new", "mid", "old"), ids)
    }

    @Test
    fun `较早创建但刚活动的会话排在较晚创建的旧会话之前`() {
        claudeSessionWithActivity("active-now", Instant.parse("2026-08-05T02:00:00Z"))
        Thread.sleep(1_100)
        claudeSessionWithActivity("inactive", Instant.parse("2026-08-04T02:00:00Z"))

        val ids = repository().scan("/Users/demo/proj").map { it.id }

        assertEquals(listOf("active-now", "inactive"), ids)
    }

    @Test
    fun `不做条数截断`() {
        repeat(60) { claudeSession("s$it", 1_000_000L + it * 1000) }

        assertEquals(60, repository().scan("/Users/demo/proj").size)
    }

    @Test
    fun `所有会话库都为空时返回空列表`() {
        assertTrue(repository().scan("/Users/demo/proj").isEmpty())
    }
}
