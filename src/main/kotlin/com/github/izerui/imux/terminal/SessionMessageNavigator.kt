package com.github.izerui.imux.terminal

import com.github.izerui.imux.model.AgentSession
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.monitor.SessionMonitor
import com.github.izerui.imux.session.NavigationTranscriptIndex
import com.github.izerui.imux.session.SessionExchange
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.plugins.terminal.view.TerminalContentChangeEvent
import org.jetbrains.plugins.terminal.view.TerminalOutputModelListener
import org.jetbrains.plugins.terminal.view.TerminalOutputModel
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JComponent

internal data class UserMessageAnchor(
    /** 定位当次快照中的相对位置，保留给纯函数测试与预览身份比较。 */
    val offset: Int,
    val userPreview: String,
    val replyPreview: String,
    /** Platform TerminalOutputModel 中不受 history trimming 平移影响的绝对位置。 */
    val absoluteOffset: Long = offset.toLong(),
)

internal data class NavigationSessionState(
    val sessionId: String?,
    val session: AgentSession?,
    val transcriptGeneration: Long = 0L,
)

internal data class NavigationSessionIdentity(
    val sessionId: String?,
    val agentType: AgentType?,
    val filePath: Path?,
    val lastActiveAt: Instant?,
    val transcriptGeneration: Long,
)

internal fun NavigationSessionState.identity(): NavigationSessionIdentity =
    NavigationSessionIdentity(
        sessionId,
        session?.agentType,
        session?.filePath,
        session?.lastActiveAt,
        transcriptGeneration,
    )

internal class NavigationSessionChangeTracker {
    private var initialized = false
    private var identity: NavigationSessionIdentity? = null

    fun changed(next: NavigationSessionState): Boolean {
        val nextIdentity = next.identity()
        if (initialized && identity == nextIdentity) return false
        initialized = true
        identity = nextIdentity
        return true
    }

    fun remember(next: NavigationSessionState) {
        initialized = true
        identity = next.identity()
    }
}

internal fun refreshedAnchor(
    current: UserMessageAnchor,
    anchors: List<UserMessageAnchor>,
): UserMessageAnchor? =
    anchors.firstOrNull {
        it.absoluteOffset == current.absoluteOffset && it.userPreview == current.userPreview
    }

internal fun nextCollocatedAnchor(
    anchors: List<UserMessageAnchor>,
    previous: UserMessageAnchor?,
): UserMessageAnchor? {
    if (anchors.isEmpty()) return null
    val previousIndex = anchors.indexOf(previous)
    return anchors[(previousIndex + 1).mod(anchors.size)]
}

internal fun previewNeedsRebuild(
    current: UserMessageAnchor?,
    refreshed: UserMessageAnchor,
    currentScreenPoint: Point?,
    refreshedScreenPoint: Point,
): Boolean = current != refreshed || currentScreenPoint != refreshedScreenPoint

private data class NormalizedText(
    val text: String,
    val offsets: IntArray,
)

/**
 * 把 transcript 里的用户消息定位到 Terminal Editor 文档中。
 *
 * 终端会软换行、窗口缩放后也会重新排版，因此先把两边空白折叠，同时保留归一化字符
 * 到原文 offset 的映射。
 *
 * 定位只用一条各家 CLI 通用的规律：**对话按 transcript 的顺序自上而下渲染**。
 * 于是把提问和回复展平成一条消息链，从文档尾部反向逐条匹配，每定位一条就把游标
 * 压到它之前——回复虽然不产出锚点，但同样推游标。三件事因此一起解决：
 *
 * - 助手复述提问抢不走锚点：复述在回复内部，必然落在游标之后，够不着；
 * - 重复提问按轮次分开：游标单调递减，n 轮相同提问必然落在 n 个不同位置；
 * - 终端裁剪历史后只剩一处时，归给较新的那轮——裁掉的总是更早的内容。
 *
 * 不认识任何渲染符号，因此换一版 TUI 前缀不会让整轮消息从轨道上消失。已知局限见
 * `末轮回复尚未写入时锚点可能落在后续输出上`。
 */
