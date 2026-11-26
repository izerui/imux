package com.github.izerui.imux.settings

/** imux 自带的查用法、查调用链 MCP 服务名；提示词按名字提到它，服务端注册时也用它。 */
internal const val CODE_TOOLS_SERVER = "imux_code"

/**
 * 挂上某组工具后，引导词里要带上的那部分。由提供工具的功能自己声明（终端启动配置、副驾驶的凭证），
 * 终端和副驾驶只把声明交给 [agentGuidance]，不必认识具体的服务名。IDEA MCP 不在其中：它由设置直接决定。
 */
enum class GuidanceCapability {
    /** 看文件大纲、查用法、查调用链、查继承：引导词带上「摸清链路」一节。 */
    CODE_TOOLS,
}

/**
 * 拿到引导词的智能体这次是来干什么的。同样的工具，干活的和只查不改的要听到不同的话：
 * 把「改的时候怎么重命名、改完怎么构建」发给被禁止改动的审查者，是在和它自己的提示词打架，也白占字数。
 */
enum class GuidanceRole {
    /** 会话终端里干活的主智能体：查、改、验证、调试都讲。 */
    WORKER,

    /** 只查不改的角色（副驾驶审查、需求澄清）：只讲怎么用 IDE 查清事实，不讲改、构建、调试。 */
    INVESTIGATOR,
}

/**
 * 注入给智能体的引导词，按这次实际挂上了哪几组工具、[role] 是来干什么的拼装。
 *
 * - [ideaMcp]：挂了 IDEA MCP（重命名、编译检查、调试、数据库……）；
 * - [codeTools]：挂了 [CODE_TOOLS_SERVER]（查用法、查调用链、查继承）。会话终端、副驾驶审查、需求澄清都挂
 *   （随「查用法、查调用链、查继承工具」开关）；会话报告只读会话记录，什么都不挂、也不给引导词。
 *
 * 写法上的几条约定：
 * - 介绍每项能力适用的情形以及能提供的证据；
 * - 按干活的时刻排：看清 → 摸清链路 → 改 → 证明改对了 → 运行时出错，冷门能力单独放在「用得上再用」；
 * - 没挂的工具一个字不提：提到用不了的工具，智能体会去调、失败、再换路，白白浪费回合；
 * - 各节不编号：节是按工具组合和角色取舍的，编号会断（只挂 IDEA MCP 时曾经从「2.」开始）。
 *
 * 和工具说明的分工：结果里各个标记是什么意思写在工具说明里（用到时才读），这里只讲什么时候用、为什么用。
 * 不对用户开放编辑：内容和工具组合绑定，用户改过的版本跟不上工具变化。
 */
internal fun agentGuidance(
    chinese: Boolean,
    ideaMcp: Boolean,
    codeTools: Boolean,
    role: GuidanceRole = GuidanceRole.WORKER,
): String? {
    if (!ideaMcp && !codeTools) return null
    val text = if (chinese) ZH else EN
    val worker = role == GuidanceRole.WORKER
    return buildList {
        add(text.intro(ideaMcp, codeTools, worker))
        if (ideaMcp) add(text.look(codeTools, worker))
        if (codeTools) add(text.chain(ideaMcp, worker))
        if (ideaMcp && worker) {
            add(text.whileEditing)
            add(text.verify(codeTools))
            add(text.debugging)
            add(text.onDemand)
        }
        add(text.ownTools(worker))
    }.joinToString("\n\n")
}

internal fun agentGuidanceForLanguage(
    language: PluginLanguage,
    ideaMcp: Boolean,
    codeTools: Boolean,
    role: GuidanceRole = GuidanceRole.WORKER,
): String? = agentGuidance(language.isChinese(), ideaMcp, codeTools, role)

/** 提示词只有中文、英文两套：简体、繁体用中文，其余语言用英文。 */
internal fun PluginLanguage.isChinese(): Boolean =
    this == PluginLanguage.SIMPLIFIED_CHINESE || this == PluginLanguage.TRADITIONAL_CHINESE

private interface GuidanceText {
    fun intro(ideaMcp: Boolean, codeTools: Boolean, worker: Boolean): String

    /** IDEA MCP 里用来看清代码的工具：干活的在改之前用，只查不改的拿它查证事实。 */
    fun look(codeTools: Boolean, worker: Boolean): String
    fun chain(ideaMcp: Boolean, worker: Boolean): String
    val whileEditing: String
    /** [codeTools] 时多一句：说明查用法结果里的对应测试（`tests` 只在挂了查代码工具时才有）。 */
    fun verify(codeTools: Boolean): String
    val debugging: String

