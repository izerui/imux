package com.github.izerui.imux.bridge.terminal

import com.github.izerui.imux.bridge.core.SessionTarget
import com.github.izerui.imux.bridge.core.SubmitResult
import com.github.izerui.imux.bridge.core.TerminalSessionPort
import com.github.izerui.imux.terminal.TerminalHost
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.terminal.frontend.view.TerminalViewSessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.plugins.terminal.view.TerminalSendTextBuilder

/**
 * 桥接触达终端的唯一入口，只调用 TerminalHost 已公开的方法。
 *
 * 每次提交都重新按 tabId 查找终端，不缓存 view：标签可能已关闭、拖动重建或被迁移。
 * 不检查 Agent 是否在忙；排队还是插入由 CLI 决定。找不到终端就是离线，绝不启动 CLI。
 *
 * 每次调用只在 EDT 上尝试一次。未就绪（括号粘贴尚未开启）直接返回 NOT_READY，
 * 有限重试由 BridgeRouter 负责：它在每次重试前复核绑定，换绑后不会再提交到旧终端。
 */
class ImuxTerminalSessionPort(
    private val projectAt: (String) -> Project? = ::openProjectAt,
) : TerminalSessionPort {
    override suspend fun submit(
        target: SessionTarget,
        text: String,
    ): SubmitResult = withContext(Dispatchers.EDT) { submitOnce(target, text) }

    private fun submitOnce(
        target: SessionTarget,
        text: String,
    ): SubmitResult {
        val project = projectAt(target.projectPath)?.takeUnless { it.isDisposed } ?: return SubmitResult.OFFLINE
        val host = TerminalHost.getInstance(project)
        val sessionKey = host.openTabsByTabId()[target.tabId] ?: return SubmitResult.OFFLINE
        val view = host.terminalViewOf(sessionKey) ?: return SubmitResult.OFFLINE
        if (view.sessionState.value is TerminalViewSessionState.Terminated) return SubmitResult.OFFLINE
        return if (submitText(view.createSendTextBuilder(), text)) SubmitResult.SUBMITTED_TO_TERMINAL else SubmitResult.NOT_READY
    }
}

/**
 * 与副驾驶已抓包验证的形态一致：require 在括号粘贴未开启时一个字节都不写并返回 false；
 * 正文与回车同一次发送，回车落在括号外才会被 CLI 当作提交。
 */
internal fun submitText(
    builder: TerminalSendTextBuilder,
    text: String,
): Boolean = builder.requireBracketedPasteMode().shouldExecute().trySend(text)

private fun openProjectAt(projectPath: String): Project? =
    ProjectManager.getInstance().openProjects.firstOrNull { it.basePath == projectPath }
