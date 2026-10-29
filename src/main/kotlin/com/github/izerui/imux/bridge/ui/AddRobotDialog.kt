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
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.CardLayout
import java.awt.Font
import java.awt.GridBagLayout
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

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
 * 添加机器人：选渠道 → 扫码 →（需要时）配对，配对成功留在配对页显示完成态。
 * 传入 [pairRobotId] 时直接进入配对卡片，为已保存但未认主的机器人重新配对。
 * 网络与轮询在 RobotManager 的协程里进行，界面状态经 invokeLater(ModalityState.any()) 刷新。
 */
class AddRobotDialog(
    project: Project?,
    pairRobotId: RobotId? = null,
) : DialogWrapper(project, true) {
    private val manager = RobotManager.getInstance()
    private val cards = CardLayout()
    private val root = JPanel(cards)
    private val radios = mutableMapOf<ChannelKind, JBRadioButton>()
    private val qrLabel = JBLabel()
    private val qrTitle = JBLabel().apply { font = JBFont.label().asBold() }
    private val qrSteps = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val qrStatus = JBLabel()
    private val retryButton = JButton(ImuxBundle.message("bridge.add.retry"))
    private val pairTitle = JBLabel().apply { font = JBFont.label().asBold() }
    private val pairCode = JBLabel()
    private val pairInstruction = JBLabel()
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
    private var kind: ChannelKind = ChannelKind.WECOM

    @Volatile
    private var robotId: RobotId? = null
    private var step = Card.CHANNEL

    /** 配对卡片实际显示的状态，只在 EDT 上用。 */
    private val pairView = PairStateView(::renderPairState)

    private enum class Card { CHANNEL, QR, PAIR }

    private companion object {
        /** 状态行超过这个宽度（未缩放像素）就换行；扫码页右栏在二维码旁边，按它取窄的。 */
        const val STATUS_WIDTH = 300
        val ONLINE_DOT = StatusDotIcon(BannerColors.ONLINE)
        val PROBLEM_DOT = StatusDotIcon(BannerColors.PROBLEM)
    }

    init {
        title = ImuxBundle.message("bridge.add.title")
        setOKButtonText(ImuxBundle.message("bridge.add.next"))
        init()
        if (pairRobotId != null) startPairing(pairRobotId)
    }

    override fun createCenterPanel(): JComponent {
        // 版式与文案对齐 docs/prototypes/terminal-channel-bridge.html 的“添加机器人 / 扫码 / 配对”三页
        val channelPanel =
            panel {
                row { label(ImuxBundle.message("bridge.add.title")).bold() }
                row { comment(ImuxBundle.message("bridge.add.subtitle")) }
                buttonsGroup {
                    ChannelKind.entries.chunked(2).forEach { kinds ->
                        row {
                            kinds.forEach { kind ->
                                radioButton(label(kind))
                                    .comment(ImuxBundle.message("bridge.channel.${kind.id}.desc"))
                                    .enabled(ChannelFactories.forKind(kind) != null)
                                    .applyToComponent { radios[kind] = this }
                            }
                        }.layout(RowLayout.PARENT_GRID).topGap(TopGap.SMALL)
                    }
                }
            }
        radios.getValue(ChannelKind.WECOM).isSelected = true

        retryButton.addActionListener { startScan() }
        retryButton.isVisible = false
        val qrPanel =
            panel {
                row {
                    cell(qrLabel).align(AlignY.TOP)
                    panel {
                        row { cell(qrTitle) }
                        row { cell(qrSteps) }
                        row { cell(qrStatus) }.topGap(TopGap.SMALL)
                        row { cell(retryButton) }
                    }.align(AlignY.TOP)
                }
            }

        pairCode.font = JBFont.create(Font(Font.MONOSPACED, Font.BOLD, 30))
        renewButton.addActionListener { robotId?.let(::renew) }
        val pairPanel =
            panel {
                row { cell(pairTitle) }
                instructionRow = row { cell(pairInstruction) }.topGap(TopGap.SMALL)
                codeRow = row { cell(pairCode) }
                noteRow = row { comment(ImuxBundle.message("bridge.add.pair.note")) }
                row { cell(pairStatus) }.topGap(TopGap.SMALL)
                renewRow = row { cell(renewButton) }
            }

        // 选渠道页按原样靠左上排；扫码、配对页内容居中
        root.add(channelPanel, Card.CHANNEL.name)
        root.add(centered(qrPanel), Card.QR.name)
        root.add(centered(pairPanel), Card.PAIR.name)
        root.preferredSize = JBUI.size(560, 320)
        return root
    }

    override fun doOKAction() {
        when (step) {
            Card.CHANNEL -> {
                kind = radios.entries.first { it.value.isSelected }.key
                show(Card.QR)
                isOKActionEnabled = false
                startScan()
            }

            Card.PAIR -> if (paired) super.doOKAction()
            Card.QR -> Unit
        }
    }

    override fun dispose() {
        job?.cancel()
        super.dispose()
    }

    private fun startScan() {
        job?.cancel()
        qrLabel.icon = null
        qrTitle.text = ImuxBundle.message("bridge.add.scan", channelLabel(kind))
        qrSteps.text = html(
            ImuxBundle.message(
                ChannelFactories.forKind(kind)?.scanStepsKey ?: "bridge.add.scan.steps",
                channelLabel(kind)
            )
        )
        retryButton.isVisible = false
        val factory = ChannelFactories.forKind(kind) ?: return
        val current = OnboardingDriver(factory.onboarding(), { state -> ui { render(state) } })
        driver = current
        job = manager.launch { current.run() }
    }

    private fun render(state: DriverState) {
        when (state) {
            DriverState.Starting -> setStatus(qrStatus, ImuxBundle.message("bridge.add.scan.starting"), PROBLEM_DOT)
            is DriverState.Qr -> {
                qrLabel.icon = ImageIcon(qrImage(state.content, JBUI.scale(200)))
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

            is DriverState.Done -> created(state.step)
        }
    }

    private fun created(result: OnboardingStep.Succeeded) {
        val name = result.suggestedName ?: ImuxBundle.message("bridge.add.defaultName", manager.robots().size + 1)
        job =
            manager.launch {
                try {
                    val record = manager.add(kind, result.credentials, result.ownerId, name)
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
                    }
                }
            }
    }

    /** 为已保存的机器人重新配对：续期配对码、确保连接在线，再等待认主。 */
    private fun startPairing(id: RobotId) {
        val record = manager.robot(id) ?: return
        kind = record.kind
        robotId = id
        isOKActionEnabled = false
        pairTitle.text = ImuxBundle.message("bridge.add.pair.repair", record.name)
        if (record.owner() != null) {
            finish()
            return
        }
        reconnectIfDown(id)
        manager.renewPairCode(id)?.let { showPair(it.pairCode.orEmpty()) }
        job = manager.launch { awaitPaired(id) }
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
        pairInstruction.text = ImuxBundle.message("bridge.add.pair.instruction", channelLabel(kind))
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

                else -> ImuxBundle.message("bridge.add.pair.waiting")
            }
        // 原型：等待与异常都是黄点
        setStatus(pairStatus, text, PROBLEM_DOT)
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
        setStatus(pairStatus, ImuxBundle.message("bridge.add.pair.done"), ONLINE_DOT)
        show(Card.PAIR)
        setOKButtonText(CommonBundle.getOkButtonText())
        isOKActionEnabled = true
    }

    private fun setStatus(
        label: JBLabel,
        text: String,
        dot: Icon,
    ) {
        label.icon = dot
        // 内容居中，超宽的一行会左右两头都被截掉：长文字限宽换行
        label.text = if (label.getFontMetrics(label.font).stringWidth(text) > JBUI.scale(STATUS_WIDTH)) html(
            text,
            STATUS_WIDTH
        ) else text
    }

    private fun show(card: Card) {
        step = card
        cards.show(root, card.name)
    }

    /** 内容作为一个整体在对话框里水平、垂直居中，不贴左上角留出大片空白。 */
    private fun centered(content: JComponent): JComponent = JPanel(GridBagLayout()).apply { add(content) }

    /** 原型里配对码逐位隔开，便于对照着在手机上输入。 */
    private fun spaced(code: String): String = code.toList().joinToString(" ")

    /** 多行说明用 HTML 换行；先转义，文案里的尖括号不会被当成标签。 */
    private fun html(
        text: String,
        width: Int? = null,
    ): String {
        val body = StringUtil.escapeXmlEntities(text).replace("\n", "<br>")
        return if (width == null) "<html>$body</html>" else "<html><div style='width:${width}px'>$body</div></html>"
    }

    private fun label(kind: ChannelKind): String =
        channelLabel(kind) + if (ChannelFactories.forKind(kind) == null) "（" + ImuxBundle.message("bridge.channel.later") + "）" else ""

    private fun ui(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ if (!isDisposed) block() }, ModalityState.any())
    }
}
