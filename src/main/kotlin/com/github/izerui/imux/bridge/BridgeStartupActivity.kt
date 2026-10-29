package com.github.izerui.imux.bridge

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** 项目打开时交给 [BridgeLifecycle] 自动连接已添加的机器人。 */
class BridgeStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) = BridgeLifecycle.onProjectOpened(project)
}
