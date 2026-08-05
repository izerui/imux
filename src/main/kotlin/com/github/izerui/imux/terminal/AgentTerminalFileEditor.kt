package com.github.izerui.imux.terminal

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLayeredPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.awt.Dimension
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JLayeredPane

/**
 * 把终端 view 的组件挂到 editor tab 上，并在标签页关闭时结束会话。
 */
class AgentTerminalFileEditor(
    private val project: Project,
    private val virtualFile: AgentTerminalVirtualFile,
) : UserDataHolderBase(), FileEditor {

    /**
     * 把落到面板上的焦点转交给真正的编辑器组件。
     *
     * 输入法候选窗的位置由 AWT 向**焦点所有者**问 `getInputMethodRequests()` 得到。
     * 全平台只有 EditorComponentImpl 会返回跟随 caret 的实现（内部换算成屏幕坐标），
     * 而 TerminalView.component 是外层的 TerminalPanel，它返回 null——
     * AWT 拿不到位置就退回窗口原点，表现就是候选窗永远钉在左上角。
     *
     * 为什么 codex 没事而 claude 有事：codex 的 TUI 跑在 alternate screen buffer 里，
     * 平台切 buffer 时会把焦点移到另一个 editor 上，顺手把焦点“修好”了；
     * claude 的 TUI 始终在主 buffer 重绘，从不触发这次转移，焦点就一直停在面板上。
     * 底部终端工具窗口有自己的焦点管理兜着，只有我们这套 FileEditor 承载会暴露。
     */
    private val focusForwarder = object : FocusAdapter() {
        override fun focusGained(event: FocusEvent) {
            // 必须每次实时取：它解析自 TerminalLayeredPane 的 curEditor，
            // TUI 切 alternate buffer 时会换成另一个 editor 的 content component。
            // 存成字段就会在切 buffer 后把焦点投到已经不显示的旧 editor 上。
            val target = virtualFile.terminalView.preferredFocusableComponent
            if (target !== event.component && target.isShowing) {
                target.requestFocusInWindow()
            }
        }
    }

    /** 已挂上转发器的面板。用它保证只挂一次，也用于 [dispose] 时摘除。 */
    private var focusHost: JComponent? = null

    private var scrollToolbarComponent: JComponent? = null
    private var observedEditor: Editor? = null
    private val visibleAreaListener = VisibleAreaListener { refreshScrollButton() }
    private var activeModelJob: Job? = null
    private var disposed = false

    private val scrollToBottomAction =
        object : AnAction(
            "滚动到底部",
            "滚动到终端最新输出",
            AllIcons.General.ArrowDown,
        ), DumbAware {
            override fun actionPerformed(event: AnActionEvent) {
                val host = TerminalHost.getInstance(project)
                host.scrollToBottom(virtualFile.terminalView)
                scrollToolbarComponent?.isVisible = false
            }
        }

    private val editorComponent: JComponent by lazy(::createEditorComponent)

    override fun getComponent(): JComponent = editorComponent

    private fun createEditorComponent(): JComponent {
        val terminal = virtualFile.terminalView.component
        // getComponent 会被平台反复调用，这里做成幂等的
        if (focusHost !== terminal) {
            focusHost?.removeFocusListener(focusForwarder)
            terminal.addFocusListener(focusForwarder)
            focusHost = terminal
        }

        val toolbar = ActionManager.getInstance().createActionToolbar(
            "imuxTerminalEditor",
            DefaultActionGroup(scrollToBottomAction),
            true,
        ).apply {
            targetComponent = terminal
            setMiniMode(true)
        }
        val toolbarComponent = toolbar.component.apply {
            isOpaque = false
            isVisible = false
        }
        scrollToolbarComponent = toolbarComponent
        startScrollTracking()

        return object : JBLayeredPane() {
            init {
                isOpaque = false
                add(terminal)
                setLayer(terminal, JLayeredPane.DEFAULT_LAYER)
                add(toolbarComponent)
                setLayer(toolbarComponent, JLayeredPane.PALETTE_LAYER)
            }

            override fun getPreferredSize(): Dimension = terminal.preferredSize

            override fun addNotify() {
                super.addNotify()
                scheduleScrollButtonRefresh()
            }

            override fun doLayout() {
                terminal.setBounds(0, 0, width, height)

                val preferred = toolbarComponent.preferredSize
                val buttonSize = ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE
                val toolbarWidth = maxOf(preferred.width, buttonSize.width)
                val toolbarHeight = maxOf(preferred.height, buttonSize.height)
                val x = ((width - toolbarWidth) / 2).coerceAtLeast(0)
                val y = (height - toolbarHeight - JBUI.scale(12)).coerceAtLeast(0)
                toolbarComponent.setBounds(x, y, toolbarWidth, toolbarHeight)
            }
        }
    }

    private fun startScrollTracking() {
        activeModelJob = virtualFile.terminalView.coroutineScope.launch {
            virtualFile.terminalView.outputModels.active.collect {
                scheduleScrollButtonRefresh()
            }
        }
    }

    private fun scheduleScrollButtonRefresh() {
        ApplicationManager.getApplication().invokeLater {
            if (!disposed && !project.isDisposed) refreshScrollButton()
        }
    }

    private fun refreshScrollButton() {
        val host = TerminalHost.getInstance(project)
        val editor = host.currentTerminalEditor(virtualFile.terminalView)
        if (editor !== observedEditor) {
            observedEditor?.scrollingModel?.removeVisibleAreaListener(visibleAreaListener)
            observedEditor = editor
            editor?.scrollingModel?.addVisibleAreaListener(visibleAreaListener)
        }

        val toolbarComponent = scrollToolbarComponent ?: return
        toolbarComponent.isVisible = editor != null && !host.isScrolledToBottom(editor)
    }

    /** 只在标签页首次打开时被平台咨询，之后的焦点由 [focusForwarder] 兜住。 */
    override fun getPreferredFocusedComponent(): JComponent =
        virtualFile.terminalView.preferredFocusableComponent

    override fun getName(): String = virtualFile.name

    override fun getFile(): VirtualFile = virtualFile

    override fun setState(state: FileEditorState) = Unit

    override fun getState(level: FileEditorStateLevel): FileEditorState = FileEditorState.INSTANCE

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun dispose() {
        disposed = true
        activeModelJob?.cancel()
        activeModelJob = null
        observedEditor?.scrollingModel?.removeVisibleAreaListener(visibleAreaListener)
        observedEditor = null
        scrollToolbarComponent = null

        // 摘监听器必须在下面的早退之前：拖动标签页会销毁本实例再建一个新的，
        // 早退时不摘就会把已死实例的转发器留在面板上，拖几次叠一串。
        focusHost?.removeFocusListener(focusForwarder)
        focusHost = null

        // 拖动标签页、分屏等操作会先销毁再重建编辑器，平台用这个标记区分。
        // 漏判会导致拖一下标签页就把会话杀了。
        if (virtualFile.getUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN) == true) return

        TerminalHost.getInstance(project).closeSession(virtualFile.sessionKey)
    }
}
