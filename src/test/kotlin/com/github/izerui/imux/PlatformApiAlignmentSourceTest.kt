package com.github.izerui.imux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlatformApiAlignmentSourceTest {

    private fun source(path: String): String =
        File(path).takeIf(File::exists)?.readText().orEmpty()

    @Test
    fun `标签标题使用 EditorTabTitleProvider 而不是重命名只读虚拟文件`() {
        val terminalHost = source(
            "src/main/kotlin/com/github/izerui/imux/terminal/TerminalHost.kt",
        )
        val titleProvider = source(
            "src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalTabTitleProvider.kt",
        )
        val pluginXml = source("src/main/resources/META-INF/plugin.xml")

        assertFalse(terminalHost.contains("WriteAction"))
        assertFalse(terminalHost.contains("file.rename("))
        assertTrue(terminalHost.contains("updateFilePresentation(file)"))
        assertTrue(titleProvider.contains("EditorTabTitleProvider"))
        assertTrue(pluginXml.contains("<editorTabTitleProvider"))
    }

    @Test
    fun `运行态与未读变化通过官方文件呈现刷新标签图标`() {
        val provider = source(
            "src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileIconProvider.kt",
        )
        val monitor = source(
            "src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt",
        )

        assertTrue(provider.contains("SessionMonitor.getInstance(project)"))
        assertTrue(provider.contains("AnimatedIcon.Default.INSTANCE"))
        assertTrue(provider.contains("AllIcons.General.Modified"))
        // 状态图标取代品牌图标，不并排——并排会让标签页随状态变宽变窄
        assertTrue(provider.contains("else -> AgentIcons.forAgent(agentType)"))
        assertFalse(provider.contains("RowIcon"))
        assertTrue(monitor.contains("val runningChanged = runningIds != running"))
        assertTrue(monitor.contains("if (runningChanged) updateOpenTabIcons()"))
        assertTrue(monitor.contains("updateFilePresentation(file)"))
        assertTrue(
            Regex("""updateOpenTabIcons\(setOf\(sessionId\)\)""")
                .findAll(monitor)
                .count() >= 2,
        )
    }

    @Test
    fun `已打开标记覆盖已有与待绑定会话`() {
        val tree = source(
            "src/main/kotlin/com/github/izerui/imux/toolwindow/AgentSessionTree.kt",
        )

        assertTrue(tree.contains("data class PendingSession("))
        assertTrue(tree.contains("opened = entry.pending.key in openTabs"))
        assertTrue(
            Regex(
                """is NodeData\.PendingSession ->\s+""" +
                    """sessionStatusIcon\(running = false, unread = false, opened = data\.opened\)""",
            ).containsMatchIn(tree),
        )
    }

    @Test
    fun `界面监听器绑定父级 Disposable`() {
        val terminalHost = source(
            "src/main/kotlin/com/github/izerui/imux/terminal/TerminalHost.kt",
        )
        val monitor = source(
            "src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt",
        )
        val factory = source(
            "src/main/kotlin/com/github/izerui/imux/toolwindow/AgentToolWindowFactory.kt",
        )

        assertTrue(terminalHost.contains("EventDispatcher"))
        assertTrue(terminalHost.contains("addListener(listener, parentDisposable)"))
        assertTrue(monitor.contains("EventDispatcher"))
        assertTrue(monitor.contains("addListener(listener, parentDisposable)"))
        assertTrue(factory.contains("content.setDisposer(contentDisposable)"))
    }

    @Test
    fun `终端交互使用终端与 Editor 官方事件`() {
        val editor = source(
            "src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt",
        )
        val monitor = source(
            "src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt",
        )

        assertTrue(editor.contains("keyEventsFlow.collect"))
        assertFalse(editor.contains("addInputInterceptor("))
        assertTrue(editor.contains("EditorMouseListener"))
        assertFalse(monitor.contains("addAWTEventListener"))
        assertFalse(monitor.contains("Toolkit.getDefaultToolkit()"))
    }

    @Test
    fun `终端创建不使用平台 Internal API`() {
        val terminalHost = source(
            "src/main/kotlin/com/github/izerui/imux/terminal/TerminalHost.kt",
        )

        assertFalse(terminalHost.contains("shouldAddToToolWindow("))
        assertTrue(terminalHost.contains("requestFocus(false)"))
        assertTrue(terminalHost.contains("deferSessionStartUntilUiShown(false)"))
    }

    @Test
    fun `项目服务使用注入的 CoroutineScope 调度扫描与界面更新`() {
        val monitor = source(
            "src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt",
        )

        assertTrue(monitor.contains("CoroutineScope"))
        assertTrue(monitor.contains("Dispatchers.IO"))
        assertTrue(monitor.contains("Dispatchers.EDT"))
        assertFalse(monitor.contains("executeOnPooledThread"))
        assertFalse(monitor.contains("ApplicationManager.getApplication().invokeLater"))
    }

    @Test
    fun `Action 使用 DumbAwareAction Presentation 与明确更新线程`() {
        val editor = source(
            "src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt",
        )
        val factory = source(
            "src/main/kotlin/com/github/izerui/imux/toolwindow/AgentToolWindowFactory.kt",
        )

        assertTrue(editor.contains("DumbAwareAction("))
        assertTrue(editor.contains("event.presentation.isEnabledAndVisible"))
        assertTrue(editor.contains("updateActionsAsync()"))
        assertFalse(editor.contains("toolbarComponent.isVisible ="))
        assertTrue(factory.contains("DumbAwareAction"))
        assertTrue(factory.contains("ActionUpdateThread"))
    }

    @Test
    fun `焦点工具窗口颜色图标与快捷键使用平台 API`() {
        val editor = source(
            "src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt",
        )
        val factory = source(
            "src/main/kotlin/com/github/izerui/imux/toolwindow/AgentToolWindowFactory.kt",
        )
        val tree = source(
            "src/main/kotlin/com/github/izerui/imux/toolwindow/AgentSessionTree.kt",
        )

        assertTrue(editor.contains("IdeFocusManager"))
        assertTrue(factory.contains("IdeFocusManager"))
        assertFalse(editor.contains("requestFocusInWindow()"))
        assertFalse(factory.contains("requestFocusInWindow()"))
        assertTrue(factory.contains("SimpleToolWindowPanel"))
        assertTrue(factory.contains("setTitleActions("))
        assertTrue(factory.contains("toolWindowShown("))
        assertTrue(tree.contains("AllIcons.General.Modified"))
        assertTrue(tree.contains("AnimatedIcon.Default.INSTANCE"))
        assertTrue(tree.contains("AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED"))
        assertFalse(tree.contains("AllIcons.Nodes.RunnableMark"))
        assertTrue(tree.contains("CommonShortcuts.ENTER"))
        assertFalse(tree.contains("JBColor("))
        assertFalse(tree.contains("\"● \""))
        assertFalse(tree.contains("\"▶ \""))
    }

    @Test
    fun `相对时间与耗时使用平台本地化格式化工具`() {
        val relativeTime = source(
            "src/main/kotlin/com/github/izerui/imux/toolwindow/RelativeTime.kt",
        )
        val completionSubtitle = source(
            "src/main/kotlin/com/github/izerui/imux/turn/CompletionSubtitle.kt",
        )

        assertTrue(relativeTime.contains("DateFormatUtil.formatBetweenDates"))
        assertTrue(completionSubtitle.contains("NlsMessages.formatDuration"))
    }
}
