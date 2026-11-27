package com.github.izerui.imux.settings

import com.github.izerui.imux.model.AgentType
import com.intellij.DynamicBundle
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.EventDispatcher
import java.util.EventListener
import java.util.Locale

internal const val DEFAULT_IDEA_MCP_PORT = 64342
/**
 * 「最终回复说人话」的默认引导词。
 *
 * 刻意只写一句，贴近用户平时口头说的「直白点说」：规矩列得越细，模型越会照格式填空，
 * 反而偏离它本来想表达的内容。三个分句都是实测留下的：
 * - 「少用类名、函数名」：Codex 自带的回复格式偏爱把代码名放进反引号，只说「直白点」时
 *   它照样满屏类名；Claude 只说「直白点」就够。
 * - 「先把事情本身说清楚……比方不能代替原意」：用户最初提的诉求是「打个比方」，但只写
 *   「可以打个比方」时，回复有时只剩比方，原本要说的事反倒没讲。所以要先讲清本体，
 *   比方只能跟在后面帮忙理解。
 * - 「没做完的、没验证的、失败的、有风险的照实说」：防止为了简短把坏消息一起略掉。「没验证的」是后补的：
 *   做了但没验证过，和做完了不是一回事，原来的三项里漏了这一种——它也是用户最难自己发现的一种。
 *
 * 只注入 imux 打开的会话终端；副驾驶审查、需求澄清这类由程序解析输出的一次性调用不加，
 * 否则会打乱它们约定的输出格式。
 */
internal val DEFAULT_PLAIN_REPLY_GUIDANCE_EN =
    "Write the final reply plainly so someone who doesn't read code can follow it: use few class or function names. " +
        "Explain the actual point first; add an analogy after it where things get complicated, never in place of it. " +
        "State anything unfinished, unverified, failed, or risky; don't leave it out."

internal val DEFAULT_PLAIN_REPLY_GUIDANCE_ZH =
    "最终回复请直白点说，让不懂代码的人也能看明白：少用类名、函数名这类代码里的名字；" +
        "先把事情本身说清楚，复杂的地方再补个比方，比方不能代替原意。" +
        "没做完的、没验证的、失败的、有风险的照实说，不要省略。"

/**
 * 插件的全局偏好。
 *
 * 应用级而非项目级：这是操作手感，不是项目配置，换个项目还要重设一遍很别扭。
 * 关掉漫游是同样的道理——手感跟着这台机器的输入设备走，不该跟着账号跑到别的机器上。
 */
@Service(Service.Level.APP)
@State(
    name = "ImuxSettings",
    storages = [Storage("imux.xml", roamingType = RoamingType.DISABLED)],
)
class ImuxSettings : SimplePersistentStateComponent<ImuxSettings.State>(State()) {
    private val languageListeners = EventDispatcher.create(LanguageListener::class.java)
    private val enabledAgentsListeners = EventDispatcher.create(EnabledAgentsListener::class.java)
    private var ideaMcpPortDefaultInitialized = false

    class State : BaseState() {
        /** 单击即打开会话；false 表示需要双击。 */
        var openWithSingleClick: Boolean by property(false)

        /** 关闭仍在运行的会话标签前显示确认。 */
        var confirmBeforeClosingSession: Boolean by property(true)

        /**
         * 插件界面语言；使用稳定 id，避免枚举重命名破坏已有配置。
         *
         * 默认 null 表示「尚未显式选择」，此时跟随 IDE 语言，装完插件不必先去设置里改语言。
         */
        var languageId: String? by string(null)

        /** 在 Project 工具窗口的“新建”菜单中显示 AI 智能体入口。 */
        var showProjectNewAgentMenu: Boolean by property(true)

        /** 只给 imux 启动的会话临时注入 IDEA MCP，不修改各 CLI 的全局配置。 */
        var injectIdeaMcp: Boolean by property(true)

