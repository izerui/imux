package com.github.izerui.imux.code

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.terminal.TerminalLaunchContribution
import com.github.izerui.imux.terminal.TerminalLaunchContributor
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * 给会话终端挂上 `imux_code`：三种 CLI 都走插件内置 MCP 的同一条注入通道，标签关闭时撤销凭证。
 *
 * 有自己的开关（「查用法、查调用链工具」），不跟「注入 IDEA MCP」走：它由 imux 自己直接调
 * IDE 引擎，不经过 IDEA MCP。挂上后，使用引导随之自动注入，没有单独开关。
 */
class CodeLaunchContributor : TerminalLaunchContributor {
    override suspend fun prepare(project: Project, tabId: String, agentType: AgentType): TerminalLaunchContribution {
        val lease = runInterruptible(Dispatchers.IO) { CodeToolsLease.acquire(project, agentType) }
            ?: return TerminalLaunchContribution()
        return TerminalLaunchContribution(
            listOf(lease.server),
            lease.environment,
            listOfNotNull(lease.piExtension),
        ) { lease.release() }
    }
}
