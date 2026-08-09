package com.github.izerui.imux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlatformApiAlignmentSourceTest {

    private fun source(path: String): String =
        File(path).takeIf(File::exists)?.readText().orEmpty()

    /** 断言「不许调用某 API」时用，免得注释里解释原因的那句话把测试带红。 */
    private fun stripComments(source: String): String =
        source.lineSequence()
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
            .joinToString("\n")

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

    /**
     * 覆盖后的 [com.intellij.openapi.wm.impl.FrameTitleBuilder] 对 IDE 里**所有**项目
     * 窗口生效，包括从没开过 AI 会话的项目。若用 `SessionMonitor.getInstance(project)`
     * 查未读，平台每渲染一次标题就会把这个项目服务连同它的轮询协程一起创建出来——
     * 用户没用过 imux，却被扫了一遍会话目录。
     *
     * 这条差异在开发沙箱里看不出来（那儿只有一个项目、还总是开着工具窗口），
     * 单测也碰不到服务容器，只能在源码层面钉死。
     */
    @Test
    fun `窗口标题查未读不得意外创建项目服务`() {
        val builder = source(
            "src/main/kotlin/com/github/izerui/imux/frame/UnreadFrameTitleBuilder.kt",
        )

        assertTrue(builder.contains("getServiceIfCreated(SessionMonitor::class.java)"))
        // 只禁实际调用；注释里那段「为什么不能用 getInstance」的说明要留着
        assertFalse(
            "getInstance 会创建服务，标题渲染不该有这个副作用",
            stripComments(builder).contains("SessionMonitor.getInstance("),
        )
        // 标题也可能在项目关闭过程中被重算
        assertTrue(builder.contains("project.isDisposed"))
    }

    /**
     * 光有 builder 不够：它只在平台自发重算标题时被调用（切文件、项目状态变化）。
     * 未读刚变化的那一刻没有任何重算触发，星号要等到用户下次切文件才出现或消失——
     * 而「切文件」本身往往就是清未读的动作，用户根本看不到星号亮起。
     *
     * 所以 markUnread / clearUnread 两处都必须主动推一次标题。
     */
    @Test
    fun `未读变化时主动刷新窗口标题`() {
        val monitor = source(
            "src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt",
        )

        assertTrue(
            "标记与清除未读都要刷新标题，缺一处就会出现「星号不消失」或「星号不出现」",
            Regex("""updateFrameTitle\(\)""").findAll(monitor).count() >= 3,
        )
        assertTrue(monitor.contains("WindowManager.getInstance().getIdeFrame(project)"))
        assertTrue("设置标题要碰 Swing，必须在 EDT", monitor.contains("Dispatchers.EDT"))
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

    /**
     * 会话树的行高原先完全交给主题：`Tree` 不设 rowHeight，平台就去读当前 LaF 的
     * `Tree.rowHeight`。Islands（2026.2 默认主题）给的是 24，而不少第三方主题
     * ——例如 GitHub Dark Dimmed——的 `ui.Tree` 是空的，只能吃 Darcula 基线，
     * 于是同一份代码在开发沙箱里间距舒展、在装了主题的正式 IDE 里挤成一团。
     *
     * 行高是这个面板的产品要求（16px 图标上下各留 4px），不该随主题漂移，
     * 所以按 Islands 的 24 钉死。这条差异只在装了第三方主题的 IDE 里显形，
     * 单测起不了 LaF，只能在源码层面守住。
     */
    @Test
    fun `会话树行高固定不随主题漂移`() {
        val tree = source(
            "src/main/kotlin/com/github/izerui/imux/toolwindow/AgentSessionTree.kt",
        )

        assertTrue(tree.contains("private const val ROW_HEIGHT = 24"))
        assertTrue(tree.contains("rowHeight = JBUI.scale(ROW_HEIGHT)"))
    }

    /**
     * 系统通知点下去要落到那个会话上，而不是只把 IDEA 叫醒。
     *
     * 原先调的是三参 [com.intellij.ui.SystemNotifications.notify]，点击回调整个没传——
     * 于是点它只等于激活 IDEA 应用，停在哪个窗口全看系统心情。多开项目窗口时最难受：
     * 人是被叫回来了，却停在别的项目上，还得自己翻。
     *
     * 平台其实给了四参重载：`MacOsNotifications` 生成 activationId 把 Runnable 存进映射，
     * 点击时经 NSUserNotificationCenter 的 delegate 取回执行。
     *
     * 光传回调还不够。`FileEditorManager.openFile` 与 `IdeFocusManager.requestFocusInProject`
     * 都只在项目**内部**调焦点，谁都不会把那个 JFrame 提到前台，所以回调里必须显式
     * `focusProjectWindow`。而回调来自 JNA 的原生线程，落到 EDT 才能碰 UI。
     *
     * 这条差异只在正式 IDE 里显形——单测起不了 NSUserNotificationCenter，
     * 只能在源码层面守住。
     */
    @Test
    fun `系统通知点击回到对应项目窗口并打开会话`() {
        val notifier = source(
            "src/main/kotlin/com/github/izerui/imux/turn/TurnNotifier.kt",
        )

        // 四参重载，末位的 trailing lambda 就是点击回调
        assertTrue(
            "系统通知要带点击回调，否则点了无处可去",
            notifier.contains("notify(GROUP_ID, subtitle, title) {"),
        )
        assertTrue(
            "项目内部的焦点调用提不动 JFrame，必须显式激活窗口",
            notifier.contains("ProjectUtil.focusProjectWindow(target, true)"),
        )
        // 回调由 JNA 原生线程发起，碰 UI 前必须回 EDT
        assertTrue(notifier.contains("invokeLater"))
    }

    /**
     * 系统通知的点击回调被平台的应用级静态单例攥着
     * （`MacOsNotifications.myCallbacksByActivationId`，上限 32 条、满了才整体清空）。
     * 回调捕获了什么，什么就跟着一起留到那时——捕获 Project 等于把它连同
     * [com.github.izerui.imux.monitor.SessionMonitor]、`TerminalHost` 整条服务链
     * 钉在静态字段上，用户关掉项目也回收不了。
     *
     * 所以打开动作只能经无捕获的顶层函数引用传进去，回调本身只带走两个字符串，
     * Project 到点击那一刻才按 locationHash 现查。
     *
     * 改回就地写 lambda 会让泄漏悄悄回来：编译照过、单测照绿、界面行为也一模一样，
     * 只有堆里那份 Project 不肯走。只能在源码层面守住。
     */
    @Test
    fun `系统通知回调不捕获 Project 与服务实例`() {
        val notifier = source(
            "src/main/kotlin/com/github/izerui/imux/turn/TurnNotifier.kt",
        )
        val monitor = source(
            "src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt",
        )

        // 回调里认 locationHash，不认捕获来的 Project
        assertTrue(notifier.contains("val locationHash = project.locationHash"))
        assertTrue(notifier.contains("it.locationHash == locationHash"))
        // 打开动作由外部以 (Project, String) 传入，TurnNotifier 自己不持有服务
        assertTrue(notifier.contains("openSession: (Project, String) -> Unit"))

        // 顶层函数（无缩进声明）才没有捕获；挪进类里就成了带 this 的方法引用
        assertTrue(
            "打开动作必须是顶层函数，否则函数引用会捕获宿主实例",
            monitor.contains(
                "internal fun openSessionFromNotification(project: Project, sessionId: String)",
            ),
        )
        assertTrue(monitor.contains("::openSessionFromNotification"))
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
