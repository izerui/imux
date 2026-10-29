package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.bridge.BindingService
import com.github.izerui.imux.bridge.BridgeHub
import com.github.izerui.imux.bridge.RobotManager
import com.github.izerui.imux.bridge.SessionTitles
import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.core.SessionTarget
import com.github.izerui.imux.terminal.AgentTerminalVirtualFile
import com.github.izerui.imux.terminal.TerminalHost
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.terminal.frontend.view.TerminalView

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

internal fun terminalFile(event: AnActionEvent): AgentTerminalVirtualFile? {
    val project = event.project ?: return null
    val view = event.getData(TerminalView.DATA_KEY) ?: return null
    return TerminalHost.getInstance(project).fileFor(view)
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
        if (current != null && current.target.tabId != file.tabId && !confirmReplace(project, robot.name, SessionTitles.of(current), title)) return
        val target = SessionTarget(project.basePath ?: System.getProperty("user.home"), file.tabId, file.agentType, file.sessionId)
        BindingService.bind(project, robotId, target, title) ?: return
        // 机器人不在线时如实说明：会话已连上，但要等机器人恢复在线才能收发
        val status = RobotManager.getInstance().statusOf(robotId)
        val notice =
            if (status == ConnectionStatus.Online) {
                ImuxBundle.message("bridge.notify.connected", title, robot.name)
            } else {
                ImuxBundle.message("bridge.notify.connected.offline", title, robot.name, statusLabel(status, paired = true))
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
    run: (AnActionEvent) -> Unit = {},
): AnAction =
    object : DumbAwareAction(text) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = enabled
        }

        override fun actionPerformed(e: AnActionEvent) = run(e)
    }

/** 打开通讯机器人设置页。 */
internal fun showRobotSettings(project: Project?) = ShowSettingsUtil.getInstance().showSettingsDialog(project, RobotsConfigurable::class.java)

/** 这个终端的连接菜单条目。只在远程控制开着时调用（会实例化中枢）。 */
internal fun phoneMenuFor(tabId: String): List<PhoneMenuEntry> {
    val manager = RobotManager.getInstance()
    val hub = BridgeHub.getInstance()
    return phoneMenuModel(manager.robots(), hub.bindings::bindingOf, manager::statusOf, tabId, SessionTitles::of, BundleMenuLabels, ::robotMenuName)
}
