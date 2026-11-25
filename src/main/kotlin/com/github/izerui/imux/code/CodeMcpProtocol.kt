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
}

/**
 * `imux_code` 服务：用 IDE 自己的引用索引查引用和调用链，按文件 + 行列定位符号，
 * 因此对 IDE 能解析的每一种语言都可用（包括 IDEA MCP `analyze_calls` 解析不了名字的 Go 等）。
 */
class CodeMcpProtocol(private val backend: CodeToolsBackend, private val scope: CoroutineScope) {
    fun receive(root: JsonObject): McpRpcReply = McpJsonRpc.receive(root, CODE_MCP_SERVER, ::tools, call = ::call)

    private fun call(id: JsonElement, name: String, args: JsonObject): McpRpcReply {
        fun error(text: String) = McpJsonRpc.reply(rpcError(id, -32602, text))
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
        const val MAX_DEPTH = 6
        private val COMMON_ARGS = setOf("file", "line", "column", "symbol", "include_libraries", "max_results")

        fun defaultDepth(direction: CallDirection) = if (direction == CallDirection.CALLERS) 3 else 1

        private const val POSITION_NOTE =
            "Locate the symbol by file and line (1-based): give `symbol` (its name as written on that line) or `column`; " +
                "the line can be a declaration or any line that uses the symbol. Works for every language the IDE understands, " +
                "including ones where IDEA MCP `analyze_calls` reports `No callable symbol found` (e.g. Go). " +
                "Positions from `search_symbol`, compiler errors, or grep can be passed directly. " +
                "`overrides_incomplete: true` means the search for what a method overrides stopped early because too many declarations share its name: " +
                "the overrides, and calls made through them, may be incomplete — check the enclosing type's interfaces yourself."

        /** 只有查用法、查调用方会补查「分析不了的文件」；继承、查依赖不会，说明里不能写。 */
        private const val UNANALYZED_NOTE =
            "Files in languages the IDE cannot analyze are listed separately as `unanalyzed_mentions`: check those yourself. "

        fun tools(): JsonArray = JsonArray().apply {
            add(tool(
                FIND_REFERENCES,
                "Find every usage of a function, method, type, field, variable, or constant through the IDE's own reference index — " +
                    "the same engine as the IDE's Find Usages. Returns each usage with file, line, column, the line text, and the " +
                    "function or class it sits in. For a method that overrides or implements another, `overrides` lists what it overrides and " +
                    "`references_via_overridden` lists usages made through that interface or base type (e.g. `greeter.greet()` where greeter is the interface) — " +
                    "they can run this implementation too; `overridden_by` lists what overrides or implements it. " +
                    "Use it to see everything that depends on a symbol — before changing it, and when reviewing, reading, or debugging code that involves it. $UNANALYZED_NOTE$POSITION_NOTE",
            ))
            add(tool(
                CALL_HIERARCHY,
                "Build the call chain of a function or method through the IDE's own reference index — before changing it, and when reviewing, reading, or debugging code. " +
                    "direction=\"callers\" walks up who calls it, who calls those, and so on (default depth 3), grouping call sites under each caller. " +
                    "Calls made through an interface or base method it overrides are included and marked `via`. Each site is marked usage=call or reference; " +
                    "places that only pass it as a value (method references, callbacks, event handlers) are listed under `referenced_without_calling` and not walked further. " +
                    "A caller already shown elsewhere in the tree is marked expanded_above instead of repeated; " +
                    "direction=\"callees\" lists what its body uses — functions, methods, types, fields (default depth 1; deeper levels expand project functions; a call to itself is marked recursive). " +
                    "Every node carries file, line, and column, so any node can be queried again. " +
                    "For callers: $UNANALYZED_NOTE" +
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
                "Show the inheritance around a method or type through the IDE's own index. " +
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
                        addProperty("description", "1-based column of the symbol name; used when symbol is omitted.")
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
            add("annotations", JsonObject().apply { addProperty("readOnlyHint", true) })
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
