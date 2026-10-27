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
}
