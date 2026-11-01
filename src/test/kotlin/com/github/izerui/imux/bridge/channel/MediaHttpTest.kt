package com.github.izerui.imux.bridge.channel

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress

class MediaHttpTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `实际 multipart 请求保留字段和原始文件字节`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var body = byteArrayOf()
        var contentType = ""
        var authorization: String? = null
        server.createContext("/upload") { exchange ->
            body = exchange.requestBody.readAllBytes()
            contentType = exchange.requestHeaders.getFirst("Content-Type")
            authorization = exchange.requestHeaders.getFirst("Authorization")
            val response = """{"code":0,"data":{"file_key":"key"}}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val bytes = byteArrayOf(0, 1, 2, 0xff.toByte(), 13, 10)
            val file = tmp.newFile("data.bin").apply { writeBytes(bytes) }
            val result = JdkMediaHttp().multipart(
                "http://127.0.0.1:${server.address.port}/upload", mapOf("file_type" to "stream"),
                "file", file.toPath(), "report.bin", mapOf("Authorization" to "Bearer fake"),
            )
            assertEquals(200, result.status)
            assertEquals("key", result.json!!.getAsJsonObject("data")["file_key"].asString)
            val boundary = contentType.substringAfter("boundary=")
            val expectedPrefix = "--$boundary\r\nContent-Disposition: form-data; name=\"file_type\"\r\n\r\nstream\r\n" +
                "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"report.bin\"\r\nContent-Type: application/octet-stream\r\n\r\n"
            assertArrayEquals(expectedPrefix.toByteArray() + bytes + "\r\n--$boundary--\r\n".toByteArray(), body)
            assertEquals("Bearer fake", authorization)
        } finally { server.stop(0) }
    }

    @Test fun `二进制上传不携带平台认证并保留下载响应头`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var received: ByteArray? = null
        var authorization: String? = "not_called"
        server.createContext("/cdn") { exchange ->
            received = exchange.requestBody.readAllBytes()
            authorization = exchange.requestHeaders.getFirst("Authorization")
            exchange.responseHeaders.add("X-Encrypted-Param", "download")
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            val file = tmp.newFile("encrypted").apply { writeBytes(byteArrayOf(0, 10, 127)) }
            val result = JdkMediaHttp().binary("http://127.0.0.1:${server.address.port}/cdn", file.toPath())
            assertArrayEquals(file.readBytes(), received)
            assertNull(authorization)
            assertEquals("download", result.headers["x-encrypted-param"])
        } finally { server.stop(0) }
    }
}
