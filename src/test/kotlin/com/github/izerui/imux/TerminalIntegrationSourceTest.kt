package com.github.izerui.imux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TerminalIntegrationSourceTest {

    @Test
    fun `只支持 262 并使用官方 detached tab 生命周期`() {
        val buildFile = File("build.gradle.kts").readText()
        val terminalHost = File(
            "src/main/kotlin/com/github/izerui/imux/terminal/TerminalHost.kt",
        ).readText()

        assertTrue("插件最低版本必须是 IDEA 2026.2 / build 262", buildFile.contains("""sinceBuild = "262""""))
        assertTrue(
            "隐藏终端创建后必须通过 detachTab 脱离工具窗口，避免被 backend 持久化恢复",
            terminalHost.contains("manager.detachTab(tab)"),
        )
        assertFalse(
            "不能直接取 createTab().view，否则 backend 会把会话当作可恢复终端标签",
            terminalHost.contains(".createTab()\n            .view"),
        )
    }

    @Test
    fun `完成通知打开已有会话时滚动到终端底部`() {
        val monitor = File(
            "src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt",
        ).readText()
        val terminalHost = File(
            "src/main/kotlin/com/github/izerui/imux/terminal/TerminalHost.kt",
        ).readText()

        assertTrue(
            "完成通知必须使用带滚动语义的打开方法",
            monitor.contains("host.openResumeAtBottom(it.agentType, it.id, it.title)"),
        )
        assertTrue(
            "终端打开后必须通过当前 Editor 的 ScrollingModel 滚到底部",
            terminalHost.contains("scrollVertically(Int.MAX_VALUE)"),
        )
    }

    @Test
    fun `会话 editor 内提供滚动到底部按钮`() {
        val fileEditor = File(
            "src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt",
        ).readText()

        assertTrue(
            "会话 editor 必须使用平台 Action Toolbar 提供按钮",
            fileEditor.contains("createActionToolbar(") &&
                fileEditor.contains(""""imuxTerminalEditor""""),
        )
        assertTrue(
            "按钮必须使用接近 OpenAI 样式的简洁向下箭头并提供明确 tooltip",
            fileEditor.contains(""""滚动到终端最新输出"""") &&
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
            "alternate buffer 切换后必须重新绑定当前 Editor",
            fileEditor.contains(
                "virtualFile.terminalView.outputModels.active.collect",
            ),
        )
        assertTrue(
            "滚动事件必须直接更新 toolbar 可见性，不能依赖 Action Toolbar 的 update 时机",
            fileEditor.contains(
                "toolbarComponent.isVisible = editor != null && !host.isScrolledToBottom(editor)",
            ),
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
    }

    @Test
    fun `滚动按钮必须位于终端组件上层`() {
        val fileEditor = File(
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
        val fileEditor = File(
            "src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt",
        ).readText()

        assertTrue(
            "面板拿到焦点后必须转发给 preferredFocusableComponent，否则输入法候选窗定位在左上角",
            fileEditor.contains("target.requestFocusInWindow()"),
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
}
