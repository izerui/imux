package com.github.izerui.imux.bridge

import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.robot.RobotManager
import com.github.izerui.imux.monitor.SessionMonitor
import com.intellij.openapi.project.ProjectManager

/** 开关切换与会话标题查询。两者都要跨项目查找，集中在这里。 */
object BridgeLifecycle {
    fun applyEnabled(enabled: Boolean) {
        val manager = RobotManager.getInstance()
        if (!enabled) {
            manager.stopAll()
            return
        }
        manager.startAll()
        ProjectManager.getInstance().openProjects
            .filterNot { it.isDisposed }
            .forEach { TerminalBridgeService.getInstance(it).start() }
    }

    fun sessionTitle(binding: Binding): String {
        val sessionId = binding.target.sessionId ?: return binding.target.tabId
        val project = ProjectManager.getInstance().openProjects.firstOrNull { it.basePath == binding.target.projectPath && !it.isDisposed }
        return project?.let { SessionMonitor.getInstance(it).model.sessionOf(sessionId)?.title } ?: sessionId.take(8)
    }
}
