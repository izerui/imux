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
    fun `英文交接提示只有一句引导语和复制内容`() {
        assertEquals(
            "Take over the task from the session below, read its full context, and assess the current progress; " +
                "report the results and wait for further instructions.\n\n" +
                "Session type: Claude Code\nSession ID: session-abc-123",
            handoffPrompt(session, PluginLanguage.ENGLISH),
        )
    }

    @Test
    fun `中文交接提示只有一句引导语和复制内容`() {
        assertEquals(
            "接手以下会话中的任务，读取完整上下文并梳理当前进展；" +
                "反馈结果并等待下一步指示。\n\n" +
                "会话类型：Claude Code\n会话 ID：session-abc-123",
            handoffPrompt(session, PluginLanguage.SIMPLIFIED_CHINESE),
        )
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
        assertSame(AllIcons.Actions.MoveTo2, group.templatePresentation.icon)
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
