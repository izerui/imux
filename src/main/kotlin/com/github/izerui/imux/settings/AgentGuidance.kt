package com.github.izerui.imux.settings

/** imux 自带的查引用、查调用链 MCP 服务名；提示词按名字提到它，服务端注册时也用它。 */
internal const val CODE_TOOLS_SERVER = "imux_code"

/**
 * 注入给智能体的引导词，按这次会话实际挂上了哪几组工具拼装。
 *
 * - [ideaMcp]：挂了 IDEA MCP（重命名、编译检查、调试、数据库……）；
 * - [codeTools]：挂了 [CODE_TOOLS_SERVER]（查引用、查调用链）。会话终端始终挂，
 *   会话终端和副驾驶审查都挂（随「查用法、查调用链工具」开关），需求澄清这类一次性调用不挂。
 *
 * 没挂的工具一个字不提：提到用不了的工具，智能体会去调、失败、再换路，白白浪费回合。
 * 结构按改代码的流程排：改之前摸清链路 → 改的时候 → 改完验证，其余能力放后面。
 * 不对用户开放编辑：内容和工具组合绑定，用户改过的版本跟不上工具变化。
 */
internal fun agentGuidance(chinese: Boolean, ideaMcp: Boolean, codeTools: Boolean): String? {
    if (!ideaMcp && !codeTools) return null
    val text = if (chinese) ZH else EN
    return buildList {
        add(text.intro(ideaMcp, codeTools))
        if (codeTools) add(text.preEdit(ideaMcp))
        if (ideaMcp) {
            add(text.whileEditing)
            add(text.verify)
            add(text.debugging)
            add(text.otherCapabilities(codeTools))
            add(text.ownTools)
            add(text.safety)
        }
    }.joinToString("\n\n")
}

internal fun agentGuidanceForLanguage(language: PluginLanguage, ideaMcp: Boolean, codeTools: Boolean): String? =
    agentGuidance(language in setOf(PluginLanguage.SIMPLIFIED_CHINESE, PluginLanguage.TRADITIONAL_CHINESE), ideaMcp, codeTools)

private interface GuidanceText {
    fun intro(ideaMcp: Boolean, codeTools: Boolean): String
    fun preEdit(ideaMcp: Boolean): String
    val whileEditing: String
    val verify: String
    val debugging: String
    fun otherCapabilities(codeTools: Boolean): String
    val ownTools: String
    val safety: String
}

private object EN : GuidanceText {
    override fun intro(ideaMcp: Boolean, codeTools: Boolean): String = buildString {
        append("You are working inside a JetBrains IDE (IntelliJ IDEA, PyCharm, WebStorm, GoLand, Rider, CLion, or others). ")
        append("Besides your own tools, you have MCP tools backed by this IDE:")
        if (ideaMcp) {
            append("\n- `idea` (IDEA MCP): refactoring, inspections, builds, run configurations, the debugger, databases, formatting, and symbol lookup.")
        }
        if (codeTools) {
            append("\n- `$CODE_TOOLS_SERVER`: `find_references`, `call_hierarchy`, and `type_hierarchy`, which use the IDE's own index and work for every language the IDE understands.")
        }
        append("\n\nThe IDE resolves symbols by meaning, not by text, across files, modules, inheritance, and languages. Follow the workflow below whenever you change code.")
    }

