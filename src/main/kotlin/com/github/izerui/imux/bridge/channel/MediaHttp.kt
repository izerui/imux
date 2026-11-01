package com.github.izerui.imux.bridge.channel

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.future.await
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

data class MediaHttpResponse(val status: Int, val json: JsonObject?, val headers: Map<String, String>)

interface MediaHttp {
    suspend fun multipart(url: String, fields: Map<String, String>, field: String, path: Path, name: String, headers: Map<String, String>): MediaHttpResponse
    suspend fun binary(url: String, path: Path): MediaHttpResponse
}

/** 文件由 BodyPublisher 流式读取，不将整份媒体编码进 JSON 或内存。默认不跟随上传重定向。 */
class JdkMediaHttp(
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
) : MediaHttp {
    override suspend fun multipart(
        url: String, fields: Map<String, String>, field: String, path: Path, name: String, headers: Map<String, String>,
    ): MediaHttpResponse {
        val boundary = "imux-${UUID.randomUUID()}"
        fun safe(text: String) = text.filter { it != '"' && it != '\\' && !it.isISOControl() }
        val prefix = buildString {
            fields.forEach { (key, value) ->
                append("--$boundary\r\nContent-Disposition: form-data; name=\"${safe(key)}\"\r\n\r\n$value\r\n")
            }
            append("--$boundary\r\nContent-Disposition: form-data; name=\"${safe(field)}\"; filename=\"${safe(name)}\"\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
        }
        val publisher = HttpRequest.BodyPublishers.concat(
            HttpRequest.BodyPublishers.ofString(prefix),
            HttpRequest.BodyPublishers.ofFile(path),
            HttpRequest.BodyPublishers.ofString("\r\n--$boundary--\r\n"),
        )
        return execute(url, "multipart/form-data; boundary=$boundary", publisher, headers)
    }

    override suspend fun binary(url: String, path: Path): MediaHttpResponse =
        execute(url, "application/octet-stream", HttpRequest.BodyPublishers.ofFile(path), emptyMap())

    private suspend fun execute(url: String, type: String, body: HttpRequest.BodyPublisher, headers: Map<String, String>): MediaHttpResponse {
        val builder = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(110)).header("Content-Type", type).POST(body)
        headers.forEach { (key, value) -> builder.header(key, value) }
        val response = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).await()
        val json = runCatching { JsonParser.parseString(response.body()).takeIf { it.isJsonObject }?.asJsonObject }.getOrNull()
        return MediaHttpResponse(response.statusCode(), json, response.headers().map().mapValues { it.value.firstOrNull().orEmpty() })
    }
}

suspend fun SlidingWindowLimiter.awaitMediaSlot(chatId: String, maxWaitMillis: Long = 20_000): Boolean {
    val wait = tryAcquire(chatId)
    if (wait == 0L) return true
    if (wait > maxWaitMillis) return false
    kotlinx.coroutines.delay(wait)
    return tryAcquire(chatId) == 0L
}
