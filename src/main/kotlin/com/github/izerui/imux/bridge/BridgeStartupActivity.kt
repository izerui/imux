package com.github.izerui.imux.bridge

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** 项目打开时交给 [BridgeLifecycle]：开关关着时什么也不做，现有功能不受任何影响。 */
class BridgeStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) = BridgeLifecycle.onProjectOpened(project)
}
