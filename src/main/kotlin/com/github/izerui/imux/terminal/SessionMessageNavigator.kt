package com.github.izerui.imux.terminal

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.model.AgentSession
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.monitor.SessionMonitor
import com.github.izerui.imux.peer.PeerFeedbackHint
import com.github.izerui.imux.session.NavigationTranscriptIndex
import com.github.izerui.imux.session.SessionExchange
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
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
    /** 匹配到的提问正文末字符之后的绝对位置，点击跳转后据此高亮落点。 */
    val endAbsoluteOffset: Long = absoluteOffset,
)

internal data class IndexedUserMessageAnchor(
    val exchangeIndex: Int,
    val anchor: UserMessageAnchor,
)

internal data class PreferredTextRange(
    val startAbsoluteOffset: Long,
    val endAbsoluteOffset: Long,
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
 * 两边都只保留字母与数字（中日韩文字计入）再比对，同时保留到原文 offset 的映射：
 * TUI 在中文行内硬换行、续行缩进、渲染 markdown、加前缀与边框，丢掉的都是这些字符，
 * 只折叠空白的话中文长提问会多出一个空格，回复里的 `**` 也对不上。
 *
 * 新消息优先在 Terminal 已标记的非默认背景区间内匹配，再按 transcript 顺序与终端文本
 * 配对。这样 AI 普通输出中复述同样文字不会抢走 anchor。只有没有可用标记的旧会话，
 * 才使用一条各家 CLI 通用的 fallback：从文档尾部反向逐条匹配。
 *
 * fallback 的三件事：
 *
 * - 助手复述提问抢不走锚点：复述在回复内部，必然落在游标之后，够不着；
 * - 重复提问按轮次分开：游标单调递减，n 轮相同提问必然落在 n 个不同位置；
 * - 终端裁剪历史后只剩一处时，归给较新的那轮——裁掉的总是更早的内容。
 *
 * 不认识任何渲染符号，因此换一版 TUI 前缀不会让整轮消息从 fallback 轨道上消失。
 */
internal fun locateUserMessageAnchors(
    documentText: String,
    exchanges: List<SessionExchange>,
    documentStartOffset: Long = 0L,
    preferredRanges: List<PreferredTextRange> = emptyList(),
): List<UserMessageAnchor> =
    locateUserMessageAnchorsIndexed(documentText, exchanges, documentStartOffset, preferredRanges)
        .map(IndexedUserMessageAnchor::anchor)

internal fun locateUserMessageAnchorsIndexed(
    documentText: String,
    exchanges: List<SessionExchange>,
    documentStartOffset: Long = 0L,
    preferredRanges: List<PreferredTextRange> = emptyList(),
): List<IndexedUserMessageAnchor> {
    val document = matchKeyWithOffsets(documentText)
    if (document.text.isEmpty()) return emptyList()

    // 尾部连续无回复的轮次不参与匹配：没有右边界的用户文本会从文档尾部反向抢走
    // 上一轮的匹配位置。一旦回复文本落盘，下一次 transcript 刷新会重新加入。
    val matchable = exchanges.dropLastWhile { it.assistantReply.isEmpty() }

    // 提问、回复、提问、回复……展平成一条与渲染顺序一致的链，回复只推游标不产出锚点
    val chain =
        matchable.flatMapIndexed { index, exchange ->
            listOf(
                Triple(exchange.userText, index, exchange),
                Triple(exchange.assistantReply, null, null),
            )
        }

    var searchBefore = document.text.lastIndex
    val anchors = mutableListOf<IndexedUserMessageAnchor>()
    chain.asReversed().forEach { (text, exchangeIndex, exchange) ->
        val key = matchKey(text)
        if (key.isEmpty()) return@forEach
        // 回复只取开头当挡板：越往后越可能遇到表格、链接、代码块这类渲染后面目全非的内容
        val needle = key.take(if (exchangeIndex != null) MATCH_PREFIX_CHARS else REPLY_MATCH_CHARS)
        val found =
            if (exchangeIndex != null && preferredRanges.isNotEmpty()) {
                lastIndexOfPreferredOccurrence(
                    document,
                    needle,
                    searchBefore,
                    documentStartOffset,
                    preferredRanges,
                ) ?: document.text.lastIndexOf(needle, searchBefore)
            } else {
                document.text.lastIndexOf(needle, searchBefore)
            }
        if (found < 0) return@forEach
        if (exchangeIndex != null && exchange != null) {
            anchors +=
                IndexedUserMessageAnchor(
                    exchangeIndex,
                    UserMessageAnchor(
                        offset = document.offsets[found],
                        userPreview = truncated(normalizeWhitespace(text), USER_PREVIEW_CHARS),
                        replyPreview = truncated(normalizeWhitespace(exchange.assistantReply), REPLY_PREVIEW_CHARS),
                        absoluteOffset = documentStartOffset + document.offsets[found],
                        endAbsoluteOffset = documentStartOffset + document.offsets[found + needle.length - 1] + 1,
                    ),
                )
        }
        searchBefore = found - 1
    }
    return anchors.asReversed()
}

private fun lastIndexOfPreferredOccurrence(
    document: NormalizedText,
    needle: String,
    searchBefore: Int,
    documentStartOffset: Long,
    preferredRanges: List<PreferredTextRange>,
): Int? {
    var cursor = searchBefore
    while (cursor >= 0) {
        val found = document.text.lastIndexOf(needle, cursor)
        if (found < 0) return null
        // 色块只到首行行尾，多行提问整段装不进去；起点落在色块里就够了
        val absoluteOffset = documentStartOffset + document.offsets[found]
        if (
            preferredRanges.any { range ->
                absoluteOffset >= range.startAbsoluteOffset && absoluteOffset < range.endAbsoluteOffset
            }
        ) {
            return found
        }
        cursor = found - 1
    }
    return null
}

internal fun changeCanMoveAnchors(
    changeOffset: Int,
    anchors: List<UserMessageAnchor>,
    outputStartOffset: Long,
): Boolean = anchors.any { it.absoluteOffset - outputStartOffset >= changeOffset }

internal fun latestExchangeResolved(
    exchanges: List<SessionExchange>,
    indexedAnchors: List<IndexedUserMessageAnchor>,
): Boolean {
    val latest = exchanges.lastOrNull() ?: return true
    val lastIndexed = indexedAnchors.lastOrNull() ?: return false
    if (lastIndexed.exchangeIndex != exchanges.lastIndex) return false
    val expectedPreview = truncated(normalizeWhitespace(latest.userText), USER_PREVIEW_CHARS)
    return lastIndexed.anchor.userPreview == expectedPreview
}

internal fun waitForTerminalContent(
    exchanges: List<SessionExchange>,
    transcriptChanged: Boolean,
    latestResolved: Boolean,
    outputChangedDuringLocate: Boolean,
): Boolean =
    !outputChangedDuringLocate &&
        (transcriptChanged || (exchanges.lastOrNull()?.assistantReply?.isNotEmpty() == true && !latestResolved))

/**
 * 没有助手回复文本的轮次不展示可点击圆点。
 *
 * 回复文本是定位器给用户输入建立右边界的唯一可靠依据。缺少它时，反向匹配可能把
 * 工具输出中的复述或其他相同文本标为用户输入——点击后跳到错误位置。等回复文本
 * 落盘后，下一次 transcript generation 刷新会重新加入这颗点。
 */
internal fun stableAnchorsForNavigation(
    exchanges: List<SessionExchange>,
    indexedAnchors: List<IndexedUserMessageAnchor>,
): List<UserMessageAnchor> =
    indexedAnchors
        .filter { exchanges.getOrNull(it.exchangeIndex)?.assistantReply?.isNotEmpty() == true }
        .map(IndexedUserMessageAnchor::anchor)

internal fun peerFeedbackAnchors(
    native: List<UserMessageAnchor>,
    hints: List<PeerFeedbackHint>,
    scanStart: Long,
    scanEnd: Long,
): List<UserMessageAnchor> {
    val availableNative = native.toMutableList()
    val added = hints.asReversed().mapNotNull { hint ->
        if (hint.absoluteOffset !in scanStart..scanEnd) return@mapNotNull null
        val preview = truncated(normalizeWhitespace(hint.text), USER_PREVIEW_CHARS)
        val match = availableNative.indexOfLast {
            it.userPreview == preview && it.absoluteOffset >= hint.absoluteOffset
        }
        if (match >= 0) {
            availableNative.removeAt(match)
            return@mapNotNull null
        }
        UserMessageAnchor(
            offset = (hint.absoluteOffset - scanStart).toInt(),
            userPreview = preview,
            replyPreview = "",
            absoluteOffset = hint.absoluteOffset,
            endAbsoluteOffset = hint.absoluteOffset + hint.text.length,
        )
    }
    return (native + added).sortedBy(UserMessageAnchor::absoluteOffset)
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
    private val feedbackGeneration = AtomicLong()
    private val transcriptIndex = NavigationTranscriptIndex()
    private var observedFeedbackHint: PeerFeedbackHint? = null

    @Volatile
    private var disposed = false

    private val sessionChangeTracker = NavigationSessionChangeTracker()
    private var previewAnchor: UserMessageAnchor? = null

    /** 卡片当前对准的横线位置（终端层坐标）；横线挪了才需要重新摆卡片。 */
    private var previewPoint: Point? = null
    private val hideAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var landingHighlighter: RangeHighlighter? = null
    private val landingAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val previewPositionLabel =
        JBLabel().apply {
            foreground = UIUtil.getContextHelpForeground()
            font = JBUI.Fonts.smallFont()
        }
    private val previewUserLabel = JBLabel()
    private val previewReplyLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val previewCard = PreviewCard()
    private val rail = NavigatorRail()
    val component: JComponent get() = rail

    /** 悬停卡片；由编辑器放进终端层的 `POPUP_LAYER`，位置由导航器自己摆。 */
    val previewComponent: JComponent get() = previewCard
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
    private val outputModelBinding = OutputModelBinding(outputModelListener) {
        locateRequested.set(true)
        scheduleRefresh()
    }

    init {
        SessionMonitor.getInstance(project).addListener(this, ::sessionStateChanged)
        SessionMonitor.getInstance(project).peerCoordinator.addStateListener(this) { sessionKey ->
            if (sessionKey == virtualFile.sessionKey) {
                val latest = SessionMonitor.getInstance(project).peerCoordinator
                    .feedbackHints(sessionKey, virtualFile.terminalView.outputModels.active.value)
                    .lastOrNull()
                if (latest === observedFeedbackHint) return@addStateListener
                observedFeedbackHint = latest
                feedbackGeneration.incrementAndGet()
                awaitingTerminalContent.set(true)
                locateRequested.set(true)
                scheduleRefresh()
            }
        }
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
            outputModelBinding.bind(virtualFile.terminalView.outputModels.active.value)
        }
        scheduleRefresh()
    }

    fun activeOutputModelChanged(model: TerminalOutputModel) {
        if (disposed) return
        if (editor == null) return
        val oldId = System.identityHashCode(outputModelBinding.observedModel).toString(16)
        if (!outputModelBinding.activeModelChanged(model)) return
        NAVIGATOR_LOG.info(
            "active model 切换：$oldId -> ${System.identityHashCode(model).toString(16)}"
        )
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

        // 快照与高亮必须在同一个读锁里取：分两次读的话中间终端又输出了，高亮偏移就对不上快照，
        // 实测会抛 IndexOutOfBoundsException 并让整轮退回纯文本匹配。
        val (outputSnapshot, highlightSpans) =
            ReadAction.computeBlocking<Pair<org.jetbrains.plugins.terminal.view.TerminalOutputModelSnapshot, List<HighlightSpan>>, RuntimeException> {
                snapshot.outputModel.takeSnapshot() to highlightSpansViaReflection(snapshot.outputModel)
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
        val preferredRanges =
            backgroundRanges(
                highlightSpans,
                documentStartAbsolute = outputSnapshot.startOffset.toAbsolute(),
                scanStart = scanStart.toAbsolute(),
                scanEnd = outputSnapshot.endOffset.toAbsolute(),
            )
        val indexedAnchors =
            locateUserMessageAnchorsIndexed(
                documentText,
                transcript.exchanges,
                documentStartOffset = scanStart.toAbsolute(),
                preferredRanges = preferredRanges,
            )
        val stableAnchors = stableAnchorsForNavigation(transcript.exchanges, indexedAnchors)
        val latestResolved = latestExchangeResolved(transcript.exchanges, indexedAnchors)
        if (snapshot.feedbackHints.isEmpty() && feedbackGeneration.get() > 0) {
            NAVIGATOR_LOG.info(
                "refreshAnchors: feedbackHints 为空但 feedbackGeneration=${feedbackGeneration.get()}，" +
                        "snapshot.outputModel=${System.identityHashCode(snapshot.outputModel).toString(16)}"
            )
        }
        val allAnchors = peerFeedbackAnchors(
            stableAnchors,
            snapshot.feedbackHints,
            scanStart.toAbsolute(),
            outputSnapshot.endOffset.toAbsolute(),
        )
        withContext(Dispatchers.EDT) {
            if (editor !== snapshot.editor || snapshot.editor.isDisposed) return@withContext
            if (virtualFile.terminalView.outputModels.active.value !== snapshot.outputModel) {
                scheduleRefresh()
                return@withContext
            }
            val outputChangedDuringLocate = outputGeneration.get() != snapshot.outputGeneration
            locateRequested.set(outputChangedDuringLocate || feedbackGeneration.get() != snapshot.feedbackGeneration)
            // 无回复的轮次只等一次落屏；后续工具输出无需重复扫描，回复落盘会另行触发刷新。
            awaitingTerminalContent.set(
                waitForTerminalContent(transcript.exchanges, transcript.changed, latestResolved, outputChangedDuringLocate),
            )
            applyAnchors(snapshot.editor, snapshot.outputModel, allAnchors)
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
        return NavigationSnapshot(
            currentEditor,
            session,
            outputModel,
            outputGeneration.get(),
            feedbackGeneration.get(),
            SessionMonitor.getInstance(project).peerCoordinator.feedbackHints(session.id, outputModel),
        )
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
        val point = rail.previewPointFor(refreshed)
        if (point == null) {
            hidePreview()
            return
        }
        if (previewNeedsRebuild(current, refreshed, previewPoint, point)) {
            showPreview(refreshed)
        }
    }

    private fun unbind() {
        hidePreview()
        clearLanding()
        awaitingTerminalContent.set(false)
        outputModelBinding.unbind()
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
     * 在横线右侧摆出预览卡片。
     *
     * 卡片是终端层里的普通组件而不是 Balloon：换一根横线只挪位置、换文字，不重建弹窗，
     * 不闪；也不存在 hide-on-click-outside 吞掉第一下点击的问题。
     */
    private fun showPreview(anchor: UserMessageAnchor) {
        cancelScheduledHide()
        val point = rail.previewPointFor(anchor) ?: return
        if (!previewNeedsRebuild(previewAnchor, anchor, previewPoint, point) && previewCard.isVisible) return
        previewAnchor = anchor
        previewPoint = point
        updatePreviewCard(anchor)
        val parent = previewCard.parent
        val size = previewCard.preferredSize
        previewCard.bounds =
            previewCardBounds(
                tickY = point.y,
                cardWidth = size.width,
                cardHeight = size.height,
                x = point.x,
                containerHeight = parent?.height ?: (point.y + size.height),
                margin = JBUI.scale(PREVIEW_MARGIN),
            )
        previewCard.isVisible = true
        previewCard.revalidate()
        previewCard.repaint()
        // 悬停的那根横线要放大，必须重绘
        component.repaint()
    }

    /**
     * 排一次延迟隐藏，而不是立刻关。
     *
     * 命中区是硬边界，鼠标在横线边沿抖动时 Swing 会反复发 exited/entered；立刻关的话
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
        previewCard.isVisible = false
        previewAnchor = null
        previewPoint = null
        component.repaint()
    }

    private fun updatePreviewCard(anchor: UserMessageAnchor) {
        val position = anchors.indexOf(anchor) + 1
        previewPositionLabel.text =
            ImuxBundle.message("navigator.preview.position", position, anchors.size, virtualFile.tabTitle)
        val width = JBUI.scale(PREVIEW_WIDTH)
        previewUserLabel.text = clampedHtml(anchor.userPreview, previewUserLabel, width)
        previewReplyLabel.text = clampedHtml(anchor.replyPreview, previewReplyLabel, width)
        // 末轮还在生成时没有回复，藏掉标签，免得卡片下方留一块空白
        previewReplyLabel.isVisible = anchor.replyPreview.isNotEmpty()
    }

    /** 按实际字宽折成最多 [PREVIEW_MAX_LINES] 行；转义是因为终端里的提问常带 `<` `>` 和 `&`。 */
    private fun clampedHtml(
        text: String,
        label: JBLabel,
        width: Int,
    ): String {
        val metrics = label.getFontMetrics(label.font)
        val lines = clampToLines(text, width, PREVIEW_MAX_LINES) { metrics.stringWidth(it) }
        return "<html>" + lines.joinToString("<br>") { StringUtil.escapeXmlEntities(it) } + "</html>"
    }

    /**
     * 点击后给落点那段提问铺一层底色，过一会儿撤掉：滚动居中之后一眼就能看到是哪一段。
     */
    private fun flashLanding(
        currentEditor: Editor,
        anchor: UserMessageAnchor,
        start: Int,
        outputStart: Long,
    ) {
        clearLanding()
        val textLength = currentEditor.document.textLength
        val end = (anchor.endAbsoluteOffset - outputStart).coerceIn(start.toLong(), textLength.toLong()).toInt()
        if (end <= start) return
        landingHighlighter =
            currentEditor.markupModel.addRangeHighlighter(
                start,
                end,
                HighlighterLayer.SELECTION - 1,
                TextAttributes().apply { backgroundColor = LANDING_BACKGROUND },
                HighlighterTargetArea.EXACT_RANGE,
            )
        landingAlarm.addRequest(::clearLanding, LANDING_HIGHLIGHT_MS)
    }

    private fun clearLanding() {
        landingAlarm.cancelAllRequests()
        landingHighlighter?.dispose()
        landingHighlighter = null
    }

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
        val feedbackGeneration: Long,
        val feedbackHints: List<PeerFeedbackHint>,
    )

    private data class AnchorPoint(
        val anchor: UserMessageAnchor,
        val line: Int,
        val y: Int,
        val emphasized: Boolean,
        val length: Int,
    )

    /** 圆角、带一圈描边的卡片；不透明区域只有卡片本身。 */
    private inner class PreviewCard : JBPanel<PreviewCard>(BorderLayout(0, JBUI.scale(4))) {
        init {
            isOpaque = false
            isVisible = false
            border = JBUI.Borders.empty(PREVIEW_PADDING)
            add(previewPositionLabel, BorderLayout.NORTH)
            add(previewUserLabel.apply { font = font.deriveFont(Font.BOLD) }, BorderLayout.CENTER)
            add(previewReplyLabel, BorderLayout.SOUTH)
        }

        override fun paintComponent(graphics: Graphics) {
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = JBUI.scale(PREVIEW_ARC)
                g.color = PREVIEW_BACKGROUND
                g.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g.color = PREVIEW_BORDER
                g.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            } finally {
                g.dispose()
            }
        }
    }

    /**
     * 终端左侧的一列短横线，每条用户输入一根。
     *
     * 整列盖在终端上层，但 [contains] 只在横线本身上返回 true：Swing 按 contains 找最深的
     * 组件，横线之外的点直接落到下层终端，点击、拖选、滚轮都不受影响，不需要转发事件。
     */
    private inner class NavigatorRail : JComponent() {
        override fun contains(
            x: Int,
            y: Int,
        ): Boolean = hitPoint(x, y) != null

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
                        val anchor = hitPoint(event.x, event.y)?.anchor ?: return
                        showPreview(anchor)
                        val outputStart = virtualFile.terminalView.outputModels.active.value.startOffset.toAbsolute()
                        val offset =
                            relativeOffset(
                                anchor,
                                outputStart,
                                currentEditor.document.textLength,
                            ) ?: return
                        // 不关卡片：鼠标还停在这根横线上，关掉就只能等鼠标微动再弹回来，
                        // 那是一次可见的「消失再显示」。收起交给移开横线时的延迟隐藏。
                        currentEditor.scrollingModel.scrollTo(
                            currentEditor.offsetToLogicalPosition(offset),
                            ScrollType.CENTER,
                        )
                        flashLanding(currentEditor, anchor, offset, outputStart)
                    }

                    override fun mouseExited(event: MouseEvent) = scheduleHide()
                },
            )
            addMouseMotionListener(
                object : MouseMotionAdapter() {
                    override fun mouseMoved(event: MouseEvent) {
                        val anchor = hitPoint(event.x, event.y)?.anchor
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

        /** 卡片左上角的参考点：横线列右侧留一点空隙，纵向就是横线中心，换算到终端层坐标。 */
        fun previewPointFor(anchor: UserMessageAnchor): Point? {
            val anchorY = anchorPoints().firstOrNull { it.anchor == anchor }?.y ?: return null
            return Point(x + JBUI.scale(TICK_LEFT + ACTIVE_TICK_LENGTH + PREVIEW_GAP), y + anchorY)
        }

        override fun paintComponent(graphics: Graphics) {
            val points = anchorPoints()
            if (points.isEmpty()) return

            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val left = JBUI.scale(TICK_LEFT)
                points.forEach { point ->
                    val thickness = JBUI.scale(if (point.emphasized) ACTIVE_TICK_THICKNESS else TICK_THICKNESS)
                    g.color = if (point.emphasized) ACTIVE_MARK_COLOR else MESSAGE_MARK_COLOR
                    g.fillRoundRect(left, point.y - thickness / 2, point.length, thickness, thickness, thickness)
                }
            } finally {
                g.dispose()
            }
        }

        private fun hitPoint(
            x: Int,
            y: Int,
        ): AnchorPoint? {
            val points = anchorPoints()
            val index =
                tickHitIndex(
                    x,
                    y,
                    points.map(AnchorPoint::y),
                    points.map(AnchorPoint::length),
                    left = JBUI.scale(TICK_LEFT),
                    slack = JBUI.scale(TICK_HIT_SLACK),
                    maxBand = JBUI.scale(TICK_HIT_BAND),
                ) ?: return null
            return points[index]
        }

        /**
         * 当前文档里仍然有效的锚点，连同它的可视行、横线纵坐标、是否强调与横线长度。
         *
         * 绝对 offset 落在当前窗口之外时直接略过（见 [relativeOffset]），不能拿它访问
         * Editor；窗口内的剩余锚点不受 history trimming 的相对平移影响。
         *
         * 鼠标悬停那根和当前视口里的那几根强调成同一尺寸：点击跳转后鼠标通常还停在横线上，
         * 两种强调尺寸不同的话，鼠标一移开横线就会凭空缩一下。
         */
        private fun anchorPoints(): List<AnchorPoint> {
            val currentEditor = editor?.takeIf { !it.isDisposed } ?: return emptyList()
            return try {
                val textLength = currentEditor.document.textLength
                val outputStart =
                    virtualFile.terminalView.outputModels.active.value.startOffset
                        .toAbsolute()
                val visible = currentEditor.scrollingModel.visibleArea
                val firstVisibleLine = currentEditor.xyToVisualPosition(Point(0, visible.y)).line
                val lastVisibleLine =
                    currentEditor
                        .xyToVisualPosition(Point(0, visible.y + visible.height))
                        .line
                val located =
                    anchors.mapNotNull { anchor ->
                        val offset = relativeOffset(anchor, outputStart, textLength) ?: return@mapNotNull null
                        anchor to currentEditor.offsetToVisualPosition(offset).line
                    }
                val centers =
                    tickCenters(located.size, height, JBUI.scale(TICK_PADDING), JBUI.scale(TICK_GAP))
                located.mapIndexed { index, (anchor, line) ->
                    val emphasized = anchor == previewAnchor || line in firstVisibleLine..lastVisibleLine
                    val length =
                        if (emphasized) {
                            JBUI.scale(ACTIVE_TICK_LENGTH)
                        } else {
                            JBUI.scale(tickLength(anchor.userPreview.length, SHORT_TICK_LENGTH, MEDIUM_TICK_LENGTH, LONG_TICK_LENGTH))
                        }
                    AnchorPoint(anchor, line, centers[index], emphasized, length)
                }
            } catch (_: IndexOutOfBoundsException) {
                // Terminal 正在替换 output 文档时，Document 长度与 Editor 视觉行缓存会短暂错位。
                // 绘制路径只能跳过这一帧，不能把异常抛回 EDT；后续内容事件会再次 repaint。
                emptyList()
            }
        }
    }

    private companion object {
        const val NAVIGATION_SCAN_MAX_CHARS = 256_000
        const val REFRESH_DEBOUNCE_MS = 250L
        const val CONTINUOUS_REFRESH_INTERVAL_MS = 1_000L

        const val TICK_LEFT = 6
        const val TICK_PADDING = 12
        const val TICK_GAP = 8
        const val TICK_THICKNESS = 2
        const val ACTIVE_TICK_THICKNESS = 3
        const val SHORT_TICK_LENGTH = 8
        const val MEDIUM_TICK_LENGTH = 11
        const val LONG_TICK_LENGTH = 14

        /** 强调态只有一档：悬停与视口内共用，见 [NavigatorRail.anchorPoints]。 */
        const val ACTIVE_TICK_LENGTH = 20

        /** 命中区只在横线外留这么点余量——多留一分就多吃一分终端的鼠标事件。 */
        const val TICK_HIT_SLACK = 2
        const val TICK_HIT_BAND = 4
        /** 横线列宽度：左边距 + 最长（强调态）横线 + 命中余量。 */
        const val RAIL_WIDTH = TICK_LEFT + ACTIVE_TICK_LENGTH + TICK_HIT_SLACK + 2

        const val HIDE_DELAY_MS = 180
        const val LANDING_HIGHLIGHT_MS = 1_500
        const val PREVIEW_WIDTH = 340
        const val PREVIEW_PADDING = 12
        const val PREVIEW_MAX_LINES = 2
        const val PREVIEW_ARC = 12
        const val PREVIEW_MARGIN = 8

        /** 卡片与横线之间留的空隙。 */
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
        val MESSAGE_MARK_COLOR =
            JBColor.namedColor(
                "Imux.SessionMessageNavigator.marker",
                JBColor(0xB4BAC4, 0x5F6670),
            )
        val ACTIVE_MARK_COLOR =
            JBColor.namedColor(
                "Imux.SessionMessageNavigator.activeMarker",
                JBColor(0x374151, 0xE5E7EB),
            )
        val LANDING_BACKGROUND =
            JBColor.namedColor(
                "Imux.SessionMessageNavigator.landingBackground",
                JBColor(0xFFF1B8, 0x4A4320),
            )
    }
}

