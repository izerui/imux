package com.github.izerui.imux.terminal

import com.github.izerui.imux.model.AgentType
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.terminal.frontend.view.TerminalView
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class AgentTerminalVirtualFileTest {

    /**
     * 允许分屏时，第二个编辑器会把唯一的终端组件挪走，原分屏变空白；
     * 关掉任一分屏又会走到真正关闭的路径，把还开着的另一份连同 CLI 进程一起结束。
     */
    @Test
    fun `会话标签禁止分屏`() {
        val file =
            AgentTerminalVirtualFile(
                name = "会话",
                terminalView = terminalView(),
                sessionKey = "s1",
                agentType = AgentType.CLAUDE,
                tabId = "tab-1",
            )

        assertEquals(true, file.getUserData(FileEditorManagerKeys.FORBID_TAB_SPLIT))
    }

    @Test
    fun `旧报告迟到后切换到新 Pi 会话仍能读到其技能和工具`() {
        val file = AgentTerminalVirtualFile("Pi", terminalView(), "old", AgentType.PI, "tab")
        val fresh = com.github.izerui.imux.session.PiSessionReport(
            com.github.izerui.imux.session.PiReportType.SESSION_START, "tab", "new", "/work",
            activeTools = listOf("read"), skillNames = listOf("review"),
        )
        file.recordPiCapabilities(fresh)
        file.recordPiCapabilities(fresh.copy(sessionId = "old", activeTools = emptyList(), skillNames = emptyList()))
        file.sessionKey = "new"
        assertEquals(listOf("review"), file.piCapabilitiesFor(file.sessionKey)?.skillNames)
        assertEquals(listOf("read"), file.piCapabilitiesFor(file.sessionKey)?.activeTools)
        file.sessionKey = "not-reported"
        org.junit.Assert.assertNull(file.piCapabilitiesFor(file.sessionKey))
        file.clearPiCapabilities()
        org.junit.Assert.assertNull(file.piCapabilitiesFor("new"))
        org.junit.Assert.assertNull(file.piCapabilitiesFor("old"))
    }

    private fun terminalView(): TerminalView =
        Proxy.newProxyInstance(TerminalView::class.java.classLoader, arrayOf(TerminalView::class.java)) { _, method, _ ->
            when (method.name) {
                "toString" -> "test TerminalView"
                "hashCode" -> 0
                "equals" -> false
                else -> error("Unexpected TerminalView.${method.name}")
            }
        } as TerminalView
}
