package com.github.izerui.imux.bridge

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager

/** 远程控制开关：唯一的写入口与项目打开时的启动。 */
object BridgeLifecycle {
    /**
     * 远程控制的开启代次，每次开启加一。轮询与入站据此识别“关闭过又开启”：
     * 只看开关当前值会漏掉两次检查之间的快速关闭再开启。
     */
    @Volatile
    var switchGeneration: Long = 0
        private set

    /** 远程控制开关的唯一写入口：写入设置并立即生效。值没变时什么也不做，返回 false。 */
    fun setEnabled(enabled: Boolean): Boolean {
        val state = BridgeSettings.getInstance().state
        if (state.enabled == enabled) return false
        state.enabled = enabled
        if (enabled) start() else stop()
        return true
    }

    /** 项目打开时：开关开着才连接机器人、观察本项目的终端；关着时什么也不做，现有功能不受影响。 */
    fun onProjectOpened(project: Project) {
        if (!BridgeSettings.getInstance().state.enabled) return
        RobotManager.getInstance().startAll()
        if (!project.isDisposed) TerminalBridgeService.getInstance(project).start()
    }

    private fun start() {
        synchronized(this) { switchGeneration++ }
        RobotManager.getInstance().startAll()
        ProjectManager.getInstance().openProjects
            .filterNot { it.isDisposed }
            .forEach { TerminalBridgeService.getInstance(it).start() }
    }

    private fun stop() {
        RobotManager.getInstance().stopAll()
        // 关闭前攒下、尚未发出的回复不再补发
        BridgeHub.getInstance().router.discardPending()
    }
}
