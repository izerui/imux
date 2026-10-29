package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.ImuxBundle
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
        return phoneMenuFor(file.tabId).flatMap { entry ->
            when (entry) {
                is PhoneMenuEntry.Robot ->
                    listOf(robotAction(entry) { e -> e.project?.let { PhoneOperations.bind(it, file, entry.robotId) } })

                PhoneMenuEntry.NoRobots -> listOf(
                    phoneAction(
                        ImuxBundle.message("bridge.action.none"),
                        enabled = false
                    )
                )

                PhoneMenuEntry.AddRobot -> listOf(
                    Separator.getInstance(),
                    phoneAction(ImuxBundle.message("bridge.action.add")) { AddRobotDialog(it.project).show() })

                is PhoneMenuEntry.Disconnect ->
                    listOf(phoneAction(ImuxBundle.message("bridge.action.disconnect")) { e ->
                        e.project?.let {
                            PhoneOperations.disconnect(
                                it,
                                entry.robotId
                            )
                        }
                    })
            }
        }.toTypedArray()
    }
}
