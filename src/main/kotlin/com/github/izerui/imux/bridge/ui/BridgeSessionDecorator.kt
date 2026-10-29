package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.bridge.BridgeHub
import com.github.izerui.imux.terminal.AgentTerminalVirtualFile
import com.github.izerui.imux.terminal.NoticeSink
import com.github.izerui.imux.terminal.SessionDecorator
import com.github.izerui.imux.terminal.SessionTool
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project

/** 手机连接的会话装饰。开关关闭时一律"未连接"，且不实例化中枢。 */
class BridgeSessionDecorator : SessionDecorator {
    override fun isPhoneConnected(
        project: Project,
        tabId: String,
    ): Boolean = BridgeHub.getInstance().bindings.bindingForTab(tabId) != null

    override fun createTool(
        project: Project,
        file: AgentTerminalVirtualFile,
        parent: Disposable,
        notices: NoticeSink,
    ): SessionTool = PhoneTool(project, file, parent, notices)
}
