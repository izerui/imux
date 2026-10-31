package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.bridge.BridgeHub
import com.github.izerui.imux.bridge.RobotManager
import com.github.izerui.imux.bridge.channel.ChannelFactories
import com.github.izerui.imux.bridge.channel.ChannelKind
import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.channel.OnboardingStep
import com.github.izerui.imux.bridge.channel.isWorking
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.robot.RobotRecord
import com.github.izerui.imux.terminal.BannerColors
import com.github.izerui.imux.terminal.StatusDotIcon
import com.intellij.CommonBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Font
import java.awt.GridBagLayout
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * 把文字折成每行不超过 [width] 的若干行：原有换行保留；中日韩字符可在任意字之间断开，
 * 其它文字按词断开（一个词本身超宽时独占一行）。
 */
internal fun wrapLines(
    text: String,
    width: Int,
    measure: (String) -> Int,
): List<String> =
    text.split('\n').flatMap { paragraph ->
        val lines = mutableListOf<String>()
        var line = ""
        for (token in WRAP_TOKEN.findAll(paragraph).map { it.value }) {
            val candidate = line + token
            if (line.isNotEmpty() && measure(candidate.trimEnd()) > width) {
                lines += line.trimEnd()
                line = token.trimStart()
            } else {
                line = candidate
            }
        }
        lines + line.trimEnd()
    }

/** 折行单位：单个中日韩字符或全角标点，或一个连同后面空白的词。 */
private val WRAP_TOKEN = Regex("[\\u2E80-\\u9FFF\\uF900-\\uFAFF\\uFF00-\\uFFEF\\u3000-\\u303F]|[^\\s\\u2E80-\\u9FFF\\uF900-\\uFAFF\\uFF00-\\uFFEF\\u3000-\\u303F]+\\s*|\\s+")

/**
 * 等待认主的一次检查结果。[INVALIDATED]、[EXPIRED]、[DISCONNECTED] 时这个配对码已经不可能成功，
 * 页面要说明原因并提示重新生成，不能继续显示“等待配对码”。
 */
internal enum class PairWait { WAITING, PAIRED, GONE, INVALIDATED, EXPIRED, DISCONNECTED }

internal fun pairWait(
    robot: RobotRecord?,
    nowMillis: Long,
    status: ConnectionStatus?,
): PairWait =
    when {
        robot == null -> PairWait.GONE
        robot.owner() != null -> PairWait.PAIRED
        // 连续猜错后配对码被作废
        robot.pairCode == null -> PairWait.INVALIDATED
        nowMillis > robot.pairCodeExpiresAtMillis -> PairWait.EXPIRED
        // 连接中或尚未开始连接都算还在等；失败、被接管、已停止就收不到配对码
        status != null && !status.isWorking -> PairWait.DISCONNECTED
        else -> PairWait.WAITING
    }

/**
 * 等认主，返回 [PairWait.PAIRED] 或 [PairWait.GONE]；其余状态交给 [show] 显示后继续等。
 *
 * 不轮询：认主、重新生成配对码、连接状态变化都会推进 [revision]，每次判断前先记下版本，
 * 判断之后发生的变化一定等得到。配对码到期没有事件，所以配对码还有效时总安排一次到期检查：
 * 剩余时间与判断用同一个时刻算，至少 1ms，判断后恰好跨过到期点也不会变成无限期地等事件。
 */
internal suspend fun awaitPairing(
    revision: StateFlow<Long>,
    now: () -> Long,
    robot: () -> RobotRecord?,
    status: () -> ConnectionStatus?,
    show: (PairWait, ConnectionStatus?) -> Unit,
): PairWait {
    while (true) {
        val seen = revision.value
        val at = now()
        val record = robot()
        val current = status()
        val wait = pairWait(record, at, current)
        if (wait == PairWait.PAIRED || wait == PairWait.GONE) return wait
        show(wait, current)
        if (wait == PairWait.WAITING && record != null) {
            // pairWait 在当前时刻晚于到期时刻才算过期，所以等到到期时刻之后 1ms
            val untilExpired = maxOf(1L, record.pairCodeExpiresAtMillis - at + 1)
            withTimeoutOrNull(untilExpired) { revision.first { it != seen } }
        } else {
            revision.first { it != seen }
        }
    }
}

