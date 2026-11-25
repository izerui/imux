package com.github.izerui.imux.code

import com.github.izerui.imux.mcp.McpJsonRpc
import com.github.izerui.imux.mcp.McpJsonRpc.rpcError
import com.github.izerui.imux.mcp.McpJsonRpc.rpcResult
import com.github.izerui.imux.mcp.McpJsonRpc.toolResult
import com.github.izerui.imux.mcp.McpRpcReply
import com.github.izerui.imux.mcp.text
import com.github.izerui.imux.settings.CODE_TOOLS_SERVER
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

const val CODE_MCP_SERVER = CODE_TOOLS_SERVER

data class CodeToolResult(val value: JsonObject, val failed: Boolean = false)

/** 失败结果：原因代号和一句说明。查询后端、查代码引擎都用这一份，字段不会两边对不上。 */
internal fun codeFailure(reason: String, message: String) = CodeToolResult(JsonObject().apply {
    addProperty("reason", reason)
    addProperty("message", message)
}, failed = true)

data class CodeQuery(
    val file: String,
    val line: Int,
    val column: Int? = null,
    val symbol: String? = null,
    val includeLibraries: Boolean = false,
    val limit: Int = DEFAULT_LIMIT,
) {
    companion object {
        const val DEFAULT_LIMIT = 200
        const val MAX_LIMIT = 1000
    }
}

enum class CallDirection(val id: String) { CALLERS("callers"), CALLEES("callees") }

enum class HierarchyDirection(val id: String) { SUPERS("supers"), IMPLEMENTATIONS("implementations") }

interface CodeToolsBackend {
    suspend fun references(query: CodeQuery): CodeToolResult
    suspend fun callHierarchy(query: CodeQuery, direction: CallDirection, depth: Int): CodeToolResult
    suspend fun typeHierarchy(query: CodeQuery, direction: HierarchyDirection): CodeToolResult

    /** 文件大纲：不定位符号，只要文件和条数上限。 */
    suspend fun fileOutline(file: String, limit: Int): CodeToolResult
}

/**
 * `imux_code` 服务：用 IDE 自己的引用索引查引用和调用链，按文件 + 行列定位符号，
 * 因此对 IDE 能解析的每一种语言都可用（包括 IDEA MCP `analyze_calls` 解析不了名字的 Go 等）。
 *
 * 工具说明里不提别的服务的工具（`analyze_calls`、`search_symbol`）：关掉 IDEA MCP 时智能体照样读得到这些说明，
 * 提到没挂的工具它会去调。和引导词的分工：结果里每个标记是什么意思写在这里（用到时才读），
 * 什么时候用、为什么用写在引导词里。
 */
class CodeMcpProtocol(private val backend: CodeToolsBackend, private val scope: CoroutineScope) {
    fun receive(root: JsonObject): McpRpcReply = McpJsonRpc.receive(root, CODE_MCP_SERVER, ::tools, call = ::call)

    private fun call(id: JsonElement, name: String, args: JsonObject): McpRpcReply {
        fun error(text: String) = McpJsonRpc.reply(rpcError(id, -32602, text))
        if (name == FILE_OUTLINE) {
            args.keySet().firstOrNull { it !in OUTLINE_ARGS }?.let { return error("Unsupported argument: $it") }
            val file = args.text("file")?.takeIf { it.isNotBlank() } ?: return error("file must be a non-empty string")
            val limit = if (args.has("max_results")) {
                args.positiveInt("max_results")?.coerceAtMost(CodeQuery.MAX_LIMIT)
                    ?: return error("max_results must be a positive integer")
            } else CodeQuery.MAX_LIMIT
            return async(id) { backend.fileOutline(file, limit) }
        }
        val allowed = when (name) {
            FIND_REFERENCES -> COMMON_ARGS
            CALL_HIERARCHY -> COMMON_ARGS + setOf("direction", "depth")
            TYPE_HIERARCHY -> COMMON_ARGS + setOf("direction")
            else -> return error("Unknown tool")
        }
        args.keySet().firstOrNull { it !in allowed }?.let { return error("Unsupported argument: $it") }
        val file = args.text("file")?.takeIf { it.isNotBlank() } ?: return error("file must be a non-empty string")
        val line = args.positiveInt("line") ?: return error("line must be a positive integer")
        val column = if (args.has("column")) args.positiveInt("column") ?: return error("column must be a positive integer") else null
        val symbol = if (args.has("symbol")) args.text("symbol")?.takeIf { it.isNotBlank() } ?: return error("symbol must be a non-empty string") else null
        val includeLibraries = if (args.has("include_libraries")) args.bool("include_libraries") ?: return error("include_libraries must be a boolean") else false
        val limit = if (args.has("max_results")) args.positiveInt("max_results")?.coerceAtMost(CodeQuery.MAX_LIMIT) ?: return error("max_results must be a positive integer") else CodeQuery.DEFAULT_LIMIT
        val query = CodeQuery(file, line, column, symbol, includeLibraries, limit)
        if (name == FIND_REFERENCES) return async(id) { backend.references(query) }
        if (name == TYPE_HIERARCHY) {
            val hierarchy = when (args.text("direction")) {
                "supers" -> HierarchyDirection.SUPERS
                "implementations" -> HierarchyDirection.IMPLEMENTATIONS
                else -> return error("direction must be \"supers\" or \"implementations\"")
            }
            return async(id) { backend.typeHierarchy(query, hierarchy) }
        }
        val direction = when (args.text("direction")) {
            "callers" -> CallDirection.CALLERS
            "callees" -> CallDirection.CALLEES
            else -> return error("direction must be \"callers\" or \"callees\"")
        }
        val depth = if (args.has("depth")) args.positiveInt("depth")?.coerceAtMost(MAX_DEPTH) ?: return error("depth must be a positive integer") else defaultDepth(direction)
        return async(id) { backend.callHierarchy(query, direction, depth) }
    }

