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
    fun `先聊再做入口在结对编程未开启与已开启两种状态下都显示`() {
        val refresh = editor.bodyAfter("private fun refreshPeerBanner()", '{')
        val idle = refresh.substringBefore("} else {")
        val bound = refresh.substringAfter("} else {")
        assertTrue("未开启结对编程时显示", idle.contains("clarifyLink.isVisible = true"))
        assertTrue("已开启结对编程时显示", bound.contains("clarifyLink.isVisible = true"))
        assertTrue("任何分支都不隐藏它", !refresh.contains("clarifyLink.isVisible = false"))
    }

    @Test
    fun `关掉结对编程提示后横幅仍在且只隐藏结对编程的内容`() {
        val idle = editor.bodyAfter("private fun refreshPeerBanner()", '{').substringBefore("} else {")
        assertTrue("横幅保留以放先聊再做", idle.contains("peerBanner.isVisible = true"))
        assertTrue("结对编程提示随不再提示隐藏", idle.contains("peerEnableLink.isVisible = !virtualFile.peerBannerDismissed"))
        assertTrue("单次审查随不再提示隐藏", idle.contains("peerReviewOnceLink.isVisible = !virtualFile.peerBannerDismissed"))
    }
}
