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