    private fun async(id: JsonElement, block: suspend () -> CodeToolResult) = McpRpcReply(200, scope.async {
        val result = block()
        rpcResult(id, toolResult(result.value, result.failed))
    })

    companion object {
        const val FIND_REFERENCES = "find_references"
        const val CALL_HIERARCHY = "call_hierarchy"
        const val TYPE_HIERARCHY = "type_hierarchy"
        const val FILE_OUTLINE = "file_outline"
        const val MAX_DEPTH = 6
        private val COMMON_ARGS = setOf("file", "line", "column", "symbol", "include_libraries", "max_results")
        private val OUTLINE_ARGS = setOf("file", "max_results")

        fun defaultDepth(direction: CallDirection) = if (direction == CallDirection.CALLERS) 3 else 1

        private const val POSITION_NOTE =
            "Locate the symbol by file and line (1-based): give `symbol` (its name as written on that line) or `column`; " +
                "the line can be a declaration or any line that uses the symbol. Works for the languages the IDE understands " +
                "(resolved by position, so it needs no qualified name) and answers `language_not_supported` for the ones it does not. " +
                "Positions from search results, compiler errors, or grep can be passed directly. " +
                "`unresolved` means the name is on that line but the IDE cannot tell what it refers to — usually a library name whose SDK or dependency is not set up in the IDE. " +
                "Entries in files the IDE treats as test code are marked `in_test` (test source roots, plus language rules such as Go `_test.go` and JS `.spec`/`.test`; " +
                "Python has no such rule, so its tests are marked only when their directory is marked as a test source root): unmarked does not prove it is production code. " +
                "Results cover what the IDE can resolve statically: uses through reflection, names built from strings, or generated code are not in any result, so an empty result is not proof that nothing uses it. " +
                "`overrides_incomplete: true` means the search for what a method overrides stopped early because too many declarations share its name: " +
                "the overrides, and calls made through them, may be incomplete — check the enclosing type's interfaces yourself."

        /** 框架隐式使用：只有接入了的插件能认出来，没标不能当作没人用。 */
        private const val IMPLICIT_NOTE =
            "`implicit_usage: true` means a framework or the runtime uses it without any call in the code (Spring beans and their scheduled, event-listener and `@Bean` methods, JPA fields, Vue/React/Angular members, Next.js exports such as `metadata`; " +
                "in IDEs with Java support also test methods, `main`, and other entry points, for Kotlin code too): do not treat it as unused. " +
                "Only what the IDE recognizes is covered — nothing for Python or Go, and not every entry point (Spring web request handlers and a Next.js page's default export are not marked) — so its absence proves nothing. "

        /** 大文件不建结构视图（见 CodeNavigator 的 LARGE_FILE_BYTES），「在哪个函数里」改用轻量办法。 */
        private const val LARGE_FILE_NOTE =
            "In files over 1 MB (usually bundled or generated code) the enclosing function is found by a lighter method: it may be missing, e.g. in minified code. "

        /** 只有查用法、查调用方会补查「分析不了的文件」；继承、查依赖不会，说明里不能写。 */
        private const val UNANALYZED_NOTE =
            "Files in languages the IDE cannot analyze are listed separately as `unanalyzed_mentions`: check those yourself. "

        fun tools(): JsonArray = JsonArray().apply {
            add(tool(
                FIND_REFERENCES,
                "Find what actually refers to a function, method, type, field, variable, or constant, through the IDE's own reference index — " +
                    "the same engine as the IDE's Find Usages. Text search only matches names that look alike; this resolves each usage to the symbol it means. " +
                    "Use it to see everything that depends on a symbol — before changing it, and when reviewing, reading, or debugging code that involves it. " +
                    "Returns each usage with file, line, column, the line text, and the " +
                    "function or class it sits in. For a method that overrides or implements another, `overrides` lists what it overrides and " +
                    "`references_via_overridden` lists usages made through that interface or base type (e.g. `greeter.greet()` where greeter is the interface) — " +
                    "they can run this implementation too; `overridden_by` lists what overrides or implements it. " +
                    "An import that renames it (`from m import f as g`) is followed: uses through the new name are included and marked `via_alias`. " +
                    "For variables, fields, and properties each usage carries `access`: read, write, or read_write (`x += 1`), judged by where the name sits — " +
                    "`items.add(x)` is a read of items, so read does not mean the object it points to was left unchanged. " +
                    "$IMPLICIT_NOTE" +
                    "`tests` lists the tests the IDE pairs with it by naming convention (as in its Go to Test), including tests that never call it directly; " +
                    "when it is itself a test, `tested_code` lists what it tests. Neither is given for variables, fields, or parameters; `tests_omitted` counts entries beyond the first 20. $LARGE_FILE_NOTE$UNANALYZED_NOTE$POSITION_NOTE",
            ))
            add(tool(
                CALL_HIERARCHY,
                "Build the call chain of a function or method through the IDE's own reference index: who relies on it and what it relies on. " +
                    "Use it before changing it, and when reviewing, reading, or debugging code. " +
                    "direction=\"callers\" walks up who calls it, who calls those, and so on (default depth 3), grouping call sites under each caller. " +
                    "Calls made through an interface or base method it overrides are included and marked `via`; calls through a renaming import (`import f as g`) are included and marked `via_alias`. Each site is marked usage=call or reference; " +
                    "places that only pass it as a value (method references, callbacks, event handlers) are listed under `referenced_without_calling` and not walked further. " +
                    "A caller already shown elsewhere in the tree is marked expanded_above instead of repeated. " +
                    "The queried function, and any caller whose chain ends with no callers of its own, is marked `implicit_usage` when a framework or the runtime uses it without any call in the code, so the chain may end there and still be in use. " +
                    "direction=\"callees\" lists what its body uses — functions, methods, types, fields (default depth 1; deeper levels expand project functions; a call to itself is marked recursive). " +
                    "Every node carries file, line, and column, so any node can be queried again. " +
                    "For callers: $UNANALYZED_NOTE$LARGE_FILE_NOTE" +
                    "A node marked `unanalyzed_not_checked` or `not_expanded` was not checked or walked further because the query ran out of time or max_results: " +
                    "query that node again if it matters. $POSITION_NOTE",
            ) {
                add("direction", JsonObject().apply {
                    addProperty("type", "string")
                    add("enum", JsonArray().apply { add("callers"); add("callees") })
                    addProperty("description", "callers: who calls it; callees: what it calls and uses.")
                })
                add("depth", JsonObject().apply {
                    addProperty("type", "integer"); addProperty("minimum", 1); addProperty("maximum", MAX_DEPTH)
                    addProperty("description", "Levels to expand. Default 3 for callers, 1 for callees.")
                })
            })
            add(tool(
                TYPE_HIERARCHY,
                "Show the inheritance around a method or type through the IDE's own index: whose contract it has to keep, and who has to change with it. " +
                    "direction=\"supers\" lists what it overrides, implements, or extends (base methods, interface methods, base classes, interfaces it satisfies); " +
                    "direction=\"implementations\" lists what overrides, implements, or extends it (overriding methods, implementing classes, subclasses). " +
                    "Use it before changing a method signature or a type (overriding and implementing members must change together), and when reviewing or analyzing code to see which implementation can run. $POSITION_NOTE",
            ) {
                add("direction", JsonObject().apply {
                    addProperty("type", "string")
                    add("enum", JsonArray().apply { add("supers"); add("implementations") })
                    addProperty("description", "supers: what it overrides, implements, or extends; implementations: what overrides, implements, or extends it.")
                })
            })
            add(outlineTool())
        }

        /**
         * 文件大纲：先纵观一个文件、决定从哪下手的第一层。它不替代阅读——要改、要下结论的代码照样完整读（用户定的），
 * 说明里写明这一点，免得智能体看完大纲就跳过细读。和另外三个工具不同，它不定位符号，
         * 所以参数只有文件和条数上限，不带位置说明和「分析不了的文件」那一栏。
         */
        private fun outlineTool() = JsonObject().apply {
            addProperty("name", FILE_OUTLINE)
            addProperty(
                "description",
                "Get an overview of a file from the IDE's structure view: its declarations as a tree (classes, functions, fields; for other files headings, keys, or tags), " +
                    "each with its display name (with signature where the language shows one), kind, the line and column of its name, and its end line. " +
                    "Use it to see how a file is laid out and decide where to look. It is an overview, not a substitute for reading: read in full whatever you will change or draw a conclusion about. " +
                    "To query a node with the other tools, pass this file, its `line` and `column`, and its `symbol` (the plain name, when the display name has a signature) or its name. " +
                    "Types list in `supers` the parents written in their declaration, with file and line (`in_project: false` means a library), so you know which file to outline next; " +
                    "inherited members are not listed — use `type_hierarchy` for the full inheritance. " +
                    "`non_public: true` marks non-public members when `visibility_known` is true; otherwise the IDE gives no visibility, so unmarked does not mean public. " +
                    "When nodes exceed max_results, top levels come first; `top_level_omitted` and `children_omitted` say what was left out, and `total_nodes` the size " +
                    "(a lower bound when `total_nodes_is_lower_bound` is set). Local variables in function bodies are left out; for flat files such as .properties, text search is often more direct. " +
                    "Needs no index: while the IDE is indexing it still works, without parents (`supers_skipped`). Answers `language_not_supported` when the file type has no structure view.",
            )
            add("inputSchema", JsonObject().apply {
                addProperty("type", "object"); addProperty("additionalProperties", false)
                add("properties", JsonObject().apply {
                    add("file", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "File path, relative to the project root or absolute.")
                    })
                    add("max_results", JsonObject().apply {
                        addProperty("type", "integer"); addProperty("minimum", 1); addProperty("maximum", CodeQuery.MAX_LIMIT)
                        addProperty("description", "Upper bound on returned nodes. Default ${CodeQuery.MAX_LIMIT}; nodes beyond it set truncated=true.")
                    })
                })
                add("required", JsonArray().apply { add("file") })
            })
            addToolHints()
        }

        /**
         * 四个工具共有的提示：只读；并要求 Claude Code 直接加载，不藏到工具搜索后面。
         * Claude Code 默认把外接工具一律延迟加载，模型要先搜索才看得到说明：实测里 Claude 因此跳过这几个工具，
         * 或用文本搜索找到调用处就先改了、事后才查。`anthropic/alwaysLoad` 是它认的工具级标记（用户在配置里
         * 对整个服务写 `alwaysLoad: false` 时仍以用户为准）；Codex、Pi 不认，本来就直接加载，不受影响。
         * 只标这四个：说明加起来不长，IDEA MCP 那五十多个照旧延迟。
         */
        private fun JsonObject.addToolHints() {
            add("annotations", JsonObject().apply { addProperty("readOnlyHint", true) })
            add("_meta", JsonObject().apply { addProperty("anthropic/alwaysLoad", true) })
        }

        private fun tool(name: String, description: String, extra: JsonObject.() -> Unit = {}) = JsonObject().apply {
            addProperty("name", name)
            addProperty("description", description)
            add("inputSchema", JsonObject().apply {
                addProperty("type", "object"); addProperty("additionalProperties", false)
                add("properties", JsonObject().apply {
                    add("file", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "File path, relative to the project root or absolute.")
                    })
                    add("line", JsonObject().apply {
                        addProperty("type", "integer"); addProperty("minimum", 1)
                        addProperty("description", "1-based line where the symbol is declared or used.")
                    })
                    add("symbol", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "Name of the symbol as written on that line. Preferred over column.")
                    })
                    add("column", JsonObject().apply {
                        addProperty("type", "integer"); addProperty("minimum", 1)
                        addProperty("description", "1-based column of the symbol name. Used alone when symbol is omitted; together with symbol it picks among several occurrences of that name on the line (e.g. two overloads), nearest first.")
                    })
                    add("include_libraries", JsonObject().apply {
                        addProperty("type", "boolean")
                        addProperty("description", "Also include dependencies and SDKs: usages inside them, and library callees (by default only counted in library_callees_omitted). Default false: project sources only.")
                    })
                    add("max_results", JsonObject().apply {
                        addProperty("type", "integer"); addProperty("minimum", 1); addProperty("maximum", CodeQuery.MAX_LIMIT)
                        addProperty("description", "Upper bound on returned entries. Default ${CodeQuery.DEFAULT_LIMIT}; results beyond it set truncated=true.")
                    })
                    extra()
                })
                add("required", JsonArray().apply {
                    add("file"); add("line")
                    if (name == CALL_HIERARCHY || name == TYPE_HIERARCHY) add("direction")
                })
            })
            addToolHints()
        }
    }
}

private fun JsonObject.positiveInt(key: String): Int? {
    val value = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber } ?: return null
    val number = value.asDouble
    return if (number >= 1 && number == Math.floor(number) && number <= Int.MAX_VALUE) number.toInt() else null
}

private fun JsonObject.bool(key: String): Boolean? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
