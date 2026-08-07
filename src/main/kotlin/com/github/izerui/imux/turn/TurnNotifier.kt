package com.github.izerui.imux.turn

import com.github.izerui.imux.model.AgentType
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.SystemNotifications
import java.time.Duration
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

    fun notifyCompleted(
        project: Project,
        sessionId: String,
        title: String,
        agentType: AgentType?,
        duration: Duration?,
        onOpen: () -> Unit,
    ) {
        val subtitle = completionSubtitle(agentType, duration)

        // 上行放「Agent · 耗时」，下行放会话标题。
        //
        // 反过来看似更直觉，实际不好用：会话标题长短悬殊（上限 60 字符），
        // 长的会把加粗的标题行撑满甚至截断，而元信息长度稳定、正好一行。
        // 标题挪到下面反倒能完整读到，元信息也就顺势替它扛住了截断。
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(subtitle, title, NotificationType.INFORMATION)

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

        notifyOutsideIde(project, title, subtitle)
    }

    /**
     * 该项目的窗口不在前台时补一条系统通知。
     *
     * 气泡只在自己那个项目窗口里可见，而等一轮跑完的这段时间人多半已经切去别处了，
     * 那正是最需要被叫回来的时刻。
     *
     * 必须按**窗口**判断而不是 `Application.isActive`：后者是应用级的，同时开着
     * 多个项目窗口时，只要有一个在前台它就为真。那样后台项目的提醒会被判成
     * 「你正看着呢」而不发——可那个窗口正压在别的窗口下面，气泡根本看不见。
     * 拿不到窗口时按「不在前台」处理：宁可多发一条，也不要漏掉。
     *
     * 平台不会自动把普通通知转成系统通知——测试跑完、构建完成这些场景都是各自
     * 显式调用的（见 TestsUIUtil、ProgressManagerImpl），本插件同理。
     *
     * 系统通知点不出「打开会话」：[SystemNotifications.notify] 只收三个字符串，
     * 没有回调。所以它只负责把人叫回来，真正的入口仍是留在 IDE 里的那个气泡。
     *
     * 通知后端不可用时（例如未授权、非 macOS 且无 libnotify），平台实现内部静默跳过。
     */
    private fun notifyOutsideIde(project: Project, title: String, subtitle: String) {
        val frame = WindowManager.getInstance().getFrame(project)
        if (frame?.isActive == true) return
        SystemNotifications.getInstance().notify(GROUP_ID, subtitle, title)
    }

    /**
     * 该会话正作为后台 agent 运行，无法恢复。
     *
     * 提前提示，避免用户在终端里撞上 CLI 的
     * 「currently running as a background agent」报错。
     */
    fun notifyBusy(project: Project, title: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(
                "会话正在后台运行",
                "「$title」正作为后台 agent 执行任务，等它空闲后再打开。",
                NotificationType.WARNING,
            )
            .notify(project)
    }

    /** 用户已经通过别的途径看到该会话，撤掉对应通知。 */
    fun dismiss(sessionId: String) {
        active.remove(sessionId)?.expire()
    }
}
