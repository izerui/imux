package com.github.izerui.imux.terminal

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.settings.ImuxSettings
import com.intellij.icons.AllIcons
import com.intellij.ide.actions.CloseAction
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.IdeFrame
import com.intellij.ui.components.JBLayeredPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
) : UserDataHolderBase(),
    FileEditor {
    /**
     * 把落到面板上的焦点转交给真正的编辑器组件。
     *
     * 输入法候选窗的位置由 AWT 向**焦点所有者**问 `getInputMethodRequests()` 得到。
     * 终端的 EditorComponentImpl 上装有平台自己的输入法支持，外层 TerminalPanel 没有，
     * 因此 FileEditor 获得焦点时仍要把焦点交给当前 terminal editor。
     *
     * Claude 曾经出现的候选窗钉在旧输出处并不是焦点问题，而是 IDEA 262 在真实光标
     * 隐藏时不更新 cursorOffset；该问题由启动时启用 Claude native cursor 模式解决，
     * 见 AgentCommand.kt。
     */
    private val focusForwarder =
        object : FocusAdapter() {
            override fun focusGained(event: FocusEvent) {
                // 必须每次实时取：它解析自 TerminalLayeredPane 的 curEditor，
                // TUI 切 alternate buffer 时会换成另一个 editor 的 content component。
                // 存成字段就会在切 buffer 后把焦点投到已经不显示的旧 editor 上。
                val target = virtualFile.terminalView.preferredFocusableComponent
                if (target !== event.component && target.isShowing) {
                    IdeFocusManager.getInstance(project).requestFocusInProject(target, project)
                }
            }
        }

    /** 已挂上转发器的面板。用它保证只挂一次，也用于 [dispose] 时摘除。 */
    private var focusHost: JComponent? = null

    private var scrollToolbar: ActionToolbar? = null
    private var scrollToolbarComponent: JComponent? = null
    private var scrollButtonVisible = false
    private var observedEditor: Editor? = null
    private var imeCompositionSupport: AgentImeCompositionSupport? = null
    private val visibleAreaListener = VisibleAreaListener { refreshScrollButton() }
    private val editorMouseListener =
        object : EditorMouseListener {
            override fun mousePressed(event: EditorMouseEvent) {
                clearUnread()
            }
        }
    private var activeModelJob: Job? = null
    private var keyEventsJob: Job? = null
    private var followBottomOnActivation = false
    private var disposed = false

    /**
     * macOS 的 CloseContent（Command+W）从组件 DataContext 取 CloseTarget，平台默认目标
     * 会直接 closeFile、绕过 VirtualFilePreCloseCheck。只在本编辑器组件内覆盖该目标，
     * 让快捷键与标签关闭按钮共享同一份确认规则，并保持只关闭当前分屏的原生语义。
     */
    private val closeTarget =
        CloseAction.CloseTarget {
            val fileEditorManager = FileEditorManagerEx.getInstanceEx(project)
            fileEditorManager.currentWindow?.let { currentWindow ->
                fileEditorManager.closeFileWithChecks(virtualFile, currentWindow)
            }
        }

    private val scrollToBottomAction =
        object : DumbAwareAction(
            ImuxBundle.message("action.scroll.bottom.text"),
            ImuxBundle.message("action.scroll.bottom.description"),
            AllIcons.General.ArrowDown,
        ) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun update(event: AnActionEvent) {
                event.presentation.text = ImuxBundle.message("action.scroll.bottom.text")
                event.presentation.description = ImuxBundle.message("action.scroll.bottom.description")
                event.presentation.isEnabledAndVisible = scrollButtonVisible
            }

            override fun actionPerformed(event: AnActionEvent) {
                val host = TerminalHost.getInstance(project)
                host.scrollToBottom(virtualFile.terminalView)
                scrollButtonVisible = false
                scrollToolbar?.updateActionsAsync()
            }
        }

    init {
        ImuxSettings.getInstance().addLanguageListener(this) {
            scrollToBottomAction.templatePresentation.text = ImuxBundle.message("action.scroll.bottom.text")
            scrollToBottomAction.templatePresentation.description =
                ImuxBundle.message("action.scroll.bottom.description")
            scrollToolbar?.updateActionsAsync()
        }

        // 终端输出在应用后台仍会为每次光标变化排 EDT 滚动任务。激活时先同步到
        // 最新位置，随后积压任务因不能向上回滚而全部成为 no-op，避免逐次追到底部。
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            ApplicationActivationListener.TOPIC,
            object : ApplicationActivationListener {
                override fun applicationDeactivated(ideFrame: IdeFrame) {
                    if (ideFrame.project !== project || disposed) return
                    val manager = FileEditorManager.getInstance(project)
                    if (manager.selectedFiles.none { it === virtualFile }) return

                    val host = TerminalHost.getInstance(project)
                    val editor = host.currentTerminalEditor(virtualFile.terminalView)
                    followBottomOnActivation = editor != null && host.isScrolledToBottom(editor)
                }

                override fun applicationActivated(ideFrame: IdeFrame) {
                    if (ideFrame.project !== project || !followBottomOnActivation || disposed) return
                    followBottomOnActivation = false
                    val manager = FileEditorManager.getInstance(project)
                    if (manager.selectedFiles.none { it === virtualFile }) return

                    TerminalHost.getInstance(project).scrollToBottom(virtualFile.terminalView)
                }
            },
        )
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

        val toolbar =
            ActionManager
                .getInstance()
                .createActionToolbar(
                    "imuxTerminalEditor",
                    DefaultActionGroup(scrollToBottomAction),
                    true,
                ).apply {
                    targetComponent = terminal
                    setMiniMode(true)
                }
        scrollToolbar = toolbar
        val toolbarComponent = toolbar.component.apply { isOpaque = false }
        scrollToolbarComponent = toolbarComponent
        startScrollTracking()
        startInputTracking()

        return object : JBLayeredPane(), UiDataProvider {
            init {
                isOpaque = false
                add(terminal)
                setLayer(terminal, JLayeredPane.DEFAULT_LAYER)
                add(toolbarComponent)
                setLayer(toolbarComponent, JLayeredPane.PALETTE_LAYER)
            }

            override fun uiDataSnapshot(sink: DataSink) {
                sink[CloseAction.CloseTarget.KEY] = closeTarget
            }

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
        activeModelJob =
            virtualFile.terminalView.coroutineScope.launch {
                virtualFile.terminalView.outputModels.active.collect {
                    scheduleScrollButtonRefresh()
                }
            }
    }

    private fun startInputTracking() {
        keyEventsJob =
            virtualFile.terminalView.coroutineScope.launch {
                virtualFile.terminalView.keyEventsFlow.collect {
                    withContext(Dispatchers.EDT) {
                        if (!disposed && !project.isDisposed) clearUnread()
                    }
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
            if (observedEditor?.isDisposed == false) {
                observedEditor?.scrollingModel?.removeVisibleAreaListener(visibleAreaListener)
                observedEditor?.removeEditorMouseListener(editorMouseListener)
            }
            imeCompositionSupport?.dispose()
            imeCompositionSupport = null
            observedEditor = editor
            editor?.scrollingModel?.addVisibleAreaListener(visibleAreaListener)
            editor?.addEditorMouseListener(editorMouseListener)
            if (editor != null && virtualFile.agentType.needsImeCompositionOverlay) {
                imeCompositionSupport =
                    AgentImeCompositionSupport(
                        editor,
                        virtualFile.terminalView,
                    )
            }
        }

        scrollButtonVisible = editor != null && !host.isScrolledToBottom(editor)
        scrollToolbar?.updateActionsAsync()
    }

    private fun clearUnread() {
        com.github.izerui.imux.monitor.SessionMonitor
            .getInstance(project)
            .clearUnread(virtualFile.sessionKey)
    }

    /** 只在标签页首次打开时被平台咨询，之后的焦点由 [focusForwarder] 兜住。 */
    override fun getPreferredFocusedComponent(): JComponent = virtualFile.terminalView.preferredFocusableComponent

    override fun getName(): String = virtualFile.displayName

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
        keyEventsJob?.cancel()
        keyEventsJob = null
        if (observedEditor?.isDisposed == false) {
            observedEditor?.scrollingModel?.removeVisibleAreaListener(visibleAreaListener)
            observedEditor?.removeEditorMouseListener(editorMouseListener)
        }
        imeCompositionSupport?.dispose()
        imeCompositionSupport = null
        observedEditor = null
        scrollToolbar = null
        scrollToolbarComponent = null

        // 摘监听器必须在下面的早退之前：拖动标签页会销毁本实例再建一个新的，
        // 早退时不摘就会把已死实例的转发器留在面板上，拖几次叠一串。
        focusHost?.removeFocusListener(focusForwarder)
        focusHost = null

        // 拖动标签页、分屏等操作会先销毁再重建编辑器，平台用这个标记区分。
        // 漏判会导致拖一下标签页就把会话杀了。
        if (virtualFile.getUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN) == true) return

        val sessionKey = virtualFile.sessionKey
        TerminalHost.getInstance(project).closeSession(virtualFile)
        com.github.izerui.imux.monitor.SessionMonitor
            .getInstance(project)
            .sessionClosed(sessionKey)
    }
}
