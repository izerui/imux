package com.github.izerui.imux.bridge.channel

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.http.HttpTimeoutException
import java.time.Duration

class JdkHttpJsonTimeoutTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { it.start() }
    private val base = "http://127.0.0.1:${server.address.port}"

    @After
    fun tearDown() = server.stop(0)

    private fun slow(millis: Long) {
        server.createContext("/slow") { exchange ->
            Thread.sleep(millis)
            val bytes = """{"ok":1}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
    }

    @Test
    fun `请求超时可按实例设置`() =
        runBlocking {
            slow(400)
            val error = runCatching { JdkHttpJson(requestTimeout = Duration.ofMillis(100)).get("$base/slow") }.exceptionOrNull()
            assertTrue("应超时，实际 $error", error is HttpTimeoutException || error?.cause is HttpTimeoutException)
        }

    @Test
    fun `默认超时不变，慢一点的请求照常返回`() =
        runBlocking {
            slow(400)
            assertEquals(1, JdkHttpJson().get("$base/slow").json!!["ok"].asInt)
        }
}
