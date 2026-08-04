package com.github.izerui.imux.session

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun repository(): SessionRepository {
        val claudeHome = File(tmp.root, "claude").apply { mkdirs() }
        val codexHome = File(tmp.root, "codex").apply { mkdirs() }
        return SessionRepository(
            ClaudeSessionReader(claudeHome.toPath()),
            CodexSessionReader(codexHome.toPath()),
        )
    }

    private fun claudeSession(name: String, lastModified: Long) {
        val dir = File(tmp.root, "claude/projects/-Users-demo-proj").apply { mkdirs() }
        File(dir, "$name.jsonl").apply {
            writeText("""{"type":"ai-title","aiTitle":"C-$name","sessionId":"$name"}""")
            setLastModified(lastModified)
        }
    }

    private fun codexSession(uuid: String, lastModified: Long) {
        val dir = File(tmp.root, "codex/sessions/2026/08/03").apply { mkdirs() }
        File(dir, "rollout-2026-08-03T10-00-00-$uuid.jsonl").apply {
            writeText("""{"type":"session_meta","payload":{"id":"$uuid","cwd":"/Users/demo/proj"}}""")
            setLastModified(lastModified)
        }
    }

    @Test
    fun `两个会话库的结果被合并`() {
        claudeSession("aaa", 1_000_000)
        codexSession("bbb", 2_000_000)

        val sessions = repository().scan("/Users/demo/proj")

        assertEquals(2, sessions.size)
        assertEquals(setOf(AgentType.CLAUDE, AgentType.CODEX), sessions.map { it.agentType }.toSet())
    }

    @Test
    fun `按最后活动时间倒序排列`() {
        claudeSession("old", 1_000_000)
        codexSession("new", 3_000_000)
        claudeSession("mid", 2_000_000)

        val ids = repository().scan("/Users/demo/proj").map { it.id }

        assertEquals(listOf("new", "mid", "old"), ids)
    }

    @Test
    fun `不做条数截断`() {
        repeat(60) { claudeSession("s$it", 1_000_000L + it * 1000) }

        assertEquals(60, repository().scan("/Users/demo/proj").size)
    }

    @Test
    fun `两个会话库都为空时返回空列表`() {
        assertTrue(repository().scan("/Users/demo/proj").isEmpty())
    }
}
