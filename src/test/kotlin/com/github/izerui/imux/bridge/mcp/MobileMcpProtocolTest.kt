package com.github.izerui.imux.bridge.mcp

import com.github.izerui.imux.bridge.core.*
import com.github.izerui.imux.mcp.localOriginAllowed
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MobileMcpProtocolTest {
    private class Backend : MobileToolsBackend {
        var bound = false
        val requests = mutableListOf<MediaRequest>()
        var next: CompletableDeferred<MediaResult>? = null
        override fun status() = JsonObject().apply { addProperty("binding", if (bound) "bound" else "unbound") }
        override fun send(request: MediaRequest): Deferred<MediaResult> {
            requests += request
            return next ?: CompletableDeferred(if (bound) MediaResult("accepted", request.kind, request.kind) else MediaResult.rejected(request.kind, "unbound"))
        }
    }
    private fun json(text: String) = JsonParser.parseString(text).asJsonObject
    private fun call(name: String, args: String = """{"path":"/tmp/test"}""", id: Int = 1) =
        json("""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$name","arguments":$args}}""")

    @Test fun `工具列表精确暴露审查确定的四个入口`() = runBlocking {
        val protocol = MobileMcpProtocol(Backend(), this)
        val reply = protocol.receive(json("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")).body!!.await()
        val names = reply.getAsJsonObject("result").getAsJsonArray("tools").map { it.asJsonObject["name"].asString }
        assertEquals(listOf("get_mobile_status", "send_image", "send_video", "send_file"), names)
        val file = reply.getAsJsonObject("result").getAsJsonArray("tools").last().asJsonObject
        assertFalse(file.getAsJsonObject("inputSchema").getAsJsonObject("properties").has("chatId"))
    }

    @Test fun `绑定变化后查询和发送使用后端当前状态`() = runBlocking {
        val backend = Backend()
        val protocol = MobileMcpProtocol(backend, this)
        val before = protocol.receive(call("get_mobile_status", "{}")).body!!.await()
        assertEquals("unbound", before.getAsJsonObject("result").getAsJsonObject("structuredContent")["binding"].asString)
        val rejected = protocol.receive(call("send_file")).body!!.await()
        assertTrue(rejected.getAsJsonObject("result")["isError"].asBoolean)
        backend.bound = true
        val after = protocol.receive(call("get_mobile_status", "{}")).body!!.await()
        assertEquals("bound", after.getAsJsonObject("result").getAsJsonObject("structuredContent")["binding"].asString)
        assertFalse(protocol.receive(call("send_file")).body!!.await().getAsJsonObject("result")["isError"].asBoolean)
    }

    @Test fun `指定另一个收件人的参数被拒绝且不执行发送`() = runBlocking {
        val backend = Backend()
        val protocol = MobileMcpProtocol(backend, this)
        val result = protocol.receive(call("send_file", """{"path":"/tmp/test","robotId":"other"}""")).body!!.await()
        assertEquals(-32602, result.getAsJsonObject("error")["code"].asInt)
        assertTrue(backend.requests.isEmpty())
    }

    @Test fun `图片和视频分别分派实际媒体类型`() = runBlocking {
        val backend = Backend()
        val protocol = MobileMcpProtocol(backend, this)
        protocol.receive(call("send_image")).body!!.await()
        assertEquals(MediaKind.IMAGE, backend.requests.single().kind)
        protocol.receive(call("send_video", """{"path":"/tmp/demo.mp4","duration_ms":1500,"thumbnail_path":"/tmp/cover.png"}""")).body!!.await()
        assertEquals(MediaKind.VIDEO, backend.requests.last().kind)
        assertEquals(1500L, backend.requests.last().durationMillis)
        assertEquals("/tmp/cover.png", backend.requests.last().thumbnailPath)
    }

    @Test fun `同凭证活动请求重复编号不会再入队`() = runBlocking {
        val backend = Backend().apply { next = CompletableDeferred() }
        val protocol = MobileMcpProtocol(backend, this)
        val first = protocol.receive(call("send_file"))
        val duplicate = protocol.receive(call("send_file")).body!!.await()
        assertEquals(-32600, duplicate.getAsJsonObject("error")["code"].asInt)
        assertEquals(1, backend.requests.size)
        backend.next!!.complete(MediaResult.rejected(MediaKind.FILE, "cancelled"))
        assertTrue(first.body!!.await().getAsJsonObject("result")["isError"].asBoolean)
    }

    @Test fun `初始化协商版本且通知不返回伪造的请求响应`() = runBlocking {
        val protocol = MobileMcpProtocol(Backend(), this)
        val initialize = protocol.receive(json("""{"jsonrpc":"2.0","id":"init","method":"initialize","params":{"protocolVersion":"2025-06-18"}}""")).body!!.await()
        assertEquals("2025-06-18", initialize.getAsJsonObject("result")["protocolVersion"].asString)
        assertEquals("init", initialize["id"].asString)
        val notification = protocol.receive(json("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        assertEquals(202, notification.status)
        assertNull(notification.body)
    }

    @Test fun `无效视频时长不会触发文件回退或上传`() = runBlocking {
        val backend = Backend()
        val protocol = MobileMcpProtocol(backend, this)
        val invalid = protocol.receive(call("send_video", """{"path":"/tmp/demo.mp4","duration_ms":-1}""")).body!!.await()
        assertEquals(-32602, invalid.getAsJsonObject("error")["code"].asInt)
        assertTrue(backend.requests.isEmpty())
    }

    @Test fun `请求来源必须匹配本地监听端口`() {
        assertTrue(localOriginAllowed("127.0.0.1:63342", null, 63342))
        assertTrue(localOriginAllowed("localhost:63342", "http://localhost:63342", 63342))
        assertFalse(localOriginAllowed("evil.example:63342", null, 63342))
        assertFalse(localOriginAllowed("127.0.0.1:63342", "https://evil.example", 63342))
        assertFalse(localOriginAllowed("127.0.0.1:63343", null, 63342))
        assertFalse(localOriginAllowed("evil@127.0.0.1:63342", null, 63342))
    }

    @Test fun `撤销一个终端凭证不影响另一个终端`() = runBlocking {
        val registry = MobileCallerRegistry(this)
        try {
            val (first, _) = registry.register("/project", "tab-a")
            val (second, _) = registry.register("/project", "tab-b")
            assertNotEquals(first, second)
            assertEquals("tab-a", registry.get(first)?.tabId)
            assertEquals("tab-b", registry.get(second)?.tabId)
            registry.revoke(first)
            assertNull(registry.get(first))
            assertEquals("tab-b", registry.get(second)?.tabId)
        } finally { registry.close() }
    }
}
