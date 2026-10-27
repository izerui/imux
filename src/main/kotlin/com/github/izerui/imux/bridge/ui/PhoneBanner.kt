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
import com.github.izerui.imux.settings.ImuxSettings
import com.github.izerui.imux.terminal.AgentTerminalVirtualFile
import com.github.izerui.imux.terminal.SessionDecorationsListener
import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.RowIcon
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
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

/** 横幅宽度够时显示渠道与名称，稍窄只留渠道（出问题时换成状态），再窄只留图标。宽度为未缩放像素。 */
fun phoneBannerText(
    model: PhoneBannerModel,
    bannerWidth: Int,
    robotLabel: (channel: String, name: String) -> String,
): String =
    when {
        bannerWidth >= WIDE_BANNER -> robotLabel(model.channel, model.robotName) + (model.problem?.let { " · $it" } ?: "")
        bannerWidth >= NARROW_BANNER -> model.problem ?: model.channel
        else -> ""
    }

private const val WIDE_BANNER = 900
private const val NARROW_BANNER = 600

/**
 * 终端顶部横幅右端的手机连接段（横幅合并方案 A）：分隔线、状态圆点、手机图标、
 * 机器人名下拉。只在本标签连着手机时显示；说明、换绑、重连、断开都在下拉菜单里。
 */
class PhoneBanner(
    private val project: Project,
    private val file: AgentTerminalVirtualFile,
    parent: Disposable,
) : JPanel() {
    private val divider =
        object : JPanel() {
            init {
                isOpaque = true
                background = DIVIDER
            }

            override fun getPreferredSize() = Dimension(JBUI.scale(1), JBUI.scale(14))

            override fun getMaximumSize() = preferredSize
        }
    private val dot = StatusDot()
    private val icons = JBLabel(dot)
    private val link = ActionLink("") { showMenu() }.apply {
        isFocusable = false
        setDropDownLinkIcon()
    }
    private var model: PhoneBannerModel? = null
    private val widthListener =
        object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = updateText()
        }

    init {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        alignmentY = Component.CENTER_ALIGNMENT
        add(Box.createHorizontalStrut(JBUI.scale(12)))
        add(divider)
        add(Box.createHorizontalStrut(JBUI.scale(12)))
        add(icons)
        add(Box.createHorizontalStrut(JBUI.scale(4)))
        add(link)
        val listener =
            SessionDecorationsListener {
                try {
                    refresh()
                } catch (e: Throwable) {
                    if (e is CancellationException || e is ControlFlowException || e is VirtualMachineError) throw e
                    LOG.warn("PhoneBanner 刷新出错，已忽略", e)
                }
            }
        ApplicationManager.getApplication().messageBus.connect(parent).subscribe(SessionDecorationsListener.TOPIC, listener)
        ImuxSettings.getInstance().addLanguageListener(parent) { listener.decorationsChanged() }
        refresh()
    }

    override fun addNotify() {
        super.addNotify()
        getParent()?.addComponentListener(widthListener)
        updateText()
    }

    override fun removeNotify() {
        getParent()?.removeComponentListener(widthListener)
        super.removeNotify()
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
            dot.color = if (next.problem == null) ONLINE else PROBLEM
            icons.icon = RowIcon(dot, AgentIcons.phone)
            val title = ImuxBundle.message("bridge.banner.connected", next.channel, next.robotName)
            toolTipText = "<html>$title<br>${ImuxBundle.message("bridge.banner.hint")}</html>"
            icons.toolTipText = toolTipText
            link.toolTipText = toolTipText
        }
        updateText()
    }

    private fun updateText() {
        val current = model ?: return
        val width = (getParent()?.width ?: 0).let { if (it == 0) Int.MAX_VALUE else (it / JBUIScale.scale(1f)).toInt() }
        val text = phoneBannerText(current, width) { channel, name -> ImuxBundle.message("bridge.banner.robot", channel, name) }
        if (link.text != text) link.text = text
        revalidate()
        repaint()
    }

    private fun showMenu() {
        val current = model ?: return
        val hub = BridgeHub.getInstance()
        val rebind = DefaultActionGroup(ImuxBundle.message("bridge.banner.rebind"), true)
        phoneMenuModel(
            RobotManager.getInstance().robots(),
            bindingOf = { hub.bindings.bindingOf(RobotId(it)) },
            thisTabId = file.tabId,
            titleOf = BridgeLifecycle::sessionTitle,
            labels = BundleMenuLabels,
        ).filterIsInstance<PhoneMenuEntry.Robot>().forEach { entry ->
            rebind.add(
                object : DumbAwareAction(entry.label) {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT

                    override fun update(e: AnActionEvent) {
                        e.presentation.isEnabled = entry.enabled
                    }

                    override fun actionPerformed(e: AnActionEvent) = PhoneOperations.bind(project, file, entry.robotId)
                },
            )
        }
        val group = DefaultActionGroup()
        group.add(Separator.create(ImuxBundle.message("bridge.banner.hint")))
        group.add(rebind)
        group.add(simpleAction(ImuxBundle.message("bridge.settings.reconnect")) { RobotManager.getInstance().reconnect(current.robotId) })
        group.add(simpleAction(ImuxBundle.message("bridge.action.disconnect")) { PhoneOperations.disconnect(project, current.robotId) })
        group.addSeparator()
        group.add(
            simpleAction(ImuxBundle.message("bridge.banner.manage")) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, RobotsConfigurable::class.java)
            },
        )
        JBPopupFactory.getInstance()
            .createActionGroupPopup(null, group, DataManager.getInstance().getDataContext(link), JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false)
            .showUnderneathOf(link)
    }

    private fun simpleAction(
        text: String,
        run: () -> Unit,
    ) = object : DumbAwareAction(text) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) = run()
    }

    /** 连接状态圆点：在线为绿，重连、被接管、失败等为黄。 */
    private class StatusDot : Icon {
        var color: JBColor = ONLINE

        override fun paintIcon(
            c: Component?,
            g: Graphics,
            x: Int,
            y: Int,
        ) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                val size = JBUI.scale(7)
                g2.fillOval(x + (iconWidth - size) / 2, y + (iconHeight - size) / 2, size, size)
            } finally {
                g2.dispose()
            }
        }

        override fun getIconWidth() = JBUI.scale(12)

        override fun getIconHeight() = JBUI.scale(16)
    }

    private companion object {
        val ONLINE = JBColor(0x369650, 0x5FB865)
        val PROBLEM = JBColor(0xC27D04, 0xE0B153)
        val DIVIDER = JBColor(0xA8BFE8, 0x35538F)
    }
}
