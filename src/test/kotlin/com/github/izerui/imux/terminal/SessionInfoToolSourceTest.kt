package com.github.izerui.imux.terminal

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionInfoToolSourceTest {
    private val source = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/SessionInfoTool.kt")
    private val code = source.normalized

    @Test
    fun `更新标题之前关闭已换会话的弹窗`() {
        val refresh = source.bodyAfter("fun refreshView()", '{')
        source.assertSameCode(
            "先拒绝旧会话的刷新，再更新标题和内容",
            """
            {
                if (popup.isDisposed) return
                if (file.sessionKey != key) {
                    popup.cancel(); return
                }
                panel.updateTitle(file.tabTitle)
                render(snapshot.usage, snapshot.branch)
                if (snapshot.readFailed) panel.readFailed()
            }
            """.trimIndent(),
            refresh,
        )
    }

    @Test
    fun `会话迁移通知关闭旧弹窗且监听随弹窗释放`() {
        assertTrue(code.contains(
            "host.addSessionKeyMigratedListener(contentLifetime) { oldKey, _ -> " +
                "if (oldKey == key) popup.cancel() }"
        ))
        assertTrue(code.contains("Disposer.register(popup, contentLifetime)"))
        assertTrue(code.contains("Disposer.register(popup, Disposable { job?.cancel() })"))
        assertTrue(code.contains("if (event.oldFile === file && event.newFile !== file) popup.cancel()"))
    }

    @Test
    fun `打开弹窗立即读取并保留手动刷新入口`() {
        val perform = source.bodyAfter("override fun perform(anchor: JComponent)", '{')
        assertTrue(source.compact(perform).contains(
            "AgentIcons.forAgent(file.agentType),::reload,{popup.cancel()}"
        ))
        assertTrue(source.compact(perform).endsWith(
            "popup.show(com.intellij.ui.awt.RelativePoint(strip,offset))reload()}"
        ))
        val reload = source.bodyAfter("fun reload()", '{')
        assertTrue(reload.contains("if (popup.isDisposed) return"))
        assertTrue(reload.contains("panel.reading()"))
        assertTrue(reload.contains("readSessionUsage(file.agentType, transcript) { ensureActive() }"))
    }

    @Test
    fun `弹窗不再定时读取或订阅内容变化`() {
        val perform = source.bodyAfter("override fun perform(anchor: JComponent)", '{')
        assertFalse(perform.contains("delay("))
        assertFalse(perform.contains("sessionInfoFingerprint("))
        assertFalse(perform.contains("monitor.addListener("))
        assertFalse(perform.contains("coordinator.addStateListener("))
        assertFalse(perform.contains("addSessionInfoChangedListener("))
        assertFalse(perform.contains("SessionDecorationsListener"))
        assertFalse(perform.contains("TurnProgressStore"))
    }
}
