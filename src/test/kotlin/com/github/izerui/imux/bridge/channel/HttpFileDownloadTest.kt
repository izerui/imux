package com.github.izerui.imux.bridge.channel

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.nio.file.Files

class HttpFileDownloadTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private var started = false

    @After
    fun tearDown() = server.stop(0)

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
}
