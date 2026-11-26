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
 * - 开头先讲为什么（读文本只知道代码写了什么，IDE 知道每个名字指的是谁），再给一条原则（IDE 能回答的事实就问它，
 *   不要从文字里猜）；后面每一节都是这条原则在某个时刻的用法——流程没写到的场景，智能体靠原则自己判断；
 * - 每条做法带上理由（为什么重命名不用文本替换、为什么调试不加 print）：知道理由才能在没写到的情形里做对；
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
    /** [codeTools] 时多一句：先跑查用法给出的对应测试（`tests` 只在挂了查代码工具时才有）。 */
    fun verify(codeTools: Boolean): String
    val debugging: String

    /** 特定场景才用得上的能力，和每次都该用的分开放，免得冷门的冲淡常用的。 */
    val onDemand: String
    fun ownTools(worker: Boolean): String
}

private object EN : GuidanceText {
    override fun intro(ideaMcp: Boolean, codeTools: Boolean, worker: Boolean): String = buildString {
        append("You are working inside a JetBrains IDE (IntelliJ IDEA, PyCharm, WebStorm, GoLand, Rider, CLion, or others). ")
        append("Besides your own tools, you have MCP tools backed by this IDE:")
        if (ideaMcp) {
            append(
                if (worker) "\n- `idea` (IDEA MCP): refactoring, inspections, builds, run configurations, the debugger, databases, formatting, and symbol lookup."
                else "\n- `idea` (IDEA MCP): symbol lookup, inspections, library sources, and the module graph, among others.",
            )
        }
        if (codeTools) {
            append(
                "\n- `$CODE_TOOLS_SERVER`: `file_outline` (a file's structure, from the IDE's structure view) and `find_references`, `call_hierarchy`, `type_hierarchy` (from the IDE's own index). " +
                    "They work for the languages the IDE understands and answer `language_not_supported` for the ones it does not.",
            )
        }
        append(
            "\n\nReading text only shows what the code says; the IDE has resolved the whole project and knows what each name refers to and where it is used. " +
                "So one principle covers everything below: when the IDE can answer a question of fact, ask it instead of inferring from text — ",
        )
        append(if (worker) "gather evidence before you conclude or change anything." else "gather evidence before you conclude.")
        if (!worker) {
            append(
                "\n\nThis time you only investigate. Deciding what to change and making the change belong to the user and the agent doing the work; " +
                    "what you contribute is facts that have been checked. The test for any call: would the project, the repository, or an outside system be different afterwards? " +
                    "If so, do not make it (renaming, formatting, builds, run configurations, the debugger, database writes).",
            )
        }
    }

    override fun look(codeTools: Boolean, worker: Boolean): String = buildString {
        append(if (worker) "## See the code clearly\n\n" else "## Checking facts with the IDE\n\n")
        append("- What a name refers to: `search_symbol` finds it by name; `get_symbol_info` shows its declaration, type, and docs.\n")
        if (!codeTools) {
            append(
                "- Who calls a function and what it calls: `analyze_calls`. It resolves symbols by fully qualified name, which some language plugins (e.g. Go) do not provide; " +
                    "when it answers `No callable symbol found`, do not guess names — use the LSP tool's `incomingCalls` / `findReferences`, or text search.\n",
            )
        }
        if (!worker) append("- Errors and warnings in a file, as the IDE sees them: `lint_files`.\n")
        append("- How a dependency actually behaves: `read_file` with the archive path reads sources inside JARs or node_modules and decompiled classes — do not infer a library's behavior from memory.\n")
        append("- How modules depend on each other: `get_project_modules` / `get_project_dependencies`.\n")
        append("- Which files the developer is editing right now: `get_all_open_file_paths`.\n")
        append("\nFor quick go-to-definition and hover lookups, your own LSP tool is fine when it is active for the current language.")
    }

