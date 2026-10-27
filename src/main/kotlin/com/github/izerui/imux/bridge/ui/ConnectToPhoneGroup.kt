package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.bridge.BridgeHub
import com.github.izerui.imux.bridge.BridgeLifecycle
import com.github.izerui.imux.bridge.BridgeSettings
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.robot.RobotManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAware

/** 终端右键「连接到手机」。机器人与会话分开管理，这里只改绑定关系。 */
class ConnectToPhoneGroup : ActionGroup(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        event.presentation.text = ImuxBundle.message("bridge.action.group")
        event.presentation.isEnabledAndVisible = terminalFile(event) != null
    }

    override fun getChildren(event: AnActionEvent?): Array<AnAction> {
        val e = event ?: return emptyArray()
        val file = terminalFile(e) ?: return emptyArray()
        if (!BridgeSettings.getInstance().state.enabled) return arrayOf(OpenSettingsAction())
        val hub = BridgeHub.getInstance()
        return phoneMenuModel(
            RobotManager.getInstance().robots(),
            bindingOf = { hub.bindings.bindingOf(RobotId(it)) },
            thisTabId = file.tabId,
            titleOf = BridgeLifecycle::sessionTitle,
            labels = BundleMenuLabels,
        ).flatMap { entry ->
            when (entry) {
                is PhoneMenuEntry.Robot -> listOf(BindRobotAction(entry.robotId, entry.label, entry.enabled))
                PhoneMenuEntry.NoRobots -> listOf(DisabledLabelAction(ImuxBundle.message("bridge.action.none")))
                PhoneMenuEntry.AddRobot -> listOf(Separator.getInstance(), AddRobotAction())
                is PhoneMenuEntry.Disconnect -> listOf(DisconnectPhoneAction(entry.robotId))
            }
        }.toTypedArray()
    }
}