    /** 特定场景才用得上的能力，和每次都该用的分开放，免得冷门的冲淡常用的。 */
    val onDemand: String
    fun ownTools(worker: Boolean): String
}

private object EN : GuidanceText {
    private val outline = "- To see how a file fits together, `file_outline` lists declarations and their lines, including the parents and interfaces named by a class. A method's position can go straight into a reference or call query. Read the relevant implementation in full to understand its behavior. If several parts share state and their interaction is still unclear, read the whole file."

    override fun intro(ideaMcp: Boolean, codeTools: Boolean, worker: Boolean): String = buildString {
        append("You are working inside a JetBrains IDE such as IntelliJ IDEA, PyCharm, WebStorm, GoLand, Rider or CLion. ")
        append(if (ideaMcp && codeTools) "This session has two sets of MCP tools." else "This session has the following MCP tools.")
        if (!worker) append("\n\nThis time you only investigate. The user and the agent doing the work decide what to change and how. Your role is to establish the facts.")
        append("\n")
        if (ideaMcp) {
            append(
                if (worker) "\n- `idea` provides symbol lookup, refactoring, inspections, builds, run configurations, debugging, database access and formatting in the current IDE project."
                else "\n- `idea` provides symbol lookup, dependency sources, module structure and IDE diagnostics.",
            )
        }
        if (codeTools) append("\n- `$CODE_TOOLS_SERVER` provides `file_outline` from the IDE structure view and `find_references`, `call_hierarchy` and `type_hierarchy` from the IDE index. An unsupported language returns `language_not_supported`.")
    }

    override fun look(codeTools: Boolean, worker: Boolean): String = buildString {
        append("## See the code clearly\n")
        append("\n- To find what a name refers to, `search_symbol` locates candidate declarations and `get_symbol_info` shows their type, signature and documentation.")
        if (codeTools) append("\n$outline")
        append("\n- To see how a dependency actually behaves, `read_file` with an archive path can read source in JARs or node_modules and decompiled classes from the version this project uses.")
        append("\n- To see how modules depend on each other, use `get_project_modules` and `get_project_dependencies`. When the user mentions a file they just opened, `get_all_open_file_paths` can narrow it down.")
        if (!codeTools) append("\n- `analyze_calls` looks up call relationships by fully qualified name. Resolution depends on the language plugin. `No callable symbol found` means this query could not locate the target method.")
        if (!worker) append("\n- `lint_files` shows errors and warnings in a file.")
    }

    override fun chain(ideaMcp: Boolean, worker: Boolean): String = buildString {
        if (!ideaMcp) append("## See the code clearly\n\n$outline\n\n")
        append(
            """
                ## Map the chain

                To understand a piece of code, consider who relies on it and whose contract it must keep. The IDE index connects declarations with the same name to their respective uses and follows interface dispatch and references across modules.

                - To see who relies on a method or what it relies on, use `call_hierarchy`. Follow callers up to find affected entry points and callees down to see where the work goes. Calls found through an interface or base method are marked `via`.
                - To see which contract a method or type implements, follow `supers` in `type_hierarchy`. When the contract changes, `implementations` shows which implementations may need updating.
                - To trace a value, use `find_references` on its parameter, field or shared state and inspect the read and write sites. When the meaning of a return value changes, callers can reveal where the old meaning is still assumed.

                When shared behavior changes, who else depends on the old contract?

                To locate a symbol, give its `file`, declaration `line` and `symbol` name. Add `column` to distinguish candidates on the same line. If a call fails, read the reason. It may give the actual declaration line.

                Claim only what the results establish. `truncated` and `not_expanded` flag omitted results or branches. `overrides_incomplete`, `unanalyzed_mentions` and `unanalyzed_not_checked` indicate limits on override and reference coverage. Follow up on the branch your conclusion depends on.
            """.trimIndent(),
        )
    }

    override val whileEditing =
        """
            ## While editing

            - `rename_refactoring` updates references the IDE resolves to the declaration being renamed. It can distinguish a method with the same name in a different scope.
            - When the project configures its code style in the IDE, `reformat_file` applies those settings.
        """.trimIndent()

    override fun verify(codeTools: Boolean): String = buildString {
        append(
            """
                ## After editing

                Saying the work is done takes evidence. What result will the user get in practice? How much of that have you verified? If the evidence covers only an intermediate step, verify the final result through the entry point the user normally uses. If the environment limits what you can check, say how far you got and what still needs confirmation. The IDE can help verify different aspects of the change.

                - For changes involving cross-file references or public signatures, `build_project` builds the current IDE project and reports compiler errors with file and line.
                - `lint_files` reports errors, warnings and framework inspections enabled for the project, such as Spring annotations, Android resources and JPA mappings. When a shared contract changes, the callers' files are also worth checking.
            """.trimIndent(),
        )
        append("\n- ")
        if (codeTools) append("To find related tests, look under `tests` in the reference results. ")
        append("To reproduce what happens when the developer clicks Run, `get_run_configurations` lists existing run configurations and `execute_run_configuration` runs one with its arguments and environment.")
    }

