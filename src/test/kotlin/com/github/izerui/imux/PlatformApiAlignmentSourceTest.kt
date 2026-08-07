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

    /**
     * 插件的 jar 不在系统 classpath 上，[java.sql.DriverManager] 靠 ServiceLoader
     * 发现驱动时用的却是系统类加载器，于是 `sqlite-jdbc` 明明打进了包也会报
     * 「No suitable driver found」——实测正式 IDE 日志里刷了上百条，
     * codex 的标题库从来没读成功过，标题一直回退到首条用户消息。
     *
     * 这个差异单测复现不了：Gradle 的测试 JVM 里 DriverManager 一切正常。
     * 只能在源码层面把它钉死。
     */
    @Test
    fun `读 sqlite 不走 DriverManager`() {
        val index = source(
            "src/main/kotlin/com/github/izerui/imux/session/CodexThreadIndex.kt",
        )

        // 只禁实际调用；注释里那段「不要改回 DriverManager」的原因说明要留着
        assertFalse(
            "插件类加载器下 DriverManager 找不到驱动",
            index.contains("DriverManager.getConnection"),
        )
        assertTrue(index.contains("SQLiteDataSource"))
    }

    /**
     * pending key 换成真实会话 id 是核心状态迁移，不能挂在界面的重绘上。
     *
     * `drainNewBindings()` 是破坏性读取，取走即清空。原先它的唯一消费点在
     * `AgentSessionTree.reload()` 里，于是这笔迁移发生与否取决于「那棵 Swing 树
     * 有没有重绘」——而树是工具窗口懒加载出来、可被销毁的东西。它一旦没接住，
     * 终端就永远停留在 pending key 下：再点该会话会重开一个 --resume 终端，
     * 与仍在运行的原终端抢同一个会话。
     */
    @Test
    fun `绑定迁移由 monitor 消费而不是界面`() {
        val monitor = source(
            "src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt",
        )
        val tree = source(
            "src/main/kotlin/com/github/izerui/imux/toolwindow/AgentSessionTree.kt",
        )

        assertTrue(monitor.contains("drainNewBindings()"))
        assertTrue(monitor.contains("rebindKey("))
        assertFalse("界面不该消费绑定", tree.contains("drainNewBindings"))
        assertFalse("界面不该负责换 key", tree.contains("rebindKey"))
    }

    @Test
    fun `运行态与未读变化通过官方文件呈现刷新标签图标`() {
        val provider = source(
            "src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileIconProvider.kt",
        )
        val monitor = source(
            "src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt",
        )

        val icons = source("src/main/kotlin/com/github/izerui/imux/icons/AgentIcons.kt")

        assertTrue(provider.contains("SessionMonitor.getInstance(project)"))
        // 状态修饰品牌图标而不取代它
        assertTrue(provider.contains("AgentIcons.forTab(agentType, running, unread)"))
        // 帧调度与透明度都用平台 API，不自己造帧循环
        assertTrue(icons.contains("AnimatedIcon("))
        assertTrue(icons.contains("IconLoader.getTransparentIcon("))
        // 未读格与会话列表用同一个常量，两处样式才一致
        assertTrue(icons.contains("AllIcons.General.Modified"))
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

    /**
     * 单击/双击开关组件写好了，但只有 `setAdditionalGearActions(` 把它挂上齿轮菜单才生效。
     * 这一行一旦被误删，⋮ 菜单里的开关就消失，而现有单测依然全绿——编译器查不出、
     * 单测碰不到，只能在源码层面钉死这次挂载。
     */
    @Test
    fun `单击双击开关挂在工具窗口齿轮菜单上`() {
        val factory = source(
            "src/main/kotlin/com/github/izerui/imux/toolwindow/AgentToolWindowFactory.kt",
        )

        assertTrue(factory.contains("setAdditionalGearActions("))
        assertTrue(factory.contains("ToggleSingleClickAction()"))
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
