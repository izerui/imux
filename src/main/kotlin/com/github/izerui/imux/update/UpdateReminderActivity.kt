package com.github.izerui.imux.update

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** 启动新版本检查；服务是应用级的，多个项目打开也只调度一次。 */
class UpdateReminderActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        UpdateReminderService.getInstance().start()
    }
}
