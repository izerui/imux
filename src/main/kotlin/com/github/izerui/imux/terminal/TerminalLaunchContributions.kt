package com.github.izerui.imux.terminal

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.settings.GuidanceCapability
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

data class TerminalMcpServer(val name: String, val url: String, val tokenEnvironment: String, val timeoutSeconds: Int = 180)

/** 终端只认识附加启动配置，不依赖提供能力的具体功能包。 */
class TerminalLaunchContribution(
    val servers: List<TerminalMcpServer> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val piExtensions: List<Path> = emptyList(),
    val claudeSettings: String? = null,
    /** 这些工具需要引导词带上的部分；终端只负责汇总交给引导词，不关心是哪组工具。 */
    val guidance: Set<GuidanceCapability> = emptySet(),
    private val release: () -> Unit = {},
) : AutoCloseable {
    /**
     * 加 [guidance] 之前的构造方法，只留在字节码里：按旧版编译的终端扩展调用的就是它（含默认参数那个），
     * 删掉会在升级后报 NoSuchMethodError。对 Kotlin 源码隐藏，免得和主构造方法产生调用歧义。
     */
    @Deprecated("Use the constructor with guidance", level = DeprecationLevel.HIDDEN)
    constructor(
        servers: List<TerminalMcpServer> = emptyList(),
        environment: Map<String, String> = emptyMap(),
        piExtensions: List<Path> = emptyList(),
        claudeSettings: String? = null,
        release: () -> Unit = {},
    ) : this(servers, environment, piExtensions, claudeSettings, emptySet(), release)

    private val closed = AtomicBoolean()
    override fun close() { if (closed.compareAndSet(false, true)) release() }
    override fun toString(): String = "TerminalLaunchContribution(servers=${servers.map { it.name }})"
}

interface TerminalLaunchContributor {
    suspend fun prepare(project: Project, tabId: String, agentType: AgentType): TerminalLaunchContribution
}

object TerminalLaunchContributions {
    private val LOG = logger<TerminalLaunchContributions>()

    /** 各功能的启动配置合成一份：服务、环境变量、Pi 扩展、引导能力取并集，关闭时逐个释放。 */
    internal fun merge(parts: List<TerminalLaunchContribution>): TerminalLaunchContribution =
        TerminalLaunchContribution(
            parts.flatMap { it.servers },
            parts.flatMap { it.environment.entries }.associate { it.toPair() },
            parts.flatMap { it.piExtensions }.distinct(),
            parts.firstNotNullOfOrNull { it.claudeSettings },
            parts.flatMapTo(LinkedHashSet()) { it.guidance },
        ) { closeAll(parts) }

    /**
     * 逐个释放：某个功能释放出错也要接着释放后面的（否则后面发出的凭证不会撤销）。
     * 出错只记日志、不往外抛：释放发生在标签关闭、终端结束、项目关闭这些收尾环节，
     * 调用方都不接异常，抛出去只会让收尾的后续步骤（关终端、清状态）被跳过。
     */
    private fun closeAll(parts: List<TerminalLaunchContribution>) {
        for (part in parts) {
            try {
                part.close()
            } catch (e: Exception) {
                LOG.warn("终端附加工具释放出错：$part", e)
            }
        }
    }

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
            return merge(parts)
        } catch (e: Exception) {
            closeAll(parts)
            throw e
        }
    }
}