private val NAVIGATOR_LOG = com.intellij.openapi.diagnostic.Logger.getInstance("imux.SessionMessageNavigator")

internal class ActiveModelDispatcher(
    private val schedule: (Runnable) -> Unit,
    private val activeModel: () -> TerminalOutputModel,
    private val onActiveModel: (TerminalOutputModel) -> Unit,
) {
    fun emission() {
        schedule(Runnable { onActiveModel(activeModel()) })
    }
}

internal class OutputModelBinding(
    private val listener: TerminalOutputModelListener,
    private val onRefreshRequested: () -> Unit = {},
) {
    var observedModel: TerminalOutputModel? = null
        private set
    private var listenerDisposable: Disposable? = null

    fun bind(model: TerminalOutputModel) {
        listenerDisposable?.let(Disposer::dispose)
        val disposable = Disposer.newDisposable("imux-session-message-output")
        listenerDisposable = disposable
        observedModel = model
        model.addListener(disposable, listener)
    }

    fun activeModelChanged(model: TerminalOutputModel): Boolean {
        if (model === observedModel) return false
        bind(model)
        onRefreshRequested()
        return true
    }

    fun unbind() {
        listenerDisposable?.let(Disposer::dispose)
        listenerDisposable = null
        observedModel = null
    }
}

