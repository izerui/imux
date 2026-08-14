package com.github.izerui.imux.turn

import com.github.izerui.imux.model.AgentType
import com.intellij.ide.impl.ProjectUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.SystemNotifications
import com.intellij.ui.mac.foundation.Foundation
import com.intellij.ui.mac.foundation.ID
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

    /**
     * [openSession] 必须是**无捕获**的函数引用（顶层函数），不能传捕获了 Project
     * 或服务实例的 lambda——它会被平台的应用级静态单例长期持有，详见 [notifyOutsideIde]。
     */
    fun notifyCompleted(
        project: Project,
        sessionId: String,
        title: String,
        agentType: AgentType?,
        duration: Duration?,
        openSession: (Project, String) -> Unit,
    ) = notify(project, sessionId, title, completionSubtitle(agentType, duration), openSession)

    /**
     * 该会话弹出了权限确认或选择框，停下等用户操作。
     *
     * 与 [notifyCompleted] 共用同一套气泡与去重表——对用户来说下一步动作都是
     * 「打开看看」，区别只在副标题说的是「等待权限确认」还是「已完成 · 耗时 2 分」。
     * 不报耗时：这一轮并未结束，任何数字都是错的。
     *
     * 调用方需先用 [waitingNotificationWanted] 判断该不该弹。
     */
    fun notifyWaiting(
        project: Project,
        sessionId: String,
        title: String,
        agentType: AgentType?,
        waitingFor: String?,
        openSession: (Project, String) -> Unit,
    ) = notify(project, sessionId, title, waitingSubtitle(agentType, waitingFor), openSession)

    private fun notify(
        project: Project,
        sessionId: String,
        title: String,
        subtitle: String,
        openSession: (Project, String) -> Unit,
    ) {
        // 上行放「Agent · 耗时」，下行放会话标题。
        //
        // 反过来看似更直觉，实际不好用：会话标题长短悬殊（上限 60 字符），
        // 长的会把加粗的标题行撑满甚至截断，而元信息长度稳定、正好一行。
        // 标题挪到下面反倒能完整读到，元信息也就顺势替它扛住了截断。
        val notification =
            NotificationGroupManager
                .getInstance()
                .getNotificationGroup(GROUP_ID)
                .createNotification(subtitle, title, NotificationType.INFORMATION)

        // createSimpleExpiring 会在点击后自动让通知过期。
        // 普通的 AnAction 不会——点了之后气泡仍然挂着。
        // 气泡这边捕获 project 无妨：Notification 的生命周期本就跟着项目走
        notification.addAction(
            NotificationAction.createSimpleExpiring("Open Session") {
                active.remove(sessionId)
                openSession(project, sessionId)
            },
        )

        notification.whenExpired { active.remove(sessionId) }

        active[sessionId] = notification
        notification.notify(project)

        notifyOutsideIde(project, sessionId, title, subtitle, openSession)
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
     * 走四参重载把点击回调带上。三参那版等于把回调丢了，点击只能激活 IDEA 应用，
     * 落到哪个窗口由系统决定——多开项目窗口时人被叫回来却停在别的项目上。
     * macOS 侧由 `MacOsNotifications` 生成 activationId 存起 Runnable，
     * 点击时经 NSUserNotificationCenter 的 delegate 取回执行。
     *
     * 回调里必须自己把窗口提到前台：[openSession] 内部走的 `FileEditorManager.openFile`
     * 与 `IdeFocusManager` 都只在项目**内部**调焦点，谁也提不动那个 JFrame。
     *
     * 回调由 JNA 的原生线程发起，碰 UI 前先回 EDT，`focusProjectWindow` 也要求 EDT。
     *
     * **回调里一个对象都不能捕获。** 平台把它存在应用级静态单例
     * `MacOsNotifications.myCallbacksByActivationId` 里，上限 32 条、满了才整体清空——
     * 在此之前捕获的东西一直回收不掉。捕获 [Project] 就等于把它连同整条服务链
     * 钉在那里，用户关掉项目也释放不了。所以这里只带走 locationHash 与会话 id 两个
     * 字符串，Project 等到点击那一刻现查；查不到（项目已关闭）就安静地什么都不做。
     *
     * 发之前先撤掉此前投递的那些，原因见 [dismissDeliveredSystemNotifications]。
     *
     * 通知后端不可用时（例如未授权、非 macOS 且无 libnotify），平台实现内部静默跳过；
     * 非 macOS 后端只认三参，`Notifier` 接口的默认实现会忽略回调退回去，不会因此报错。
     */
    private fun notifyOutsideIde(
        project: Project,
        sessionId: String,
        title: String,
        subtitle: String,
        openSession: (Project, String) -> Unit,
    ) {
        val frame = WindowManager.getInstance().getFrame(project)
        if (frame?.isActive == true) return

        val locationHash = project.locationHash

        dismissDeliveredSystemNotifications()

        SystemNotifications.getInstance().notify(GROUP_ID, subtitle, title) {
            ApplicationManager.getApplication().invokeLater {
                val target =
                    ProjectManager
                        .getInstance()
                        .openProjects
                        .firstOrNull { it.locationHash == locationHash }
                        ?: return@invokeLater
                ProjectUtil.focusProjectWindow(target, true)
                // 撤气泡的活交给 openSession 内部的 clearUnread，不在这里重复
                openSession(target, sessionId)
            }
        }
    }

    /**
     * 撤掉此前已投递的系统通知，保证通知中心里同时只挂着最新那一条。
     *
     * **不这样做，第二条起点下去必然毫无反应。** 平台把点击回调存在应用级的
     * `MacOsNotifications.myCallbacksByActivationId` 里，而任意一条通知被点击时执行的
     * `cleanupDeliveredNotifications()` 清的是**整张表**（`map.clear()`），不是它自己那条。
     * 于是同时堆着几条通知时，点掉第一条之后其余全成死通知：`map.remove` 取不到回调就
     * 静默 return，既不跳转、也不把通知撤掉——撤通知的动作就在 `callback != null`
     * 分支里面。表面症状是「点了没反应，通知还赖着不走，但有时候又是好的」。
     *
     * 多开工程时必然踩到：每个项目各自发提醒，人离开一会儿回来，通知中心里就是好几条。
     * 单开一个工程通常只有一条，点它永远算「第一条」，所以一直看着正常。
     * `map.size() >= 32` 那条整表清空是同一个坑的另一半。
     *
     * 平台没有公开撤系统通知的入口（`MacOsNotifications.removeDeliveredNotifications`
     * 是私有静态方法），所以这里按平台自己的写法用 [Foundation] 调同一个 selector。
     * 代价是离开期间完成多个会话只保留最新一条可点，其余靠 IDE 内的未读标记找回——
     * 而在此之前，那些「多出来的通知」本来就是点不动的死通知。
     *
     * 非 macOS 没有这套机制，直接跳过；[SystemNotifications] 那边同样会自行退化。
     */
    private fun dismissDeliveredSystemNotifications() {
        if (!SystemInfo.isMac) return

        val center = Foundation.invoke("NSUserNotificationCenter", "defaultUserNotificationCenter")
        if (center == ID.NIL) return
        Foundation.invoke(center, "removeAllDeliveredNotifications")
    }

    /**
     * 该会话正作为后台 agent 运行，无法恢复。
     *
     * 提前提示，避免用户在终端里撞上 CLI 的
     * 「currently running as a background agent」报错。
     */
    fun notifyBusy(
        project: Project,
        title: String,
    ) {
        NotificationGroupManager
            .getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(
                "Session Is Running in the Background",
                "\"$title\" is running as a background agent. Open it after it becomes idle.",
                NotificationType.WARNING,
            ).notify(project)
    }

    /** 用户已经通过别的途径看到该会话，撤掉对应通知。 */
    fun dismiss(sessionId: String) {
        active.remove(sessionId)?.expire()
    }
}