internal fun locateUserMessageAnchors(
    documentText: String,
    exchanges: List<SessionExchange>,
    documentStartOffset: Long = 0L,
): List<UserMessageAnchor> {
    val document = normalizedWithOffsets(documentText)
    if (document.text.isEmpty()) return emptyList()

    // 提问、回复、提问、回复……展平成一条与渲染顺序一致的链，回复只推游标不产出锚点
    val chain =
        exchanges.flatMap { listOf(it.userText to it, it.assistantReply to null) }

    var searchBefore = document.text.lastIndex
    val anchors = mutableListOf<UserMessageAnchor>()
    chain.asReversed().forEach { (text, exchange) ->
        val normalized = normalizeWhitespace(text)
        if (normalized.isEmpty()) return@forEach
        val found = document.text.lastIndexOf(normalized.take(MATCH_PREFIX_CHARS), searchBefore)
        if (found < 0) return@forEach
        if (exchange != null) {
            anchors +=
                UserMessageAnchor(
                    offset = document.offsets[found],
                    userPreview = truncated(normalized, USER_PREVIEW_CHARS),
                    replyPreview = truncated(normalizeWhitespace(exchange.assistantReply), REPLY_PREVIEW_CHARS),
                    absoluteOffset = documentStartOffset + document.offsets[found],
                )
        }
        searchBefore = found - 1
    }
    return anchors.asReversed()
}

internal fun changeCanMoveAnchors(
    changeOffset: Int,
    anchors: List<UserMessageAnchor>,
    outputStartOffset: Long,
): Boolean = anchors.any { it.absoluteOffset - outputStartOffset >= changeOffset }

internal fun latestExchangeResolved(
    exchanges: List<SessionExchange>,
    anchors: List<UserMessageAnchor>,
): Boolean {
    val latest = exchanges.lastOrNull() ?: return true
    val expectedPreview = truncated(normalizeWhitespace(latest.userText), USER_PREVIEW_CHARS)
    return anchors.lastOrNull()?.userPreview == expectedPreview
}

private fun truncated(
    value: String,
    limit: Int,
): String = if (value.length <= limit) value else value.take(limit - 1).trimEnd() + "…"

