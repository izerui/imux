package com.github.izerui.imux.terminal

import com.github.izerui.imux.ImuxBundle
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import java.net.InetSocketAddress
import java.net.Socket

private const val IDEA_MCP_SETTINGS_ID = "com.intellij.mcpserver.settings"
private const val NOTIFICATION_GROUP = "imux.ideaMcp"

private val LOG = logger<IdeaMcpReadiness>()

/**
 * JetBrains MCP Server 认的项目定向请求头。
 *
 * **多项目窗口下这不是锦上添花，是必需品。** 不带它时，任何没有显式 `projectPath` 实参的
 * 工具调用都会被服务端拒绝，返回「Unable to determine the target project」并要求模型
 * 反过来问用户选哪个项目——本机开着 3 个项目时实测如此。带上之后，同一个调用不传任何
 * 参数就能正确落到本标签所属的项目。
 *
 * 名字取自 `com.intellij.mcpserver.stdio.McpStdioRunnerKt.IJ_MCP_SERVER_PROJECT_PATH`；
 * 服务端在 `/stream` 与 `/sse` 的路由入口直接 `request.headers[...]` 读它。
 */
internal const val IDEA_MCP_PROJECT_HEADER = "IJ_MCP_SERVER_PROJECT_PATH"

internal data class IdeaMcpEndpoint(
    val port: Int,
    val projectPath: String,
) {
    val url: String = "http://127.0.0.1:$port/stream"
}

internal fun canConnectToIdeaMcp(
    port: Int,
    timeoutMillis: Int = 250,
): Boolean =
    runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMillis)
        }
        true
    }.getOrDefault(false)

/** 本次会话要不要注入，以及注入时是否提示端口不可达。 */
internal sealed interface IdeaMcpDecision {
    data class Inject(
        val endpoint: IdeaMcpEndpoint,
        val notifyUnavailable: Boolean,
    ) : IdeaMcpDecision

    /** 用户在 imux 里关掉了注入：这是显式选择，不提示。 */
    data object Disabled : IdeaMcpDecision
}

/**
 * 决定本次会话用哪个端点。Imux 设置是唯一配置来源；探测只决定是否提示。
 *
 * 端口暂时不可达仍然注入：IDE 启动早期 MCP Server 与标签恢复存在竞态，若探测结果
 * 决定注不注入，恢复出来的会话会永久缺少 IDEA 工具。CLI 自己负责连接与重连，imux
 * 只用探测结果给用户一个设置入口。
 */
internal fun decideIdeaMcp(
    injectEnabled: Boolean,
    configuredPort: Int,
    projectPath: String,
    probe: (Int) -> Boolean,
    shouldNotify: (Int) -> Boolean,
): IdeaMcpDecision {
    if (!injectEnabled) return IdeaMcpDecision.Disabled

    val reachable = probe(configuredPort)
    return IdeaMcpDecision.Inject(
        endpoint = IdeaMcpEndpoint(configuredPort, projectPath),
        notifyUnavailable = !reachable && shouldNotify(configuredPort),
    )
}

/**
 * 端口可达性的**短时缓存**，外加「不可达」的边沿检测。
 *
 * 为什么需要缓存：一次会话启动会分别在 `createView` 与 `newCommand` / `resumeCommand`
 * 里各问一次。缓存避免同一次启动重复建立回环连接，也保证提示去重状态一致。
 *
 * 为什么可以同步探测：目标是 127.0.0.1。端口在监听时连接在亚毫秒级返回，没监听时内核直接
 * 回 RST（ECONNREFUSED），同样立即返回——[canConnectToIdeaMcp] 的 250ms 只在数据包被丢弃时
 * 才会走满，而这在回环上基本不存在。
 *
 * 不可达的提示按**端口 + 连续不可达**去重：同一端口连着不可达只提示一次，恢复过一次之后
 * 再次不可达会重新提示。一次性的 `AtomicBoolean` 永不复位，用户照着提示去改端口、又改错了，
 * 之后就再也收不到任何反馈。
 */
internal class IdeaMcpProbe(
    private val ttlMillis: Long = 3_000L,
    private val now: () -> Long = System::currentTimeMillis,
    private val connect: (Int) -> Boolean = { canConnectToIdeaMcp(it) },
) {
    private var cachedPort: Int? = null
    private var cachedAt = 0L
    private var cachedResult = false
    private var notifiedPort: Int? = null

    @Synchronized
    fun reachable(port: Int): Boolean {
        val timestamp = now()
        if (cachedPort != port || timestamp - cachedAt >= ttlMillis) {
            cachedPort = port
            cachedAt = timestamp
            cachedResult = connect(port)
        }
        if (cachedResult) notifiedPort = null
        return cachedResult
    }

    @Synchronized
    fun shouldNotify(port: Int): Boolean {
        if (notifiedPort == port) return false
        notifiedPort = port
        return true
    }

}

/**
 * IDEA MCP 是可选增强。开启时始终按 Imux 设置注入；不可达只提示，不篡改本次启动配置。
 */
@Service(Service.Level.PROJECT)
internal class IdeaMcpReadiness(
    private val project: Project,
) {
    private val probe = IdeaMcpProbe()

    fun endpointFor(
        injectEnabled: Boolean,
        configuredPort: Int,
        projectPath: String,
    ): IdeaMcpEndpoint? {
        val decision =
            decideIdeaMcp(
                injectEnabled = injectEnabled,
                configuredPort = configuredPort,
                projectPath = projectPath,
                probe = probe::reachable,
                shouldNotify = probe::shouldNotify,
            )
        LOG.info("IDEA MCP 判定: $decision (injectEnabled=$injectEnabled, configuredPort=$configuredPort)")
        return when (decision) {
            is IdeaMcpDecision.Inject -> {
                if (decision.notifyUnavailable && !project.isDisposed) notifyUnavailable()
                decision.endpoint
            }
            IdeaMcpDecision.Disabled -> null
        }
    }

    private fun notifyUnavailable() {
        val notification =
            NotificationGroupManager
                .getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(
                    ImuxBundle.message("notification.idea.mcp.title"),
                    ImuxBundle.message("notification.idea.mcp.content"),
                    NotificationType.INFORMATION,
                )
        notification.addAction(
            NotificationAction.createSimpleExpiring(ImuxBundle.message("notification.idea.mcp.open.settings")) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, IDEA_MCP_SETTINGS_ID)
            },
        )
        notification.notify(project)
    }

    companion object {
        fun getInstance(project: Project): IdeaMcpReadiness = project.service()
    }
}
