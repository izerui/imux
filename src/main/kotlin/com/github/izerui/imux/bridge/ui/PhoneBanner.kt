package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.bridge.BridgeHub
import com.github.izerui.imux.bridge.BridgeLifecycle
import com.github.izerui.imux.bridge.BridgeSettings
import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.robot.ChannelKind
import com.github.izerui.imux.bridge.robot.RobotManager
import com.github.izerui.imux.bridge.robot.RobotRecord
import com.github.izerui.imux.icons.AgentIcons
import com.github.izerui.imux.terminal.AgentTerminalVirtualFile
import com.github.izerui.imux.terminal.SessionDecorationsListener
import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import kotlin.coroutines.cancellation.CancellationException

private val LOG = logger<PhoneBanner>()

data class PhoneBannerModel(
    val robotId: String,
    val channel: String,
    val robotName: String,
    val problem: String?,
)

fun phoneBannerModel(
    enabled: Boolean,
    binding: Binding?,
    robot: RobotRecord?,
    status: ConnectionStatus?,
    channel: (ChannelKind) -> String,
    statusText: (ConnectionStatus?) -> String,
): PhoneBannerModel? {
    if (!enabled || binding == null || robot == null) return null
    val problem = if (status == null || status == ConnectionStatus.Online) null else statusText(status)
    return PhoneBannerModel(robot.id, channel(robot.kind), robot.name, problem)
}

/** 终端顶部的手机连接提示条（原型第 7 步）。只在本标签连着手机时显示。 */
class PhoneBanner(
    private val project: Project,
    private val file: AgentTerminalVirtualFile,
    parent: Disposable,
) : JPanel() {
    private val title = JBLabel(AgentIcons.phone)
    private val hint = JBLabel(ImuxBundle.message("bridge.banner.hint")).apply { foreground = UIUtil.getContextHelpForeground() }
    private val rebind = ActionLink(ImuxBundle.message("bridge.banner.rebind")) { showRebindPopup() }
    private val disconnect = ActionLink(ImuxBundle.message("bridge.banner.disconnect")) { model?.let { PhoneOperations.disconnect(project, it.robotId) } }
    private var model: PhoneBannerModel? = null

    init {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = JBUI.Borders.empty(3, 12, 3, 24)
        isOpaque = true
        // 262 上 JBUI.CurrentTheme.Banner.SUCCESS_BACKGROUND 不存在，用 INFO_BACKGROUND 代替
        background = JBUI.CurrentTheme.Banner.INFO_BACKGROUND
        add(title)
        add(Box.createHorizontalStrut(JBUI.scale(10)))
        add(hint)
        add(Box.createHorizontalGlue())
        add(rebind)
        add(Box.createHorizontalStrut(JBUI.scale(12)))
        add(disconnect)
        ApplicationManager.getApplication().messageBus.connect(parent)
            .subscribe(
                SessionDecorationsListener.TOPIC,
                SessionDecorationsListener {
                    try {
                        refresh()
                    } catch (e: Throwable) {
                        if (e is CancellationException || e is ControlFlowException) throw e
                        LOG.warn("PhoneBanner 刷新出错，已忽略", e)
                    }
                },
            )
        refresh()
    }

    private fun refresh() {
        val enabled = BridgeSettings.getInstance().state.enabled
        val next =
            if (!enabled) {
                null
            } else {
                val binding = BridgeHub.getInstance().bindings.bindingForTab(file.tabId)
                val manager = RobotManager.getInstance()
                val robot = binding?.let { manager.robot(it.robotId.value) }
                phoneBannerModel(enabled, binding, robot, robot?.let { manager.statusOf(it.id) }, ::channelLabel) { statusLabel(it, paired = true) }
            }
        model = next
        isVisible = next != null
        if (next != null) {
            val text = ImuxBundle.message("bridge.banner.connected", next.channel, next.robotName)
            title.text = if (next.problem == null) text else text + " " + ImuxBundle.message("bridge.banner.status", next.problem)
        }
        revalidate()
        repaint()
    }

    private fun showRebindPopup() {
        val hub = BridgeHub.getInstance()
        val group = DefaultActionGroup()
        phoneMenuModel(
            RobotManager.getInstance().robots(),
            bindingOf = { hub.bindings.bindingOf(RobotId(it)) },
            thisTabId = file.tabId,
            titleOf = BridgeLifecycle::sessionTitle,
            labels = BundleMenuLabels,
        ).filterIsInstance<PhoneMenuEntry.Robot>().forEach { entry ->
            group.add(
                object : DumbAwareAction(entry.label) {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT

                    override fun update(e: AnActionEvent) {
                        e.presentation.isEnabled = entry.enabled
                    }

                    override fun actionPerformed(e: AnActionEvent) = PhoneOperations.bind(project, file, entry.robotId)
                },
            )
        }
        JBPopupFactory.getInstance()
            .createActionGroupPopup(null, group, DataManager.getInstance().getDataContext(rebind), JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false)
            .showUnderneathOf(rebind)
    }
}
