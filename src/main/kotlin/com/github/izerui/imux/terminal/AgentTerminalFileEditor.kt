package com.github.izerui.imux.terminal

import com.github.izerui.imux.ImuxBundle
import javax.swing.Icon
import com.intellij.openapi.actionSystem.Separator
import com.intellij.ide.DataManager
import com.github.izerui.imux.icons.AgentIcons
import com.github.izerui.imux.clarification.ClarificationService
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.monitor.SessionMonitor
import com.github.izerui.imux.peer.PeerOutcome
import com.github.izerui.imux.peer.PeerOutcomeKind
import com.github.izerui.imux.peer.PeerProgressEvent
import com.github.izerui.imux.peer.collapseWhitespace
import com.github.izerui.imux.peer.PeerProgressKind
import com.github.izerui.imux.peer.PeerProgressSnapshot
import com.github.izerui.imux.settings.ImuxSettings
import com.intellij.icons.AllIcons
import com.intellij.ide.actions.CloseAction
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.actionSystem.ex.ActionButtonLook
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.IdeFrame
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.GotItTooltip
import com.intellij.ui.components.JBLayeredPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.Point
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.Timer

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

    private var scrollButton: ActionButton? = null
    private var scrollButtonVisible = false
    private var observedEditor: Editor? = null
    private var imeCompositionSupport: AgentImeCompositionSupport? = null
    private val messageNavigator = SessionMessageNavigator(project, virtualFile, this)
    private var terminalLayer: JComponent? = null

    /** 终端顶部叠放的提示条：审查进度与结果、断线、轨迹失败等，平时不占行。 */
    private val noticeOverlay = TerminalNoticeOverlay(this, onChanged = { onNoticesChanged() })
    private val pairNotices = noticeOverlay.sink(NoticeSource.PAIR)
    private val talkNotices = noticeOverlay.sink(NoticeSource.TALK)

    /** 点到轨迹卡片之外（终端正文、滚动条、叠加控件、提示条）就收起面板。 */
    private val trailOutsideClicks by lazy { TrailOutsideClicks { trailOverlay.collapse() } }
    private val trailOverlay =
        SessionTrailOverlay(
            project,
            virtualFile,
            this,
            focusTerminal = {
                IdeFocusManager.getInstance(project)
                    .requestFocusInProject(virtualFile.terminalView.preferredFocusableComponent, project)
            },
            relayout = {
                toolStrip?.refresh()
                terminalLayer?.revalidate()
                terminalLayer?.repaint()
            },
            notices = noticeOverlay.sink(NoticeSource.TRAIL),
        )
    private val activeModelDispatcher = ActiveModelDispatcher(
        schedule = { runnable ->
            ApplicationManager.getApplication().invokeLater {
                if (!disposed && !project.isDisposed) runnable.run()
            }
        },
        activeModel = { virtualFile.terminalView.outputModels.active.value },
        onActiveModel = { messageNavigator.activeOutputModelChanged(it) },
    )
    private val visibleAreaListener =
        VisibleAreaListener {
            refreshScrollButton()
            messageNavigator.viewportChanged()
        }
    private val editorMouseListener =
        object : EditorMouseListener {
            override fun mousePressed(event: EditorMouseEvent) {
                clearUnread()
            }

            override fun mouseReleased(event: EditorMouseEvent) {
                toolStrip?.component?.isVisible = true
            }
        }

    /** 拖选终端文字时先藏起浮动图标组，免得挡住要选的那几行；松开鼠标再出来。 */
    private val editorDragListener =
        object : EditorMouseMotionListener {
            override fun mouseDragged(event: EditorMouseEvent) {
                toolStrip?.component?.isVisible = false
            }
        }
    private var activeModelJob: Job? = null
    private var keyEventsJob: Job? = null
    private var followBottomOnActivation = false
    private var disposed = false
    private val peerBusyIcon = AnimatedIcon.Default()
    private var peerSegment = PeerSegmentModel(PeerSegmentKind.IDLE)
    private var peerRefreshed = false
    /** 已经发过一次性提示的审查结果，按完成时间认；打开标签页时已有的结果不再提示。 */
    private var announcedOutcomeAt: Long? = null
    private var discussing: Boolean? = null

    // 右上角浮动图标组：结对编程、先聊再做、会话轨迹，以及其它功能的按钮（例如手机连接）。
    // 开着的功能显示「图标＋短字」并着色（结对显示伙伴名、审查中显示秒数），悬停看说明，点开是各自的菜单。
    private var pairState = ToolState(AgentIcons.pair, "", "")
    private val pairTool =
        object : SessionTool {
            override fun state() = pairState

            override fun perform(anchor: JComponent) = showPeerMenu(anchor)
        }
    private val talkTool =
        object : SessionTool {
            override fun state(): ToolState {
                val open = discussing == true
                return ToolState(
                    icon = AgentIcons.talk,
                    title = ImuxBundle.message("clarify.action.text"),
                    description = ImuxBundle.message(if (open) "clarify.status.open" else "clarify.action.description"),
                    label = if (open) ImuxBundle.message("clarify.tool.open") else null,
                    tone = if (open) NoticeTone.SUCCESS else null,
                )
            }

            override fun perform(anchor: JComponent) {
                ClarificationService.getInstance(project).open(virtualFile.sessionKey, virtualFile.tabId, virtualFile.agentType)
            }
        }

    /** 上一轮审查结果：结果提示收起后留在图标组左边，直到下一轮审查开始。 */
    private val resultChip = ResultChip { showPeerMenu(it) }
    private var toolStrip: TerminalToolStrip? = null
    private val peerElapsedTimer =
        Timer(1_000) {
            if (!disposed) refreshPeer()
        }

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
                setScrollButtonVisible(false)
            }
        }

    init {
        ImuxSettings.getInstance().addLanguageListener(this) {
            scrollToBottomAction.templatePresentation.text = ImuxBundle.message("action.scroll.bottom.text")
            scrollToBottomAction.templatePresentation.description =
                ImuxBundle.message("action.scroll.bottom.description")
            scrollButton?.update()
            refreshPeer()
            refreshTalk()
        }
        SessionMonitor.getInstance(project).peerCoordinator.addStateListener(
            this,
        ) { sessionKey ->
            if (sessionKey == virtualFile.sessionKey) refreshPeer()
        }
        ClarificationService.getInstance(project).addOpenListener(this) { sessionKey ->
            ApplicationManager.getApplication().invokeLater({
                if (!disposed && sessionKey == virtualFile.sessionKey) refreshTalk()
            }, project.disposed)
        }
        refreshPeer()
        refreshTalk()

        // 会话装饰变化（例如连上或断开手机）时，让平台重新取本标签的图标。
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            SessionDecorationsListener.TOPIC,
            SessionDecorationsListener {
                if (!disposed && !project.isDisposed) {
                    FileEditorManager.getInstance(project).updateFilePresentation(virtualFile)
                    toolStrip?.refresh()
                    terminalLayer?.revalidate()
                }
            },
        )

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

        // 必须是 init 的最后一步：上面以及各字段初始化时发出的提示（例如轨迹已是失败状态）
        // 到这里才画出来，回调里用到的 resultChip、toolStrip 此时都已初始化
        noticeOverlay.start()
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

        val scrollButton =
            ActionButton(
                scrollToBottomAction,
                scrollToBottomAction.templatePresentation.clone(),
                SCROLL_ACTION_PLACE,
                ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE,
            ).apply {
                setLook(ActionButtonLook.SYSTEM_LOOK)
                isVisible = scrollButtonVisible
            }
        this.scrollButton = scrollButton
        val navigatorComponent = messageNavigator.component
        val toolButtons =
            listOf(ToolPill(pairTool, pinned = true), ToolPill(talkTool), ToolPill(trailOverlay.tool)) +
                SessionDecorations.tools(project, virtualFile, this, noticeOverlay.sink(NoticeSource.PHONE)).map(::ToolPill)
        val toolStrip = TerminalToolStrip(resultChip, toolButtons)
        this.toolStrip = toolStrip
        toolStrip.refresh()
        val notices = noticeOverlay.component
        // 标签页里固定不变、不属于轨迹卡片的组件；终端正文与滚动条随编辑器切换另行绑定。
        // 轨迹按钮自己负责展开收起，不挂“点外面收起”，否则按下时先收起、松开又展开。
        trailOutsideClicks.attach(navigatorComponent)
        trailOutsideClicks.attach(scrollButton)
        trailOutsideClicks.attach(notices)
        trailOutsideClicks.attach(resultChip)
        toolButtons.filter { it.tool !== trailOverlay.tool }.forEach(trailOutsideClicks::attach)
        startScrollTracking()
        startInputTracking()

        val terminalLayer =
            object : JBLayeredPane(), UiDataProvider {
                init {
                    isOpaque = false
                    add(terminal)
                    setLayer(terminal, JLayeredPane.DEFAULT_LAYER)
                    add(scrollButton)
                    setLayer(scrollButton, JLayeredPane.PALETTE_LAYER)
                    add(navigatorComponent)
                    setLayer(navigatorComponent, JLayeredPane.PALETTE_LAYER)
                    add(trailOverlay.card)
                    setLayer(trailOverlay.card, JLayeredPane.PALETTE_LAYER)
                    add(toolStrip.component)
                    setLayer(toolStrip.component, JLayeredPane.PALETTE_LAYER)
                    // 提示条压在导航轨道之上
                    add(notices)
                    setLayer(notices, JLayeredPane.PALETTE_LAYER + 1)
                }

                override fun uiDataSnapshot(sink: DataSink) {
                    sink[CloseAction.CloseTarget.KEY] = closeTarget
                }

                override fun addNotify() {
                    super.addNotify()
                    scheduleScrollButtonRefresh()
                    ApplicationManager.getApplication().invokeLater({ showToolsGotIt() }, project.disposed)
                }

                override fun doLayout() {
                    terminal.setBounds(0, 0, width, height)

                    val horizontalInset = JBUI.scale(SCROLL_ROW_HORIZONTAL_INSET)
                    val minimumButtonSize = ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE
                    val toolbarWidth =
                        maxOf(width - horizontalInset * 2, minimumButtonSize.width)
                            .coerceAtMost(width.coerceAtLeast(0))
                    val toolbarHeight = minimumButtonSize.height
                    val x = ((width - toolbarWidth) / 2).coerceAtLeast(0)
                    val y = (height - toolbarHeight - JBUI.scale(12)).coerceAtLeast(0)
                    scrollButton.setBounds(x, y, toolbarWidth, toolbarHeight)

                    val navigatorWidth = messageNavigator.preferredWidth
                    val navigatorX =
                        (width - navigatorWidth - JBUI.scale(NAVIGATOR_RIGHT_INSET)).coerceAtLeast(0)
                    navigatorComponent.setBounds(navigatorX, 0, navigatorWidth, height)

                    // 提示条铺满顶部；图标组在它下面、导航轨道左侧；轨迹面板再往下，轨道始终完整可用
                    val noticeHeight = if (notices.isVisible) notices.preferredSize.height else 0
                    notices.setBounds(0, 0, width, noticeHeight)
                    val rightReserve = navigatorWidth + JBUI.scale(NAVIGATOR_RIGHT_INSET)
                    val margin = JBUI.scale(TOOL_STRIP_MARGIN)
                    // 窄分屏时先保住按钮，结果文字按剩余宽度缩短或只留图标
                    val stripRoom = (width - rightReserve - margin * 2).coerceAtLeast(0)
                    val strip = toolStrip.fit(stripRoom)
                    val stripY = noticeHeight + margin
                    toolStrip.component.setBounds(
                        (width - rightReserve - margin - strip.width).coerceAtLeast(0),
                        stripY,
                        strip.width,
                        strip.height,
                    )
                    trailOverlay.layout(width, height, rightReserve, stripY + strip.height)
                }
            }
        this.terminalLayer = terminalLayer
        return terminalLayer
    }

    /** 第一次看到浮动图标组时说明一次；平台按 id 记住已看过。 */
    private fun showToolsGotIt() {
        val target = toolStrip?.component ?: return
        if (disposed || !target.isShowing) return
        GotItTooltip(TOOLS_GOT_IT_ID, ImuxBundle.message("terminal.tools.gotit.text"), this)
            .withHeader(ImuxBundle.message("terminal.tools.gotit.header"))
            // 平台的 BOTTOM_MIDDLE 挂在 @ApiStatus.Internal 的 Companion 上，这里自己算锚点
            .show(target) { component, _ -> Point(component.width / 2, component.height) }
    }

    private fun onNoticesChanged() {
        refreshResultChip()
        toolStrip?.refresh()
        terminalLayer?.revalidate()
        terminalLayer?.repaint()
    }

    /**
     * 结对编程的状态落到三处：按钮图标与说明；审查进行中、失败时的常驻提示；
     * 新结果的一次性提示（之后收进结果 chip）。
     */
    private fun refreshPeer() {
        val coordinator = SessionMonitor.getInstance(project).peerCoordinator
        val key = virtualFile.sessionKey
        val status = coordinator.status(key)
        val outcome = coordinator.lastOutcome(key)
        val model = peerSegmentModel(status, System.currentTimeMillis())
        val previousKind = peerSegment.kind
        peerSegment = model
        val name = model.agentName.orEmpty()
        val progress = status?.progress
        val running = model.kind == PeerSegmentKind.RUNNING
        val failed = outcome?.takeIf { it.kind == PeerOutcomeKind.FAILED && !running }
        val runningText =
            if (progress != null) progressSummary(name, progress) else ImuxBundle.message("action.peer.status.starting", name)

        pairNotices.persistent(
            when {
                running ->
                    Notice(
                        key = "pair:running:${progress?.startedAtMillis ?: 0}",
                        tone = NoticeTone.INFO,
                        text = runningText,
                        icon = peerBusyIcon,
                        actions = listOf(NoticeAction(ImuxBundle.message("action.peer.segment.cancelRun")) { coordinator.cancelCurrentRun(key) }),
                    )
                failed != null -> failedNotice(failed)
                else -> null
            },
        )
        if (peerRefreshed) {
            val fresh = outcome?.takeIf { it.kind != PeerOutcomeKind.FAILED && it.finishedAtMillis != announcedOutcomeAt }
            when {
                fresh != null -> pairNotices.flash(resultNotice(fresh))
                previousKind == PeerSegmentKind.IDLE && model.kind == PeerSegmentKind.READY ->
                    pairNotices.flash(Notice("pair:ready", NoticeTone.SUCCESS, ImuxBundle.message("action.peer.notice.ready", name)))
            }
        }
        announcedOutcomeAt = outcome?.finishedAtMillis
        peerRefreshed = true
        if (running) peerElapsedTimer.start() else peerElapsedTimer.stop()

        pairState =
            pairToolState(
                model,
                failed,
                lastResultLine(outcome, virtualFile.agentType.displayName),
                runningText,
                failed?.let(::failedText),
                peerBusyIcon,
            )
        refreshResultChip()
        toolStrip?.refresh()
    }

    private fun failedText(outcome: PeerOutcome): String =
        ImuxBundle.message(
            "action.peer.notice.failed",
            outcome.targetAgentType.displayName,
            collapseWhitespace(outcome.detail.orEmpty()).let { if (it.length <= MAX_FAILURE_CHARS) it else it.take(MAX_FAILURE_CHARS - 1) + "…" },
        )

    /** 审查失败：「重试」按当时的方式再审一次；还在结对时可以直接停止。 */
    private fun failedNotice(outcome: PeerOutcome): Notice {
        val coordinator = SessionMonitor.getInstance(project).peerCoordinator
        val key = virtualFile.sessionKey
        val bound = coordinator.boundTarget(key) != null
        val retry =
            NoticeAction(ImuxBundle.message("action.peer.notice.retry")) {
                if (coordinator.boundTarget(virtualFile.sessionKey) != null) {
                    coordinator.onTurnCompleted(virtualFile.sessionKey)
                } else {
                    coordinator.reviewOnce(virtualFile.sessionKey, outcome.targetAgentType, null)
                }
            }
        val stop = NoticeAction(ImuxBundle.message("action.peer.close")) { coordinator.unbind(virtualFile.sessionKey) }
        return Notice(
            key = "pair:failed:${outcome.finishedAtMillis}",
            tone = NoticeTone.WARNING,
            text = failedText(outcome),
            actions = if (bound) listOf(retry, stop) else listOf(retry),
        )
    }

    private fun resultNotice(outcome: PeerOutcome): Notice {
        val agent = outcome.targetAgentType.displayName
        return if (outcome.kind == PeerOutcomeKind.PASSED) {
            Notice("pair:passed", NoticeTone.SUCCESS, ImuxBundle.message("action.peer.notice.passed", agent))
        } else {
            Notice(
                "pair:feedback",
                NoticeTone.INFO,
                ImuxBundle.message("action.peer.notice.feedback", agent, virtualFile.agentType.displayName),
                icon = AgentIcons.review,
            )
        }
    }

    /** 结果 chip 只在没有结对提示条时出现：审查中、失败、结果提示还在顶部时不重复显示。 */
    private fun refreshResultChip() {
        val outcome =
            SessionMonitor.getInstance(project).peerCoordinator.lastOutcome(virtualFile.sessionKey)
                ?.takeIf { it.kind != PeerOutcomeKind.FAILED }
        val visible = outcome != null && peerSegment.kind != PeerSegmentKind.RUNNING && !noticeOverlay.isShowing(NoticeSource.PAIR)
        if (outcome != null) {
            val agent = outcome.targetAgentType.displayName
            val time = resultTime(outcome)
            val passed = outcome.kind == PeerOutcomeKind.PASSED
            resultChip.icon = if (passed) AllIcons.Status.Success else AgentIcons.review
            resultChip.setResult(
                visible,
                full = if (passed) ImuxBundle.message("action.peer.chip.passed", time) else ImuxBundle.message("action.peer.chip.feedback", agent, time),
                short = ImuxBundle.message(if (passed) "action.peer.chip.passed.short" else "action.peer.chip.feedback.short"),
            )
            resultChip.toolTipText =
                ImuxBundle.message(
                    "action.peer.chip.tooltip",
                    outcome.round,
                    agent,
                    time,
                    if (passed) ImuxBundle.message("action.peer.chip.passed.detail")
                    else ImuxBundle.message("action.peer.chip.feedback.detail", virtualFile.agentType.displayName),
                )
        } else {
            resultChip.setResult(false, "", "")
        }
        // 文字随语言、结果变化，宽度要重新分配；真正显示哪一种由 doLayout 里的 fit 决定
        toolStrip?.refresh()
        terminalLayer?.revalidate()
    }

    /** 先聊再做：讨论窗口开着时按钮显示「讨论中」，刚打开时提示一次。 */
    private fun refreshTalk() {
        val open = ClarificationService.getInstance(project).isOpen(virtualFile.sessionKey)
        if (discussing == false && open) {
            talkNotices.flash(Notice("talk:open", NoticeTone.SUCCESS, ImuxBundle.message("clarify.notice.open")))
        }
        discussing = open
        toolStrip?.refresh()
    }

    /** 结对编程的下拉菜单：第一行是说明，其余按状态给出可做的事。 */
    private fun showPeerMenu(anchor: JComponent) {
        val key = virtualFile.sessionKey
        val coordinator = SessionMonitor.getInstance(project).peerCoordinator
        val targets = ImuxSettings.getInstance().enabledAgentTypes
        val status = coordinator.status(key)
        val group = DefaultActionGroup()
        group.add(Separator.create(ImuxBundle.message("action.peer.segment.hint")))
        // 上一轮结果（包括单次审查跑完自动解绑之后）：结果 chip 在窄屏被收起时，菜单里也看得到
        if (peerSegment.kind != PeerSegmentKind.RUNNING) {
            lastResultLine(coordinator.lastOutcome(key), virtualFile.agentType.displayName)?.let { group.add(Separator.create(it)) }
        }
        when (peerSegment.kind) {
            // 伙伴直接列在第一层，选一次就开启，不用再展开子菜单
            PeerSegmentKind.IDLE -> {
                group.add(Separator.create(ImuxBundle.message("action.peer.enable.hint")))
                group.addAll(*peerProgrammingActions(project, key, targets))
                group.addSeparator()
                group.add(
                    bannerAction(ImuxBundle.message("action.peer.once.hint"), AgentIcons.review, enabled = targets.isNotEmpty()) {
                        showReviewOnceDialog(project, virtualFile)
                    },
                )
            }
            PeerSegmentKind.RUNNING -> {
                group.add(bannerAction(ImuxBundle.message("action.peer.segment.cancelRun")) { coordinator.cancelCurrentRun(key) })
                group.add(bannerAction(ImuxBundle.message("action.peer.close")) { coordinator.unbind(key) })
            }
            PeerSegmentKind.READY, PeerSegmentKind.PASSED -> {
                val others = targets.filter { it != status?.targetAgentType }
                if (others.isNotEmpty()) {
                    group.add(Separator.create(ImuxBundle.message("action.peer.segment.switch")))
                    group.addAll(*peerProgrammingActions(project, key, others, includeUnbind = false))
                }
                group.addSeparator()
                group.add(bannerAction(ImuxBundle.message("action.peer.close")) { coordinator.unbind(key) })
            }
        }
        JBPopupFactory.getInstance()
            .createActionGroupPopup(null, group, DataManager.getInstance().getDataContext(anchor), JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false)
            .showUnderneathOf(anchor)
    }

    private fun bannerAction(
        text: String,
        icon: Icon? = null,
        enabled: Boolean = true,
        run: () -> Unit,
    ) = object : DumbAwareAction(text, null, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = enabled
        }

        override fun actionPerformed(event: AnActionEvent) = run()
    }

    private fun progressSummary(
        agentName: String,
        progress: PeerProgressSnapshot,
    ): String {
        val elapsedSeconds = ((System.currentTimeMillis() - progress.startedAtMillis) / 1_000).coerceAtLeast(0)
        return ImuxBundle.message(
            "action.peer.progress.summary",
            agentName,
            progress.round,
            progress.completedActions,
            progressText(progress.current, MAX_BANNER_SUBJECT_CHARS),
            elapsedSeconds,
        )
    }

    private fun progressText(
        event: PeerProgressEvent,
        subjectLimit: Int = Int.MAX_VALUE,
    ): String {
        val subject =
            event.subject.let {
                if (it.length <= subjectLimit) it else it.take(subjectLimit - 1) + "…"
            }
        return when (event.kind) {
            PeerProgressKind.STARTING -> ImuxBundle.message("action.peer.progress.starting")
            PeerProgressKind.THINKING -> ImuxBundle.message("action.peer.progress.thinking")
            PeerProgressKind.TOOL_STARTED ->
                ImuxBundle.message("action.peer.progress.tool.running", subject.ifBlank { "tool" })

            PeerProgressKind.TOOL_FINISHED ->
                ImuxBundle.message("action.peer.progress.tool.finished", subject.ifBlank { "tool" })

            PeerProgressKind.RETRYING ->
                ImuxBundle.message("action.peer.progress.retrying", subject)

            PeerProgressKind.RESPONDING -> ImuxBundle.message("action.peer.progress.responding")
            PeerProgressKind.COMPLETED -> ImuxBundle.message("action.peer.progress.completed")
            PeerProgressKind.FAILED -> ImuxBundle.message("action.peer.progress.failed", subject)
        }
    }

    private fun startScrollTracking() {
        activeModelJob =
            virtualFile.terminalView.coroutineScope.launch {
                virtualFile.terminalView.outputModels.active.collect {
                    scheduleScrollButtonRefresh()
                    activeModelDispatcher.emission()
                }
            }
    }

    private fun startInputTracking() {
        keyEventsJob =
            virtualFile.terminalView.coroutineScope.launch {
                val monitor = SessionMonitor.getInstance(project)
                virtualFile.terminalView.keyEventsFlow.conflate().collect {
                    if (!monitor.isUnread(virtualFile.sessionKey)) return@collect
                    withContext(Dispatchers.EDT) {
                        if (
                            !disposed &&
                            !project.isDisposed &&
                            monitor.isUnread(virtualFile.sessionKey)
                        ) {
                            monitor.clearUnread(virtualFile.sessionKey)
                        }
                    }
                }
            }
    }

    private fun scheduleScrollButtonRefresh() {
        ApplicationManager.getApplication().invokeLater {
            if (!disposed && !project.isDisposed) {
                refreshScrollButton()
            }
        }
    }

    private fun refreshScrollButton() {
        val host = TerminalHost.getInstance(project)
        val editor = host.currentTerminalEditor(virtualFile.terminalView)
        if (editor !== observedEditor) {
            if (observedEditor?.isDisposed == false) {
                observedEditor?.scrollingModel?.removeVisibleAreaListener(visibleAreaListener)
                observedEditor?.removeEditorMouseListener(editorMouseListener)
                observedEditor?.removeEditorMouseMotionListener(editorDragListener)
            }
            imeCompositionSupport?.dispose()
            imeCompositionSupport = null
            observedEditor = editor
            trailOutsideClicks.bindEditor(editor?.let(::terminalClickTargets).orEmpty())
            editor?.scrollingModel?.addVisibleAreaListener(visibleAreaListener)
            editor?.addEditorMouseListener(editorMouseListener)
            editor?.addEditorMouseMotionListener(editorDragListener)
            messageNavigator.bind(editor)
            if (editor != null && virtualFile.agentType.needsImeCompositionOverlay) {
                imeCompositionSupport =
                    AgentImeCompositionSupport(
                        editor,
                        virtualFile.terminalView,
                    )
            }
        }

        setScrollButtonVisible(editor != null && !host.isScrolledToBottom(editor))
    }

    private fun setScrollButtonVisible(visible: Boolean) {
        scrollButtonVisible = visible
        scrollButton?.isVisible = visible
        scrollButton?.update()
    }

    private fun clearUnread() {
        SessionMonitor
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
        peerElapsedTimer.stop()
        activeModelJob?.cancel()
        activeModelJob = null
        keyEventsJob?.cancel()
        keyEventsJob = null
        if (observedEditor?.isDisposed == false) {
            observedEditor?.scrollingModel?.removeVisibleAreaListener(visibleAreaListener)
            observedEditor?.removeEditorMouseListener(editorMouseListener)
            observedEditor?.removeEditorMouseMotionListener(editorDragListener)
        }
        imeCompositionSupport?.dispose()
        imeCompositionSupport = null
        Disposer.dispose(messageNavigator)
        observedEditor = null
        scrollButton = null

        // 摘监听器必须在下面的早退之前：拖动标签页会销毁本实例再建一个新的，
        // 早退时不摘就会把已死实例的转发器留在面板上，拖几次叠一串。
        focusHost?.removeFocusListener(focusForwarder)
        focusHost = null
        // 同理：终端编辑器会被新实例沿用，收起监听要摘干净，免得点一下去调已销毁的面板
        trailOutsideClicks.dispose()

        // 拖动标签页、分屏等操作会先销毁再重建编辑器，平台用这个标记区分。
        // 漏判会导致拖一下标签页就把会话杀了。
        if (virtualFile.getUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN) == true) return

        val sessionKey = virtualFile.sessionKey
        // 会话真正关闭才取消轨迹生成；拖动标签页、分屏重建已在上面早退，任务继续。
        // 按 tabId 而不是当前 sessionKey 清理：/clear、/new 迁移前仍在跑的任务登记在旧 key 下
        com.github.izerui.imux.overview.ProgressOverviewService
            .getInstance(project)
            .trailStore
            .releaseOwner(virtualFile.tabId)
        project.serviceIfCreated<ClarificationService>()?.releaseOwner(virtualFile.tabId)
        TerminalHost.getInstance(project).closeSession(virtualFile)
        com.github.izerui.imux.monitor.SessionMonitor
            .getInstance(project)
            .sessionClosed(sessionKey)
    }

    private companion object {
        const val MAX_BANNER_SUBJECT_CHARS = 48
        const val MAX_FAILURE_CHARS = 160
        const val TOOL_STRIP_MARGIN = 8
        const val TOOLS_GOT_IT_ID = "imux.terminal.tools"
        const val NAVIGATOR_RIGHT_INSET = 14
        const val SCROLL_ACTION_PLACE = "imuxTerminalEditor"
        const val SCROLL_ROW_HORIZONTAL_INSET = 30
    }
}