        /** JetBrains MCP Server 的 HTTP Stream 端口。 */
        var ideaMcpPort: Int by property(DEFAULT_IDEA_MCP_PORT)

        /** 用户是否明确修改过端口；明确值不得再被自动检测覆盖。 */
        var ideaMcpPortCustomized: Boolean by property(false)

        /**
         * 是否给 imux 启动的会话挂上查用法、查调用链工具（imux_code）。不依赖 IDEA MCP；
         * 使用引导没有单独开关，随挂上的工具自动注入（见 agentGuidance）。
         */
        var injectCodeTools: Boolean by property(true)

        /** 是否要求 imux 打开的会话用大白话写最终回复；与 IDEA MCP 无关，单独注入。 */
        var plainReplyEnabled: Boolean by property(true)

        /** Agent 开关使用显式字段持久化；枚举名不是配置文件契约。 */
        var claudeEnabled: Boolean by property(true)
        var codexEnabled: Boolean by property(true)
        var piEnabled: Boolean by property(true)

        /** 结对编程：副驾驶最大交互轮次。 */
        var peerMaxRounds: Int by property(50)

        /** 结对编程：会话通过 MCP 开启结对且未指定伙伴时用的伙伴（cli 名）；null 表示自动选择。 */
        var peerDefaultPartner: String? by string(null)
    }

    /**
     * IDE 语言优先于 JVM 默认语言：用户在 IDE 里装了语言包时 [Locale.getDefault] 仍是系统语言。
     * [DynamicBundle.getLocale] 依赖应用服务，单元测试等无应用环境下退回系统语言。
     *
     * 缓存结果：IDE 切换语言需要重启，而 [ImuxBundle.message] 是渲染路径上的高频调用。
     */
    private val detectedLanguage: PluginLanguage by lazy {
        val locale = runCatching { DynamicBundle.getLocale() }.getOrNull() ?: Locale.getDefault()
        PluginLanguage.fromLocale(locale)
    }

    val language: PluginLanguage
        get() = state.languageId?.let(PluginLanguage::fromId) ?: detectedLanguage

    /**
     * 设置对话框里尚未 Apply 的语言选择。
     *
     * 主设置页切换下拉框时写入，MCP 子页面读取 [effectiveLanguage] 即可看到待应用的语言，
     * 不必等到 Apply 落盘后 [language] 才变化。对话框关闭或重置时清除。
     */
    @Volatile
    var pendingLanguage: PluginLanguage? = null
        private set

    val effectiveLanguage: PluginLanguage
        get() = pendingLanguage ?: language

    fun setPreviewLanguage(lang: PluginLanguage) {
        val oldEffective = effectiveLanguage
        pendingLanguage = lang
        if (effectiveLanguage != oldEffective) {
            languageListeners.multicaster.languageChanged()
        }
    }

    fun clearPreviewLanguage() {
        val oldEffective = effectiveLanguage
        pendingLanguage = null
        if (effectiveLanguage != oldEffective) {
            languageListeners.multicaster.languageChanged()
        }
    }

    /** 按这次挂上的工具组合拼出的引导词；两组都没挂时为 null。内容不开放编辑，见 [agentGuidance]。 */
    fun agentGuidance(
        ideaMcp: Boolean,
        capabilities: Set<GuidanceCapability>,
        role: GuidanceRole = GuidanceRole.WORKER,
    ): String? = listOfNotNull(
        agentGuidanceForLanguage(language, ideaMcp, codeTools = GuidanceCapability.CODE_TOOLS in capabilities, role),
        if (GuidanceCapability.TURN_PROGRESS in capabilities && role == GuidanceRole.WORKER) {
            if (language.isChinese()) "直接调用 imux_session.report_progress 上报 percent（0–100整数）和 message（当前动作），无需步骤或计划。已做完直接报100，否则报告当前估计。有实际进展、开始等待或收到子任务结果时主动更新，不限制次数，不必每次工具调用都报，不按时间编造增长。结束由插件判断。无需预先获取上下文或填写身份参数；失败继续工作，不轮询重试。由主会话统一上报，等待子任务可写入当前动作；子代理不得覆盖主进度，独立后台服务和副驾驶不混入比例。"
            else "Call imux_session.report_progress directly with percent (integer 0–100) and message (current action). No steps or plan required. If already finished report 100; otherwise report your current estimate. Proactively update when meaningful progress occurs, you begin waiting, or subagent results arrive. No fixed count or timer; not every tool call needs an update. The plugin detects turn end. No context lookup, identity, or revision arguments are required. On failure continue work without polling or retries. The main conversation aggregates progress and may describe waiting for subagents. Subagents must not overwrite it; exclude independent background services and copilot review."
        } else null,
    ).takeIf { it.isNotEmpty() }?.joinToString("\n\n")