internal class SessionMessageNavigator(
    private val project: com.intellij.openapi.project.Project,
    private val virtualFile: AgentTerminalVirtualFile,
    private val ownerEditor: FileEditor,
) : Disposable {
    private var editor: Editor? = null
    private var anchors = emptyList<UserMessageAnchor>()
    private var refreshJob: Job? = null
    private val refreshRequested = AtomicBoolean()
    private val locateRequested = AtomicBoolean(true)
    private val awaitingTerminalContent = AtomicBoolean()
    private val outputGeneration = AtomicLong()
    private val transcriptIndex = NavigationTranscriptIndex()

    @Volatile
    private var disposed = false

    private var outputListenerDisposable: Disposable? = null
    private val sessionChangeTracker = NavigationSessionChangeTracker()
    private var previewAnchor: UserMessageAnchor? = null
    private var previewBalloon: Balloon? = null
    private var previewScreenPoint: Point? = null
    private val hideAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val previewUserLabel = JBLabel()
    private val previewReplyLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val rail = NavigatorRail()
    val component: JComponent get() = rail
    val preferredWidth: Int get() = JBUI.scale(RAIL_WIDTH)
    private val outputModelListener =
        object : TerminalOutputModelListener {
            override fun afterContentChanged(event: TerminalContentChangeEvent) {
                outputGeneration.incrementAndGet()
                if (!awaitingTerminalContent.compareAndSet(true, false)) return
                locateRequested.set(true)
                scheduleRefresh()
            }
        }

    init {
        SessionMonitor.getInstance(project).addListener(this, ::sessionStateChanged)
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    if (event.oldEditor === ownerEditor && event.newEditor !== ownerEditor) {
                        hidePreview()
                    }
                }
            },
        )
    }

    fun bind(editor: Editor?) {
        if (this.editor === editor) {
            scheduleRefresh()
            return
        }
        unbind()
        locateRequested.set(true)
        this.editor = editor
        if (editor != null) {
            val listenerDisposable = Disposer.newDisposable("imux-session-message-output")
            outputListenerDisposable = listenerDisposable
            virtualFile.terminalView.outputModels.active.value.addListener(
                listenerDisposable,
                outputModelListener,
            )
        }
        scheduleRefresh()
    }

    fun scheduleRefresh() {
        refreshRequested.set(true)
        if (refreshJob?.isActive == true) return

        refreshJob =
            virtualFile.terminalView.coroutineScope.launch(Dispatchers.IO) {
                try {
                    var delayMillis = REFRESH_DEBOUNCE_MS
                    while (refreshRequested.get()) {
                        delay(delayMillis)
                        // 合并等待期间的变化；计算期间的新变化会重新置 true，再跑一轮。
                        refreshRequested.set(false)
                        refreshAnchors()
                        // 首次尽快补出圆点；持续输出时降低整篇终端扫描频率，避免争抢文档读锁。
                        delayMillis = CONTINUOUS_REFRESH_INTERVAL_MS
                    }
                } finally {
                    withContext(NonCancellable + Dispatchers.EDT) {
                        refreshJob = null
                        // 补住「while 判空之后、Job 真正结束之前」到达的最后一次变化。
                        if (!disposed && refreshRequested.get()) scheduleRefresh()
                    }
                }
            }
    }

    private suspend fun refreshAnchors() {
        val snapshot = withContext(Dispatchers.EDT) { navigationSnapshot() }
        if (snapshot == null) {
            withContext(Dispatchers.EDT) { clearHighlighters() }
            return
        }
        val transcript = transcriptIndex.refresh(snapshot.session)
        if (transcript.changed) {
            locateRequested.set(true)
        }
        if (!locateRequested.get()) return

        val outputSnapshot =
            ReadAction.computeBlocking<org.jetbrains.plugins.terminal.view.TerminalOutputModelSnapshot, RuntimeException> {
                snapshot.outputModel.takeSnapshot()
            }
        // 用户输入总在终端 buffer 尾部。不要为一次回车读取数 MB 的完整历史：
        // 262 的 hyperlink 处理也会在同一份大文档上创建 range marker，全文读锁会把
        // terminal 的回显和重绘排在后面。保留绝对起点，裁剪只影响导航扫描范围。
        val scanStart =
            maxOf(
                outputSnapshot.startOffset,
                outputSnapshot.endOffset - NAVIGATION_SCAN_MAX_CHARS.toLong(),
            )
        val documentText = outputSnapshot.getText(scanStart, outputSnapshot.endOffset).toString()
        val locatedAnchors =
            locateUserMessageAnchors(
                documentText,
                transcript.exchanges,
                documentStartOffset = scanStart.toAbsolute(),
            )
        val latestResolved = latestExchangeResolved(transcript.exchanges, locatedAnchors)
        withContext(Dispatchers.EDT) {
            if (editor !== snapshot.editor || snapshot.editor.isDisposed) return@withContext
            if (virtualFile.terminalView.outputModels.active.value !== snapshot.outputModel) {
                scheduleRefresh()
                return@withContext
            }
            val outputChangedDuringLocate = outputGeneration.get() != snapshot.outputGeneration
            locateRequested.set(outputChangedDuringLocate)
            // message_end 可能早于 Terminal 把用户消息画出来。只在这种待补齐状态下
            // 等下一次 output model 内容事件；普通持续输出不会触发导航扫描。
            awaitingTerminalContent.set(!outputChangedDuringLocate && (transcript.changed || !latestResolved))
            applyAnchors(snapshot.editor, snapshot.outputModel, locatedAnchors)
            if (outputChangedDuringLocate) scheduleRefresh()
        }
    }

    fun viewportChanged() {
        refreshOpenPreview()
        component.repaint()
    }

    /** Monitor 还会透传每秒运行态通知；会话本身没变时不能把它升级成 transcript 重解析。 */
    private fun sessionStateChanged() {
        if (!sessionChangeTracker.changed(currentSessionState())) return
        scheduleRefresh()
    }

    private fun currentSessionState(): NavigationSessionState {
        val sessionId = virtualFile.sessionId
        val monitor = SessionMonitor.getInstance(project)
        val session = sessionId?.let(monitor.model::sessionOf)
        val generation = sessionId?.let(monitor::transcriptGeneration) ?: 0L
        return NavigationSessionState(sessionId, session, generation)
    }

    private fun navigationSnapshot(): NavigationSnapshot? {
        val currentEditor = editor?.takeIf { !it.isDisposed } ?: return null
        val state = currentSessionState()
        sessionChangeTracker.remember(state)
        val session = state.session ?: return null
        val outputModel = virtualFile.terminalView.outputModels.active.value
        return NavigationSnapshot(currentEditor, session, outputModel, outputGeneration.get())
    }

    private fun applyAnchors(
        currentEditor: Editor,
        outputModel: TerminalOutputModel,
        anchors: List<UserMessageAnchor>,
    ) {
        this.anchors =
            if (currentEditor.document.textLength == 0) {
                emptyList()
            } else {
                anchors.filter {
                    relativeOffset(it, outputModel.startOffset.toAbsolute(), currentEditor.document.textLength) != null
                }
            }
        component.isVisible = this.anchors.isNotEmpty()
        refreshOpenPreview()
        component.repaint()
    }

    private fun refreshOpenPreview() {
        val current = previewAnchor ?: return
        val refreshed = refreshedAnchor(current, anchors)
        if (refreshed == null) {
            hidePreview()
            return
        }
        val screenPoint = previewScreenPointFor(refreshed)
        if (screenPoint == null) {
            hidePreview()
            return
        }
        if (previewNeedsRebuild(current, refreshed, previewScreenPoint, screenPoint)) {
            showPreview(refreshed)
        }
    }

    private fun unbind() {
        hidePreview()
        awaitingTerminalContent.set(false)
        outputListenerDisposable?.let(Disposer::dispose)
        outputListenerDisposable = null
        clearHighlighters()
        editor = null
    }

    private fun clearHighlighters() {
        hidePreview()
        anchors = emptyList()
        component.isVisible = false
        component.repaint()
    }

    /**
     * 把预览做成一张指向圆点的对话气泡。
     *
     * 用平台 Balloon 而不是普通弹窗：尾巴、圆角、阴影和贴边翻转都是它自带的，
     * 位置只要给出圆点的坐标，[Balloon.Position.atLeft] 会把气泡开在左边、尾巴指回来。
     *
     * 气泡挪不了位置，换一颗点只能重建，所以动画必须关掉——带上淡入淡出就是一次可见的闪。
     */
    private fun showPreview(anchor: UserMessageAnchor) {
        cancelScheduledHide()
        val point = rail.previewPointFor(anchor) ?: return
        val screenPoint = runCatching(point::getScreenPoint).getOrNull() ?: return
        if (
            !previewNeedsRebuild(previewAnchor, anchor, previewScreenPoint, screenPoint) &&
            previewBalloon?.isDisposed == false
        ) {
            return
        }
        previewBalloon?.hide()
        previewAnchor = anchor
        previewScreenPoint = screenPoint
        updatePreviewCard(anchor)
        // 悬停的那颗点要放大，必须重绘
        component.repaint()

        previewBalloon =
            JBPopupFactory
                .getInstance()
                .createBalloonBuilder(previewCard)
                .setShowCallout(true)
                .setAnimationCycle(0)
                .setRequestFocus(false)
                // 开着 hide-on-click-outside 时，点圆点的第一下会被气泡拿去关自己，
                // 轨道收不到，于是要点两下才跳转。气泡的关闭交给 mouseExited 和跳转本身。
                .setHideOnClickOutside(false)
                .setHideOnKeyOutside(false)
                .setHideOnAction(false)
                .setShadow(true)
                .setFillColor(PREVIEW_BACKGROUND)
                .setBorderColor(PREVIEW_BORDER)
                .setBorderInsets(JBUI.emptyInsets())
                .createBalloon()
                .also { it.show(point, Balloon.Position.atLeft) }
    }

    /**
     * 排一次延迟隐藏，而不是立刻关。
     *
     * 命中区是硬边界，鼠标在圆点边沿抖动时 Swing 会反复发 exited/entered；立刻关的话
     * 那就是肉眼可见的来回闪。延迟这一小会儿，抖回来时 [showPreview] 会把它撤销，
     * 真正移开了才关。
     */
    private fun scheduleHide() {
        cancelScheduledHide()
        hideAlarm.addRequest(::hidePreview, HIDE_DELAY_MS)
    }

    private fun cancelScheduledHide() {
        hideAlarm.cancelAllRequests()
    }

    private fun hidePreview() {
        cancelScheduledHide()
        previewBalloon?.hide()
        previewBalloon = null
        previewAnchor = null
        previewScreenPoint = null
        component.repaint()
    }

    private fun previewScreenPointFor(anchor: UserMessageAnchor): Point? =
        rail.previewPointFor(anchor)?.let { runCatching(it::getScreenPoint).getOrNull() }

    private val previewCard: JComponent by lazy {
        JBPanel<JBPanel<*>>(BorderLayout(0, JBUI.scale(6))).apply {
            border = JBUI.Borders.empty(PREVIEW_PADDING)
            add(previewUserLabel.apply { font = font.deriveFont(Font.BOLD) }, BorderLayout.NORTH)
            add(previewReplyLabel, BorderLayout.CENTER)
        }
    }

    private fun updatePreviewCard(anchor: UserMessageAnchor) {
        previewUserLabel.text = wrappedHtml(anchor.userPreview)
        previewReplyLabel.text = wrappedHtml(anchor.replyPreview)
        // 末轮还在生成时没有回复，藏掉标签，免得卡片下方留一块空白
        previewReplyLabel.isVisible = anchor.replyPreview.isNotEmpty()
    }

    /** 定宽 HTML 让 JBLabel 自己折行；转义是因为终端里的提问常带 `<` `>` 和 `&`。 */
    private fun wrappedHtml(text: String): String =
        "<html><body style='width:${JBUI.scale(PREVIEW_WIDTH)}px'>" +
            StringUtil.escapeXmlEntities(text) +
            "</body></html>"

    override fun dispose() {
        disposed = true
        refreshRequested.set(false)
        locateRequested.set(false)
        refreshJob?.cancel()
        refreshJob = null
        unbind()
        hidePreview()
    }

    private data class NavigationSnapshot(
        val editor: Editor,
        val session: AgentSession,
        val outputModel: TerminalOutputModel,
        val outputGeneration: Long,
    )

    private data class AnchorPoint(
        val anchor: UserMessageAnchor,
        val line: Int,
        val y: Int,
    )

    private inner class NavigatorRail : JComponent() {
        private var clickGroup = emptyList<UserMessageAnchor>()
        private var clickedAnchor: UserMessageAnchor? = null

        /**
         * 只有圆点四周一圈算落在轨道上，横竖都收窄。
         *
         * 轨道是盖在终端上层的浮层，命中区放多大就从终端手里吃掉多少鼠标事件——在那片
         * 区域里划选文本、点击定位光标都到不了终端，所以只在点周围留 [MARK_HIT_SLACK]
         * 这么点余量。
         *
         * 边沿抖动带来的 exited/entered 反复触发不在这里解决：那是 [scheduleHide] 的活，
         * 靠放大命中面积去躲边界，只是把边界挪个位置，代价却是整条终端右侧都不能用了。
         */
        override fun contains(
            x: Int,
            y: Int,
        ): Boolean {
            val hitRadius = JBUI.scale(ACTIVE_MARK_RADIUS + MARK_HIT_SLACK)
            if (kotlin.math.abs(x - width / 2) > hitRadius) return false
            return nearestAnchorDistance(mouseY = y)?.let { it <= hitRadius } == true
        }

        init {
            isOpaque = false
            isVisible = false
            addComponentListener(
                object : java.awt.event.ComponentAdapter() {
                    override fun componentMoved(event: java.awt.event.ComponentEvent) {
                        refreshOpenPreview()
                    }

                    override fun componentResized(event: java.awt.event.ComponentEvent) {
                        refreshOpenPreview()
                    }
                },
            )
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(event: MouseEvent) {
                        val currentEditor = editor?.takeIf { !it.isDisposed } ?: return
                        val group = nearestAnchorGroup(event.y).map(AnchorPoint::anchor)
                        val previous = clickedAnchor.takeIf { clickGroup == group }
                        val anchor = nextCollocatedAnchor(group, previous) ?: return
                        clickGroup = group
                        clickedAnchor = anchor
                        showPreview(anchor)
                        val outputModel = virtualFile.terminalView.outputModels.active.value
                        val offset =
                            relativeOffset(
                                anchor,
                                outputModel.startOffset.toAbsolute(),
                                currentEditor.document.textLength,
                            ) ?: return
                        // 不关气泡：鼠标还停在这颗点上，关掉就只能等鼠标微动再弹回来，
                        // 那是一次可见的「消失再显示」。收起交给移开轨道时的延迟隐藏。
                        currentEditor.scrollingModel.scrollTo(
                            currentEditor.offsetToLogicalPosition(offset),
                            ScrollType.CENTER,
                        )
                    }

                    override fun mouseExited(event: MouseEvent) = scheduleHide()
                },
            )
            addMouseMotionListener(
                object : MouseMotionAdapter() {
                    override fun mouseMoved(event: MouseEvent) {
                        val anchor = nearestAnchor(event.y)
                        cursor =
                            if (anchor == null) {
                                Cursor.getDefaultCursor()
                            } else {
                                Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                            }
                        if (anchor == null) {
                            scheduleHide()
                            return
                        }
                        showPreview(anchor)
                    }
                },
            )
        }

        /**
         * 气泡锚在圆点本身上，而不是鼠标位置——尾巴要对准的是那颗点。
         *
         * 鼠标可以落在点四周的余量里，跟着鼠标走的话尾巴就会偏出圆点。
         */
        fun previewPointFor(anchor: UserMessageAnchor): RelativePoint? {
            val anchorY = anchorPoints().firstOrNull { it.anchor == anchor }?.y ?: return null
            // 锚点取圆点左边缘再往左挪一点：尾巴尖落在这里，才不会压在圆点上
            val x = width / 2 - JBUI.scale(ACTIVE_MARK_RADIUS + PREVIEW_GAP)
            return RelativePoint(this, Point(x, anchorY))
        }

        override fun paintComponent(graphics: Graphics) {
            val currentEditor = editor?.takeIf { !it.isDisposed } ?: return
            val points = anchorPoints()
            if (points.isEmpty()) return

            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val center = width / 2
                val padding = JBUI.scale(RAIL_PADDING)
                g.color = TRACK_COLOR
                g.stroke = BasicStroke(JBUI.scale(1).toFloat())
                g.drawLine(center, padding, center, (height - padding).coerceAtLeast(padding))

                val visible = currentEditor.scrollingModel.visibleArea
                val firstVisibleLine = currentEditor.xyToLogicalPosition(Point(0, visible.y)).line
                val lastVisibleLine =
                    currentEditor
                        .xyToLogicalPosition(Point(0, visible.y + visible.height))
                        .line
                points.forEach { (anchor, line, y) ->
                    // 鼠标划过的那颗和当前视口所在的那颗放大到同一尺寸：点击跳转后鼠标
                    // 通常还停在点上，两个尺寸不一样的话，鼠标一移开点就会凭空缩一下。
                    val emphasized =
                        anchor == previewAnchor || line in firstVisibleLine..lastVisibleLine
                    val radius = JBUI.scale(if (emphasized) ACTIVE_MARK_RADIUS else MARK_RADIUS)
                    g.color = if (emphasized) ACTIVE_MARK_COLOR else MESSAGE_MARK_COLOR
                    g.fillOval(center - radius, y - radius, radius * 2, radius * 2)
                }
            } finally {
                g.dispose()
            }
        }

        /** 纵向离 [mouseY] 最近的点；并列时保持当前预览项，点击则在整组中循环。 */
        private fun nearestAnchor(mouseY: Int): UserMessageAnchor? {
            val group = nearestAnchorGroup(mouseY)
            return group.firstOrNull { it.anchor == previewAnchor }?.anchor
                ?: group.firstOrNull()?.anchor
        }

        private fun nearestAnchorGroup(mouseY: Int): List<AnchorPoint> {
            val points = anchorPoints()
            val distance = points.minOfOrNull { kotlin.math.abs(it.y - mouseY) } ?: return emptyList()
            return points.filter { kotlin.math.abs(it.y - mouseY) == distance }
        }

        /** 到最近那颗点的纵向距离，供 [contains] 判命中；一颗有效的点都没有时返回 null。 */
        private fun nearestAnchorDistance(mouseY: Int): Int? = anchorPoints().minOfOrNull { kotlin.math.abs(it.y - mouseY) }

        /**
         * 当前文档里仍然有效的锚点，连同它的行号和纵坐标。
         *
         * 绝对 offset 落在当前窗口之外时直接略过（见 [relativeOffset]），不能拿它访问
         * Editor；窗口内的剩余锚点不受 history trimming 的相对平移影响。
         */
        private fun anchorPoints(): List<AnchorPoint> {
            val currentEditor = editor?.takeIf { !it.isDisposed } ?: return emptyList()
            val padding = JBUI.scale(RAIL_PADDING)
            val document = currentEditor.document
            val outputStart =
                virtualFile.terminalView.outputModels.active.value.startOffset
                    .toAbsolute()
            return anchors.mapNotNull { anchor ->
                val offset = relativeOffset(anchor, outputStart, document.textLength) ?: return@mapNotNull null
                val line = document.getLineNumber(offset)
                AnchorPoint(anchor, line, markerY(line, document.lineCount, height, padding))
            }
        }
    }

    private companion object {
        const val NAVIGATION_SCAN_MAX_CHARS = 256_000
        const val REFRESH_DEBOUNCE_MS = 250L
        const val CONTINUOUS_REFRESH_INTERVAL_MS = 1_000L
        const val RAIL_WIDTH = 22
        const val RAIL_PADDING = 10

        const val HIDE_DELAY_MS = 180
        const val PREVIEW_WIDTH = 340
        const val PREVIEW_PADDING = 12

        /** 气泡尾巴尖与圆点之间留的空隙，别让箭头压在点上。 */
        const val PREVIEW_GAP = 6
        val PREVIEW_BACKGROUND =
            JBColor.namedColor(
                "Imux.SessionMessageNavigator.previewBackground",
                JBColor(0xF7F8FA, 0x2B2D30),
            )
        val PREVIEW_BORDER =
            JBColor.namedColor(
                "Imux.SessionMessageNavigator.previewBorder",
                JBColor(0xD1D5DB, 0x393B40),
            )
        val TRACK_COLOR =
            JBColor.namedColor(
                "Imux.SessionMessageNavigator.track",
                JBColor(0xD1D5DB, 0x4B5563),
            )
        val MESSAGE_MARK_COLOR =
            JBColor.namedColor(
                "Imux.SessionMessageNavigator.marker",
                JBColor(0x6B7280, 0xA8B0BA),
            )
        val ACTIVE_MARK_COLOR =
            JBColor.namedColor(
                "Imux.SessionMessageNavigator.activeMarker",
                JBColor(0x2563EB, 0x60A5FA),
            )
    }
}