/**
 * 配对页实际显示的状态，只在 EDT 上读写。后台每次判断都先投递到 EDT 再交给 [offer]：比较必须对照
 * 执行那一刻页面上的样子——重新生成配对码会在 EDT 上把页面改回“等待”，若在后台先比较再投递，
 * 拿的是过时的显示状态，会跳过本该有的刷新。连接状态（含失败原因）也参与比较。
 */
internal class PairStateView(
    private val render: (PairWait, ConnectionStatus?) -> Unit,
) {
    private var shown: Pair<PairWait, ConnectionStatus?>? = null

    /** 直接显示（重新生成配对码、完成）。 */
    fun show(
        wait: PairWait,
        status: ConnectionStatus?,
    ) {
        shown = wait to status
        // 完成页由对话框自己画，这里只记下
        if (wait != PairWait.PAIRED) render(wait, status)
    }

    /** 后台判断出的状态：与当前显示不同才刷新。 */
    fun offer(
        wait: PairWait,
        status: ConnectionStatus?,
    ) {
        if (shown != wait to status) show(wait, status)
    }
}

/**
 * 添加机器人：左边渠道列表，选中即在右边扫码 →（需要时）配对，配对成功留在配对页显示完成态
 * （版式见 docs/prototypes/add-robot-dialog，用户 2026-10-30 选定方向 C）。
 * 扫码成功、机器人建好后锁定列表。传入 [pairRobotId] 时直接进入配对卡片，为已保存但未认主的机器人重新配对。
 * 网络与轮询在 RobotManager 的协程里进行，界面状态经 invokeLater(ModalityState.any()) 刷新。
 */
