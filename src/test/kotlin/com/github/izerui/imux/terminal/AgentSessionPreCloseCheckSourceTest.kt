package com.github.izerui.imux.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentSessionPreCloseCheckSourceTest {
    private val source: String by lazy {
        File("src/main/kotlin/com/github/izerui/imux/terminal/AgentSessionPreCloseCheck.kt").readText()
    }
    private val editorSource: String by lazy {
        File("src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt").readText()
    }

    @Test
    fun `command w close target uses the same confirmation check`() {
        assertTrue(editorSource.contains("UiDataProvider"))
        assertTrue(editorSource.contains("CloseAction.CloseTarget"))
        assertTrue(editorSource.contains("FileEditorManagerEx.getInstanceEx(project)"))
        assertTrue(editorSource.contains("closeFileWithChecks(virtualFile, currentWindow)"))
        assertFalse(editorSource.contains("FileEditorManager.getInstance(project).closeFile(virtualFile)"))
        assertTrue(!editorSource.contains("registerKeyboardAction"))
    }

    @Test
    fun `user close uses platform pre-close check and native dialog`() {
        assertTrue(source.contains("VirtualFilePreCloseCheck"))
        assertTrue(source.contains("override fun canCloseFiles"))
        assertTrue(source.contains("MessageDialogBuilder"))
    }

    @Test
    fun `internal editor recreation and terminated sessions are not confirmed`() {
        assertTrue(source.contains("FileEditorManagerKeys.CLOSING_TO_REOPEN"))
        assertTrue(source.contains("TerminalViewSessionState.Terminated"))
    }

    @Test
    fun `batch close is confirmed once after filtering imux sessions`() {
        assertTrue(source.contains("filterIsInstance<AgentTerminalVirtualFile>()"))
        assertTrue(source.contains("val multiple = sessions.size > 1"))
        assertTrue(source.contains("sessions.first().terminalView.component"))
    }
}
