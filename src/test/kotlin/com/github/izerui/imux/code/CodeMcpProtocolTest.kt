package com.github.izerui.imux.code

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CodeMcpProtocolTest {
    private class Backend : CodeToolsBackend {
        val calls = mutableListOf<String>()
        override suspend fun references(query: CodeQuery) = record("refs $query")
        override suspend fun callHierarchy(query: CodeQuery, direction: CallDirection, depth: Int) = record("calls ${direction.id} $depth $query")
        override suspend fun typeHierarchy(query: CodeQuery, direction: HierarchyDirection) = record("types ${direction.id} $query")
        override suspend fun fileOutline(file: String, limit: Int) = record("outline $file $limit")
        private fun record(call: String) = CodeToolResult(JsonObject().apply { addProperty("call", call) }).also { calls += call }
    }

    private fun json(text: String) = JsonParser.parseString(text).asJsonObject
    private fun call(name: String, args: String) =
        json("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$name","arguments":$args}}""")

    private fun reply(backend: Backend, name: String, args: String): JsonObject = runBlocking {
        CodeMcpProtocol(backend, this).receive(call(name, args)).body!!.await()
    }

    private fun toolList(): List<JsonObject> = runBlocking {
        CodeMcpProtocol(Backend(), this).receive(json("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")).body!!.await()
            .getAsJsonObject("result").getAsJsonArray("tools").map { it.asJsonObject }
    }

    @Test fun `工具列表是查引用、调用链、继承、文件大纲四个只读工具，调用链和继承必须给方向`() {
        val tools = toolList()
        assertEquals(listOf("find_references", "call_hierarchy", "type_hierarchy", "file_outline"), tools.map { it["name"].asString })
        val common = setOf("file", "line", "column", "symbol", "include_libraries", "max_results")
        fun properties(i: Int) = tools[i].getAsJsonObject("inputSchema").getAsJsonObject("properties").keySet()
        fun required(i: Int) = tools[i].getAsJsonObject("inputSchema").getAsJsonArray("required").map { it.asString }
        assertEquals(common, properties(0))
        assertEquals(common + setOf("direction", "depth"), properties(1))
        assertEquals(common + setOf("direction"), properties(2))
        assertEquals(listOf("file", "line"), required(0))
        assertEquals(listOf("file", "line", "direction"), required(1))
        assertEquals(listOf("file", "line", "direction"), required(2))
        assertTrue(tools[0].getAsJsonObject("annotations")["readOnlyHint"].asBoolean)
        assertTrue(tools[1].getAsJsonObject("annotations")["readOnlyHint"].asBoolean)
        assertTrue(tools[2].getAsJsonObject("annotations")["readOnlyHint"].asBoolean)
        assertEquals(setOf("file", "max_results"), properties(3))
        assertEquals(listOf("file"), required(3))
        assertTrue(tools[3].getAsJsonObject("annotations")["readOnlyHint"].asBoolean)
    }

    @Test fun `四个工具都要求 Claude Code 直接加载，不藏到工具搜索后面`() {
        val tools = toolList()
        fun alwaysLoad(i: Int) = tools[i].getAsJsonObject("_meta")?.get("anthropic/alwaysLoad")?.asBoolean
        assertEquals("find_references", true, alwaysLoad(0))
        assertEquals("call_hierarchy", true, alwaysLoad(1))
        assertEquals("type_hierarchy", true, alwaysLoad(2))
        assertEquals("file_outline", true, alwaysLoad(3))
    }

    @Test fun `文件大纲把文件和条数上限交给后端，未给上限取默认值，超出封顶 1000`() {
        val backend = Backend()
        reply(backend, "file_outline", """{"file":"a.go"}""")
        reply(backend, "file_outline", """{"file":"a.go","max_results":5}""")
        reply(backend, "file_outline", """{"file":"a.go","max_results":50000}""")
        assertEquals(listOf("outline a.go 1000", "outline a.go 5", "outline a.go 1000"), backend.calls)
    }

    @Test fun `文件大纲拒绝定位符号用的参数和非法值，且不调用后端`() {
        val backend = Backend()
        val rejected = listOf(
            """{}""",
            """{"file":""}""",
            """{"file":"a.go","line":1}""",
            """{"file":"a.go","symbol":"Run"}""",
            """{"file":"a.go","direction":"callers"}""",
            """{"file":"a.go","max_results":0}""",
        )
        for (args in rejected) {
            val error = reply(backend, "file_outline", args).getAsJsonObject("error")
            assertNotNull("$args 应被拒绝", error)
            assertEquals(-32602, error["code"].asInt)
        }
        assertEquals(emptyList<String>(), backend.calls)
    }

    @Test fun `文件大纲的说明讲清它是总览、不替代阅读、怎么交给其它工具、父类与可见性、截断怎么标、扁平文件和建索引时怎么办`() {
        val description = toolList()[3]["description"].asString
        assertTrue(description.startsWith("Get an overview of a file from the IDE's structure view"))
        assertTrue(description.contains("It is an overview, not a substitute for reading: read in full whatever you will change or draw a conclusion about."))
        assertTrue(description.contains("To query a node with the other tools, pass this file, its `line` and `column`, and its `symbol`"))
        assertTrue(description.contains("Types list in `supers` the parents written in their declaration, with file and line (`in_project: false` means a library), so you know which file to outline next"))
        assertTrue(description.contains("inherited members are not listed — use `type_hierarchy` for the full inheritance."))
        assertTrue(description.contains("otherwise the IDE gives no visibility, so unmarked does not mean public."))
        assertTrue("截断时先给顶层，并说明漏了什么", description.contains("When nodes exceed max_results, top levels come first; `top_level_omitted` and `children_omitted` say what was left out"))
        assertTrue(description.contains("Local variables in function bodies are left out; for flat files such as .properties, text search is often more direct."))
        assertTrue(description.contains("Needs no index: while the IDE is indexing it still works, without parents (`supers_skipped`)."))
        assertFalse("节点里没有 file 字段，不能说节点的 file", description.contains("each node's file"))
        assertFalse("不再有时限、也不会标不全", description.contains("supers_incomplete"))
        assertFalse("不能写成替代阅读", description.contains("only the parts you need"))
    }

    @Test fun `查用法和查调用链的说明都写明会顺着改名导入继续找并标出经由别名`() {
        val descriptions = toolList().map { it["description"].asString }
        assertTrue(descriptions[0].contains("An import that renames it (`from m import f as g`) is followed: uses through the new name are included and marked `via_alias`."))
        assertTrue(descriptions[1].contains("calls through a renaming import (`import f as g`) are included and marked `via_alias`."))
    }

    @Test fun `三个按位置定位的工具都说明认不出名字时多半是没配 SDK 或缺依赖`() {
        val descriptions = toolList().map { it["description"].asString }
        val note = "`unresolved` means the name is on that line but the IDE cannot tell what it refers to — usually a library name whose SDK or dependency is not set up in the IDE."
        assertTrue(descriptions[0].contains(note))
        assertTrue(descriptions[1].contains(note))
        assertTrue(descriptions[2].contains(note))
    }

    @Test fun `查用法的说明写明读写区分及其看不出内容被改`() {
        val description = toolList()[0]["description"].asString
        assertTrue(description.contains("each usage carries `access`: read, write, or read_write (`x += 1`)"))
        assertTrue(description.contains("`items.add(x)` is a read of items, so read does not mean the object it points to was left unchanged."))
    }

    @Test fun `查用法和查调用链的说明都写明框架隐式使用，且没标不能当没人用`() {
        val descriptions = toolList().map { it["description"].asString }
        val mark = "`implicit_usage: true` means a framework or the runtime uses it without any call in the code"
        val caveat = "nothing for Python or Go, and not every entry point (Spring web request handlers and a Next.js page's default export are not marked) — so its absence proves nothing."
        assertTrue("查用法", descriptions[0].contains(mark) && descriptions[0].contains(caveat))
        assertTrue("带 Java 支持时还认程序入口，Kotlin 也算", descriptions[0].contains("in IDEs with Java support also test methods, `main`, and other entry points, for Kotlin code too"))
        assertFalse("实测认不出 JUnit 5 测试，不能再这么写", descriptions[0].contains("JUnit 5 tests"))
        assertTrue("查调用链", descriptions[1].contains("The queried function, and any caller whose chain ends with no callers of its own, is marked `implicit_usage` when a framework or the runtime uses it without any call in the code, so the chain may end there and still be in use."))
    }

    @Test fun `查用法的说明写明按命名配对的测试与反查被测代码`() {
        val description = toolList()[0]["description"].asString
        assertTrue(description.contains("`tests` lists the tests the IDE pairs with it by naming convention (as in its Go to Test), including tests that never call it directly;"))
        assertTrue(description.contains("when it is itself a test, `tested_code` lists what it tests. Neither is given for variables, fields, or parameters; `tests_omitted` counts entries beyond the first 20."))
    }

    @Test fun `查用法和查调用链的说明都写明大文件里的所在函数用轻量办法找、可能缺失`() {
        val descriptions = toolList().map { it["description"].asString }
        val note = "In files over 1 MB (usually bundled or generated code) the enclosing function is found by a lighter method: it may be missing, e.g. in minified code."
        assertTrue("查用法", descriptions[0].contains(note))
        assertTrue("查调用链", descriptions[1].contains(note))
    }

    @Test fun `三个按位置定位的工具都说明测试代码标 in_test，且没标不等于是产品代码`() {
        val descriptions = toolList().map { it["description"].asString }
        val note = "Entries in files the IDE treats as test code are marked `in_test`"
        val caveat = "Python has no such rule, so its tests are marked only when their directory is marked as a test source root): unmarked does not prove it is production code."
        assertTrue("查用法", descriptions[0].contains(note) && descriptions[0].contains(caveat))
        assertTrue("查调用链", descriptions[1].contains(note) && descriptions[1].contains(caveat))
        assertTrue("查继承", descriptions[2].contains(note) && descriptions[2].contains(caveat))
    }

    @Test fun `三个工具的说明都如实写明适用范围：IDE 能解析的语言可用，解析不了会明确告知`() {
        val descriptions = toolList().map { it["description"].asString }
        val scope = "Works for the languages the IDE understands (resolved by position, so it needs no qualified name) and answers `language_not_supported` for the ones it does not."
        assertTrue(descriptions[0].contains(scope))
        assertTrue(descriptions[1].contains(scope))
        assertTrue(descriptions[2].contains(scope))
    }

    /** 关掉 IDEA MCP 时智能体照样读得到这些说明：提到没挂的工具，它会去调。 */
    @Test fun `三个工具的说明和参数说明都不提别的服务的工具`() {
        val tools = toolList()
        assertEquals(4, tools.size)
        for (tool in tools) {
            val text = tool.toString()
            val name = tool["name"].asString
            assertFalse(name, text.contains("analyze_calls"))
            assertFalse(name, text.contains("search_symbol"))
            assertFalse(name, text.contains("IDEA MCP"))
        }
    }

    @Test fun `查用法和调用链的说明提醒 IDE 分析不了的文件要自己核对，继承的说明不提（它不返回这一栏）`() {
        val descriptions = toolList().map { it["description"].asString }
        assertTrue(descriptions[0].contains("listed separately as `unanalyzed_mentions`: check those yourself"))
        assertTrue(descriptions[1].contains("For callers: Files in languages the IDE cannot analyze are listed separately as `unanalyzed_mentions`"))
        assertFalse(descriptions[2].contains("unanalyzed_mentions"))
    }

    @Test fun `调用链说明解释没查和没展开的节点`() {
        assertTrue(toolList()[1]["description"].asString.contains("A node marked `unanalyzed_not_checked` or `not_expanded` was not checked or walked further"))
    }

    @Test fun `三个工具的说明都解释继承没查全的标记`() {
        val descriptions = toolList().map { it["description"].asString }
        val note = "`overrides_incomplete: true` means the search for what a method overrides stopped early"
        assertTrue(descriptions[0].contains(note))
        assertTrue(descriptions[1].contains(note))
        assertTrue(descriptions[2].contains(note))
    }

    @Test fun `查引用和调用链的说明都讲清楚经由接口调用的结果`() {
        val descriptions = toolList().map { it["description"].asString }
        assertTrue(descriptions[0].contains("`references_via_overridden` lists usages made through that interface or base type"))
        assertTrue(descriptions[1].contains("Calls made through an interface or base method it overrides are included and marked `via`"))
    }

    @Test fun `调用链说明讲清只引用不调用的地方单列且不往上追`() {
        val description = toolList()[1]["description"].asString
        assertTrue(description.contains("listed under `referenced_without_calling` and not walked further"))
        assertTrue(description.contains("a call to itself is marked recursive"))
    }

    @Test fun `三个工具的说明都写明不只改代码前用，审查、分析代码时也用`() {
        val descriptions = toolList().map { it["description"].asString }
        assertTrue(descriptions[0].contains("before changing it, and when reviewing, reading, or debugging code"))
        assertTrue(descriptions[1].contains("before changing it, and when reviewing, reading, or debugging code"))
        assertTrue(descriptions[2].contains("and when reviewing or analyzing code"))
    }

    /** 工具能看到的只是 IDE 能静态解析的引用：不说明这一点，空结果会被当成「没人用」。 */
    @Test fun `三个工具的说明都写明看不到反射和字符串拼出的用法，空结果不等于没人用`() {
        val descriptions = toolList().map { it["description"].asString }
        val limit = "Results cover what the IDE can resolve statically: uses through reflection, names built from strings, or generated code are not in any result, so an empty result is not proof that nothing uses it."
        assertTrue(descriptions[0].contains(limit))
        assertTrue(descriptions[1].contains(limit))
        assertTrue(descriptions[2].contains(limit))
    }

    @Test fun `三个工具的说明开头各自讲清它回答什么问题`() {
        val descriptions = toolList().map { it["description"].asString }
        assertTrue(descriptions[0].startsWith("Find what actually refers to a function, method, type, field, variable, or constant"))
        assertTrue(descriptions[0].contains("Text search only matches names that look alike; this resolves each usage to the symbol it means."))
        assertTrue(descriptions[1].startsWith("Build the call chain of a function or method through the IDE's own reference index: who relies on it and what it relies on."))
        assertTrue(descriptions[2].startsWith("Show the inheritance around a method or type through the IDE's own index: whose contract it has to keep, and who has to change with it."))
    }

    @Test fun `列号参数的说明讲清它和名字一起给时用来挑同一行的同名符号`() {
        val column = toolList()[0].getAsJsonObject("inputSchema").getAsJsonObject("properties").getAsJsonObject("column")["description"].asString
        assertTrue(column.contains("together with symbol it picks among several occurrences of that name on the line (e.g. two overloads), nearest first."))
    }

    @Test fun `继承的两个方向原样交给后端`() {
        val backend = Backend()
        reply(backend, "type_hierarchy", """{"file":"a.go","line":3,"direction":"supers"}""")
        reply(backend, "type_hierarchy", """{"file":"a.go","line":3,"direction":"implementations"}""")
        val query = CodeQuery("a.go", 3)
        assertEquals(listOf("types supers $query", "types implementations $query"), backend.calls)
    }

    @Test fun `初始化报告代码导航服务名`() = runBlocking {
        val reply = CodeMcpProtocol(Backend(), this).receive(json("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")).body!!.await()
        assertEquals("imux_code", reply.getAsJsonObject("result").getAsJsonObject("serverInfo")["name"].asString)
    }

    @Test fun `查引用把位置参数原样交给后端，未给的取默认值`() {
        val backend = Backend()
        reply(backend, "find_references", """{"file":"a.go","line":3}""")
        reply(backend, "find_references", """{"file":"a.go","line":3,"column":7,"symbol":"Run","include_libraries":true,"max_results":5}""")
        assertEquals(listOf(
            "refs ${CodeQuery("a.go", 3)}",
            "refs ${CodeQuery("a.go", 3, 7, "Run", true, 5)}",
        ), backend.calls)
    }

    @Test fun `调用链两个方向的默认深度分别是 3 和 1，显式深度封顶 6`() {
        val backend = Backend()
        reply(backend, "call_hierarchy", """{"file":"a.go","line":3,"direction":"callers"}""")
        reply(backend, "call_hierarchy", """{"file":"a.go","line":3,"direction":"callees"}""")
        reply(backend, "call_hierarchy", """{"file":"a.go","line":3,"direction":"callers","depth":99}""")
        val query = CodeQuery("a.go", 3)
        assertEquals(listOf("calls callers 3 $query", "calls callees 1 $query", "calls callers 6 $query"), backend.calls)
    }

    @Test fun `结果条数上限封顶 1000`() {
        val backend = Backend()
        reply(backend, "find_references", """{"file":"a.go","line":3,"max_results":50000}""")
        assertEquals(listOf("refs ${CodeQuery("a.go", 3, limit = 1000)}"), backend.calls)
    }

    @Test fun `后端失败结果标为 isError，成功不标`() = runBlocking {
        val failing = object : CodeToolsBackend {
            override suspend fun references(query: CodeQuery) = CodeToolResult(JsonObject().apply { addProperty("reason", "indexing") }, failed = true)
            override suspend fun callHierarchy(query: CodeQuery, direction: CallDirection, depth: Int) = CodeToolResult(JsonObject())
            override suspend fun typeHierarchy(query: CodeQuery, direction: HierarchyDirection) = CodeToolResult(JsonObject())
            override suspend fun fileOutline(file: String, limit: Int) = CodeToolResult(JsonObject())
        }
        val protocol = CodeMcpProtocol(failing, this)
        val refs = protocol.receive(call("find_references", """{"file":"a.go","line":1}""")).body!!.await().getAsJsonObject("result")
        assertTrue(refs["isError"].asBoolean)
        assertEquals("indexing", refs.getAsJsonObject("structuredContent")["reason"].asString)
        val calls = protocol.receive(call("call_hierarchy", """{"file":"a.go","line":1,"direction":"callers"}""")).body!!.await().getAsJsonObject("result")
        assertFalse(calls["isError"].asBoolean)
    }

    @Test fun `非法参数被拒绝且不调用后端`() {
        val backend = Backend()
        val rejected = listOf(
            "find_references" to """{"file":"a.go"}""",
            "find_references" to """{"line":1}""",
            "find_references" to """{"file":"","line":1}""",
            "find_references" to """{"file":"a.go","line":0}""",
            "find_references" to """{"file":"a.go","line":1.5}""",
            "find_references" to """{"file":"a.go","line":"1"}""",
            "find_references" to """{"file":"a.go","line":1,"column":0}""",
            "find_references" to """{"file":"a.go","line":1,"symbol":""}""",
            "find_references" to """{"file":"a.go","line":1,"include_libraries":"yes"}""",
            "find_references" to """{"file":"a.go","line":1,"max_results":0}""",
            "find_references" to """{"file":"a.go","line":1,"direction":"callers"}""",
            "call_hierarchy" to """{"file":"a.go","line":1}""",
            "call_hierarchy" to """{"file":"a.go","line":1,"direction":"up"}""",
            "call_hierarchy" to """{"file":"a.go","line":1,"direction":"callers","depth":0}""",
            "call_hierarchy" to """{"file":"a.go","line":1,"direction":"callers","session":"x"}""",
            "type_hierarchy" to """{"file":"a.go","line":1}""",
            "type_hierarchy" to """{"file":"a.go","line":1,"direction":"callers"}""",
            "type_hierarchy" to """{"file":"a.go","line":1,"direction":"supers","depth":2}""",
            "analyze_calls" to """{"file":"a.go","line":1}""",
        )
        for ((name, args) in rejected) {
            val error = reply(backend, name, args).getAsJsonObject("error")
            assertNotNull("$name $args 应被拒绝", error)
            assertEquals(-32602, error["code"].asInt)
        }
        assertEquals(emptyList<String>(), backend.calls)
    }

    @Test fun `按名字在行内定位只认完整标识符`() {
        assertEquals(listOf(5), wordOffsets("func Run(r Runner) { Run2() }", "Run"))
        assertEquals(listOf(0, 8), wordOffsets("foo.bar(foo)", "foo"))
        assertEquals(listOf(2), wordOffsets("a \$x", "\$x"))
        assertEquals(emptyList<Int>(), wordOffsets("myRun_x RunX", "Run"))
        assertEquals(emptyList<Int>(), wordOffsets("anything", ""))
    }

    @Test fun `名字不在给的行上时按远近给出附近出现它的行，只认完整标识符且不超出窗口`() {
        val lines = listOf("a", "fun acquire()", "b", "c", "myacquire2", "acquire(x)", "d")
        fun text(n: Int) = lines[n - 1]
        assertEquals(listOf(6, 2), nearbyLines(5, lines.size, "acquire", text = ::text))
        assertEquals(listOf(6), nearbyLines(5, lines.size, "acquire", max = 1, text = ::text))
        assertEquals(listOf(6), nearbyLines(7, lines.size, "acquire", window = 2, text = ::text))
        assertEquals(emptyList<Int>(), nearbyLines(4, lines.size, "missing", text = ::text))
    }
}