class AddRobotDialog(
    project: Project?,
    pairRobotId: RobotId? = null,
) : DialogWrapper(project, true) {
    private val manager = RobotManager.getInstance()
    private val cards = CardLayout()
    private val content = JPanel(cards)
    private val channelList =
        JBList(CHANNELS).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer =
                object : ColoredListCellRenderer<ChannelKind>() {
                    override fun customizeCellRenderer(
                        list: JList<out ChannelKind>,
                        value: ChannelKind,
                        index: Int,
                        selected: Boolean,
                        hasFocus: Boolean,
                    ) {
                        val ready = ChannelFactories.forKind(value) != null
                        append(label(value), if (ready) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        toolTipText = ImuxBundle.message("bridge.channel.${value.id}.desc")
                        ipad = JBUI.insets(5, 10)
                    }
                }
        }
    private val qrLabel = JBLabel()
    private val qrTitle = JBLabel().apply { font = JBFont.label().asBold() }
    private val qrSteps = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val qrStatus = JBLabel()
    private val retryButton = JButton(ImuxBundle.message("bridge.add.retry"))
    private val pairTitle = JBLabel().apply { font = JBFont.label().asBold() }
    private val pairCode = JBLabel()
    private val pairInstruction = JBLabel()
    private val pairNote = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val pairStatus = JBLabel()

    /** 当前配对码，配对成功后划掉显示；扫码即认主的渠道没有配对码。 */
    private var shownCode = ""
    private var paired = false

    // 完成页只留标题、划掉的配对码（扫码即认主时没有）与状态，说明和按钮都收起
    private lateinit var instructionRow: Row
    private lateinit var codeRow: Row
    private lateinit var noteRow: Row
    private lateinit var renewRow: Row
    private val renewButton = JButton(ImuxBundle.message("bridge.add.pair.renew"))
    private var job: Job? = null
    private var driver: OnboardingDriver? = null
    private var kind: ChannelKind = CHANNELS.first()

    @Volatile
    private var robotId: RobotId? = null

    /** 配对卡片实际显示的状态，只在 EDT 上用。 */
    private val pairView = PairStateView(::renderPairState)

    private enum class Card { QR, PAIR }

    private companion object {
        /** 扫码页二维码旁说明栏的宽度（未缩放像素），超过就换行。 */
        const val SCAN_TEXT_WIDTH = 210

        /** 配对页文字的宽度（未缩放像素），超过就换行。 */
        const val PAIR_TEXT_WIDTH = 320

        /** 二维码边长（未缩放像素）。 */
        const val QR_SIZE = 170

        /** 左侧渠道列表宽度（未缩放像素）。 */
        const val LIST_WIDTH = 140

        /** 渠道按推荐顺序排列，打开时选中第一个（用户 2026-10-30 决定）。 */
        val CHANNELS = listOf(ChannelKind.FEISHU, ChannelKind.WECOM, ChannelKind.DINGTALK, ChannelKind.WEIXIN)

        val ONLINE_DOT = StatusDotIcon(BannerColors.ONLINE)
        val PROBLEM_DOT = StatusDotIcon(BannerColors.PROBLEM)
    }

    init {
        title = ImuxBundle.message("bridge.add.title")
        setOKButtonText(CommonBundle.getOkButtonText())
        init()
        isOKActionEnabled = false
        // 要重新配对的机器人已被删除时，按正常添加流程走
        if (pairRobotId == null || !startPairing(pairRobotId)) {
            channelList.addListSelectionListener { e ->
                if (!e.valueIsAdjusting) channelList.selectedValue?.let(::pick)
            }
            channelList.selectedIndex = 0
        }
    }

    override fun createCenterPanel(): JComponent {
        retryButton.addActionListener { startScan() }
        retryButton.isVisible = false
        val qrPanel =
            panel {
                row {
                    cell(qrLabel).align(AlignY.CENTER)
                    panel {
                        row { cell(qrTitle) }
                        row { cell(qrSteps) }.topGap(TopGap.SMALL)
                        row { cell(qrStatus) }.topGap(TopGap.SMALL)
                        row { cell(retryButton) }
                    }.align(AlignY.CENTER)
                }
            }

        pairCode.font = JBFont.create(Font(Font.MONOSPACED, Font.BOLD, 30))
        renewButton.addActionListener { robotId?.let(::renew) }
        val pairPanel =
            panel {
                // 配对页各行在右侧居中，文字居中排
                row { cell(pairTitle).align(AlignX.CENTER) }
                instructionRow = row { cell(pairInstruction).align(AlignX.CENTER) }.topGap(TopGap.SMALL)
                codeRow = row { cell(pairCode).align(AlignX.CENTER) }.topGap(TopGap.SMALL)
                noteRow = row { cell(pairNote).align(AlignX.CENTER) }
                row { cell(pairStatus).align(AlignX.CENTER) }.topGap(TopGap.MEDIUM)
                renewRow = row { cell(renewButton).align(AlignX.CENTER) }
            }
        setText(pairNote, ImuxBundle.message("bridge.add.pair.note"), PAIR_TEXT_WIDTH, center = true)

        // 右边内容在剩余区域里居中
        content.add(centered(qrPanel), Card.QR.name)
        content.add(centered(pairPanel), Card.PAIR.name)
        val list =
            JBScrollPane(channelList).apply {
                border = JBUI.Borders.customLineRight(JBColor.border())
                preferredSize = JBUI.size(LIST_WIDTH, 0)
            }
        return JPanel(BorderLayout()).apply {
            add(list, BorderLayout.WEST)
            add(content, BorderLayout.CENTER)
            preferredSize = JBUI.size(560, 360)
        }
    }

    override fun doOKAction() {
        if (paired) super.doOKAction()
    }

    /** 选中渠道即扫码；换渠道时取消上一个渠道的扫码。 */
    private fun pick(selected: ChannelKind) {
        if (ChannelFactories.forKind(selected) == null) return
        kind = selected
        show(Card.QR)
        startScan()
    }

    override fun dispose() {
        job?.cancel()
        super.dispose()
    }

    private fun startScan() {
        job?.cancel()
        qrLabel.icon = null
        qrTitle.text = ImuxBundle.message("bridge.add.scan", channelLabel(kind))
        setText(
            qrSteps,
            ImuxBundle.message(ChannelFactories.forKind(kind)?.scanStepsKey ?: "bridge.add.scan.steps", channelLabel(kind)),
            SCAN_TEXT_WIDTH,
        )
        retryButton.isVisible = false
        driver = null
        val factory = ChannelFactories.forKind(kind) ?: return
        val scanKind = kind
        lateinit var current: OnboardingDriver
        // 换渠道后，旧扫码已投递到界面线程、还没执行的回调不再生效
        current = OnboardingDriver(factory.onboarding(), { state -> ui { if (driver === current) render(state, scanKind) } })
        driver = current
        job = manager.launch { current.run() }
    }

    private fun render(
        state: DriverState,
        scanKind: ChannelKind,
    ) {
        when (state) {
            DriverState.Starting -> {
                qrLabel.icon = null
                setStatus(qrStatus, ImuxBundle.message("bridge.add.scan.starting"), PROBLEM_DOT)
            }

            is DriverState.Qr -> {
                retryButton.isVisible = false
                qrLabel.icon = ImageIcon(qrImage(state.content, JBUI.scale(QR_SIZE)))
                setStatus(qrStatus, ImuxBundle.message("bridge.add.scan.waiting"), PROBLEM_DOT)
            }

            DriverState.TimedOut -> {
                setStatus(qrStatus, ImuxBundle.message("bridge.add.scan.timeout"), PROBLEM_DOT)
                retryButton.isVisible = true
            }

            is DriverState.Error -> {
                val reason = if (state.detail != null) ImuxBundle.message(
                    state.messageKey,
                    state.detail
                ) else ImuxBundle.message(state.messageKey)
                setStatus(qrStatus, ImuxBundle.message("bridge.add.failed", reason), PROBLEM_DOT)
                retryButton.isVisible = true
            }

            is DriverState.Done -> {
                // 机器人已建好，不能再换渠道
                channelList.isEnabled = false
                created(state.step, scanKind)
            }
        }
    }

    private fun created(
        result: OnboardingStep.Succeeded,
        scanKind: ChannelKind,
    ) {
        val name = result.suggestedName ?: ImuxBundle.message("bridge.add.defaultName", manager.robots().size + 1)
        job =
            manager.launch {
                try {
                    val record = manager.add(scanKind, result.credentials, result.ownerId, name)
                    robotId = record.id
                    ui { pairTitle.text = ImuxBundle.message("bridge.add.pair.created", record.name) }
                    if (record.owner() == null) ui { showPair(record.pairCode.orEmpty()) }
                    awaitPaired(record.id)
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ui {
                        setStatus(
                            qrStatus,
                            ImuxBundle.message("bridge.add.failed", e.message ?: e.javaClass.simpleName),
                            PROBLEM_DOT
                        )
                        retryButton.isVisible = true
                        channelList.isEnabled = true
                    }
                }
            }
    }

    /** 为已保存的机器人重新配对：续期配对码、确保连接在线，再等待认主。 */
    private fun startPairing(id: RobotId): Boolean {
        val record = manager.robot(id) ?: return false
        kind = record.kind
        robotId = id
        channelList.setSelectedValue(kind, true)
        channelList.isEnabled = false
        pairTitle.text = ImuxBundle.message("bridge.add.pair.repair", record.name)
        if (record.owner() != null) {
            finish()
            return true
        }
        reconnectIfDown(id)
        manager.renewPairCode(id)?.let { showPair(it.pairCode.orEmpty()) }
        job = manager.launch { awaitPaired(id) }
        return true
    }

    /** 重新生成配对码；连接已失败、被接管或停止时一并重连，否则新配对码同样收不到。 */
    private fun renew(id: RobotId) {
        reconnectIfDown(id)
        manager.renewPairCode(id)?.let { showPair(it.pairCode.orEmpty()) }
    }

    private fun reconnectIfDown(id: RobotId) {
        if (!manager.statusOf(id).isWorking) manager.reconnect(id)
    }

    /**
     * 认主后进入完成卡片；机器人被删除时停止等待。配对码失效、过期或连接异常时
     * 说明原因、置灰旧配对码，但继续等待：用户点“重新生成”后自动恢复。
     */
    private suspend fun awaitPaired(id: RobotId) {
        val result =
            awaitPairing(
                revision = BridgeHub.getInstance().revision,
                now = System::currentTimeMillis,
                robot = { manager.robot(id) },
                status = { manager.statusOf(id) },
            ) { wait, status ->
                // 先投递到 EDT，再与页面此刻的显示比较：重新生成配对码会在 EDT 上把页面改回“等待”
                ui { pairView.offer(wait, status) }
            }
        if (result == PairWait.PAIRED) ui { finish() }
    }

    private fun showPair(code: String) {
        shownCode = code
        setText(pairInstruction, ImuxBundle.message("bridge.add.pair.instruction", channelLabel(kind)), PAIR_TEXT_WIDTH, center = true)
        pairCode.text = spaced(code)
        // 绿点只表示可用，等待配对时标题不带点，状态行的黄点说明进度
        pairTitle.icon = null
        pairView.show(PairWait.WAITING, null)
        show(Card.PAIR)
    }

    private fun renderPairState(
        wait: PairWait,
        status: ConnectionStatus?,
    ) {
        pairCode.isEnabled = wait == PairWait.WAITING
        val text =
            when (wait) {
                PairWait.INVALIDATED -> ImuxBundle.message("bridge.add.pair.invalidated")
                PairWait.EXPIRED -> ImuxBundle.message("bridge.add.pair.expired")
                PairWait.DISCONNECTED -> ImuxBundle.message(
                    "bridge.add.pair.disconnected",
                    statusLabel(status, paired = false)
                )

                // 收发受阻仍算工作中、继续等，但要说明原因：钉钉机器人未发布时消息没有 staffId，配对码永远到不了
                else ->
                    ImuxBundle.message("bridge.add.pair.waiting") +
                        if (status is ConnectionStatus.SendFailing) " " + statusLabel(status, paired = false) else ""
            }
        // 原型：等待与异常都是黄点
        setStatus(pairStatus, text, PROBLEM_DOT, PAIR_TEXT_WIDTH, center = true)
    }

    /** 配对成功：留在配对页，划掉配对码、绿点提示可以连接会话（原型的完成态）。 */
    private fun finish() {
        paired = true
        pairView.show(PairWait.PAIRED, null)
        pairTitle.icon = ONLINE_DOT
        listOf(instructionRow, noteRow, renewRow).forEach { it.visible(false) }
        codeRow.visible(shownCode.isNotEmpty())
        pairCode.isEnabled = false
        pairCode.text = "<html><s>" + spaced(shownCode) + "</s></html>"
        setStatus(pairStatus, ImuxBundle.message("bridge.add.pair.done"), ONLINE_DOT, PAIR_TEXT_WIDTH, center = true)
        show(Card.PAIR)
        isOKActionEnabled = true
    }

    private fun setStatus(
        label: JBLabel,
        text: String,
        dot: Icon,
        width: Int = SCAN_TEXT_WIDTH,
        center: Boolean = false,
    ) {
        label.icon = dot
        setText(label, text, width, center)
    }

    /**
     * 超过 [width] 的行按字体实际宽度自己折行，再用 HTML 换行显示。不用 CSS 宽度：Swing 换算 CSS 像素后
     * 实际宽度比写的大，长文字不折、短文字又把标签撑宽，内容就偏向一边。
     */
    private fun setText(
        label: JBLabel,
        text: String,
        width: Int,
        center: Boolean = false,
    ) {
        val metrics = label.getFontMetrics(label.font)
        val lines = wrapLines(text, JBUI.scale(width), metrics::stringWidth)
        label.text = if (lines.size == 1) lines.single() else html(lines, center)
    }

    private fun show(card: Card) {
        cards.show(content, card.name)
    }

    /** 内容作为一个整体在对话框里水平、垂直居中，不贴左上角留出大片空白。 */
    private fun centered(content: JComponent): JComponent = JPanel(GridBagLayout()).apply { add(content) }

    /** 原型里配对码逐位隔开，便于对照着在手机上输入。 */
    private fun spaced(code: String): String = code.toList().joinToString(" ")

    /** 先转义，文案里的尖括号不会被当成标签。 */
    private fun html(
        lines: List<String>,
        center: Boolean,
    ): String {
        val body = lines.joinToString("<br>") { StringUtil.escapeXmlEntities(it) }
        return if (center) "<html><center>$body</center></html>" else "<html>$body</html>"
    }

    private fun label(kind: ChannelKind): String =
        channelLabel(kind) + if (ChannelFactories.forKind(kind) == null) "（" + ImuxBundle.message("bridge.channel.later") + "）" else ""

    private fun ui(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ if (!isDisposed) block() }, ModalityState.any())
    }
}