private fun normalizeWhitespace(value: String): String =
    value
        .replace(Regex("\\s+"), " ")
        .trim()

/**
 * 归一化整篇终端文档，并保留「归一化后第 i 个字符原本在第几位」。
 *
 * 两处都必须是预分配的原始数组，不能图省事用 `ArrayList<Int>`：那样每个字符都要装一个
 * `Integer`，一份 4MB 的终端输出就是四百万个临时对象。这里仅在 transcript 语义变化、
 * 待定位消息确认、reflow 或 buffer 切换时运行，仍要避免一次恢复制造大量垃圾。
 */
private fun normalizedWithOffsets(value: String): NormalizedText {
    val text = StringBuilder(value.length)
    val offsets = IntArray(value.length)
    var size = 0
    var previousWasSpace = false
    for (index in value.indices) {
        val char = value[index]
        if (char.isWhitespace()) {
            if (!previousWasSpace && size > 0) {
                text.append(' ')
                offsets[size++] = index
            }
            previousWasSpace = true
        } else {
            text.append(char)
            offsets[size++] = index
            previousWasSpace = false
        }
    }
    if (size > 0 && text.last() == ' ') {
        text.setLength(text.length - 1)
        size--
    }
    return NormalizedText(text.toString(), offsets.copyOf(size))
}