    override val debugging =
        """
            ## When it fails at runtime

            To see values at the point of failure, set a breakpoint with `xdebug_set_breakpoint` and start debugging with `xdebug_start_debugger_session`. Once paused, `xdebug_get_frame_values` shows values in the current frame and `xdebug_evaluate_expression` evaluates an expression. Use `xdebug_control_session` to step into the next call.
        """.trimIndent()

    override val onDemand =
        """
            ## Use when the task calls for it

            - For a database query, `list_database_connections` shows saved IDE connections and `execute_sql_query` runs a query on the selected connection.
            - For Python environment questions, `get_python_environment` identifies the project's interpreter and `configure_python_interpreter` can adjust it.
            - For a custom structural inspection, `generate_psi_tree` shows how the IDE parses the code and `run_inspection_kts` can run the check.
        """.trimIndent()

    override fun ownTools(worker: Boolean): String =
        """
            ## Follow up with your own tools

            Use the IDE findings to carry the investigation forward. If a relationship remains unclear or the findings differ from what actually happens, use your own tools to investigate that question. Choose where to look and how to verify it based on the evidence and the problem the user wants to solve.
        """.trimIndent() + if (worker) "" else " Keep that follow-up within your read-only scope."
}

private object ZH : GuidanceText {
    private val outline = "- 想先纵观一个文件、决定从哪下手，用 `file_outline`。它列出文件里的声明和各自所在的行，类还会标出声明里写明的父类和接口。认准一个方法以后，它的位置可以直接交给引用或调用查询。要判断一段代码的实际行为，先把相关实现读完整。如果几处逻辑共用同一份状态，读完局部仍拿不准它们怎样配合，就通读这个文件。"

    override fun intro(ideaMcp: Boolean, codeTools: Boolean, worker: Boolean): String = buildString {
        append("你正在 JetBrains IDE（IntelliJ IDEA、PyCharm、WebStorm、GoLand、Rider、CLion 等）里工作。")
        append(if (ideaMcp && codeTools) "当前会话接入了两组 MCP 工具。" else "当前会话接入了以下 MCP 工具。")
        if (!worker) append("\n\n你这次只查不改。改不改、怎么改，是用户和负责干活的智能体的事。你的用处是把事实查实。")
        append("\n")
        if (ideaMcp) {
            append(
                if (worker) "\n- `idea` 提供符号查询、重构、代码检查、构建、运行配置、调试器、数据库和格式化等能力，使用的是当前 IDE 的工程环境。"
                else "\n- `idea` 可以查询符号、依赖源码、模块结构和 IDE 诊断。",
            )
        }
        if (codeTools) append("\n- `$CODE_TOOLS_SERVER` 提供 `file_outline`（来自 IDE 的结构视图）和 `find_references`、`call_hierarchy`、`type_hierarchy`（来自 IDE 的索引）。当前语言不受支持时，工具会返回 `language_not_supported`。")
    }

    override fun look(codeTools: Boolean, worker: Boolean): String = buildString {
        append("## 看清代码\n")
        append("\n- 一个名字指的是什么，`search_symbol` 按名字找到候选声明，`get_symbol_info` 看它的类型、签名和文档。")
        if (codeTools) append("\n$outline")
        append("\n- 依赖库实际是怎么实现的，`read_file` 加归档路径，可以读 JAR、node_modules 里的源码和反编译的类。遇到版本差异时，可以查到当前工程实际引用的那份实现。")
        append("\n- 模块之间怎么依赖，用 `get_project_modules` 和 `get_project_dependencies` 看工程结构。用户提到“刚才打开的那个文件”时，`get_all_open_file_paths` 可以列出 IDE 当前打开的文件，帮助缩小范围。")
        if (!codeTools) append("\n- 调用关系可以用 `analyze_calls`。它按完整限定名定位目标，解析能力取决于语言插件。返回 `No callable symbol found` 表示这次查询没有定位到目标方法。")
        if (!worker) append("\n- `lint_files` 可以查看文件里的错误和警告。")
    }

