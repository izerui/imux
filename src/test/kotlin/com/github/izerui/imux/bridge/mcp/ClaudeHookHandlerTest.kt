package com.github.izerui.imux.bridge.mcp

import com.github.izerui.imux.bridge.core.BindingRegistry
import com.github.izerui.imux.bridge.core.BridgeRouter
import com.github.izerui.imux.bridge.core.BridgeTexts
import com.github.izerui.imux.bridge.core.HookDecision
import com.github.izerui.imux.bridge.core.HookRequest
import com.github.izerui.imux.bridge.core.HookToolDone
import com.github.izerui.imux.bridge.core.SessionTarget
import com.github.izerui.imux.bridge.core.SubmitResult
import com.github.izerui.imux.bridge.core.TerminalSessionPort
import com.google.gson.JsonParser
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** `/imux/claude-hook` 入口：用 EmbeddedChannel 驱动挂起、回写、连接关闭与鉴权分支。 */
class ClaudeHookHandlerTest {
    /** 记下路由器收到的调用；PermissionRequest 的回写函数留给测试决定何时调用。 */
    private class FakeRoutes : ClaudeHookRoutes {
        val requests = mutableListOf<Pair<HookRequest, (HookDecision) -> Boolean>>()
        val toolDone = mutableListOf<HookToolDone>()
        val closed = mutableListOf<Pair<String, Long>>()
        val stopped = mutableListOf<Pair<String, String>>()

        override suspend fun onHookSubagentStopped(tabId: String, agentId: String) {
            stopped += tabId to agentId
        }

        override suspend fun onHookRequest(tabId: String, request: HookRequest, respond: (HookDecision) -> Boolean) {
            requests += request to respond
        }

        override suspend fun onHookToolDone(tabId: String, done: HookToolDone, answers: String?) {
            toolDone += done
        }

        override suspend fun onHookClosed(tabId: String, id: Long) {
            closed += tabId to id
        }
    }

    private val channel = EmbeddedChannel()
    private val routes = FakeRoutes()
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private var ids = 0L

    private val permission = """{"hook_event_name":"PermissionRequest","tool_name":"Bash","tool_input":{"command":"rm a.txt"}}"""

    private fun post(body: String) =
        DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, CLAUDE_HOOK_PATH, Unpooled.copiedBuffer(body, Charsets.UTF_8)).apply {
            headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json")
            headers().set(HttpHeaderNames.CONTENT_LENGTH, content().readableBytes())
        }

    private fun handle(
        body: String,
        caller: HookCaller = HookCaller.Tab("tab-1"),
        routes: ClaudeHookRoutes = this.routes,
    ) {
        val request = post(body)
        try {
            handleClaudeHook(request, channel, caller, scope, routes) { ++ids }
        } finally {
            request.release()
        }
    }

    private fun response(): FullHttpResponse? = channel.readOutbound()

    private fun FullHttpResponse.text() = content().toString(Charsets.UTF_8)

    @Test
    fun `挂起期间连接被关闭时通知路由器电脑端已拒绝`() {
        handle(permission)
        assertEquals(1, routes.requests.size)
        assertNull(response())
        channel.close()
        assertEquals(listOf("tab-1" to 1L), routes.closed)
    }

    @Test
    fun `回写之后连接再关闭不算电脑端拒绝`() {
        handle(permission)
        assertTrue(routes.requests.single().second(HookDecision.Allow()))
        val written = response()!!
        assertEquals(200, written.status().code())
        val decision = JsonParser.parseString(written.text()).asJsonObject.getAsJsonObject("hookSpecificOutput").getAsJsonObject("decision")
        assertEquals("allow", decision["behavior"].asString)
        channel.close()
        assertTrue(routes.closed.isEmpty())
    }

    @Test
    fun `同一请求只回写一次`() {
        handle(permission)
        val respond = routes.requests.single().second
        assertTrue(respond(HookDecision.Allow()))
        assertFalse(respond(HookDecision.Deny("no")))
        assertTrue(response() != null)
        assertNull(response())
    }

    @Test
    fun `连接已关闭后回写返回 false 且不写出`() {
        handle(permission)
        val respond = routes.requests.single().second
        channel.close()
        assertFalse(respond(HookDecision.Allow()))
        assertNull(response())
    }

    @Test
    fun `PostToolUse 立即回空对象并交给路由器对号`() {
        handle("""{"hook_event_name":"PostToolUse","tool_name":"Bash","tool_input":{"command":"ls"}}""")
        val written = response()!!
        assertEquals(200, written.status().code())
        assertEquals("{}", written.text())
        assertEquals(listOf(HookToolDone(null, "Bash", mapOf("command" to "\"ls\""))), routes.toolDone)
    }

    @Test
    fun `SubagentStop 立即回空对象并把 agent_id 交给路由器`() {
        handle("""{"hook_event_name":"SubagentStop","agent_id":"a6e875b4ab49cb680"}""")
        val written = response()!!
        assertEquals(200, written.status().code())
        assertEquals("{}", written.text())
        assertEquals(listOf("tab-1" to "a6e875b4ab49cb680"), routes.stopped)
    }

    @Test
    fun `SubagentStop 缺 agent_id 时只回空对象`() {
        handle("""{"hook_event_name":"SubagentStop"}""")
        assertEquals("{}", response()!!.text())
        assertTrue(routes.stopped.isEmpty())
    }

    @Test
    fun `令牌无效时回 200 空对象，不交给路由器`() {
        handle(permission, caller = HookCaller.Stale)
        val written = response()!!
        assertEquals(200, written.status().code())
        assertEquals("{}", written.text())
        assertTrue(routes.requests.isEmpty())
    }

    @Test
    fun `本机或 Origin 校验不通过时回 403`() {
        handle(permission, caller = HookCaller.Forbidden)
        assertEquals(403, response()!!.status().code())
        assertTrue(routes.requests.isEmpty())
    }

    @Test
    fun `超过 1 MB 的 PermissionRequest 回 200 空对象`() {
        val big = """{"hook_event_name":"PermissionRequest","tool_name":"Write","tool_input":{"content":"${"x".repeat(1024 * 1024 + 1)}"}}"""
        handle(big)
        val written = response()!!
        assertEquals(200, written.status().code())
        assertEquals("{}", written.text())
        assertTrue(routes.requests.isEmpty())
    }

    @Test
    fun `标签未绑定机器人时立即回空对象`() {
        val texts = Proxy.newProxyInstance(BridgeTexts::class.java.classLoader, arrayOf(BridgeTexts::class.java)) { _, method, _ -> method.name } as BridgeTexts
        val port = object : TerminalSessionPort {
            override suspend fun submit(target: SessionTarget, text: String) = SubmitResult.OFFLINE
        }
        val router = BridgeRouter(BindingRegistry(), { null }, port, { null }, texts)
        handle(permission, routes = router.hookRoutes())
        val written = response()!!
        assertEquals(200, written.status().code())
        assertEquals("{}", written.text())
        channel.close()
    }

    @Test
    fun `鉴权分类：本机校验不通过 403，令牌缺失或无效回空对象，有效令牌对应标签`() {
        val tabs = mapOf("good" to "tab-1")
        assertEquals(HookCaller.Forbidden, hookCaller(loopback = false, token = "good", tabOf = tabs::get))
        assertEquals(HookCaller.Stale, hookCaller(loopback = true, token = null, tabOf = tabs::get))
        assertEquals(HookCaller.Stale, hookCaller(loopback = true, token = "revoked", tabOf = tabs::get))
        assertEquals(HookCaller.Tab("tab-1"), hookCaller(loopback = true, token = "good", tabOf = tabs::get))
    }
}
