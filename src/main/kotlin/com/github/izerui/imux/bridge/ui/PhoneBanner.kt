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
import com.github.izerui.imux.terminal.StatusDotIcon
import com.github.izerui.imux.terminal.BannerDivider
import com.github.izerui.imux.terminal.BannerColors
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
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.RowIcon
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
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
    // 与设置页状态圆点同一判断：黄点即异常
    val problem = if (rowTone(status, paired = true, bridgeEnabled = true) == RowTone.ATTENTION) statusText(status) else null
    return PhoneBannerModel(robot.id, channel(robot.kind), robot.name, problem)
}

/** 手机段显示什么。开关关着也显示（未连接），点开菜单时再引导去设置里开启。 */
sealed interface PhoneSegmentState {
    data class Unbound(val bridgeEnabled: Boolean) : PhoneSegmentState

    data class Bound(val model: PhoneBannerModel) : PhoneSegmentState
}

fun phoneSegmentState(
    bridgeEnabled: Boolean,
    model: PhoneBannerModel?,
): PhoneSegmentState = if (bridgeEnabled && model != null) PhoneSegmentState.Bound(model) else PhoneSegmentState.Unbound(bridgeEnabled)

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
 * 终端顶部横幅右端的手机连接段：分隔线、状态圆点、手机图标、机器人名下拉；说明、换绑、重连、断开都在下拉菜单里。
 * 未连接时也显示「连接手机 ▾」，不必走右键菜单；通讯桥接开关关着时，菜单引导去设置里开启。
 */
class PhoneBanner(
    private val project: Project,
    private val file: AgentTerminalVirtualFile,
    parent: Disposable,
) : JPanel() {
    private val dot = StatusDotIcon()
    private val icons = JBLabel(dot)
    private val link = ActionLink("") { showMenu() }.apply {
        isFocusable = false
        setDropDownLinkIcon()
    }
    private var state: PhoneSegmentState = PhoneSegmentState.Unbound(bridgeEnabled = false)
    private val widthListener =
        object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = updateText()
        }

    init {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        alignmentY = Component.CENTER_ALIGNMENT
        add(Box.createHorizontalStrut(JBUI.scale(12)))
        add(BannerDivider())
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
        state = phoneSegmentState(enabled, next)
        if (next != null) {
            dot.color = if (next.problem == null) BannerColors.ONLINE else BannerColors.PROBLEM
            icons.icon = RowIcon(dot, AgentIcons.phone)
            val title = ImuxBundle.message("bridge.banner.connected", next.channel, next.robotName)
            // 机器人异常时提示里也写出状态，不能只说“已连接”
            val problem = next.problem?.let { " · " + StringUtil.escapeXmlEntities(it) }.orEmpty()
            toolTipText = "<html>${StringUtil.escapeXmlEntities(title)}$problem<br>${ImuxBundle.message("bridge.banner.hint")}</html>"
        } else {
            icons.icon = AgentIcons.phone
            toolTipText = ImuxBundle.message("bridge.banner.hint")
        }
        icons.toolTipText = toolTipText
        link.toolTipText = toolTipText
        updateText()
    }

    private fun updateText() {
        val width = (getParent()?.width ?: 0).let { if (it == 0) Int.MAX_VALUE else (it / JBUIScale.scale(1f)).toInt() }
        val text =
            when (val current = state) {
                is PhoneSegmentState.Bound ->
                    phoneBannerText(current.model, width) { channel, name -> ImuxBundle.message("bridge.banner.robot", channel, name) }
                is PhoneSegmentState.Unbound -> if (width >= NARROW_BANNER) ImuxBundle.message("bridge.banner.connect") else ""
            }
        if (link.text != text) link.text = text
        revalidate()
        repaint()
    }

    private fun showMenu() {
        val current =
            when (val segment = state) {
                is PhoneSegmentState.Bound -> segment.model
                is PhoneSegmentState.Unbound -> {
                    // 开关关着时不实例化中枢，菜单只引导去设置里开启
                    showConnectMenu(if (segment.bridgeEnabled) BridgeHub.getInstance() else null)
                    return
                }
            }
        val hub = BridgeHub.getInstance()
        val rebind = DefaultActionGroup(ImuxBundle.message("bridge.banner.rebind"), true)
        phoneMenuModel(
            RobotManager.getInstance().robots(),
            bindingOf = { hub.bindings.bindingOf(RobotId(it)) },
            statusOf = RobotManager.getInstance()::statusOf,
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

    /** 未连接时的菜单：说明、可选的机器人、添加机器人、管理。 */
    private fun showConnectMenu(hub: BridgeHub?) {
        val group = DefaultActionGroup()
        group.add(Separator.create(ImuxBundle.message("bridge.banner.hint")))
        if (hub == null) {
            group.add(
                simpleAction(ImuxBundle.message("bridge.action.disabled")) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, RobotsConfigurable::class.java)
                },
            )
            popup(group)
            return
        }
        phoneMenuModel(
            RobotManager.getInstance().robots(),
            bindingOf = { hub.bindings.bindingOf(RobotId(it)) },
            statusOf = RobotManager.getInstance()::statusOf,
            thisTabId = file.tabId,
            titleOf = BridgeLifecycle::sessionTitle,
            labels = BundleMenuLabels,
        ).forEach { entry ->
            when (entry) {
                is PhoneMenuEntry.Robot ->
                    group.add(simpleAction(entry.label, entry.enabled) { PhoneOperations.bind(project, file, entry.robotId) })
                PhoneMenuEntry.NoRobots -> group.add(simpleAction(ImuxBundle.message("bridge.action.none"), enabled = false) {})
                PhoneMenuEntry.AddRobot -> {
                    group.addSeparator()
                    group.add(simpleAction(ImuxBundle.message("bridge.action.add")) { AddRobotDialog(project).show() })
                }
                is PhoneMenuEntry.Disconnect -> Unit
            }
        }
        group.add(
            simpleAction(ImuxBundle.message("bridge.banner.manage")) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, RobotsConfigurable::class.java)
            },
        )
        popup(group)
    }

    private fun popup(group: DefaultActionGroup) {
        JBPopupFactory.getInstance()
            .createActionGroupPopup(null, group, DataManager.getInstance().getDataContext(link), JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false)
            .showUnderneathOf(link)
    }

    private fun simpleAction(
        text: String,
        enabled: Boolean = true,
        run: () -> Unit,
    ) = object : DumbAwareAction(text) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = enabled
        }

        override fun actionPerformed(e: AnActionEvent) = run()
    }
}
