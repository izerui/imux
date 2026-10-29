package com.github.izerui.imux.bridge

import com.intellij.openapi.project.Project

/** 项目打开时自动连接已添加的机器人，并观察绑定的终端。 */
object BridgeLifecycle {
    fun onProjectOpened(project: Project) {
        if (project.isDisposed) return
        RobotManager.getInstance().startAll()
        if (!project.isDisposed) TerminalBridgeService.getInstance(project).start()
    }
}