    override fun chain(ideaMcp: Boolean, worker: Boolean): String = buildString {
        append(
            """
            ## Map the chain

            What a piece of code is depends not only on what it says but on who relies on it and whose contract it has to keep. Those relations are not written in its own file, and text search only recognizes names that look alike: unrelated functions with the same name get mixed in, and a call made through an interface or base type cannot be told apart from one that never reaches it. So whenever a conclusion depends on how code is connected — changing code, reviewing a change, reading or explaining code, tracking down a bug, or answering "who uses this, and what breaks if it changes" — get the relations from the IDE first:
            - Who relies on it and what it relies on: `call_hierarchy`, in both directions; calls made through an interface or base method are included and marked `via`.
            - What it overrides or implements (whose contract it must keep) and what overrides or implements it (who must change with it): `type_hierarchy`, in both directions (`supers` and `implementations`).
            - Where a value comes from and where it goes: `find_references` on the parameter, field, return value, or shared state, including other modules.

            To get an overview of a file and decide where to start, use `file_outline`: it lists the file's declarations with the line each one is on, and those lines can be passed straight to the three tools above. For a class it also gives the parents written in its declaration and where they are, so you know which file to outline next. It is an overview, not a substitute for reading: whatever you will change or draw a conclusion about, still read in full, and when you are unsure how much is involved, read the whole file.
            """.trimIndent(),
        )
        if (worker) {
            append("\n\n")
            append(
                """
                Before editing, spend effort in proportion to how far the change reaches:
                - A purely local change (the signature and the behavior others rely on stay the same): reading the function itself in full is enough.
                - A change that touches a signature, shared behavior, or anything used outside the current function:
                  1. Find each symbol you will change, read its declaration, and read the full body — not just the lines around a search hit.
                  2. Trace the chain with the three checks above until you reach a boundary the change cannot affect: an entry point, a public API, or code whose behavior stays the same.
                  3. Then edit, and update every affected caller, override, and implementation in the same change. If the chain turns out larger than expected, say so instead of quietly changing only part of it.
                """.trimIndent(),
            )
        }
        append(
            if (worker) "\n\nWhen reviewing a change, trace the same chain for each changed symbol: callers, overrides, and implementations that should have changed but did not are the problems to report."
            else "\n\nWhen reviewing a change, or weighing one that is only being discussed, trace this chain for each symbol involved: callers, overrides, and implementations that should have changed but did not are the problems to report, and the ones that would have to change are the cost to state up front.",
        )
        append("\n\n")
        append(
            """
            Claim only as much as you found — not finding something is not the same as it not being there:
            - A result that carries a not-complete marker (`unanalyzed_mentions`, `overrides_incomplete`, `truncated`, `not_expanded`, `unanalyzed_not_checked`) is not the whole chain yet: follow up as the tool description says before treating it as complete.
            - If `$CODE_TOOLS_SERVER` answers `language_not_supported` or is unavailable, take another route and let your confidence follow the source: first the language server (the LSP tool's `findReferences` / `incomingCalls` / `outgoingCalls`, or its command line run from the project root); only with no language server either, text search — read each hit in context to confirm it is a real use, and say in your reply that the chain came from text search.
            - A failed call is telling you the question is wrong or the route is closed: read the reason it returns before deciding what to do next, and do not retry it with different spellings.

            Locating a symbol: give `file` + `line` plus its `symbol` name, and add `column` when that line has more than one symbol of the name (overloads, for example). Positions from search results, compiler errors, grep, or `file_outline` can be passed straight in.
            """.trimIndent(),
        )
        if (ideaMcp) {
            append("\nFor call relations use `call_hierarchy` rather than IDEA MCP's `analyze_calls`: the latter resolves symbols by fully qualified name, which some language plugins (e.g. Go) do not provide, and then answers `No callable symbol found`.")
        }
    }

    override val whileEditing =
        """
        ## While editing

        - Rename any symbol (variable, method, class, and so on) with `rename_refactoring`: it changes what the name refers to, while text find-and-replace changes what looks alike and breaks same-name symbols in other scopes.
        - Format with `reformat_file`: it applies this project's own code style, not an external formatter's.
        """.trimIndent()

