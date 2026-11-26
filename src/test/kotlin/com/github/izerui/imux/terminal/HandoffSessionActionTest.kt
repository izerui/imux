package com.github.izerui.imux.terminal

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.model.AgentSession
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.settings.PluginLanguage
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.file.Paths
import java.time.Instant

class HandoffSessionActionTest {
    private val session =
        AgentSession(
            id = "session-abc-123",
            title = "Implement handoff",
            agentType = AgentType.CLAUDE,
            lastActiveAt = Instant.EPOCH,
            createdAt = Instant.EPOCH,
            filePath = Paths.get("/tmp/claude/session-abc-123.jsonl"),
    )

    @Test
    fun `英文交接提示包含精确源记录路径和复制内容`() {
        val path = session.filePath.toAbsolutePath().normalize().toString()

        assertEquals(
            "Take over the task from the session below. First use tools to read the complete source session record " +
                "at the exact path `$path`, then inspect the current workspace and assess " +
                "the current progress. The record tells you what was said and claimed; the workspace shows what is actually there — where they disagree, trust the workspace. Report what the user is after (including later revisions), the completed work, remaining work, anything not yet closed-loop in business flow or logic, whether the main goal has been dropped or sidetracked for minor details, and relevant local changes, then " +
                "wait for further instructions. Messages in the record that begin with \"[Copilot review\" or \"【副驾驶审查意见\" are review notes from a pairing AI, not requests from the user.\n\n" +
                "Session type: Claude Code\nSession ID: session-abc-123",
            handoffPrompt(session, PluginLanguage.ENGLISH),
        )
    }

    @Test
    fun `中文交接提示包含精确源记录路径和复制内容`() {
        val path = session.filePath.toAbsolutePath().normalize().toString()

        assertEquals(
            "接手以下会话中的任务。先使用工具完整读取精确路径 `$path` 下的源会话记录，" +
                "再检查当前工作区并梳理当前进展。记录告诉你说过什么、声称做了什么，工作区才是实际的样子：两者对不上时以工作区为准。反馈用户要的是什么（含后来的修订）、已完成事项、剩余事项、业务或逻辑上还没闭环的地方、主要目标有没有因细节被落下或带偏，以及相关本地改动，然后等待下一步指示。记录里以「【副驾驶审查意见」或「[Copilot review」开头的消息是结对的另一个 AI 发来的审查意见，不是用户提的要求。\n\n" +
                "会话类型：Claude Code\n会话 ID：session-abc-123",
            handoffPrompt(session, PluginLanguage.SIMPLIFIED_CHINESE),
        )
    }

    @Test
    fun `其余本地化交接提示分别包含精确源记录路径`() {
        val path = session.filePath.toAbsolutePath().normalize().toString()

        assertTrue(handoffPrompt(session, PluginLanguage.TRADITIONAL_CHINESE).contains(path))
        assertTrue(handoffPrompt(session, PluginLanguage.JAPANESE).contains(path))
        assertTrue(handoffPrompt(session, PluginLanguage.KOREAN).contains(path))
        assertTrue(handoffPrompt(session, PluginLanguage.GERMAN).contains(path))
        assertTrue(handoffPrompt(session, PluginLanguage.FRENCH).contains(path))
        assertTrue(handoffPrompt(session, PluginLanguage.SPANISH).contains(path))
        assertTrue(handoffPrompt(session, PluginLanguage.PORTUGUESE_BRAZIL).contains(path))
        assertTrue(handoffPrompt(session, PluginLanguage.RUSSIAN).contains(path))
    }

    /** 接手的智能体读的是旧会话记录：里面副驾驶的审查意见和用户的话长得一样，得告诉它怎么认。 */
    @Test
    fun `全部语言的交接提示都写明两种开头的消息是审查意见`() {
        assertEquals(10, PluginLanguage.entries.size)
        for (language in PluginLanguage.entries) {
            val prompt = handoffPrompt(session, language)
            assertTrue(language.name, prompt.contains("[Copilot review"))
            assertTrue(language.name, prompt.contains("【副驾驶审查意见"))
        }
    }

    @Test
    fun `交接菜单只生成已启用的 Agent`() {
        val enabled = listOf(AgentType.CLAUDE, AgentType.PI)

        val actions = handoffActions(testProject(), session, enabled)

        assertEquals(
            enabled.map(AgentType::displayName),
            actions.map { it.templatePresentation.text },
        )
    }

    @Test
    fun `会话转移使用弹出子菜单承载 Agent`() {
        val enabled = AgentType.entries

        val group = handoffActionGroup(testProject(), session, enabled)

        assertTrue(group.isPopup)
        assertSame(AllIcons.General.Vcs, group.templatePresentation.icon)
        assertEquals(ImuxBundle.message("action.handoff.group.text"), group.templatePresentation.text)
        assertEquals(enabled.map(AgentType::displayName), group.getChildren(null).map { it.templatePresentation.text })
    }

    private fun testProject(): Project =
        Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "TestProject"
                else -> error("Unexpected Project.${method.name}")
            }
        } as Project
}
