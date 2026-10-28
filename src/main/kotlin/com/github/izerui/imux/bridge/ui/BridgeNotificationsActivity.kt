package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.bridge.RobotManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** 应用启动后设置"被接管"提示；放在启动活动里，RobotManager 不依赖界面层。 */
class BridgeNotificationsActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        RobotManager.getInstance().notifyKicked = { robot ->
            BridgeNotifications.info(null, ImuxBundle.message("bridge.notify.kicked", robot.name))
        }
    }
}
