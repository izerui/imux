package com.github.izerui.imux.clarification

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertTrue
import org.junit.Test

class ClarificationEntrySourceTest {
    private val editor = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt")

    @Test
    fun `横幅里的先聊再做入口打开当前会话的窗口`() {
        assertTrue(editor.normalized.contains("ClarificationService.getInstance(project).open(virtualFile.sessionKey, virtualFile.tabId, virtualFile.agentType)"))
    }

    @Test
    fun `关闭标签页时按标签页身份清理先聊再做且不为此新建服务`() {
        val dispose = editor.bodyAfter("override fun dispose()", '{')
        assertTrue("按标签页身份清理", dispose.contains(".releaseOwner(virtualFile.tabId)"))
        assertTrue("项目关闭途中不能新建服务", dispose.contains("project.serviceIfCreated<ClarificationService>()?.releaseOwner(virtualFile.tabId)"))
    }

    @Test
    fun `先聊再做是横幅里固定的一段，任何状态都不隐藏`() {
        val banner = editor.normalized.substringAfter("private val peerBanner =").substringBefore("private val peerElapsedTimer")
        assertTrue("横幅里有先聊再做段", banner.contains("add(clarifyLink)"))
        assertTrue("与结对编程段之间有分隔线", banner.contains("add(BannerDivider())"))
        assertTrue("任何地方都不隐藏它", !editor.normalized.contains("clarifyLink.isVisible = false"))
    }

    @Test
    fun `讨论窗口开着时横幅显示讨论中，开关窗口会通知横幅刷新`() {
        val refresh = editor.bodyAfter("private fun refreshPeerBanner()", '{')
        assertTrue(refresh.contains("ClarificationService.getInstance(project).isOpen(virtualFile.sessionKey)"))
        assertTrue(refresh.contains("discussing -> ImuxBundle.message(\"clarify.status.open\")"))
        assertTrue(refresh.contains("RowIcon(clarifyDot, AgentIcons.talk)"))
        assertTrue(editor.normalized.contains("ClarificationService.getInstance(project).addOpenListener(this)"))
        val service = SourceCode("src/main/kotlin/com/github/izerui/imux/clarification/ClarificationService.kt")
        assertTrue("打开时通知", service.bodyAfter("internal fun open(", '{').contains("openListeners.multicaster.openChanged(sessionKey)"))
        assertTrue("结束时通知", service.bodyAfter("private fun discard(", '{').contains("openListeners.multicaster.openChanged(sessionKey)"))
    }

    @Test
    fun `横幅不再提供不再提示`() {
        assertTrue(!editor.normalized.contains("peerBannerDismissed"))
        assertTrue(!editor.normalized.contains("action.peer.enable.dismiss"))
        assertTrue("横幅本身不隐藏", !editor.normalized.contains("peerBanner.isVisible = false"))
    }
}
