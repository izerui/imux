package com.github.izerui.imux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TerminalIntegrationSourceTest {
    /**
     * 随插件安装的脚本**必须真的被打进去**，而且打包配置里写的路径要与仓库里的
     * 文件对得上。
     *
     * 那条 `from(...)` 收的是字符串路径，Gradle 对**不存在**的源路径不报错——
     * 打错一个字母、或者把脚本挪了个目录，构建照样成功，只是 zip 里少一个文件。
     * 失败症状是「功能没做」：`pi-imux-reporter.js` 缺失 → `piReporterScript()`
     * 返回 null → 命令行**不加** `-e` → pi 的标签不跟随（这条取舍是刻意的，
     * 正因为如此它不会报错）。
     *
     * 目标目录也一起钉：`piReporterScriptIn` 找的是插件目录下的 `scripts/`，
     * 打到别处等于没打。
     *
     * codex 曾经也有一个随包分发的 `.ps1`（Windows 上的 SessionStart hook 上报），
     * 已随整套 hook 机制删除——它改读 codex 自己写的运行态 sqlite，不需要任何
     * 随包脚本。这里因此只剩 pi 一个。
     */
    @Test
    fun `随插件安装的脚本在仓库里且打包路径对得上`() {
        val buildFile = File("build.gradle.kts").readText()

        listOf(
            "src/main/js/pi-imux-reporter.js",
        ).forEach { path ->
            assertTrue("仓库里缺少待打包的脚本：$path", File(path).exists())
            assertTrue(
                "build.gradle.kts 里没有把 $path 打进插件目录；Gradle 对不存在的 from() " +
                    "源路径不报错，构建照样成功而 zip 里少一个文件——症状是「功能没做」",
                buildFile.contains("""from("$path") { into("${'$'}{project.name}/scripts") }"""),
            )
        }
        assertTrue(
            "打包必须用 withType 覆盖全部 PrepareSandboxTask：named 只配到第一个，" +
                "runIde 的沙箱与测试沙箱里都会缺脚本，而缺了不报错",
            buildFile.contains("tasks.withType<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>"),
        )
        assertFalse(
            "codex 的 hook 上报脚本已随整套 hook 机制删除：留着一条打包行会把一个" +
                "不存在的源路径写进构建，而 Gradle 对不存在的 from() 源路径不报错",
            buildFile.contains("codex-imux-reporter.ps1"),
        )
    }

    @Test
    fun `支持整个 262 并使用跨版本 detached tab 生命周期`() {
        val buildFile = File("build.gradle.kts").readText()
        val terminalHost =
            File(
                "src/main/kotlin/com/github/izerui/imux/terminal/TerminalHost.kt",
            ).readText()

        assertTrue(
            "插件最低版本必须覆盖 IDEA 2026.2 / build 262",
            buildFile.contains("""sinceBuild = "262""""),
        )
        assertTrue(
            "IDEA 2026.2 平台要求使用 Java 25 工具链",
            buildFile.contains("jvmToolchain(25)"),
        )
        assertTrue(
            "必须通过受控桥接执行两版各自完整的 detach 生命周期",
            terminalHost.contains("detachTabAcross262(manager, tab)"),
        )
        assertTrue(
            "反射必须只按稳定参数查找 detachTab，不能绑定发生漂移的返回类型",
            terminalHost.contains(
                ".getMethod(\"detachTab\", TerminalToolWindowTab::class.java)",
            ),
        )
        assertFalse(
            "不能产生绑定某一版 detachTab 返回类型的直接调用字节码",
            terminalHost.contains("manager.detachTab(tab)"),
        )
        assertFalse(
            "不能用 Internal builder API 绕过 detach，否则旧版会泄漏 tab Content",
            terminalHost.contains(".shouldAddToToolWindow("),
        )
        assertTrue(
            "detached tab 的 TerminalView 必须从跨版本稳定的 tab getter 获取",
            terminalHost.contains("return tab.view"),
        )
        assertTrue(
            "启动环境必须传入终端进程，不能只停留在命令构造层",
            // 用正则跨换行钉住「.envVariables( 之后紧跟着 launchEnvironment(」这条链路，
            // 不能拆成几个独立的 contains：`agentType,`、`tabId,` 这类片段在本文件里
            // 到处都是，拆开之后断言几乎恒真，守卫就名存实亡了（曾因调用改成多行而被
            // 削弱成那样）。这里必须让「把 launchEnvironment 从 envVariables 里摘掉」
            // 这个破坏动作真的能让测试变红。
            Regex("""\.envVariables\(\s*launchEnvironment\(\s*agentType,\s*tabId,""")
                .containsMatchIn(terminalHost),
        )
        assertTrue(
            "tabId 必须在建 view 之前定下：它要随进程启动进入环境变量，" +
                "之后才能从进程 env 读回来认出这个终端（见 LiveSessionProbe）",
            terminalHost.contains("val tabId = newTabId()"),
        )
    }

    @Test
    fun `完成通知打开已有会话时滚动到终端底部`() {
        val monitor =
            File(
                "src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt",
            ).readText()
        val terminalHost =
            File(
                "src/main/kotlin/com/github/izerui/imux/terminal/TerminalHost.kt",
            ).readText()

        // 不绑定接收者写法：打开动作已挪进 openSession，由无捕获的顶层函数转调，
        // 接收者因此从局部的 host 变成现取的服务。要守的是「带滚动语义」这件事本身。
        assertTrue(
            "完成通知必须使用带滚动语义的打开方法",
            monitor.contains("openResumeAtBottom(it.agentType, it.id, it.title)"),
        )
        assertTrue(
            "终端打开后必须通过当前 Editor 的 ScrollingModel 滚到底部",
            terminalHost.contains("scrollVertically(Int.MAX_VALUE)"),
        )
    }

    @Test
    fun `会话 editor 内提供滚动到底部按钮`() {
        val fileEditor =
            File(
                "src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt",
            ).readText()

        assertTrue(
            "会话 editor 必须使用平台 Action Toolbar 提供按钮",
            fileEditor.contains("createActionToolbar(") &&
                fileEditor.contains(""""imuxTerminalEditor""""),
        )
        assertTrue(
            "按钮必须使用接近 OpenAI 样式的简洁向下箭头并提供本地化 tooltip",
            fileEditor.contains("action.scroll.bottom.description") &&
                fileEditor.contains("AllIcons.General.ArrowDown"),
        )
        assertTrue(
            "点击按钮必须复用 TerminalHost 的动态终端 Editor 滚动逻辑",
            fileEditor.contains("val host = TerminalHost.getInstance(project)") &&
                fileEditor.contains("host.scrollToBottom(virtualFile.terminalView)"),
        )
        assertTrue(
            "按钮显隐必须由当前终端 Editor 的可见区域变化直接驱动",
            fileEditor.contains(
                "editor?.scrollingModel?.addVisibleAreaListener(visibleAreaListener)",
            ),
        )
        assertTrue(
            "切换 Editor 时必须摘除旧的滚动监听器",
            fileEditor.contains(
                "observedEditor?.scrollingModel?.removeVisibleAreaListener(visibleAreaListener)",
            ),
        )
        assertTrue(
            "项目关闭时 terminal Editor 可能已先释放，摘 Editor listener 前必须检查生命周期",
            fileEditor.contains("if (observedEditor?.isDisposed == false)"),
        )
        assertTrue(
            "Editor 会动态切换，鼠标监听必须由 observedEditor 绑定逻辑显式管理",
            fileEditor.contains("editor?.addEditorMouseListener(editorMouseListener)"),
        )
        assertFalse(
            "不能同时绑定 parent Disposable 又手工移除，否则 FileEditor dispose 时会重复摘监听器",
            fileEditor.contains("addEditorMouseListener(editorMouseListener, this)"),
        )
        assertTrue(
            "alternate buffer 切换后必须重新绑定当前 Editor",
            fileEditor.contains(
                "virtualFile.terminalView.outputModels.active.collect",
            ),
        )
        assertTrue(
            "滚动事件必须刷新 Action Presentation，由 Action System 决定按钮显隐",
            fileEditor.contains("scrollToolbar?.updateActionsAsync()"),
        )
        assertTrue(
            "按钮必须以浮层形式放进终端 editor，不能挤占终端内容尺寸",
            fileEditor.contains("JBLayeredPane"),
        )
        assertTrue(
            "滚动按钮必须在终端底部水平居中",
            fileEditor.contains(
                "val x = ((width - toolbarWidth) / 2).coerceAtLeast(0)",
            ),
        )
        assertFalse(
            "自定义 editor 外壳不能覆写 preferredSize，否则调整标签宽度后内容区会像被旧尺寸锁住，无法随容器自适应",
            fileEditor.contains("override fun getPreferredSize(): Dimension = terminal.preferredSize"),
        )
    }

    @Test
    fun `滚动按钮必须位于终端组件上层`() {
        val fileEditor =
            File(
                "src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt",
            ).readText()

        assertTrue(
            "终端组件必须显式放在默认层",
            fileEditor.contains("setLayer(terminal, JLayeredPane.DEFAULT_LAYER)"),
        )
        assertTrue(
            "滚动按钮必须显式放在调色板层，否则会被终端组件覆盖",
            fileEditor.contains("setLayer(toolbarComponent, JLayeredPane.PALETTE_LAYER)"),
        )
        assertFalse(
            "不能把图层常量传给 add(Component, int)，JBLayeredPane 会把它解释为组件索引",
            fileEditor.contains("add(terminal, JLayeredPane.DEFAULT_LAYER)") ||
                fileEditor.contains("add(toolbarComponent, JLayeredPane.PALETTE_LAYER)"),
        )
    }

    /**
     * 输入法候选窗的位置只有在焦点落到 EditorComponentImpl 上时才跟随光标，
     * 落在外层 TerminalPanel 上就会退回窗口原点（左上角）。
     * 这两条约束都无法在无平台的单测环境里跑起来验证，只能锁住源码形态。
     */
    @Test
    fun `焦点必须转发到终端的编辑器组件且不缓存`() {
        val fileEditor =
            File(
                "src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt",
            ).readText()

        assertTrue(
            "面板拿到焦点后必须通过 IDE 焦点管理器转发，否则输入法候选窗定位在左上角",
            fileEditor.contains("requestFocusInProject(target, project)"),
        )
        assertTrue(
            "转发目标必须在 focusGained 里当场取，才能跟上 alternate buffer 切换后的新 editor",
            fileEditor.contains("val target = virtualFile.terminalView.preferredFocusableComponent"),
        )
        assertFalse(
            "不能把 preferredFocusableComponent 存成字段：切 alternate buffer 后它会指向已不显示的旧 editor",
            fileEditor.lines().any {
                it.trimStart().startsWith("private val") && it.contains("preferredFocusableComponent")
            },
        )
    }

    @Test
    fun `流式重绘 agent 的输入法组合文本不能进入终端 Editor 布局`() {
        val virtualFile =
            File(
                "src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalVirtualFile.kt",
            ).readText()
        val fileEditor =
            File(
                "src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt",
            ).readText()
        val compositionSupportFile =
            File(
                "src/main/kotlin/com/github/izerui/imux/terminal/AgentImeCompositionSupport.kt",
            )
        val compositionSupport = compositionSupportFile.readText()

        assertTrue(
            "虚拟文件必须保留 agent 类型，才能把平台 IME 规避限定在需要它的 agent 上",
            virtualFile.contains("val agentType: AgentType"),
        )
        assertTrue(
            "需要覆盖层的 agent，其当前 terminal Editor 必须安装非布局型输入法组合文本支持。" +
                "判据要走 AgentType 属性而不是点名某个 agent——点名的写法每加一个 CLI 都得记得改，" +
                "漏了就是打字时输入区抽行",
            fileEditor.contains("virtualFile.agentType.needsImeCompositionOverlay") &&
                fileEditor.contains("AgentImeCompositionSupport(") &&
                fileEditor.contains("virtualFile.terminalView"),
        )
        assertTrue(
            "alternate buffer 更换 Editor 时必须销毁旧组合文本支持并绑定新 Editor",
            fileEditor.contains("imeCompositionSupport?.dispose()") &&
                fileEditor.contains("observedEditor = editor"),
        )
        assertTrue(
            "EditorComponentImpl 会先让 terminal 内部 listener 消费事件，必须在 AWT 分发前观察",
            compositionSupport.contains("Toolkit.getDefaultToolkit().addAWTEventListener(") &&
                compositionSupport.contains("AWTEvent.INPUT_METHOD_EVENT_MASK"),
        )
        assertTrue(
            "原始输入法事件必须在平台创建 inline inlay 前消费掉",
            compositionSupport.contains("event.consume()"),
        )
        assertTrue(
            "已提交文字必须继续通过公开 TerminalView API 发给 CLI",
            compositionSupport.contains("terminalView.sendText(committedText)"),
        )
        assertTrue(
            "未提交组合文本必须使用不参与 Editor 布局的 Swing 覆盖层",
            compositionSupport.contains("contentComponent.add(compositionLabel)") &&
                compositionSupport.contains("compositionLabel.setBounds("),
        )
        assertTrue(
            "组合层必须遮住预编辑起点处的 pi 自绘光标，并在预编辑文本末尾只画一个 caret",
            compositionSupport.contains("isOpaque = true") &&
                compositionSupport.contains("compositionLabel.background") &&
                compositionSupport.contains("compositionLabel.border") &&
                compositionSupport.contains("JBUI.Borders.customLine"),
        )
        assertFalse(
            "inline inlay 必然参与 soft-wrap 计算，不能再用于 Codex 组合文本",
            compositionSupport.contains("InlayModel") ||
                compositionSupport.contains("addInlineElement(") ||
                compositionSupport.contains("disableSoftWrapping"),
        )
        assertFalse(
            "关闭整个 Editor soft wrap 会破坏 Codex 输入框背景和终端网格样式",
            compositionSupport.contains("setUseSoftWraps") ||
                compositionSupport.contains("isUseSoftWraps"),
        )
        assertFalse(
            "只允许使用公开 Terminal API，不能反射或改 terminal 内部实现",
            compositionSupport.contains("java.lang.reflect") ||
                compositionSupport.contains("setInputMethodSupport("),
        )
    }

    @Test
    fun `延迟转发焦点前必须确认终端仍是当前 editor`() {
        val toolWindowFactory =
            File(
                "src/main/kotlin/com/github/izerui/imux/toolwindow/AgentToolWindowFactory.kt",
            ).readText()

        assertTrue(
            "invokeLater 执行前必须用本次 selectionChanged 的 editor 重新校验活动选择",
            toolWindowFactory.contains("val activatedEditor = event.newEditor ?: return") &&
                toolWindowFactory.contains(
                    "event.manager.selectedEditor !== activatedEditor",
                ),
        )
    }
}
