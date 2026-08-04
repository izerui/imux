package com.github.liuyuhua.imux.turn

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

/** 会话轮次完成时弹出的 IDE 通知，点击可直接跳到该会话。 */
object TurnNotifier {

    private const val GROUP_ID = "imux.turnCompleted"

    fun notifyCompleted(project: Project, title: String, onOpen: () -> Unit) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification("会话已完成", title, NotificationType.INFORMATION)
            .addAction(object : AnAction("打开会话") {
                override fun actionPerformed(event: AnActionEvent) = onOpen()
            })
            .notify(project)
    }
}
