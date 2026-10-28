package com.github.izerui.imux.bridge

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.robot.RobotManager
import com.github.izerui.imux.monitor.SessionMonitor
import com.github.izerui.imux.terminal.TerminalHost
import com.intellij.openapi.project.ProjectManager

/** 开关切换与会话标题查询。两者都要跨项目查找，集中在这里。 */
object BridgeLifecycle {
    /**
     * 远程控制的开启代次，每次开启加一。轮询与入站据此识别“关闭过又开启”：
     * 只看开关当前值会漏掉两次检查之间的快速关闭再开启。
     */
    @Volatile
    var switchGeneration: Long = 0
        private set

    fun applyEnabled(enabled: Boolean) {
        val manager = RobotManager.getInstance()
        if (!enabled) {
            manager.stopAll()
            // 关闭前攒下、尚未发出的回复不再补发
            BridgeHub.getInstance().router.discardPending()
            return
        }
        synchronized(this) { switchGeneration++ }
        manager.startAll()
        ProjectManager.getInstance().openProjects
            .filterNot { it.isDisposed }
            .forEach { TerminalBridgeService.getInstance(it).start() }
    }

    /**
     * 绑定会话在菜单、设置页和手机提示里的名字，各处共用：会话标题 → 标签标题 → 会话 id 前 8 位 →“未命名会话”。
     * 刚打开、还没有会话记录的 CLI 只有标签标题，不能把内部的 tabId 露给用户。
     */
    fun sessionTitle(binding: Binding): String {
        val sessionId = binding.target.sessionId
        val project = ProjectManager.getInstance().openProjects.firstOrNull { it.basePath == binding.target.projectPath && !it.isDisposed }
        if (project != null) {
            sessionId?.let { SessionMonitor.getInstance(project).model.sessionOf(it)?.title }?.let { return it }
            TerminalHost.getInstance(project).tabTitleOf(binding.target.tabId)?.let { return it }
        }
        return sessionId?.take(8) ?: ImuxBundle.message("bridge.session.untitled")
    }
}
