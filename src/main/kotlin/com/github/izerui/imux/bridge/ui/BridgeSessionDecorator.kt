package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.bridge.BridgeHub
import com.github.izerui.imux.bridge.BridgeSettings
import com.github.izerui.imux.terminal.AgentTerminalVirtualFile
import com.github.izerui.imux.terminal.SessionDecorator
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import javax.swing.JComponent

/** 手机连接的会话装饰。开关关闭时一律"未连接"，且不实例化中枢。 */
class BridgeSessionDecorator : SessionDecorator {
    override fun isPhoneConnected(
        project: Project,
        tabId: String,
    ): Boolean = BridgeSettings.getInstance().state.enabled && BridgeHub.getInstance().bindings.bindingForTab(tabId) != null

    override fun createBannerSegment(
        project: Project,
        file: AgentTerminalVirtualFile,
        parent: Disposable,
    ): JComponent = PhoneBanner(project, file, parent)
}
