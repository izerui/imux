package com.github.izerui.imux.bridge.channel

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetSocketAddress

class HttpJsonTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { it.start() }
    private val base = "http://127.0.0.1:${server.address.port}"

    @After
    fun tearDown() = server.stop(0)

    private fun respond(
        path: String,
        status: Int,
        body: String,
        headers: Map<String, String> = emptyMap(),
        onRequest: (method: String, contentType: String?, authorization: String?, body: String) -> Unit = { _, _, _, _ -> },
    ) {
        server.createContext(path) { exchange ->
            onRequest(
                exchange.requestMethod,
                exchange.requestHeaders.getFirst("Content-Type"),
                exchange.requestHeaders.getFirst("Authorization"),
                exchange.requestBody.readBytes().toString(Charsets.UTF_8),
            )
            headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
    }

    @Test
    fun `POST 带内容类型、请求头与正文，返回状态和 JSON`() =
        runBlocking {
            var seen = listOf<String?>()
            respond("/p", 200, """{"code":0}""") { method, type, auth, body -> seen = listOf(method, type, auth, body) }
            val response = JdkHttpJson().post(
                "$base/p",
                "application/json; charset=utf-8",
                """{"a":"中"}""",
                mapOf("Authorization" to "Bearer T")
            )
            assertEquals(200, response.status)
            assertEquals(0, response.json!!["code"].asInt)
            assertEquals(listOf("POST", "application/json; charset=utf-8", "Bearer T", """{"a":"中"}"""), seen)
        }

    @Test
    fun `4xx 正文是 JSON 时照常返回，不抛异常`() =
        runBlocking {
            respond("/bad", 400, """{"error":"authorization_pending"}""")
            val response = JdkHttpJson().get("$base/bad")
            assertEquals(400, response.status)
            assertEquals("authorization_pending", response.json!!["error"].asString)
        }

    @Test
    fun `正文不是 JSON 对象时 json 为 null`() =
        runBlocking {
            respond("/html", 502, "<html>")
            val response = JdkHttpJson().get("$base/html")
            assertEquals(502, response.status)
            assertNull(response.json)
        }

    @Test
    fun `WebSocket 握手被拒时带出状态码与响应头`() =
        runBlocking {
            respond("/ws", 403, "", headers = mapOf("Handshake-Status" to "403", "Handshake-Msg" to "forbidden"))
            try {
                JdkWebSocketTransport().open("ws://127.0.0.1:${server.address.port}/ws", object : FrameListener {
                    override fun onText(text: String) = Unit

                    override fun onClosed(reason: String) = Unit
                })
                fail("应当抛出 WebSocketHandshakeRejected")
            } catch (e: WebSocketHandshakeRejected) {
                assertEquals(403, e.status)
                assertEquals("403", e.headers["handshake-status"])
                assertEquals("forbidden", e.headers["handshake-msg"])
            }
        }
}
