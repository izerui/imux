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
            "Your first action must be a shell tool call that runs `rg -l session-abc-123 ~/.pi/agent/sessions " +
                "~/.codex/sessions ~/.claude/projects 2>/dev/null || true` to locate the source record. Read that " +
                "record and inspect the current workspace with tools. Then report the current progress, completed work, " +
                "remaining work, and relevant local changes. Stop after reporting without implementing the source task.\n\n" +
                "Session type: Claude Code\nSession ID: session-abc-123",
            handoffPrompt(session, PluginLanguage.ENGLISH),
        )
    }

    @Test
    fun `中文交接提示只有一句引导语和复制内容`() {
        assertEquals(
            "第一项动作必须调用 shell 工具执行 `rg -l session-abc-123 ~/.pi/agent/sessions ~/.codex/sessions " +
                "~/.claude/projects 2>/dev/null || true`，定位源会话记录。读取该记录并用工具检查当前工作区，" +
                "然后汇报当前进展、已完成事项、剩余事项和相关本地改动。汇报后结束，不要实施源任务。\n\n" +
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