    val plainReplyGuidance: String
        get() = defaultPlainReplyGuidanceForLanguage(language)

    val enabledAgentTypes: List<AgentType>
        get() =
            AgentType.entries.filter { agentType ->
                when (agentType) {
                    AgentType.CLAUDE -> state.claudeEnabled
                    AgentType.CODEX -> state.codexEnabled
                    AgentType.PI -> state.piEnabled
                }
            }

    fun setLanguage(language: PluginLanguage) {
        val changed = this.language != language
        // 即使与自动推断结果一致也要落盘：显式选择后不应再跟随 IDE 语言变化。
        state.languageId = language.id
        if (changed) languageListeners.multicaster.languageChanged()
    }

    fun setEnabledAgentTypes(agentTypes: Set<AgentType>) {
        require(agentTypes.isNotEmpty()) { "At least one agent must be enabled" }
        if (enabledAgentTypes.toSet() == agentTypes) return
        state.claudeEnabled = AgentType.CLAUDE in agentTypes
        state.codexEnabled = AgentType.CODEX in agentTypes
        state.piEnabled = AgentType.PI in agentTypes
        enabledAgentsListeners.multicaster.enabledAgentsChanged()
    }

    fun initializeIdeaMcpPortDefault(detectedPort: Int?) {
        if (state.ideaMcpPortCustomized || ideaMcpPortDefaultInitialized) return
        val validPort = detectedPort?.takeIf { it in 1..65535 } ?: return
        state.ideaMcpPort = validPort
        ideaMcpPortDefaultInitialized = true
    }

    fun setIdeaMcpPort(port: Int) {
        state.ideaMcpPort = port
        state.ideaMcpPortCustomized = true
    }

    fun addLanguageListener(
        parentDisposable: Disposable,
        listener: () -> Unit,
    ) {
        languageListeners.addListener(LanguageListener(listener), parentDisposable)
    }

    fun addEnabledAgentsListener(
        parentDisposable: Disposable,
        listener: () -> Unit,
    ) {
        enabledAgentsListeners.addListener(EnabledAgentsListener(listener), parentDisposable)
    }

    fun interface LanguageListener : EventListener {
        fun languageChanged()
    }

    fun interface EnabledAgentsListener : EventListener {
        fun enabledAgentsChanged()
    }

    companion object {
        fun getInstance(): ImuxSettings =
            service<ImuxSettings>().also {
                it.initializeIdeaMcpPortDefault(readIdeaMcpPlatformPort())
            }

        fun getInstanceOrNull(): ImuxSettings? {
            val application = ApplicationManager.getApplication() ?: return null
            if (application.isDisposed) return null
            return application.getService(ImuxSettings::class.java)
        }
    }
}

internal fun defaultPlainReplyGuidanceForLanguage(language: PluginLanguage): String =
    if (language in setOf(PluginLanguage.SIMPLIFIED_CHINESE, PluginLanguage.TRADITIONAL_CHINESE)) {
        DEFAULT_PLAIN_REPLY_GUIDANCE_ZH
    } else {
        DEFAULT_PLAIN_REPLY_GUIDANCE_EN
    }