    override fun preEdit(ideaMcp: Boolean): String = buildString {
        append(
            """
            ## 1. Before editing: map what the change touches

            Do not start editing after reading only a few files. Whenever the change touches a signature, shared behavior, or anything used outside the current function:
            """.trimIndent(),
        )
        append("\n")
        append(
            if (ideaMcp) {
                "1. Find each symbol you will change (`search_symbol` when you only know the name), read its declaration and docs with `get_symbol_info`, and read the full body — not just the lines around a search hit."
            } else {
                "1. Find each symbol you will change, read its declaration, and read the full body — not just the lines around a search hit."
            },
        )
        append("\n")
        append(
            """
            2. Run `call_hierarchy` on every function or method you will change, in both directions: callers (who depends on it and what arguments they pass) and callees (what it relies on). Calls made through an interface or base method are included and marked `via`. Keep following until you reach a boundary the change cannot affect — an entry point, a public API, or code whose behavior stays the same.
            3. When a method or type you will change overrides, implements, or is overridden or implemented by others, run `type_hierarchy` in both directions (`supers` and `implementations`): a signature change has to be made on all of them together.
            4. For every parameter, field, return value, or shared state whose meaning, type, or nullability changes, run `find_references` to see where its values come from and where they go, including other modules.
            5. Then edit, and update every affected caller, override, and implementation in the same change. If the chain turns out larger than expected, say so instead of quietly changing only part of it.

            A purely local edit only needs the function itself read in full.

            Using these tools:
            - Locate a symbol by `file` + `line` plus its `symbol` name. Positions from search results, compiler errors, or grep can be passed straight in.
            - `unanalyzed_mentions` in a result lists files in languages the IDE cannot analyze, so their uses are missing from the result: open each one and check it before treating the chain as complete.
            - `overrides_incomplete: true` means the search for what a method overrides stopped early (too many declarations share its name), so calls made through an interface may be missing: check the enclosing type's interfaces and base types yourself.
            - If `$CODE_TOOLS_SERVER` answers `language_not_supported` or is unavailable, use the language server: the LSP tool's `findReferences` / `incomingCalls` / `outgoingCalls`, or its command line run from the project root. With no language server either, use text search, read each hit in context to confirm it is a real use, and say in your reply that the chain came from text search.
            - Do not retry a failing call with different spellings.
            """.trimIndent(),
        )
        if (ideaMcp) {
            append("\n- `analyze_calls` resolves symbols by fully qualified name, which some language plugins (e.g. Go) do not provide; when it answers `No callable symbol found`, use `call_hierarchy` instead.")
        }
    }

    override val whileEditing =
        """
        ## 2. While editing

        - Rename any variable, method, class, or other symbol with `rename_refactoring`; text find-and-replace breaks same-name symbols in other scopes.
        - Write and edit files with your own tools.
        - Format with `reformat_file` so the result follows the project's code style rather than an external formatter's.
        """.trimIndent()

    override val verify =
        """
        ## 3. After editing: verify before reporting done

        1. When the project has an effective IDE build step and your changes involve compiled code, cross-file references, or public signatures, run `build_project` to get compilation errors with exact file/line locations — skip this when the project has no effective build step.
        2. Run `lint_files` on every file you changed — it catches framework-rule violations and warnings that the compiler alone would miss (Spring annotations, Android resources, JPA mappings, etc.).
        3. For tests, check `get_run_configurations` first — when a matching IDE run configuration exists, use `execute_run_configuration` to get the same env vars, classpath, and runtime args the developer uses; when no matching configuration exists, fall back to the project's native test command.
        """.trimIndent()

    override val debugging =
        """
        ## Debugging runtime bugs

        Prefer interactive debugging over adding print/log statements: set breakpoints with `xdebug_set_breakpoint`, start with `xdebug_start_debugger_session`, inspect with `xdebug_get_frame_values` and `xdebug_evaluate_expression`, and step with `xdebug_control_session`. One run can test several hypotheses without edit-rebuild-rerun cycles.
        """.trimIndent()

    override fun otherCapabilities(codeTools: Boolean): String = buildString {
        append("## Other IDE capabilities to prefer\n\n")
        append("- Find a symbol by name: `search_symbol`; its declaration, type, and docs: `get_symbol_info`.\n")
        if (!codeTools) {
            append(
                "- Who calls a function and what it calls: `analyze_calls`. It resolves symbols by fully qualified name, which some language plugins (e.g. Go) do not provide; " +
                    "when it answers `No callable symbol found`, do not guess names — use the LSP tool's `incomingCalls` / `findReferences`, or text search.\n",
            )
        }
        append(OTHER_CAPABILITIES_EN)
    }

    private val OTHER_CAPABILITIES_EN =
        """
        - Sources inside JARs or node_modules, and decompiled classes: `read_file` with the archive path.
        - Module graph and dependencies: `get_project_modules` / `get_project_dependencies`.
        - Databases with IDE-saved credentials: `list_database_connections` → `execute_sql_query`.
        - Python interpreters (venv, Conda, system): `get_python_environment` / `configure_python_interpreter`.
        - Files the developer is editing right now: `get_all_open_file_paths`.
        - Custom checks: `generate_psi_tree` + `run_inspection_kts`.

        If your LSP tool is active for the current language, it is fine for quick go-to-definition and hover lookups.
        """.trimIndent()

