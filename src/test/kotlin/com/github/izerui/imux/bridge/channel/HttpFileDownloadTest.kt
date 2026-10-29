package com.github.izerui.imux.bridge.channel

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HttpFileDownloadTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private var started = false

    /** 卡住的响应在这里等待，测试结束时放行，避免服务线程挂住。 */
    private val release = CountDownLatch(1)

    @After
    fun tearDown() {
        release.countDown()
        server.stop(0)
    }

    /** 发出响应头和少量正文后停住不再发送。 */
    private fun serveStalled(
        path: String,
        chunked: Boolean,
    ) {
        server.createContext(path) { exchange ->
            exchange.sendResponseHeaders(200, if (chunked) 0 else 1_000_000L)
            val out = exchange.responseBody
            out.write(ByteArray(1_000))
            out.flush()
            release.await(30, TimeUnit.SECONDS)
            runCatching { out.close() }
        }
    }

    private fun serve(
        path: String,
        body: ByteArray,
        status: Int = 200,
        headers: Map<String, String> = emptyMap(),
        chunked: Boolean = false,
    ) {
        server.createContext(path) { exchange ->
            headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
            exchange.sendResponseHeaders(status, if (chunked) 0 else body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }

    private fun url(path: String): String {
        if (!started) {
            server.start()
            started = true
        }
        return "http://127.0.0.1:${server.address.port}$path"
    }

    @Test
    fun `解析两种 Content-Disposition 文件名`() {
        assertEquals("报告.pdf", contentDispositionFileName("attachment; filename*=UTF-8''%E6%8A%A5%E5%91%8A.pdf"))
        assertEquals("a b.png", contentDispositionFileName("attachment; filename=\"a b.png\""))
        assertEquals("x.txt", contentDispositionFileName("inline; filename=x.txt"))
        assertEquals("报告.pdf", contentDispositionFileName("attachment; filename=\"fallback.pdf\"; filename*=UTF-8''%E6%8A%A5%E5%91%8A.pdf"))
        assertEquals("报告.pdf", contentDispositionFileName("attachment; filename*=UTF-8'zh'%E6%8A%A5%E5%91%8A.pdf"))
        assertEquals("a.txt", contentDispositionFileName("attachment; filename*=utf-8'en-US'a.txt"))
        assertEquals(
            "普通 filename= 里的百分号编码按 UTF-8 解出（企业微信实测）",
            "微信图片_2025-05-16_103524_132.jpg",
            contentDispositionFileName("attachment; filename=\"%E5%BE%AE%E4%BF%A1%E5%9B%BE%E7%89%87_2025-05-16_103524_132.jpg\""),
        )
        assertEquals("不是合法 UTF-8 的百分号序列原样保留", "100%FF.txt", contentDispositionFileName("attachment; filename=100%FF.txt"))
        assertEquals("不像百分号编码的 % 原样保留", "50% off.pdf", contentDispositionFileName("attachment; filename=\"50% off.pdf\""))
        assertNull(contentDispositionFileName("attachment"))
        assertNull(contentDispositionFileName(null))
    }

    @Test
    fun `下载到临时文件并带回文件名`() =
        runBlocking {
            val body = ByteArray(200_000) { (it % 251).toByte() }
            serve("/f", body, headers = mapOf("Content-Disposition" to "attachment; filename=\"a.bin\""))
            val result = JdkHttpFileDownload().download(url("/f"), 1_000_000) as DownloadResult.Ok
            assertArrayEquals(body, Files.readAllBytes(result.path))
            assertEquals("a.bin", result.fileName)
            Files.delete(result.path)
        }

    @Test
    fun `声明长度或实际长度超过上限时返回过大且不留临时文件`() =
        runBlocking {
            serve("/declared", ByteArray(2_000))
            serve("/chunked", ByteArray(2_000), chunked = true)
            val dir = temp.newFolder().toPath()
            val download = JdkHttpFileDownload(tempDir = dir)
            assertEquals(DownloadResult.TooLarge, download.download(url("/declared"), 1_000))
            assertEquals(DownloadResult.TooLarge, download.download(url("/chunked"), 1_000))
            assertEquals("不应残留临时文件", 0L, Files.list(dir).use { it.count() })
        }

    @Test
    fun `非 2xx 返回失败`() =
        runBlocking {
            serve("/missing", ByteArray(0), status = 404)
            val result = JdkHttpFileDownload().download(url("/missing"), 1_000)
            assertEquals(DownloadResult.Failed("HTTP 404"), result)
        }

    @Test
    fun `响应头之后正文停住时整体超时返回失败且不留临时文件`() =
        runBlocking {
            serveStalled("/stall", chunked = false)
            serveStalled("/stall-chunked", chunked = true)
            val dir = temp.newFolder().toPath()
            val download = JdkHttpFileDownload(timeout = Duration.ofSeconds(1), tempDir = dir)
            for (path in listOf("/stall", "/stall-chunked")) {
                val started = System.nanoTime()
                val result = withTimeout(10_000) { download.download(url(path), 10_000_000) }
                val elapsed = Duration.ofNanos(System.nanoTime() - started)
                assertEquals(DownloadResult.Failed("timeout"), result)
                assertTrue("应在超时附近返回，实际 $elapsed", elapsed < Duration.ofSeconds(5))
            }
            assertEquals("不应残留临时文件", 0L, Files.list(dir).use { it.count() })
        }

    @Test
    fun `调用方取消照常传播，不当作下载失败`() =
        runBlocking {
            serveStalled("/stall", chunked = false)
            val dir = temp.newFolder().toPath()
            val download = JdkHttpFileDownload(timeout = Duration.ofSeconds(30), tempDir = dir)
            try {
                withTimeout(1_000) { download.download(url("/stall"), 10_000_000) }
                fail("调用方超时应抛出")
            } catch (_: TimeoutCancellationException) {
            }
            assertEquals("不应残留临时文件", 0L, Files.list(dir).use { it.count() })
        }

    @Test
    fun `下载时带上调用方给的请求头`() =
        runBlocking {
            var authorization: String? = null
            server.createContext("/auth") { exchange ->
                authorization = exchange.requestHeaders.getFirst("Authorization")
                val body = "ok".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            if (!started) {
                server.start()
                started = true
            }
            val result =
                JdkHttpFileDownload(tempDir = temp.root.toPath())
                    .download("http://127.0.0.1:${server.address.port}/auth", 100, mapOf("Authorization" to "Bearer T"))
            assertTrue(result is DownloadResult.Ok)
            assertEquals("Bearer T", authorization)
        }

    @Test
    fun `带请求头时跳转到别的主机不转发 Authorization，同源跳转照常带上`() =
        runBlocking {
            val other = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val otherAuthorization = mutableListOf<String?>()
            other.createContext("/file") { exchange ->
                synchronized(otherAuthorization) { otherAuthorization += exchange.requestHeaders.getFirst("Authorization") }
                val body = "cdn".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            other.start()
            try {
                val sameAuthorization = mutableListOf<String?>()
                server.createContext("/cross") { exchange ->
                    exchange.responseHeaders.add("Location", "http://127.0.0.1:${other.address.port}/file")
                    exchange.sendResponseHeaders(302, -1)
                    exchange.close()
                }
                server.createContext("/same") { exchange ->
                    exchange.responseHeaders.add("Location", "/target")
                    exchange.sendResponseHeaders(302, -1)
                    exchange.close()
                }
                server.createContext("/target") { exchange ->
                    synchronized(sameAuthorization) { sameAuthorization += exchange.requestHeaders.getFirst("Authorization") }
                    val body = "same".toByteArray()
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                val download = JdkHttpFileDownload(tempDir = temp.root.toPath())
                val auth = mapOf("Authorization" to "Bearer T")

                val cross = download.download(url("/cross"), 100, auth) as DownloadResult.Ok
                assertEquals("cdn", String(Files.readAllBytes(cross.path)))
                assertEquals("跨主机跳转不能带上调用方的 Authorization", listOf<String?>(null), otherAuthorization)

                val same = download.download(url("/same"), 100, auth) as DownloadResult.Ok
                assertEquals("same", String(Files.readAllBytes(same.path)))
                assertEquals(listOf<String?>("Bearer T"), sameAuthorization)
            } finally {
                other.stop(0)
            }
        }

    @Test
    fun `不带请求头时照常自动跟随跳转`() =
        runBlocking {
            serve("/plain-target", "ok".toByteArray())
            server.createContext("/plain") { exchange ->
                exchange.responseHeaders.add("Location", "/plain-target")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            val result = JdkHttpFileDownload(tempDir = temp.root.toPath()).download(url("/plain"), 100) as DownloadResult.Ok
            assertEquals("ok", String(Files.readAllBytes(result.path)))
        }
}