    override fun verify(codeTools: Boolean) =
        """
        ## After editing: prove it is right before saying it is done

        "Done" is a claim that needs evidence. Gather it in this order, and say plainly which step you did not run:
        1. It compiles: when the change involves compiled code, cross-file references, or public signatures, run `build_project`, which reports compilation errors with file and line. Skip it when the project has no effective IDE build step.
        2. It introduced no new problems: run `lint_files` on every file you changed; it also checks framework rules the compiler ignores (Spring annotations, Android resources, JPA mappings, and so on). When the change touched a signature or shared behavior, also lint the files of the callers it affects: in languages without a compile step (Python, JavaScript), a caller left unchanged shows up only there.
        3. Behavior still holds: before running tests, check `get_run_configurations`; when one matches, use `execute_run_configuration` so the environment is the one the developer gets from Run. With no match, use the project's own test command.
        """.trimIndent() + if (codeTools) " Run first the tests `find_references` lists under `tests` for what you changed; run the full suite only after those pass, and only when the change reaches further." else ""

    override val debugging =
        """
        ## When it fails at runtime: look at the scene instead of guessing

        Adding print statements means guessing where the problem is and then editing code to test the guess; a breakpoint shows you the values at the moment it goes wrong. Prefer interactive debugging: set breakpoints with `xdebug_set_breakpoint`, start with `xdebug_start_debugger_session`, inspect with `xdebug_get_frame_values` and `xdebug_evaluate_expression`, and step with `xdebug_control_session`. One run can test several hypotheses without edit-rebuild-rerun cycles.
        """.trimIndent()

    override val onDemand =
        """
        ## Use when the task calls for it

        - Databases: `list_database_connections` → `execute_sql_query`, using the credentials the IDE already holds.
        - Python interpreters (venv, Conda, system): `get_python_environment` / `configure_python_interpreter`.
        - A custom check over the code's structure: `generate_psi_tree` + `run_inspection_kts`.
        """.trimIndent()

    override fun ownTools(worker: Boolean): String =
        if (worker) {
            """
            ## Use your own tools for

            Text search (grep/rg), reading, writing and editing files, directory listings, git, and shell commands: there is nothing semantic to ask here, so doing them directly is faster than an IDE round-trip.
            """.trimIndent()
        } else {
            """
            ## Use your own tools for

            Text search (grep/rg), reading files, directory listings, and read-only git and shell commands (log, diff, status and the like): there is nothing semantic to ask here, so doing them directly is faster than an IDE round-trip. The same test applies to them: nothing that leaves things different afterwards.
            """.trimIndent()
        }
}

private object ZH : GuidanceText {
    override fun intro(ideaMcp: Boolean, codeTools: Boolean, worker: Boolean): String = buildString {
        append("你正在 JetBrains IDE（IntelliJ IDEA、PyCharm、WebStorm、GoLand、Rider、CLion 等）里工作。除了你自己的工具，还有这个 IDE 提供的 MCP 工具：")
        if (ideaMcp) {
            append(
                if (worker) "\n- `idea`（IDEA MCP）：重构、代码检查、构建、运行配置、调试器、数据库、格式化和符号查询。"
                else "\n- `idea`（IDEA MCP）：符号查询、代码检查、依赖库源码、模块结构等。",
            )
        }
        if (codeTools) {
            append("\n- `$CODE_TOOLS_SERVER`：`file_outline`（文件结构，来自 IDE 的结构视图）和 `find_references`、`call_hierarchy`、`type_hierarchy`（走 IDE 自己的索引）；IDE 能解析的语言都能用，解析不了会明确返回 `language_not_supported`。")
        }
        append("\n\n读文本只能看到代码写了什么；IDE 解析过整个项目，知道每个名字指的是谁、哪里用到了它。所以下面的内容都出自一条原则：IDE 能回答的事实就问它，不要从文字里猜——")
        append(if (worker) "下结论、动手改之前先取证。" else "下结论之前先取证。")
        if (!worker) {
            append(
                "\n\n你这次只查不改。改不改、怎么改，是用户和负责干活的智能体的事；你的用处是把事实查实。" +
                    "判断一次调用该不该做，只看一条：做完之后，项目、仓库或外部系统会不会和之前不一样。会，就不做（重命名、格式化、构建、运行配置、调试器、写数据库都属于这一类）。",
            )
        }
    }

