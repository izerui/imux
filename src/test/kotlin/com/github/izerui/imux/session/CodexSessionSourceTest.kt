package com.github.izerui.imux.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CodexSessionSourceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `交互来源与缺省来源可作为主会话`() {
        assertTrue(isInteractiveCodexSession("""{"type":"session_meta","payload":{"source":"cli"}}"""))
        assertTrue(isInteractiveCodexSession("""{"type":"session_meta","payload":{"source":"vscode"}}"""))
        assertTrue(isInteractiveCodexSession("""{"type":"session_meta","payload":{"id":"old"}}"""))
    }

    @Test
    fun `子任务与非交互来源不作为主会话`() {
        assertFalse(isInteractiveCodexSession("""{"type":"session_meta","payload":{"source":{"subagent":"review"}}}"""))
        assertFalse(isInteractiveCodexSession("""{"type":"session_meta","payload":{"source":{"subagent":{"thread_spawn":{"parent_thread_id":"main"}}}}}"""))
        assertFalse(isInteractiveCodexSession("""{"type":"session_meta","payload":{"source":"cli","thread_source":"subagent"}}"""))
        assertFalse(isInteractiveCodexSession("""{"type":"session_meta","payload":{"source":"exec"}}"""))
    }

    @Test
    fun `错误记录和未写完的元数据不认领`() {
        assertFalse(isInteractiveCodexSession("""{"type":"response_item","payload":{"source":"cli"}}"""))
        assertFalse(isInteractiveCodexSession("""{"type":"session_meta","payload":{"source":"cli" """))
        assertFalse(isInteractiveCodexSession("""{"type":"session_meta","payload":{"source":null}}"""))
        assertFalse(isInteractiveCodexSession("""{"type":"session_meta","payload":{"source":{}}}"""))
    }

    @Test
    fun `首行尚未写完时不认领而补齐后可以重试`() {
        val file = File(tmp.root, "partial.jsonl")
        assertFalse(isInteractiveCodexRollout(file.path))
        file.writeText("""{"type":"session_meta","payload":{"source":"cli" """)
        assertFalse(isInteractiveCodexRollout(file.path))
        file.appendText("}}\n")
        assertTrue(isInteractiveCodexRollout(file.path))
    }

    @Test
    fun `后续正文中的子任务字样不改变首行来源`() {
        val file = File(tmp.root, "main.jsonl").apply {
            writeText(
                """{"type":"session_meta","payload":{"source":"cli"}}""" + "\n" +
                        """{"type":"response_item","payload":{"text":"subagent"}}""" + "\n"
            )
        }
        assertTrue(isInteractiveCodexRollout(file.path))
    }
}