/**
 * 时间线上一颗点的两档半径：常态，以及被强调时（鼠标划过 **或** 当前视口所在）。
 *
 * 强调只有一档：点击跳转后鼠标通常还停在那颗点上，如果 hover 和「当前视口」尺寸不同，
 * 鼠标一移开点就会凭空缩一下。
 *
 * 放在文件级是为了让源码断言直接读到——两个值必须不同，相同的话「变大」就不存在了。
 */
internal const val MARK_RADIUS = 3
internal const val ACTIVE_MARK_RADIUS = 6

/** 命中区在最大那颗点之外再留这么点余量，仅此而已——多留一分就多吃一分终端的鼠标事件。 */
internal const val MARK_HIT_SLACK = 2

private const val MATCH_PREFIX_CHARS = 120
private const val USER_PREVIEW_CHARS = 60
private const val REPLY_PREVIEW_CHARS = 160

/**
 * 把 Platform 绝对 offset 换算到当前 Terminal 窗口。
 *
 * history trimming 只推进 [outputStartOffset]，未被裁掉的锚点仍保持原绝对位置；已经落在
 * 窗口之前或之后的锚点返回 null，绘制与点击路径都不会拿它访问 Editor。
 */
internal fun relativeOffset(
    anchor: UserMessageAnchor,
    outputStartOffset: Long,
    textLength: Int,
): Int? {
    val relative = anchor.absoluteOffset - outputStartOffset
    if (relative !in 0..textLength.toLong()) return null
    return relative.toInt()
}

internal fun markerY(
    line: Int,
    lineCount: Int,
    height: Int,
    padding: Int,
): Int {
    if (height <= padding * 2 || lineCount <= 1) return height / 2
    val usable = height - padding * 2
    return padding + (line.coerceIn(0, lineCount - 1).toLong() * usable / (lineCount - 1)).toInt()
}
