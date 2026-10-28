package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.bridge.BridgeHub
import com.github.izerui.imux.bridge.BridgeSettings
import com.github.izerui.imux.bridge.robot.RobotManager
import com.github.izerui.imux.terminal.SessionDecorationsListener
import com.github.izerui.imux.terminal.TerminalHost
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.event.MouseEvent

fun statusBarText(
    enabled: Boolean,
    robotCount: Int,
    boundHere: Int,
    connected: (Int) -> String,
    idle: String,
    problems: Int = 0,
    problem: String = "",
): String =
    when {
        !enabled || robotCount == 0 -> ""
        // 连着会话的机器人掉线、失败或被接管：先说异常，不能只报“已连接”
        problems > 0 -> problem
        boundHere > 0 -> connected(boundHere)
        else -> idle
    }

class PhoneStatusWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ID

    override fun getDisplayName(): String = ImuxBundle.message("bridge.statusbar.name")

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = PhoneStatusWidget(project)

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    companion object {
        const val ID = "imux.phoneBridge"
    }
}

private class PhoneStatusWidget(
    private val project: Project,
) : StatusBarWidget, StatusBarWidget.TextPresentation {
    private var statusBar: StatusBar? = null

    override fun ID(): String = PhoneStatusWidgetFactory.ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(SessionDecorationsListener.TOPIC, SessionDecorationsListener { statusBar.updateWidget(ID()) })
    }

    override fun getText(): String {
        val enabled = BridgeSettings.getInstance().state.enabled
        if (!enabled) return ""
        val openTabIds = TerminalHost.getInstance(project).openTabsByTabId().keys
        val manager = RobotManager.getInstance()
        val bound = BridgeHub.getInstance().bindings.snapshot().filter { it.target.tabId in openTabIds }
        // 与横幅、设置页同一判断
        val problems = bound.count { rowTone(manager.statusOf(it.robotId.value), paired = true, bridgeEnabled = true) == RowTone.ATTENTION }
        return statusBarText(
            enabled,
            manager.robots().size,
            bound.size,
            connected = { ImuxBundle.message("bridge.statusbar.connected", it) },
            idle = ImuxBundle.message("bridge.statusbar.idle"),
            problems = problems,
            problem = ImuxBundle.message("bridge.statusbar.problem"),
        )
    }

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getTooltipText(): String = ImuxBundle.message("bridge.statusbar.tooltip")

    override fun getClickConsumer(): Consumer<MouseEvent> =
        Consumer { ShowSettingsUtil.getInstance().showSettingsDialog(project, RobotsConfigurable::class.java) }

    override fun dispose() {
        statusBar = null
    }
}
