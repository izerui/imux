package com.github.izerui.imux.bridge

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** 开关打开时启动本项目的桥接观察。关闭时什么也不做，现有功能不受任何影响。 */
class BridgeStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!BridgeSettings.getInstance().state.enabled) return
        if (!project.isDisposed) TerminalBridgeService.getInstance(project).start()
    }
}
