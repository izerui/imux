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
internal val DEFAULT_IDEA_MCP_GUIDANCE_EN =
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
    | Find and use a matching IDE run configuration (if one exists) | `get_run_configurations` → `execute_run_configuration` (only when matched) |
    | View the project's module graph or dependencies  | `get_project_modules` / `get_project_dependencies` |
    | Query a database using IDE-saved credentials     | `list_database_connections` → `execute_sql_query` |
    | Manage Python interpreters (venv, Conda, system) | `get_python_environment` / `configure_python_interpreter` |
    | See what files the developer is currently editing | `get_all_open_file_paths`                    |

    Why: the IDE resolves symbols by semantic identity across inheritance, modules, and language boundaries — grep cannot distinguish overloads or trace through interfaces. IDE run configs carry env vars, runtime args, and working directories that are hard to reconstruct from build files. IDE database connections include saved credentials.

    If your LSP is active for the current file's language, use it for quick single-symbol lookups (go-to-definition, hover, find-references). Switch to IDEA MCP when you need full call hierarchy trees (`analyze_calls`) or cross-module symbol tracing through inheritance and interfaces. If LSP is not active or not configured, use IDEA MCP for ALL semantic navigation.

    ## Post-edit workflow

    After modifying code files, validate your changes before reporting the task as complete:
    1. When the project has an effective IDE build step and your changes involve compiled code, cross-file references, or public signatures, run `build_project` to get structured compilation errors with exact file/line locations — skip this when the project has no effective build step.
    2. Run `lint_files` on every file you changed — it catches framework-rule violations and warnings that the compiler alone would miss (Spring annotations, Android resources, JPA mappings, etc.).
    3. For running tests, check `get_run_configurations` first — when a matching IDE run configuration exists, use `execute_run_configuration` to guarantee the same env vars, classpath, and runtime args the developer uses; when no matching configuration exists, fall back to the project's native test command.

    Each tool covers a different layer: `build_project` = compilation, `lint_files` = IDE inspections, `execute_run_configuration` = test execution.

    ## Debug-first rule

    When investigating a runtime bug, prefer interactive debugging over adding print/log statements:
    1. Set breakpoints at the suspected failure point with `xdebug_set_breakpoint`.
    2. Start a debug session with `xdebug_start_debugger_session`.
    3. Inspect variables with `xdebug_get_frame_values`, evaluate expressions with `xdebug_evaluate_expression`, and step through code with `xdebug_control_session`.
    4. This validates multiple hypotheses in a single run — no edit-rebuild-rerun cycles needed.

    ## When you MUST NOT use IDEA MCP (your own tools are better)

    - Text search → use grep/rg (faster, no IDE round-trip)
    - File read/write/edit → use your native file tools (more direct)
    - Directory listing → use ls/tree/Glob
    - Git operations → use git commands (more complete)
    - Shell commands → use your native shell (more flexible)

    ## Safety rules

    State-changing IDEA MCP calls — `rename_refactoring`, `execute_run_configuration`, `xdebug_control_session`, `xdebug_set_variable`, `execute_sql_query`, `apply_patch` — may have already executed on the IDE side even if the response times out or the connection drops. NEVER retry these after an ambiguous failure; check the result in the IDE first.
    """.trimIndent()

internal val DEFAULT_IDEA_MCP_GUIDANCE_ZH =
    """
    你可以通过 MCP 服务器访问 JetBrains IDE（IntelliJ IDEA、PyCharm、WebStorm、GoLand、Rider、CLion 等）。它支持 IDE 所有语言——Java、Kotlin、Python、TypeScript、JavaScript、Go、Rust、C/C++、C#、PHP、Ruby 等，提供与人类开发者在 IDE 中相同的能力——重构、调试、代码检查、运行配置和数据库访问。请根据以下决策规则为每个任务选择合适的工具。

    ## 必须使用 IDEA MCP 的场景（你自己的工具无法安全完成）

    | 你想要……                              | 使用这个 IDEA MCP 工具                |
    |--------------------------------------|---------------------------------------|
    | 重命名变量、方法、类、函数或任何符号       | `rename_refactoring` |
    | 调试运行时 bug（IDE 支持的任何语言）       | `xdebug_set_breakpoint` → `xdebug_start_debugger_session` → `xdebug_control_session` → `xdebug_get_frame_values` / `xdebug_evaluate_expression` |
    | 检查代码的 IDE 级别问题（类型错误、框架规则、lint 警告） | `lint_files` 或 `get_file_problems` |
    | 按项目代码风格格式化代码                  | `reformat_file`                    |
    | 编写或运行自定义检查                      | `generate_psi_tree` + `run_inspection_kts` |
    | 读取 JAR、node_modules 内的源码或反编译类  | `read_file`（使用归档路径） |

    原因：文本查找替换会误改不同作用域中的同名符号。打印调试浪费编辑-运行周期。仅编译器检查会遗漏 IDE 级别的规则。外部格式化工具与团队风格不一致。

    ## 应该优先使用 IDEA MCP 的场景（它能给出更好的结果）

    | 你想要……                                        | 使用这个 IDEA MCP 工具                        |
    |-------------------------------------------------|-----------------------------------------------|
    | 追踪谁调用了某个函数，或某个函数调用了什么          | `analyze_calls`                           |
    | 在项目中按名称查找符号                             | `search_symbol`                              |
    | 获取符号的类型、文档或声明                          | `get_symbol_info`                            |
    | 查找并使用匹配的 IDE 运行配置（如果存在）             | `get_run_configurations` → `execute_run_configuration`（仅在匹配时） |
    | 查看项目的模块图或依赖                             | `get_project_modules` / `get_project_dependencies` |
    | 使用 IDE 保存的凭据查询数据库                       | `list_database_connections` → `execute_sql_query` |
    | 管理 Python 解释器（venv、Conda、系统）            | `get_python_environment` / `configure_python_interpreter` |
    | 查看开发者当前正在编辑哪些文件                      | `get_all_open_file_paths`                    |

    原因：IDE 通过语义身份跨继承、模块和语言边界解析符号——grep 无法区分重载或追踪接口。IDE 运行配置携带环境变量、运行参数和工作目录，这些很难从构建文件中重建。IDE 数据库连接包含已保存的凭据。

    如果你的 LSP 对当前文件的语言处于活跃状态，对于单符号快速查找（跳转定义、悬停信息、查找引用）使用 LSP 即可。当你需要完整的调用层级树（`analyze_calls`）或跨模块追踪继承和接口的符号解析时，切换到 IDEA MCP。如果 LSP 未激活或未配置，所有语义导航都使用 IDEA MCP。

    ## 编辑后验证工作流

    修改代码文件后，在报告任务完成前验证你的改动：
    1. 当项目存在有效的 IDE 构建步骤且改动涉及编译型代码、跨文件引用或公共签名时，运行 `build_project` 获取带有精确文件/行号的结构化编译错误——若项目没有有效构建步骤则跳过。
    2. 对所有修改过的文件运行 `lint_files`——它能发现编译器遗漏的框架规则违规和警告（Spring 注解、Android 资源、JPA 映射等）。
    3. 运行测试时，先用 `get_run_configurations` 检查——存在匹配的 IDE 运行配置时使用 `execute_run_configuration`，保证与开发者在 IDE 中点击 Run 时完全相同的环境变量、类路径和运行参数；不存在匹配配置时，退回项目原生测试命令。

    三个工具各管一层：`build_project` = 编译验证，`lint_files` = IDE 检查，`execute_run_configuration` = 测试执行。

    ## 调试优先规则

    排查运行时 bug 时，优先使用交互式调试而非添加 print/log 语句：
    1. 用 `xdebug_set_breakpoint` 在可疑位置设置断点。
    2. 用 `xdebug_start_debugger_session` 启动调试会话。
    3. 用 `xdebug_get_frame_values` 查看变量、用 `xdebug_evaluate_expression` 求值表达式、用 `xdebug_control_session` 单步执行。
    4. 这样可以在一次运行中验证多个假设——无需反复编辑-重建-重跑。

    ## 不得使用 IDEA MCP 的场景（你自己的工具更好）

    - 文本搜索 → 使用 grep/rg（更快，无 IDE 往返开销）
    - 文件读写编辑 → 使用你的原生文件工具（更直接）
    - 目录列表 → 使用 ls/tree/Glob
    - Git 操作 → 使用 git 命令（更完整）
    - Shell 命令 → 使用你的原生 shell（更灵活）

    ## 安全规则

    有副作用的 IDEA MCP 调用——`rename_refactoring`、`execute_run_configuration`、`xdebug_control_session`、`xdebug_set_variable`、`execute_sql_query`、`apply_patch`——即使响应超时或连接断开，也可能已在 IDE 端执行。出现不明确的失败后绝对不要重试，先在 IDE 中检查结果。
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

        /** 结对编程：副驾驶反馈是否自动注入主会话（false 时填入主会话输入框，由用户手动确认）。 */
        var peerAutoInject: Boolean by property(true)

        /** 结对编程：副驾驶最大交互轮次。 */
        var peerMaxRounds: Int by property(20)

        /** 结对编程：用户自定义副驾驶提示词；null 表示使用默认。 */
        var peerPromptOverride: String? by string(null)
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

    val ideaMcpGuidance: String
        get() = state.ideaMcpGuidanceOverride ?: defaultIdeaMcpGuidanceForLanguage(language)

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
        state.ideaMcpGuidanceOverride = guidance.takeUnless(::isDefaultIdeaMcpGuidance)
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

internal fun defaultIdeaMcpGuidanceForLanguage(language: PluginLanguage): String =
    if (language in setOf(PluginLanguage.SIMPLIFIED_CHINESE, PluginLanguage.TRADITIONAL_CHINESE)) {
        DEFAULT_IDEA_MCP_GUIDANCE_ZH
    } else {
        DEFAULT_IDEA_MCP_GUIDANCE_EN
    }

internal fun guidanceAfterLanguageSwitch(currentText: String, newLanguage: PluginLanguage): String =
    if (isDefaultIdeaMcpGuidance(currentText)) {
        defaultIdeaMcpGuidanceForLanguage(newLanguage)
    } else {
        currentText
    }

internal fun isDefaultIdeaMcpGuidance(text: String): Boolean =
    text == DEFAULT_IDEA_MCP_GUIDANCE_EN || text == DEFAULT_IDEA_MCP_GUIDANCE_ZH
