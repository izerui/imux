package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.bridge.BridgeHub
import com.github.izerui.imux.bridge.BridgeLifecycle
import com.github.izerui.imux.bridge.TerminalBridgeService
import com.github.izerui.imux.bridge.core.BindResult
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.core.SessionTarget
import com.github.izerui.imux.bridge.robot.RobotManager
import com.github.izerui.imux.monitor.SessionMonitor
import com.github.izerui.imux.terminal.AgentTerminalVirtualFile
import com.github.izerui.imux.terminal.TerminalHost
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.terminal.frontend.view.TerminalView

// ConnectToPhoneGroup 与 BridgeNotificationsActivity 各自在同名文件中，
// plugin.xml 注册检查按文件名定位实现类。

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

internal fun sessionTitle(
    project: Project,
    file: AgentTerminalVirtualFile,
): String = file.sessionId?.let { SessionMonitor.getInstance(project).model.sessionOf(it)?.title } ?: file.tabTitle

internal object PhoneOperations {
    /** 把机器人绑到这个终端：需要时确认替换，通知旧会话断开、新会话已连接，并持久化。 */
    fun bind(
        project: Project,
        file: AgentTerminalVirtualFile,
        robotId: String,
    ) {
        val robot = RobotManager.getInstance().robot(robotId) ?: return
        val hub = BridgeHub.getInstance()
        val title = sessionTitle(project, file)
        val current = hub.bindings.bindingOf(RobotId(robotId))
        if (current != null && current.target.tabId != file.tabId) {
            val message = ImuxBundle.message("bridge.action.replace.message", robot.name, BridgeLifecycle.sessionTitle(current), title)
            val choice =
                Messages.showOkCancelDialog(project, message, ImuxBundle.message("bridge.action.replace.title"), ImuxBundle.message("bridge.action.replace.ok"), Messages.getCancelButton(), Messages.getQuestionIcon())
            if (choice != Messages.OK) return
        }
        // 这个标签若已被另一个机器人占用，先让它断开
        val other = hub.bindings.bindingForTab(file.tabId)?.takeIf { it.robotId.value != robotId }
        other?.let { hub.bindings.unbind(it.robotId) }
        val target = SessionTarget(project.basePath ?: System.getProperty("user.home"), file.tabId, file.agentType, file.sessionId)
        val service = TerminalBridgeService.getInstance(project)
        // 先记读取边界再绑定：绑定一生效轮询就可能建立尾随器，从这里开始才不漏第一条回复
        service.markBoundary(target)
        val result = hub.bindings.bind(RobotId(robotId), target) as? BindResult.Bound ?: return service.forgetBoundary(target.tabId)
        service.start()
        RobotManager.getInstance().syncBindings()
        val replaced = result.replaced?.takeIf { it.target.tabId != file.tabId }
        hub.launch {
            other?.let { hub.router.onReleased(it, BridgeLifecycle.sessionTitle(it)) }
            replaced?.let { hub.router.onReleased(it, BridgeLifecycle.sessionTitle(it)) }
            hub.router.onBound(result.binding, project.name, file.agentType.displayName, title)
        }
        BridgeNotifications.info(project, ImuxBundle.message("bridge.notify.connected", title, robot.name))
    }

    /** 断开这个机器人的会话连接，通知手机并持久化。 */
    fun disconnect(
        project: Project,
        robotId: String,
    ) {
        val hub = BridgeHub.getInstance()
        val binding = hub.bindings.unbind(RobotId(robotId)) ?: return
        RobotManager.getInstance().syncBindings()
        val title = BridgeLifecycle.sessionTitle(binding)
        hub.launch { hub.router.onReleased(binding, title) }
        BridgeNotifications.info(project, ImuxBundle.message("bridge.notify.disconnected", title))
    }
}

internal class BindRobotAction(
    private val robotId: String,
    label: String,
    private val selectable: Boolean,
) : DumbAwareAction(label) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = selectable
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = terminalFile(e) ?: return
        PhoneOperations.bind(project, file, robotId)
    }
}

internal class DisconnectPhoneAction(private val robotId: String) : DumbAwareAction(ImuxBundle.message("bridge.action.disconnect")) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        PhoneOperations.disconnect(project, robotId)
    }
}

internal class AddRobotAction : DumbAwareAction(ImuxBundle.message("bridge.action.add")) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        AddRobotDialog(e.project).show()
    }
}

internal class OpenSettingsAction : DumbAwareAction(ImuxBundle.message("bridge.action.disabled")) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, RobotsConfigurable::class.java)
    }
}

internal class DisabledLabelAction(label: String) : DumbAwareAction(label) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = false
    }

    override fun actionPerformed(e: AnActionEvent) = Unit
}
