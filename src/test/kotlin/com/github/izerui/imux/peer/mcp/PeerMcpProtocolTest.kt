package com.github.izerui.imux.peer.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PeerMcpProtocolTest {
    private class Backend : PeerToolsBackend {
        val calls = mutableListOf<String>()
        override suspend fun status() = result("status")
        override suspend fun enable(agent: String?) = result("enable:$agent")
        override suspend fun reviewOnce(agent: String?, instruction: String?) = result("review:$agent:$instruction")
        override suspend fun disable() = PeerToolResult(JsonObject().apply { addProperty("reason", "session_closed") }, failed = true).also { calls += "disable" }
        private fun result(call: String) = PeerToolResult(JsonObject().apply { addProperty("call", call) }).also { calls += call }
    }
    private fun json(text: String) = JsonParser.parseString(text).asJsonObject
    private fun call(name: String, args: String = "{}") =
        json("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$name","arguments":$args}}""")

    @Test fun `工具列表是三个开关加审查一次，都不带会话参数`() = runBlocking {
        val reply = PeerMcpProtocol(Backend(), this).receive(json("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")).body!!.await()
        val tools = reply.getAsJsonObject("result").getAsJsonArray("tools").map { it.asJsonObject }
        assertEquals(listOf("get_pair_status", "enable_pair_programming", "review_once", "disable_pair_programming"), tools.map { it["name"].asString })
        val properties = tools.map { it.getAsJsonObject("inputSchema").getAsJsonObject("properties").keySet() }
        assertEquals(listOf(emptySet(), setOf("agent"), setOf("agent", "instruction"), emptySet()), properties)
        assertTrue(tools[2]["description"].asString.contains("starts when your current turn ends"))
        assertTrue(tools[3]["description"].asString.contains("explicitly asks"))
    }

    @Test fun `初始化报告会话服务名`() = runBlocking {
        val reply = PeerMcpProtocol(Backend(), this).receive(json("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")).body!!.await()
        assertEquals("imux_session", reply.getAsJsonObject("result").getAsJsonObject("serverInfo")["name"].asString)
    }

    @Test fun `参数分派到后端，失败结果标为 isError`() = runBlocking {
        val backend = Backend()
        val protocol = PeerMcpProtocol(backend, this)
        val enable = protocol.receive(call("enable_pair_programming", """{"agent":"codex"}""")).body!!.await().getAsJsonObject("result")
        assertFalse(enable["isError"].asBoolean)
        assertEquals("enable:codex", enable.getAsJsonObject("structuredContent")["call"].asString)
        protocol.receive(call("enable_pair_programming")).body!!.await()
        protocol.receive(call("review_once", """{"agent":"pi","instruction":"看并发"}""")).body!!.await()
        protocol.receive(call("review_once")).body!!.await()
        val disable = protocol.receive(call("disable_pair_programming")).body!!.await().getAsJsonObject("result")
        assertTrue(disable["isError"].asBoolean)
        assertEquals(listOf("enable:codex", "enable:null", "review:pi:看并发", "review:null:null", "disable"), backend.calls)
    }

    @Test fun `多余参数、非字符串伙伴和未知工具被拒绝且不调用后端`() = runBlocking {
        val backend = Backend()
        val protocol = PeerMcpProtocol(backend, this)
        listOf(
            call("disable_pair_programming", """{"agent":"codex"}"""),
            call("enable_pair_programming", """{"session":"other"}"""),
            call("enable_pair_programming", """{"agent":1}"""),
            call("enable_pair_programming", """{"instruction":"看并发"}"""),
            call("review_once", """{"instruction":1}"""),
            call("review_once", """{"session":"other"}"""),
            call("send_image"),
        ).forEach {
            val reply = protocol.receive(it).body!!.await()
            assertEquals(-32602, reply.getAsJsonObject("error")["code"].asInt)
        }
        assertTrue(backend.calls.isEmpty())
    }
}