internal fun showReviewOnceDialog(project: Project, file: AgentTerminalVirtualFile) {
    if (project.isDisposed) return
    val targets = ImuxSettings.getInstance().enabledAgentTypes
    if (targets.isEmpty()) return
    val coordinator = SessionMonitor.getInstance(project).peerCoordinator
    if (coordinator.boundTarget(file.sessionKey) != null) return
    val dialog = ReviewOnceDialog(project, targets, file.agentType)
    if (!dialog.showAndGet() || project.isDisposed) return
    // 弹窗期间标签页可能关闭或迁移；确认后重新校验实例，并使用此刻的会话 key。
    if (TerminalHost.getInstance(project).fileFor(file.terminalView) !== file) return
    val instruction = dialog.instruction.takeIf { it.isNotBlank() }
    coordinator.reviewOnce(file.sessionKey, dialog.selectedAgent, instruction)
}

private class ReviewOnceDialog(
    project: Project,
    private val targets: List<AgentType>,
    currentAgentType: AgentType,
) : DialogWrapper(project, false) {
    private val instructionField = JBTextField().apply { columns = 30 }
    private val agentCombo = ComboBox(targets.toTypedArray()).apply {
        renderer = javax.swing.DefaultListCellRenderer().let { base ->
            javax.swing.ListCellRenderer<Any?> { list, value, index, isSelected, cellHasFocus ->
                base.getListCellRendererComponent(list, (value as? AgentType)?.displayName ?: value, index, isSelected, cellHasFocus)
            }
        }
        val preferred = preferredReviewAgent(targets, currentAgentType)
        selectedItem = preferred
    }

    val instruction: String get() = instructionField.text.orEmpty()
    val selectedAgent: AgentType get() = agentCombo.selectedItem as AgentType

    init {
        title = ImuxBundle.message("action.peer.once.input.title")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(java.awt.GridBagLayout())
        val gbc = java.awt.GridBagConstraints().apply {
            fill = java.awt.GridBagConstraints.HORIZONTAL
            insets = java.awt.Insets(4, 4, 4, 4)
        }

        if (targets.size > 1) {
            gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
            panel.add(JLabel(ImuxBundle.message("action.peer.once.input.agent")), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0
            panel.add(agentCombo, gbc)
        }

        gbc.gridx = 0; gbc.gridy = if (targets.size > 1) 1 else 0; gbc.weightx = 0.0
        panel.add(JLabel(ImuxBundle.message("action.peer.once.input.message")), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(instructionField, gbc)

        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = instructionField
}

internal fun preferredReviewAgent(targets: List<AgentType>, current: AgentType): AgentType {
    val currentIndex = targets.indexOf(current)
    return if (currentIndex >= 0 && targets.size > 1) {
        targets[(currentIndex + 1) % targets.size]
    } else {
        targets.first()
    }
}
