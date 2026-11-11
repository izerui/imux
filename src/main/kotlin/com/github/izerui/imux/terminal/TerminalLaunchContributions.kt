package com.github.izerui.imux.terminal

import com.github.izerui.imux.model.AgentType
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

data class TerminalMcpServer(val name: String, val url: String, val tokenEnvironment: String, val timeoutSeconds: Int = 180)

/** 终端只认识附加启动配置，不依赖提供能力的具体功能包。 */
class TerminalLaunchContribution(
    val servers: List<TerminalMcpServer> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val piExtensions: List<Path> = emptyList(),
    val claudeSettings: String? = null,
    private val release: () -> Unit = {},
) : AutoCloseable {
    private val closed = AtomicBoolean()
    override fun close() { if (closed.compareAndSet(false, true)) release() }
    override fun toString(): String = "TerminalLaunchContribution(servers=${servers.map { it.name }})"
}

interface TerminalLaunchContributor {
    suspend fun prepare(project: Project, tabId: String, agentType: AgentType): TerminalLaunchContribution
}

object TerminalLaunchContributions {
    private val ep = ExtensionPointName<TerminalLaunchContributor>("com.github.izerui.imux.terminalLaunchContributor")
    fun available(): Boolean = ep.extensionList.isNotEmpty()

    suspend fun prepare(project: Project, tabId: String, agentType: AgentType): TerminalLaunchContribution {
        val parts = mutableListOf<TerminalLaunchContribution>()
        try {
            ep.extensionList.forEach { provider ->
                val part = withTimeoutOrNull(5_000) { provider.prepare(project, tabId, agentType) }
                    ?: throw IllegalStateException("terminal launch integration timed out")
                parts += part
            }
            return TerminalLaunchContribution(parts.flatMap { it.servers }, parts.flatMap { it.environment.entries }.associate { it.toPair() },
                parts.flatMap { it.piExtensions }.distinct(), parts.firstNotNullOfOrNull { it.claudeSettings }) { parts.forEach { it.close() } }
        } catch (e: Exception) {
            parts.forEach { it.close() }
            if (e is CancellationException) throw e
            throw e
        }
    }
}
