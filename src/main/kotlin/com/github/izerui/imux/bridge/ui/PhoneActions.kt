package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.bridge.BindingService
import com.github.izerui.imux.bridge.BridgeHub
import com.github.izerui.imux.bridge.RobotManager
import com.github.izerui.imux.bridge.SessionTitles
import com.github.izerui.imux.bridge.terminal.bindingSessionAvailable
import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.core.SessionTarget
import com.github.izerui.imux.icons.AgentIcons
import com.github.izerui.imux.terminal.AgentTerminalVirtualFile
import com.github.izerui.imux.terminal.BannerColors
import com.github.izerui.imux.terminal.StatusDotIcon
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

object BridgeNotifications {
    fun info(
        project: Project?,
        text: String,
    ) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("imux.phoneBridge")
            .createNotification(text, NotificationType.INFORMATION)
            .notify(project)
    }
}

/** 界面层的连接、断开：确认对话框与 IDE 提示在这里，绑定本身交给 [BindingService]。 */
internal object PhoneOperations {
    /** 把机器人连到这个终端；机器人正连着别的会话时先确认替换。 */
    fun bind(
        project: Project,
        file: AgentTerminalVirtualFile,
        robotId: RobotId,
    ) {
        val robot = RobotManager.getInstance().robot(robotId) ?: return
        val title = SessionTitles.of(project, file)
        val current = BridgeHub.getInstance().bindings.bindingOf(robotId)
        if (current != null && current.target.tabId != file.tabId && !confirmReplace(
                project,
                robot.name,
                SessionTitles.of(current),
                title
            )
        ) return
        val target = SessionTarget(
            project.basePath ?: System.getProperty("user.home"),
            file.tabId,
            file.agentType,
            file.sessionId
        )
        BindingService.bind(project, robotId, target, title) ?: return
        // 机器人手机连接异常时如实说明：会话已连上，但要等连接恢复才能收发
        val status = RobotManager.getInstance().statusOf(robotId)
        val notice =
            if (status == ConnectionStatus.Online) {
                ImuxBundle.message("bridge.notify.connected", title, robot.name)
            } else {
                ImuxBundle.message(
                    "bridge.notify.connected.robotProblem",
                    title,
                    robot.name,
                    statusLabel(status, paired = true)
                )
            }
        BridgeNotifications.info(project, notice)
    }

    /** 断开这个机器人的会话连接。 */
    fun disconnect(
        project: Project,
        robotId: RobotId,
    ) {
        val binding = BindingService.unbind(robotId) ?: return
        BridgeNotifications.info(project, ImuxBundle.message("bridge.notify.disconnected", SessionTitles.of(binding)))
    }

    private fun confirmReplace(
        project: Project,
        robotName: String,
        from: String,
        to: String,
    ): Boolean =
        Messages.showOkCancelDialog(
            project,
            ImuxBundle.message("bridge.action.replace.message", robotName, from, to),
            ImuxBundle.message("bridge.action.replace.title"),
            ImuxBundle.message("bridge.action.replace.ok"),
            Messages.getCancelButton(),
            Messages.getQuestionIcon(),
        ) == Messages.OK
}

/** 菜单里的一项。[enabled] 为 false 时只作说明，点不了。 */
internal fun phoneAction(
    text: String,
    enabled: Boolean = true,
    icon: javax.swing.Icon? = null,
    run: (AnActionEvent) -> Unit = {},
): AnAction =
    object : DumbAwareAction(text, null, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = enabled
            // 不可选的行平台默认把图标转灰，智能体图标和状态色会糊成一团；显式给禁用图标就原样画
            icon?.let { e.presentation.disabledIcon = it }
        }

        override fun actionPerformed(e: AnActionEvent) = run(e)
    }

/**
 * 菜单里的一个机器人：最左边一列表示状态，后面是名字 + 状态词 + 会话标题；当前连着的不可选。
 * 会话标题不放右侧副文本：平台把副文本画成禁用灰，不可选的行更淡，看不清。
 * 连着会话的行，最左边画智能体图标并在右下角叠状态小圆点；没连会话的行只画圆点。
 */
internal fun robotAction(
    entry: PhoneMenuEntry.Robot,
    run: (AnActionEvent) -> Unit,
): AnAction =
    phoneAction(
        listOfNotNull("${entry.label}  ${entry.status}", entry.session).joinToString("  —  "),
        entry.enabled,
        menuIcon(entry),
        run,
    )

private val connectedDot = StatusDotIcon(BannerColors.ONLINE, slotWidth = 16)
private val offlineDot = StatusDotIcon(BannerColors.OFFLINE, slotWidth = 16)
private val problemDot = StatusDotIcon(BannerColors.PROBLEM, slotWidth = 16)
private val idleDot = StatusDotIcon(BannerColors.OFFLINE, filled = false, slotWidth = 16)

private fun stateColor(state: RobotMenuState): JBColor? =
    when (state) {
        RobotMenuState.CONNECTED -> BannerColors.ONLINE
        RobotMenuState.OFFLINE -> BannerColors.OFFLINE
        RobotMenuState.PROBLEM -> BannerColors.PROBLEM
        RobotMenuState.IDLE, RobotMenuState.UNPAIRED -> null
    }

private fun menuIcon(entry: PhoneMenuEntry.Robot): Icon {
    val badge = stateColor(entry.state)
    if (entry.agent != null && badge != null) return BadgedIcon(AgentIcons.forAgent(entry.agent), badge)
    return when (entry.state) {
        RobotMenuState.CONNECTED -> connectedDot
        RobotMenuState.OFFLINE -> offlineDot
        RobotMenuState.PROBLEM -> problemDot
        RobotMenuState.IDLE, RobotMenuState.UNPAIRED -> idleDot
    }
}

/** 智能体图标右下角叠一个状态小圆点；圆点外描一圈底色，压在图标上也看得清。 */
private class BadgedIcon(
    private val base: Icon,
    private val badge: JBColor,
) : Icon {
    override fun paintIcon(
        c: Component?,
        g: Graphics,
        x: Int,
        y: Int,
    ) {
        base.paintIcon(c, g, x, y)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val size = JBUI.scale(7)
            val ring = JBUI.scale(1)
            val left = x + iconWidth - size
            val top = y + iconHeight - size
            g2.color = c?.background ?: UIUtil.getListBackground()
            g2.fillOval(left - ring, top - ring, size + 2 * ring, size + 2 * ring)
            g2.color = badge
            g2.fillOval(left, top, size, size)
        } finally {
            g2.dispose()
        }
    }

    override fun getIconWidth() = base.iconWidth

    override fun getIconHeight() = base.iconHeight
}

/** 打开通讯机器人设置页。 */
internal fun showRobotSettings(project: Project?) =
    ShowSettingsUtil.getInstance().showSettingsDialog(project, RobotsConfigurable::class.java)

/** 这个终端的连接菜单条目。 */
internal fun phoneMenuFor(tabId: String): List<PhoneMenuEntry> {
    val manager = RobotManager.getInstance()
    val hub = BridgeHub.getInstance()
    return phoneMenuModel(
        manager.robots(),
        hub.bindings::bindingOf,
        ::bindingSessionAvailable,
        manager::statusOf,
        tabId,
        SessionTitles::of,
        BundleMenuLabels,
        ::robotMenuName
    )
}