    override val ownTools =
        """
        ## Use your own tools for

        Text search (grep/rg), reading and writing files, directory listings, git, and shell commands — they are faster and more direct than IDE round-trips.
        """.trimIndent()

    override val safety =
        """
        ## Safety

        State-changing IDE calls — `rename_refactoring`, `execute_run_configuration`, `xdebug_control_session`, `xdebug_set_variable`, `execute_sql_query`, `apply_patch` — may have already executed even if the response times out or the connection drops. NEVER retry them after an ambiguous failure; check the result in the IDE first.
        """.trimIndent()
}

private object ZH : GuidanceText {
    override fun intro(ideaMcp: Boolean, codeTools: Boolean): String = buildString {
        append("你正在 JetBrains IDE（IntelliJ IDEA、PyCharm、WebStorm、GoLand、Rider、CLion 等）里工作。除了你自己的工具，还有这个 IDE 提供的 MCP 工具：")
        if (ideaMcp) {
            append("\n- `idea`（IDEA MCP）：重构、代码检查、构建、运行配置、调试器、数据库、格式化和符号查询。")
        }
        if (codeTools) {
            append("\n- `$CODE_TOOLS_SERVER`：`find_references`、`call_hierarchy` 和 `type_hierarchy`，走 IDE 自己的索引，IDE 能解析的每一种语言都能用。")
        }
        append("\n\nIDE 按语义而不是按文字解析符号，能跨文件、模块、继承和语言。改代码时按下面的流程做。")
    }

    override fun preEdit(ideaMcp: Boolean): String = buildString {
        append(
            """
            ## 1. 改之前：摸清改动牵涉的范围

            不要只读了几个文件就动手。只要改动涉及签名、共享行为或当前函数之外用到的东西：
            """.trimIndent(),
        )
        append("\n")
        append(
            if (ideaMcp) {
                "1. 找到要改的每个符号（只知道名字时用 `search_symbol`），用 `get_symbol_info` 看它的声明和文档，并把方法体完整读完——不要只看搜索命中的那几行。"
            } else {
                "1. 找到要改的每个符号，看它的声明，并把方法体完整读完——不要只看搜索命中的那几行。"
            },
        )
        append("\n")
        append(
            """
            2. 对每个要改的函数或方法运行 `call_hierarchy`，两个方向都看：调用方（谁依赖它、传进来什么参数）和被调用方（它依赖什么）。通过接口或父类方法调用的地方也会列出，并标明 `via`。一路追到改动影响不到的边界为止——入口、公开 API，或行为保持不变的代码。
            3. 要改的方法或类型如果重写、实现了别的，或者被别的重写、实现，用 `type_hierarchy` 上下两个方向（`supers` 和 `implementations`）都查：改签名要一起改。
            4. 凡是含义、类型或可空性要变的参数、字段、返回值和共享状态，都用 `find_references` 追清楚它的值从哪来、流到哪去，包括其它模块。
            5. 摸清之后再动手，并在同一次改动里把受影响的调用方、重写和实现一并改掉。链路比预想的大时如实说明，不要悄悄只改其中一部分。

            纯局部的改动只需把该函数完整读完。

            使用这些工具：
            - 按 `file` + `line` 加上符号名 `symbol` 定位。搜索结果、编译错误或 grep 给出的位置可以直接传入。
            - 结果里出现 `unanalyzed_mentions` 时，说明这些文件的语言 IDE 分析不了，其中的用法不在结果里：逐个打开核对，核对完才能当作完整链路。
            - 结果里出现 `overrides_incomplete: true` 时，说明同名声明太多、往上找重写对象提前停了，经由接口的调用可能没列全：自己看一下所在类型实现的接口和父类。
            - `$CODE_TOOLS_SERVER` 返回 `language_not_supported` 或不可用时，改用语言服务器：LSP 工具的 `findReferences` / `incomingCalls` / `outgoingCalls`，或在项目根目录运行它的命令行。这门语言也没有语言服务器时，用文本搜索，逐个打开命中处读上下文，确认是真实用法，回复里说明这条链路是靠文本搜索得出的。
            - 同一个失败的调用不要换个写法反复重试。
            """.trimIndent(),
        )
        if (ideaMcp) {
            append("\n- `analyze_calls` 按完整限定名解析符号，有些语言插件（如 Go）不提供这项换算；它返回 `No callable symbol found` 时，改用 `call_hierarchy`。")
        }
    }

