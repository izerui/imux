package com.github.izerui.imux.terminal

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SessionTabRestoreSourceTest {
    private val hostSource: String by lazy {
        File("src/main/kotlin/com/github/izerui/imux/terminal/TerminalHost.kt").readText()
    }
    private val stateSource: String by lazy {
        File("src/main/kotlin/com/github/izerui/imux/terminal/RestorableSessionTabs.kt").readText()
    }
    private val startupSource: String by lazy {
        File("src/main/kotlin/com/github/izerui/imux/monitor/ImuxStartupActivity.kt").readText()
    }
    private val monitorSource: String by lazy {
        File("src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt").readText()
    }

    @Test
    fun `tabs are stored in project workspace rather than application settings`() {
        assertTrue(stateSource.contains("StoragePathMacros.WORKSPACE_FILE"))
        assertTrue(stateSource.contains("SerializablePersistentStateComponent"))
    }

    @Test
    fun `project shutdown preserves the last open tab snapshot`() {
        assertTrue(hostSource.contains("ProjectCloseListener.TOPIC"))
        assertTrue(hostSource.contains("override fun projectClosingBeforeSave"))
        assertTrue(hostSource.contains("override fun projectClosing(project: Project)"))
        assertTrue(hostSource.contains("projectClosing = true"))
        assertTrue(hostSource.contains("restorationState.canPersist(projectClosing, project.isDisposed)"))
    }

    @Test
    fun `only resumable sessions are persisted`() {
        val persist = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/TerminalHost.kt")
            .bodyAfter("private fun persistRestorableTabs()", '{')
        assertTrue("快照应按窗口标签的视觉顺序读取", persist.contains("it.fileList.asSequence()"))
        assertTrue("只保存当前终端实例", persist.contains("files[it.sessionKey] === it"))
        assertTrue(hostSource.contains("val sessionId = file.sessionId ?: return@mapNotNull null"))
        assertTrue(hostSource.contains("agentId = file.agentType.cli"))
    }

    @Test
    fun `恢复标签按平台插入规则调整打开顺序`() {
        val restore = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/TerminalHost.kt")
            .bodyAfter("fun restoreTabs(", '{')
        assertTrue("应先筛选可恢复标签，再计算打开顺序", restore.indexOf("val resumable =") < restore.indexOf("restorationOpenOrder("))
        assertTrue("应使用平台的新标签位置设置", restore.contains("UISettings.getInstance().openTabsAtTheEnd"))
        assertTrue("应以当前窗口是否已有选中标签选择恢复次序", restore.contains("editorManager.currentWindow?.selectedFile != null"))
    }

    @Test
    fun `startup restores from a fresh scan before monitoring starts`() {
        assertTrue(startupSource.contains("monitor.restoreSavedTabs()"))
        assertTrue(startupSource.contains("finally"))
        assertTrue(startupSource.contains("monitor.start()"))
        assertTrue(monitorSource.contains("repository.scan(projectPath)"))
        assertTrue(monitorSource.contains("runtimeIndex.load(projectPath)"))
        assertTrue(monitorSource.contains("withContext(Dispatchers.EDT)"))
    }

    @Test
    fun `startup captures saved tabs before scanning and finishes restoration non cancellably`() {
        val capture = monitorSource.indexOf("host.beginTabRestoration()")
        val warmUp = monitorSource.indexOf("PiReportEndpointCache.warmUp()", startIndex = capture)
        val scan = monitorSource.indexOf("withContext(Dispatchers.IO)")

        assertTrue(capture >= 0)
        assertTrue(warmUp > capture)
        assertTrue(scan > capture)
        assertTrue(monitorSource.contains("saved = saved"))
        assertTrue(monitorSource.contains("NonCancellable + Dispatchers.EDT"))
        assertTrue(monitorSource.contains("host.finishTabRestoration"))
    }

    @Test
    fun `restored tabs do not replace the selected editor`() {
        assertTrue(hostSource.contains("selectAsCurrent = false"))
        assertTrue(hostSource.contains("FileEditorOpenRequest()"))
        assertTrue(hostSource.contains(".withSelectAsCurrent(false)"))
        assertTrue(hostSource.contains(".withRequestFocus(false)"))
    }

    @Test
    fun `restored tabs use busy preflight and register turn watcher`() {
        assertTrue(hostSource.contains("runtime[tab.sessionId].blocksResume()"))
        assertTrue(hostSource.contains("session.filePath"))
        assertTrue(hostSource.contains("inferInitialState = false"))
    }

    @Test
    fun `restored tabs use the title from the fresh session scan`() {
        assertTrue(hostSource.contains("tabTitle = session.title"))
        assertTrue(hostSource.contains("TurnNotifier.notifyBusy(project, session.title)"))
    }
}
