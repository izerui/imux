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
internal val DEFAULT_IDEA_MCP_GUIDANCE =
    """
    You have access to a JetBrains IDE (IntelliJ IDEA, PyCharm, WebStorm, GoLand, Rider, CLion, or others) through its MCP server. This works for ALL languages the IDE supports — Java, Kotlin, Python, TypeScript, JavaScript, Go, Rust, C/C++, C#, PHP, Ruby, and more. It gives you the same capabilities a human developer uses in the IDE — refactoring, debugging, inspections, run configurations, and database access. Use the decision rules below to pick the right tool for each task.

    ## When you MUST use IDEA MCP (your own tools cannot do these safely)

    | You want to…                        | Use this IDEA MCP tool                |
    |--------------------------------------|---------------------------------------|
    | Rename a variable, method, class, function, or any symbol | `rename_refactoring` |
    | Debug a runtime bug (any language the IDE supports) | `xdebug_set_breakpoint` → `xdebug_start_debugger_session` → `xdebug_control_session` → `xdebug_get_frame_values` / `xdebug_evaluate_expression` |
    | Check code for IDE-level issues (type errors, framework rules, lint warnings) | `lint_files` or `get_file_problems` |
    | Format code to the project's Code Style | `reformat_file`                    |
    | Write or run a custom inspection     | `generate_psi_tree` + `run_inspection_kts` |
    | Read source inside a JAR, node_modules, or decompile a class | `read_file` (with archive path) |

    Why: text find-and-replace breaks on same-name symbols in different scopes. Print-debugging wastes edit-run cycles. Compiler-only checks miss IDE-level rules. External formatters diverge from team style.

    ## When you SHOULD PREFER IDEA MCP (it gives better results)

    | You want to…                                    | Use this IDEA MCP tool                        |
    |-------------------------------------------------|-----------------------------------------------|
    | Trace who calls a function, or what a function calls | `analyze_calls`                           |
    | Find a symbol by name across the project         | `search_symbol`                              |
    | Get a symbol's type, docs, or declaration        | `get_symbol_info`                            |
    | Run or test with the exact IDE configuration     | `get_run_configurations` → `execute_run_configuration` |
    | View the project's module graph or dependencies  | `get_project_modules` / `get_project_dependencies` |
    | Query a database using IDE-saved credentials     | `list_database_connections` → `execute_sql_query` |
    | Manage Python interpreters (venv, Conda, system) | `get_python_environment` / `configure_python_interpreter` |

    Why: the IDE resolves symbols by semantic identity across inheritance, modules, and language boundaries — grep cannot distinguish overloads or trace through interfaces. IDE run configs carry env vars, runtime args, and working directories that are hard to reconstruct from build files. IDE database connections include saved credentials.

    ## When you MUST NOT use IDEA MCP (your own tools are better)

    - Text search → use grep/rg (faster, no IDE round-trip)
    - File read/write/edit → use your native file tools (more direct)
    - Directory listing → use ls/tree/Glob
    - Git operations → use git commands (more complete)
    - Shell commands → use your native shell (more flexible)

    ## Safety rules

    State-changing IDEA MCP calls — `rename_refactoring`, `execute_run_configuration`, `xdebug_control_session`, `xdebug_set_variable`, `execute_sql_query`, `apply_patch` — may have already executed on the IDE side even if the response times out or the connection drops. NEVER retry these after an ambiguous failure; check the result in the IDE first.
    """.trimIndent()

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

        /** 是否给 imux 启动的 Agent 增加 IDEA MCP 使用引导。 */
        var ideaMcpGuidanceEnabled: Boolean by property(true)

        /** null 表示使用随插件更新的默认引导词。 */
        var ideaMcpGuidanceOverride: String? by string(null)

        /** Agent 开关使用显式字段持久化；枚举名不是配置文件契约。 */
        var claudeEnabled: Boolean by property(true)
        var codexEnabled: Boolean by property(true)
        var piEnabled: Boolean by property(true)
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

    val ideaMcpGuidance: String
        get() = state.ideaMcpGuidanceOverride ?: DEFAULT_IDEA_MCP_GUIDANCE

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

    fun setIdeaMcpGuidance(guidance: String) {
        state.ideaMcpGuidanceOverride = guidance.takeUnless { it == DEFAULT_IDEA_MCP_GUIDANCE }
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