    override val whileEditing =
        """
        ## 2. 改的时候

        - 重命名变量、方法、类或任何符号都用 `rename_refactoring`；文本查找替换会误改其它作用域里的同名符号。
        - 读写、编辑文件用你自己的工具。
        - 用 `reformat_file` 格式化，结果才符合项目的代码风格，而不是外部格式化工具的风格。
        """.trimIndent()

    override val verify =
        """
        ## 3. 改完以后：验证通过再汇报完成

        1. 当项目存在有效的 IDE 构建步骤且改动涉及编译型代码、跨文件引用或公共签名时，运行 `build_project` 获取带有精确文件/行号的编译错误——若项目没有有效构建步骤则跳过。
        2. 对所有修改过的文件运行 `lint_files`——它能发现编译器遗漏的框架规则违规和警告（Spring 注解、Android 资源、JPA 映射等）。
        3. 跑测试时，先用 `get_run_configurations` 检查——存在匹配的 IDE 运行配置时使用 `execute_run_configuration`，保证与开发者在 IDE 中点击 Run 时完全相同的环境变量、类路径和运行参数；不存在匹配配置时，退回项目原生测试命令。
        """.trimIndent()

    override val debugging =
        """
        ## 调试运行时 bug

        优先用交互式调试，而不是加 print/log：用 `xdebug_set_breakpoint` 设断点、`xdebug_start_debugger_session` 启动、`xdebug_get_frame_values` 和 `xdebug_evaluate_expression` 查看、`xdebug_control_session` 单步。一次运行就能验证多个假设，不用反复改-编译-重跑。
        """.trimIndent()

    override fun otherCapabilities(codeTools: Boolean): String = buildString {
        append("## 其它优先用 IDE 的能力\n\n")
        append("- 按名字找符号：`search_symbol`；看它的声明、类型和文档：`get_symbol_info`。\n")
        if (!codeTools) {
            append(
                "- 谁调用了某个函数、它又调用了谁：`analyze_calls`。它按完整限定名解析符号，有些语言插件（如 Go）不提供这项换算；" +
                    "返回 `No callable symbol found` 时不要猜名字，改用 LSP 工具的 `incomingCalls` / `findReferences`，或文本搜索。\n",
            )
        }
        append(OTHER_CAPABILITIES_ZH)
    }

    private val OTHER_CAPABILITIES_ZH =
        """
        - JAR、node_modules 里的源码和反编译的类：`read_file` 加归档路径。
        - 模块结构和依赖：`get_project_modules` / `get_project_dependencies`。
        - 用 IDE 保存的凭据查数据库：`list_database_connections` → `execute_sql_query`。
        - Python 解释器（venv、Conda、系统）：`get_python_environment` / `configure_python_interpreter`。
        - 开发者此刻正在编辑的文件：`get_all_open_file_paths`。
        - 自定义检查：`generate_psi_tree` + `run_inspection_kts`。

        如果你的 LSP 工具对当前语言可用，快速跳转定义、查看悬停信息用它就行。
        """.trimIndent()

    override val ownTools =
        """
        ## 这些用你自己的工具

        文本搜索（grep/rg）、读写文件、列目录、git 和 shell 命令——比经过 IDE 更快、更直接。
        """.trimIndent()

    override val safety =
        """
        ## 安全规则

        有副作用的 IDE 调用——`rename_refactoring`、`execute_run_configuration`、`xdebug_control_session`、`xdebug_set_variable`、`execute_sql_query`、`apply_patch`——即使响应超时或连接断开，也可能已经执行。出现不明确的失败后绝对不要重试，先在 IDE 里确认结果。
        """.trimIndent()
}