    override fun chain(ideaMcp: Boolean, worker: Boolean): String = buildString {
        if (!ideaMcp) append("## 看清代码\n\n$outline\n\n")
        append(
            """
                ## 摸清链路

                一段代码是什么，还要看谁在依赖它、它得遵守谁的约定。遇到同名符号、接口分派或跨模块引用时，IDE 索引能把这些关系连起来。

                - 谁在依赖它、它又依赖谁，用 `call_hierarchy`。想知道哪些入口会受影响，往上看调用方。想知道这一步把事情交给了谁，往下看被调用方。经由接口或父类方法找到的调用会标明 `via`，可以看出这层关系是怎么连上的。
                - 它重写、实现了谁，又被谁重写、实现，用 `type_hierarchy`。往上看 `supers`，能找到它得遵守的约定。改的是约定本身，往下看 `implementations`，能找到哪些实现也得跟着变。
                - 一个值从哪来、流到哪去，可以对参数、字段或共享状态用 `find_references`，沿 IDE 找到的使用位置看谁在写、谁在读。改了方法返回值的含义，也可以沿调用处看，哪些地方还按旧约定处理结果。

                改动涉及共享行为时，还有谁依赖原来的约定？

                定位符号时，给出文件 `file`、声明所在行 `line` 和符号名 `symbol`。同一行有几个候选时，再加列号 `column`。一次调用失败，先看返回的原因，有时结果里已经指出了声明实际在哪一行。

                查到多少，就说到多少。`truncated` 和 `not_expanded` 表示结果被截断或分支尚未展开。`overrides_incomplete`、`unanalyzed_mentions` 和 `unanalyzed_not_checked` 提醒你留意重写关系和引用的覆盖范围。结论取决于哪一支，就沿那一支补查。
            """.trimIndent(),
        )
    }

    override val whileEditing =
        """
            ## 改的时候

            - 重命名符号时，`rename_refactoring` 按声明身份更新 IDE 识别出的引用。其他作用域里恰好有同名方法时，IDE 能把两者区分开。
            - 工程在 IDE 里配置了代码风格时，`reformat_file` 可以按这套设置格式化文件。
        """.trimIndent()

    override fun verify(codeTools: Boolean): String = buildString {
        append(
            """
                ## 改完以后

                “做完了”是一个需要证据的判断。用户实际使用时会得到什么结果？手里的证据验证到了哪一步？如果只验证了中间一段，就从实际入口核对最终结果。受环境限制时，说清楚已经验证到哪里，哪一步还有待确认。看这次改动要验证什么，IDE 能在相应的环节提供证据。

                - 能不能编译。改动涉及跨文件引用或公共签名时，`build_project` 可以按当前 IDE 工程构建，给出带文件和行号的编译错误。
                - IDE 能查到哪些问题。`lint_files` 可以看文件里的错误和警告，也能查到工程启用的框架规则，例如 Spring 注解、Android 资源和 JPA 映射。共享约定发生变化时，调用方所在的文件也值得一起看。
            """.trimIndent(),
        )
        append("\n- ")
        if (codeTools) append("哪些测试已经涉及这段行为。查用法结果里的 `tests` 会列出找到的相关测试。")
        append("想复现开发者平时点 Run 的情形，`get_run_configurations` 可以查看项目已有的运行配置，`execute_run_configuration` 可以带上对应的参数和环境运行。")
    }

    override val debugging =
        """
            ## 运行时出错

            想看出错那一刻的值，可以用 `xdebug_set_breakpoint` 设断点，用 `xdebug_start_debugger_session` 启动调试。停住以后，`xdebug_get_frame_values` 能看到当前栈帧里的值，`xdebug_evaluate_expression` 可以核对具体表达式。想知道这个值传进下一层以后发生了什么，用 `xdebug_control_session` 单步跟进去。
        """.trimIndent()

    override val onDemand =
        """
            ## 用得上再用

            - 查数据库时，`list_database_connections` 能看到 IDE 已保存的连接，`execute_sql_query` 可以在对应连接上执行查询。
            - 问题跟 Python 环境有关时，`get_python_environment` 能确认当前工程使用的解释器，需要调整时可以用 `configure_python_interpreter`。
            - 想按代码结构查一类问题，`generate_psi_tree` 能看到 IDE 怎样解析这段代码，`run_inspection_kts` 可以据此运行自定义检查。
        """.trimIndent()

    override fun ownTools(worker: Boolean): String =
        """
            ## 用自己的工具继续核实

            IDE 查到的结果可以顺着往下用。如果还有一段关系没查清，或者查到的结果和实际表现对不上，就围绕那个疑点，用你自己的工具继续核实。接下来查哪里、用什么方法，由你根据手里的证据和用户要解决的问题判断。
        """.trimIndent() + if (worker) "" else "继续核实时也保持只读范围。"
}
