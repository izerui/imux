package com.github.izerui.imux.code

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.terminal.TerminalLaunchContribution
import com.github.izerui.imux.terminal.TerminalLaunchContributor
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

/**
 * 给会话终端挂上 `imux_code`：三种 CLI 都走插件内置 MCP 的同一条注入通道，标签关闭时撤销凭证。
 *
 * 有自己的开关（「查用法、查调用链工具」），不跟「注入 IDEA MCP」走：它由 imux 自己直接调
 * IDE 引擎，不经过 IDEA MCP。挂上后，使用引导随之自动注入，没有单独开关。
 */
class CodeLaunchContributor : TerminalLaunchContributor {
    override suspend fun prepare(project: Project, tabId: String, agentType: AgentType): TerminalLaunchContribution {
        val lease = acquireOnIo({ CodeToolsLease.acquire(project, agentType) }) { it.release() }
            ?: return TerminalLaunchContribution()
        return TerminalLaunchContribution(
            listOf(lease.server),
            lease.environment,
            listOfNotNull(lease.piExtension),
            lease.claudeSettings,
            guidance = lease.guidance,
        ) { lease.release() }
    }
}

/**
 * 在后台线程里取一份要释放的资源（等端口会阻塞）。取到之后、结果交回之前如果被取消（标签被关、启动超时），
 * 交回这一步会抛出取消、结果就丢了，没人释放；所以取到后先记下，取消时在这里释放。
 */
internal suspend fun <T : Any> acquireOnIo(acquire: () -> T?, release: (T) -> Unit): T? {
    val issued = AtomicReference<T?>()
    return try {
        runInterruptible(Dispatchers.IO) { acquire().also(issued::set) }
    } catch (e: CancellationException) {
        issued.getAndSet(null)?.let(release)
        throw e
    }
}
