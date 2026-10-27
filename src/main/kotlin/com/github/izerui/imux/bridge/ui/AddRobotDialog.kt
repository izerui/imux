package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.bridge.BridgeLifecycle
import com.github.izerui.imux.bridge.BridgeSettings
import com.github.izerui.imux.bridge.channel.ChannelFactories
import com.github.izerui.imux.bridge.channel.OnboardingStep
import com.github.izerui.imux.bridge.robot.ChannelKind
import com.github.izerui.imux.bridge.robot.RobotManager
import com.intellij.CommonBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 添加机器人：选渠道 → 扫码 →（需要时）配对 → 完成。
 * 网络与轮询在 RobotManager 的协程里进行，界面状态经 invokeLater(ModalityState.any()) 刷新。
 */
class AddRobotDialog(project: Project?) : DialogWrapper(project, true) {
    private val manager = RobotManager.getInstance()
    private val cards = CardLayout()
    private val root = JPanel(cards)
    private val radios = ChannelKind.entries.associateWith { JBRadioButton(label(it)) }
    private val qrLabel = JBLabel()
    private val qrStatus = JBLabel()
    private val enabledHint = JBLabel()
    private val verifyField = JBTextField(8)
    private val verifyButton = JButton(ImuxBundle.message("bridge.add.verifyCode.submit"))
    private val verifyPanel = JPanel()
    private val retryButton = JButton(ImuxBundle.message("bridge.add.retry"))
    private val pairCode = JBLabel()
    private val pairInstruction = JBLabel()
    private val pairStatus = JBLabel()
    private val renewButton = JButton(ImuxBundle.message("bridge.add.pair.renew"))
    private var job: Job? = null
    private var driver: OnboardingDriver? = null
    private var kind: ChannelKind = ChannelKind.WECOM
    private var robotId: String? = null
    private var step = Card.CHANNEL

    private enum class Card { CHANNEL, QR, PAIR, DONE }

    init {
        title = ImuxBundle.message("bridge.add.title")
        setOKButtonText(ImuxBundle.message("bridge.add.next"))
        init()
    }

    override fun createCenterPanel(): JComponent {
        val group = ButtonGroup()
        val channelPanel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JBLabel(ImuxBundle.message("bridge.add.pickChannel")))
                radios.forEach { (kind, radio) ->
                    radio.isEnabled = ChannelFactories.forKind(kind) != null
                    group.add(radio)
                    add(radio)
                }
            }
        radios.getValue(ChannelKind.WECOM).isSelected = true

        verifyPanel.apply {
            add(JBLabel(ImuxBundle.message("bridge.add.verifyCode")))
            add(verifyField)
            add(verifyButton)
            isVisible = false
        }
        verifyButton.addActionListener { driver?.submitVerifyCode(verifyField.text.trim()) }
        retryButton.addActionListener { startScan() }
        retryButton.isVisible = false
        val qrPanel =
            JPanel(BorderLayout(JBUI.scale(16), 0)).apply {
                add(qrLabel, BorderLayout.WEST)
                add(
                    JPanel().apply {
                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                        add(enabledHint)
                        add(qrStatus)
                        add(verifyPanel)
                        add(retryButton)
                    },
                    BorderLayout.CENTER,
                )
            }

        pairCode.font = pairCode.font.deriveFont(Font.BOLD, JBUI.scaleFontSize(32f).toFloat())
        renewButton.addActionListener { robotId?.let { manager.renewPairCode(it) }?.let { showPair(it.pairCode.orEmpty()) } }
        val pairPanel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JBLabel(ImuxBundle.message("bridge.add.pair.title")))
                add(pairInstruction)
                add(pairCode)
                add(pairStatus)
                add(renewButton)
            }
        val donePanel = JPanel().apply { add(JBLabel(ImuxBundle.message("bridge.add.pair.done"))) }

        root.add(channelPanel, Card.CHANNEL.name)
        root.add(qrPanel, Card.QR.name)
        root.add(pairPanel, Card.PAIR.name)
        root.add(donePanel, Card.DONE.name)
        root.preferredSize = JBUI.size(520, 300)
        return root
    }

    override fun doOKAction() {
        when (step) {
            Card.CHANNEL -> {
                kind = radios.entries.first { it.value.isSelected }.key
                val justEnabled = ensureBridgeEnabled()
                if (justEnabled) enabledHint.text = ImuxBundle.message("bridge.add.enabledBridge")
                show(Card.QR)
                isOKActionEnabled = false
                startScan()
            }
            Card.DONE -> super.doOKAction()
            else -> Unit
        }
    }

    override fun dispose() {
        job?.cancel()
        super.dispose()
    }

    private fun startScan() {
        job?.cancel()
        qrLabel.icon = null
        retryButton.isVisible = false
        verifyPanel.isVisible = false
        val factory = ChannelFactories.forKind(kind) ?: return
        val current = OnboardingDriver(factory.onboarding(), { state -> ui { render(state) } })
        driver = current
        job = manager.launch { current.run() }
    }

    private fun render(state: DriverState) {
        when (state) {
            DriverState.Starting -> qrStatus.text = ImuxBundle.message("bridge.add.scan.starting")
            is DriverState.Qr -> {
                qrLabel.icon = ImageIcon(qrImage(state.content, JBUI.scale(200)))
                qrStatus.text = ImuxBundle.message("bridge.add.scan", label(kind)) + " · " + ImuxBundle.message("bridge.add.scan.waiting")
            }
            DriverState.NeedVerifyCode -> verifyPanel.isVisible = true
            DriverState.TimedOut -> {
                qrStatus.text = ImuxBundle.message("bridge.add.scan.timeout")
                retryButton.isVisible = true
            }
            is DriverState.Error -> {
                val reason = if (state.detail != null) ImuxBundle.message(state.messageKey, state.detail) else ImuxBundle.message(state.messageKey)
                qrStatus.text = ImuxBundle.message("bridge.add.failed", reason)
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
                    ui {
                        if (record.owner() != null) finish() else showPair(record.pairCode.orEmpty())
                    }
                    while (manager.robot(record.id)?.owner() == null) delay(500)
                    ui { finish() }
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ui {
                        qrStatus.text = ImuxBundle.message("bridge.add.failed", e.message ?: e.javaClass.simpleName)
                        retryButton.isVisible = true
                    }
                }
            }
    }

    private fun showPair(code: String) {
        pairInstruction.text = ImuxBundle.message("bridge.add.pair.instruction", label(kind))
        pairCode.text = code.chunked(3).joinToString(" ")
        pairStatus.text = ImuxBundle.message("bridge.add.pair.waiting")
        show(Card.PAIR)
    }

    private fun finish() {
        show(Card.DONE)
        setOKButtonText(CommonBundle.getOkButtonText())
        isOKActionEnabled = true
    }

    private fun ensureBridgeEnabled(): Boolean {
        val settings = BridgeSettings.getInstance()
        if (settings.state.enabled) return false
        settings.state.enabled = true
        BridgeLifecycle.applyEnabled(true)
        return true
    }

    private fun show(card: Card) {
        step = card
        cards.show(root, card.name)
    }

    private fun label(kind: ChannelKind): String =
        channelLabel(kind) + if (ChannelFactories.forKind(kind) == null) "（" + ImuxBundle.message("bridge.channel.later") + "）" else ""

    private fun ui(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ if (!isDisposed) block() }, ModalityState.any())
    }
}
