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
    internal var unavailableTools: Set<String> = emptySet()
    internal var piRegistrationFailures: Set<String> = emptySet()
        private set
    internal var serverLabels: Map<String, String> = emptyMap()
    internal var ideaConfigured = false

    internal fun updatePiRegistrationFailures(failed: Set<String>): Boolean {
        val labels = failed.mapNotNullTo(linkedSetOf(), serverLabels::get)
        if (labels == piRegistrationFailures) return false
        piRegistrationFailures = labels
        return true
    }
    override fun close() { if (closed.compareAndSet(false, true)) release() }
    override fun toString(): String = "TerminalLaunchContribution(servers=${servers.map { it.name }})"
}

interface TerminalLaunchContributor {
    suspend fun prepare(project: Project, tabId: String, agentType: AgentType): TerminalLaunchContribution
}

interface NamedLaunchContributor : TerminalLaunchContributor {
    val toolLabelKey: String
}

object TerminalLaunchContributions {
    private val LOG = logger<TerminalLaunchContributions>()

    /** 各功能的启动配置合成一份：服务、环境变量、Pi 扩展、引导能力取并集，关闭时逐个释放。 */
    internal fun merge(parts: List<TerminalLaunchContribution>): TerminalLaunchContribution =
        TerminalLaunchContribution(
            parts.flatMap { it.servers },
            parts.flatMap { it.environment.entries }.associate { it.toPair() },
            parts.flatMap { it.piExtensions }.distinct(),
            mergeClaudeSettings(parts.mapNotNull { it.claudeSettings }),
            parts.flatMapTo(LinkedHashSet()) { it.guidance },
        ) { closeAll(parts) }

    /**
     * 各功能给 Claude 的 `--settings` 合成一份：`hooks` 按事件拼接规则，其它顶层项后来的覆盖先来的。
     * 某一份不是合法 JSON 时跳过它，不拖垮会话启动。
     */
    internal fun mergeClaudeSettings(parts: List<String>): String? {
        if (parts.isEmpty()) return null
        if (parts.size == 1) return parts.single()
        val merged = com.google.gson.JsonObject()
        val hooks = com.google.gson.JsonObject()
        for (part in parts) {
            val json = runCatching { com.google.gson.JsonParser.parseString(part).asJsonObject }.getOrNull() ?: continue
            for ((key, value) in json.entrySet()) {
                if (key != "hooks" || !value.isJsonObject) { merged.add(key, value); continue }
                for ((event, rules) in value.asJsonObject.entrySet()) {
                    val list = hooks.getAsJsonArray(event) ?: com.google.gson.JsonArray().also { hooks.add(event, it) }
                    if (rules.isJsonArray) list.addAll(rules.asJsonArray)
                }
            }
        }
        if (hooks.size() > 0) merged.add("hooks", hooks)
        return merged.toString()
    }

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
        return prepareEach(ep.extensionList, 5_000) { it.prepare(project, tabId, agentType) }
    }

    internal suspend fun prepareEach(
        providers: List<TerminalLaunchContributor>,
        timeoutMillis: Long,
        prepare: suspend (TerminalLaunchContributor) -> TerminalLaunchContribution,
    ): TerminalLaunchContribution {
        val parts = mutableListOf<TerminalLaunchContribution>()
        val unavailable = linkedSetOf<String>()
        val labels = mutableMapOf<String, String>()
        try {
            providers.forEach { provider ->
                val label = (provider as? NamedLaunchContributor)?.toolLabelKey ?: "terminal.tools.other"
                var prepared: TerminalLaunchContribution? = null
                try {
                    val part = withTimeoutOrNull(timeoutMillis) {
                        prepare(provider).also { prepared = it }
                    }
                    if (part == null) {
                        unavailable += label
                        LOG.warn("Terminal integration timed out: ${provider.javaClass.name}")
                    } else {
                        parts += part
                        part.servers.forEach { labels[it.name] = label }
                        prepared = null
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException || e is com.intellij.openapi.progress.ProcessCanceledException) throw e
                    unavailable += label
                    LOG.warn("Terminal integration unavailable: ${provider.javaClass.name}", e)
                } finally {
                    prepared?.let { closeAll(listOf(it)) }
                }
            }
            return merge(parts).also {
                it.unavailableTools = unavailable.toSet()
                it.serverLabels = labels
            }
        } catch (e: Exception) {
            closeAll(parts)
            throw e
        }
    }
}