    override fun look(codeTools: Boolean, worker: Boolean): String = buildString {
        append(if (worker) "## 看清代码\n\n" else "## 用 IDE 查证事实\n\n")
        append("- 一个名字指的是什么：`search_symbol` 按名字找到它，`get_symbol_info` 看它的声明、类型和文档。\n")
        if (!codeTools) {
            append(
                "- 谁调用了某个函数、它又调用了谁：`analyze_calls`。它按完整限定名解析符号，有些语言插件（如 Go）不提供这项换算；" +
                    "返回 `No callable symbol found` 时不要猜名字，改用 LSP 工具的 `incomingCalls` / `findReferences`，或文本搜索。\n",
            )
        }
        if (!worker) append("- IDE 眼里某个文件有哪些错误和警告：`lint_files`。\n")
        append("- 依赖库实际是怎么实现的：`read_file` 加归档路径，可以读 JAR、node_modules 里的源码和反编译的类——不要凭印象推断库的行为。\n")
        append("- 模块之间怎么依赖：`get_project_modules` / `get_project_dependencies`。\n")
        append("- 开发者此刻正在编辑哪些文件：`get_all_open_file_paths`。\n")
        append("\n快速跳转定义、看悬停信息，你自己的 LSP 工具对当前语言可用时用它就行。")
    }

    override fun chain(ideaMcp: Boolean, worker: Boolean): String = buildString {
        append(
            """
            ## 摸清链路

            一段代码是什么，不只看它自己写了什么，还看谁在依赖它、它得遵守谁的约定。这些关系不写在它自己的文件里，文本搜索又只认得长得一样的名字：同名的无关函数会混进来，经由接口、父类发生的调用也分不出是不是落到它头上。所以凡是结论取决于代码之间怎么连着的——改代码、审查改动、阅读或讲解代码、排查 bug、回答「谁在用它、改了会影响哪里」——都先向 IDE 把关系查出来：
            - 谁在依赖它、它又依赖谁：`call_hierarchy`，两个方向都看；经由接口或父类方法的调用也会列出，并标明 `via`。
            - 它重写、实现了谁（它得遵守谁的约定），又被谁重写、实现（谁得跟着它变）：`type_hierarchy`，上下两个方向（`supers` 和 `implementations`）。
            - 一个值从哪来、流到哪去：对参数、字段、返回值、共享状态用 `find_references`，包括其它模块。

            想先纵观一个文件、决定从哪下手，用 `file_outline`：它列出文件里的声明和各自所在的行，行号可以直接交给上面三个工具；类还会标出声明里写明的父类、接口及其位置，接下来该去看哪个文件就清楚了。它只是总览，不替代阅读：要改的、要下结论的地方，照样把相关代码完整读完；拿不准牵涉多大时，就读全文件。
            """.trimIndent(),
        )
        if (worker) {
            append("\n\n")
            append(
                """
                改代码之前，力气花在和改动的波及面相称的地方：
                - 纯局部的改动（签名和别处依赖的行为都不变）：把这个函数完整读完就够。
                - 改动涉及签名、共享行为或当前函数之外用到的东西：
                  1. 找到要改的每个符号，看它的声明，并把方法体完整读完——不要只看搜索命中的那几行。
                  2. 按上面三项把链路查清，一路追到改动影响不到的边界为止：入口、公开 API，或行为保持不变的代码。
                  3. 摸清之后再动手，并在同一次改动里把受影响的调用方、重写和实现一并改掉。链路比预想的大时如实说明，不要悄悄只改其中一部分。
                """.trimIndent(),
            )
        }
        append(
            if (worker) "\n\n审查改动时，对每个改过的符号查同样的链路：该跟着改却没改的调用方、重写和实现，就是要指出的问题。"
            else "\n\n审查一处改动、或者评估一个还在讨论的改动时，对涉及的每个符号查这条链路：该跟着改却没改的调用方、重写和实现，就是要指出的问题；将来得跟着改的，就是要提前说清的代价。",
        )
        append("\n\n")
        append(
            """
            查到多少，就说到多少——没查到不等于没有：
            - 结果里带「没查全」的标记（`unanalyzed_mentions`、`overrides_incomplete`、`truncated`、`not_expanded`、`unanalyzed_not_checked`）时，这条链路还不完整：按工具说明把缺的部分补查完，才能当作完整链路。
            - `$CODE_TOOLS_SERVER` 返回 `language_not_supported` 或不可用时，换一条路查，结论的把握跟着来源走：先用语言服务器（LSP 工具的 `findReferences` / `incomingCalls` / `outgoingCalls`，或在项目根目录运行它的命令行）；这门语言也没有语言服务器，才用文本搜索——逐个打开命中处读上下文，确认是真实用法，并在回复里说明这条链路是靠文本搜索得出的。
            - 一次调用失败，是在告诉你这个问法不对或这条路不通：先读它返回的原因再决定下一步，不要换个写法反复重试。

            定位符号：给 `file` + `line`，再加符号名 `symbol`；同一行有几个同名符号时（比如重载）再加上 `column`。搜索结果、编译错误、grep 或 `file_outline` 给出的位置可以直接传入。
            """.trimIndent(),
        )
        if (ideaMcp) {
            append("\n查调用关系用 `call_hierarchy`，不用 IDEA MCP 的 `analyze_calls`：后者按完整限定名解析符号，有些语言插件（如 Go）不提供这项换算，这时它只会返回 `No callable symbol found`。")
        }
    }

