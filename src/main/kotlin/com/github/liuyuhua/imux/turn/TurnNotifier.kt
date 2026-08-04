package com.github.liuyuhua.imux.turn

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/** 会话轮次完成时弹出的 IDE 通知，点击可直接跳到该会话。 */
object TurnNotifier {

    private const val GROUP_ID = "imux.turnCompleted"

    /**
     * 尚未消失的通知，按会话 id 索引。
     *
     * 用途是「用户从别处打开了该会话」时把对应通知一并撤掉——
     * 提醒已经完成使命，留着就是噪音。
     */
    private val active = ConcurrentHashMap<String, Notification>()

    fun notifyCompleted(project: Project, sessionId: String, title: String, onOpen: () -> Unit) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification("会话已完成", title, NotificationType.INFORMATION)

        // createSimpleExpiring 会在点击后自动让通知过期。
        // 普通的 AnAction 不会——点了之后气泡仍然挂着。
        notification.addAction(
            NotificationAction.createSimpleExpiring("打开会话") {
                active.remove(sessionId)
                onOpen()
            },
        )

        notification.whenExpired { active.remove(sessionId) }

        active[sessionId] = notification
        notification.notify(project)
    }

    /** 用户已经通过别的途径看到该会话，撤掉对应通知。 */
    fun dismiss(sessionId: String) {
        active.remove(sessionId)?.expire()
    }
}