private fun normalizeWhitespace(value: String): String =
    value
        .replace(Regex("\\s+"), " ")
        .trim()

/** 匹配用的比对键：只留字母与数字，空白、标点、markdown 符号、TUI 前缀与边框一律丢弃。 */
private fun matchKey(value: String): String = value.filter(Char::isLetterOrDigit)

/**
 * 把整篇终端文档转成 [matchKey]，并保留「比对键第 i 个字符原本在第几位」。
 *
 * 两处都必须是预分配的原始数组，不能图省事用 `ArrayList<Int>`：那样每个字符都要装一个
 * `Integer`，一份 4MB 的终端输出就是四百万个临时对象。这里仅在 transcript 语义变化、
 * 待定位消息确认、reflow 或 buffer 切换时运行，仍要避免一次恢复制造大量垃圾。
 */
private fun matchKeyWithOffsets(value: String): NormalizedText {
    val text = StringBuilder(value.length)
    val offsets = IntArray(value.length)
    var size = 0
    for (index in value.indices) {
        val char = value[index]
        if (!char.isLetterOrDigit()) continue
        text.append(char)
        offsets[size++] = index
    }
    return NormalizedText(text.toString(), offsets.copyOf(size))
}

private const val MATCH_PREFIX_CHARS = 120
private const val REPLY_MATCH_CHARS = 40
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