    override val whileEditing =
        """
        ## 改的时候

        - 重命名任何符号（变量、方法、类……）用 `rename_refactoring`：它按「指的是谁」来改，文本查找替换按「长得像」来改，会误伤别的作用域里的同名符号。
        - 格式化用 `reformat_file`：用的是这个项目自己的代码风格，不是外部格式化工具的。
        """.trimIndent()

    override fun verify(codeTools: Boolean) =
        """
        ## 改完以后：证明它是对的，再说做完了

        「做完了」是一个需要证据的判断。按下面的顺序取证，哪一步没做就如实说没做：
        1. 能编译：改动涉及编译型代码、跨文件引用或公共签名时运行 `build_project`，它给出带文件和行号的编译错误。项目没有有效的 IDE 构建步骤就跳过。
        2. 没引入新问题：对每个改过的文件运行 `lint_files`，编译器不管的框架规则（Spring 注解、Android 资源、JPA 映射等）它也查。改动涉及签名或共享行为时，受它影响的调用方所在文件也一并检查：没有编译步骤的语言（Python、JavaScript）里，漏改的调用方只有这样才露得出来。
        3. 行为没坏：跑测试前先看 `get_run_configurations`，有匹配的就用 `execute_run_configuration`，环境和开发者点 Run 时一致；没有匹配的就用项目自己的测试命令。
        """.trimIndent() + if (codeTools) "先跑查用法结果里 `tests` 列出的、和改动相关的测试；它们通过后，改动波及更广时再跑全量。" else ""

    override val debugging =
        """
        ## 运行时出错：看现场，不要猜

        加 print 是先猜问题在哪、再改代码去验证这个猜测；断点让你直接看到出错那一刻的值。优先交互式调试：`xdebug_set_breakpoint` 设断点，`xdebug_start_debugger_session` 启动，`xdebug_get_frame_values` 和 `xdebug_evaluate_expression` 查看，`xdebug_control_session` 单步。一次运行能验证好几个假设，不用反复改、编译、重跑。
        """.trimIndent()

    override val onDemand =
        """
        ## 用得上再用

        - 查数据库：`list_database_connections` → `execute_sql_query`，用的是 IDE 已经保存的凭据。
        - Python 解释器（venv、Conda、系统）：`get_python_environment` / `configure_python_interpreter`。
        - 按代码结构做自定义检查：`generate_psi_tree` + `run_inspection_kts`。
        """.trimIndent()

    override fun ownTools(worker: Boolean): String =
        if (worker) {
            """
            ## 这些用你自己的工具

            文本搜索（grep/rg）、读写和编辑文件、列目录、git、shell 命令：这些事没有语义可问，直接做比绕经 IDE 更快。
            """.trimIndent()
        } else {
            """
            ## 这些用你自己的工具

            文本搜索（grep/rg）、读文件、列目录，以及只读的 git 和 shell 命令（log、diff、status 这类）：这些事没有语义可问，直接做比绕经 IDE 更快。它们同样守上面那一条：做完之后不能有任何东西变了样。
            """.trimIndent()
        }
}
