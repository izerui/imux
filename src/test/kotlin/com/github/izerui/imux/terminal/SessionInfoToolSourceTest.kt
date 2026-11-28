package com.github.izerui.imux.terminal

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionInfoToolSourceTest {
    private val source = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/SessionInfoTool.kt")
    private val code = source.normalized

    @Test
    fun `会话文件和分支变化后自动重读信息`() {
        assertTrue(code.contains("delay(SESSION_INFO_REFRESH_INTERVAL)"))
        assertTrue(code.contains("val current = sessionInfoFingerprint(watchedTranscript.get(), projectPath, file.agentType)"))
        assertTrue(code.contains("if (current != observed)"))
        assertTrue(code.contains("reload()"))
        assertTrue(
            code.contains(
                "var observed = sessionInfoFingerprint(watchedTranscript.get(), projectPath, file.agentType) " +
                    "withContext(Dispatchers.EDT) { if (!popup.isDisposed && file.sessionKey == key) reload() } " +
                    "while (isActive)"
            )
        )
    }

    @Test
    fun `会话结对手机和 Pi 能力变化后刷新弹窗`() {
        val perform = source.bodyAfter("override fun perform(anchor: JComponent)", '{')
        assertTrue(
            perform.contains(
                "monitor.addListener(contentLifetime) { " +
                        "val latest = transcript() " +
                        "if (watchedTranscript.getAndSet(latest) != latest) reload() " +
                        "}"
            )
        )
        assertTrue(
            perform.contains(
                "coordinator.addStateListener(contentLifetime) { changedKey -> " +
                        "if (changedKey == key) refreshView() " +
                        "}"
            )
        )
        assertTrue(perform.contains("SessionDecorationsListener { refreshView() }"))
        assertTrue(
            perform.contains(
                "host.addSessionInfoChangedListener(contentLifetime) { changedFile -> " +
                        "if (changedFile === file) refreshView() " +
                        "}"
            )
        )
        assertTrue(perform.contains("panel.updateTitle(file.tabTitle)"))
    }

    @Test
    fun `Pi 能力与标题变化由终端宿主发布文件级事件`() {
        val host = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/TerminalHost.kt")
        assertTrue(host.normalized.contains("private val sessionInfoChangedDispatcher = EventDispatcher.create(SessionInfoChangedListener::class.java)"))
        assertTrue(host.normalized.contains("fun addSessionInfoChangedListener("))
        val capabilities = host.bodyAfter(
            "internal fun recordPiCapabilities(report: com.github.izerui.imux.session.PiSessionReport)",
            '{',
        )
        assertTrue(capabilities.contains("if (file.recordPiCapabilities(report)) {"))
        assertTrue(capabilities.contains("sessionInfoChangedDispatcher.multicaster.sessionInfoChanged(file)"))
        val title = host.bodyAfter(
            "private fun updateTabTitle(file: AgentTerminalVirtualFile, newTitle: String)",
            '{',
        )
        assertTrue(title.contains("sessionInfoChangedDispatcher.multicaster.sessionInfoChanged(file)"))
    }
}