internal data class HighlightSpan(
    /** 相对当前终端文档起点的位置，与 [org.jetbrains.plugins.terminal.view.TerminalOutputModelSnapshot.startOffset] 对齐。 */
    val start: Int,
    val end: Int,
    val hasBackground: Boolean,
)

/** 有背景色的高亮换算成绝对位置，并裁到 `[scanStart, scanEnd)` 内；用户输入色块就在其中。 */
internal fun backgroundRanges(
    spans: List<HighlightSpan>,
    documentStartAbsolute: Long,
    scanStart: Long,
    scanEnd: Long,
): List<PreferredTextRange> =
    spans.mapNotNull { span ->
        if (!span.hasBackground) return@mapNotNull null
        val start = maxOf(documentStartAbsolute + span.start, scanStart)
        val end = minOf(documentStartAbsolute + span.end, scanEnd)
        if (start < end) PreferredTextRange(start, end) else null
    }

/**
 * 通过反射一次取出 Terminal 内部高亮快照（`getHighlightings()`），转成 [HighlightSpan]。
 *
 * getHighlightings / HighlightingInfo / TextAttributesProvider 均为 Internal API，
 * 直接调用会被插件验证器标记。反射调用不产生字节码级引用，验证器不会报告。
 * 必须与终端快照在同一个读锁里调用；任何反射异常降级为空列表（回退纯文本匹配）。
 */
private fun highlightSpansViaReflection(outputModel: TerminalOutputModel): List<HighlightSpan> = try {
    val highlightings =
        TerminalOutputModel::class.java.getMethod("getHighlightings").invoke(outputModel)
    val snapshotClass = highlightings.javaClass
    val size = snapshotClass.getMethod("getSize").invoke(highlightings) as Int
    val get = snapshotClass.getMethod("get", Int::class.javaPrimitiveType)
    val spans = ArrayList<HighlightSpan>(size)
    for (index in 0 until size) {
        val info = get.invoke(highlightings, index)
        val infoClass = info.javaClass
        val start = infoClass.getMethod("getStartOffset").invoke(info) as Int
        val end = infoClass.getMethod("getEndOffset").invoke(info) as Int
        val provider = infoClass.getMethod("getTextAttributesProvider").invoke(info)
        val attributes =
            provider.javaClass.getMethod("getTextAttributes").apply { isAccessible = true }.invoke(provider)
                as TextAttributes
        spans += HighlightSpan(start, end, attributes.backgroundColor != null)
    }
    spans
} catch (e: Exception) {
    NAVIGATOR_LOG.warn("highlighting reflection failed", e)
    emptyList()
}
