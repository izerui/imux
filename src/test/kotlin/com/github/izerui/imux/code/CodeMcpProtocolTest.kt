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

    @Test fun `工具列表是查引用、调用链、继承三个只读工具，调用链和继承必须给方向`() {
        val tools = toolList()
        assertEquals(listOf("find_references", "call_hierarchy", "type_hierarchy"), tools.map { it["name"].asString })
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
        assertEquals(3, tools.size)
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
