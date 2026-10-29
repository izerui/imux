package com.github.izerui.imux.bridge.channel

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import kotlin.coroutines.cancellation.CancellationException

interface FrameListener {
    fun onText(text: String)

    /** 二进制帧（已拼完分片）。只用文本帧的渠道不必实现。 */
    fun onBinary(bytes: ByteArray) {}

    /** WebSocket pong（回应 [FrameTransport.sendPing]）。不发 ping 的渠道不必实现。 */
    fun onPong() {}

    fun onClosed(reason: String)
}

/** 帧传输。渠道连接只依赖这个端口，测试用假实现。 */
interface FrameTransport {
    /** 握手被服务端拒绝时抛 [WebSocketHandshakeRejected]。 */
    suspend fun open(
        url: String,
        listener: FrameListener,
    )

    suspend fun send(text: String)

    /** 二进制帧；与 [send] 共用同一串行顺序。只用文本帧的渠道不必实现。 */
    suspend fun sendBinary(bytes: ByteArray): Unit = throw UnsupportedOperationException("不支持二进制帧")

    /**
     * WebSocket 级 ping，与 [send] 共用同一串行顺序。服务端应用层心跳频率不明时（钉钉）用它判断存活；
     * 不需要的渠道不必实现。
     */
    suspend fun sendPing(): Unit = throw UnsupportedOperationException("不支持 ping")

    fun close()
}

/** WebSocket 握手被服务端拒绝（非 101）。[headers] 的键统一为小写，每个键只取第一个值。 */
class WebSocketHandshakeRejected(
    val status: Int,
    val headers: Map<String, String>,
) : IOException("WebSocket 握手被拒绝：HTTP $status")

data class HttpJsonResponse(
    val status: Int,
    val json: JsonObject?,
)

/**
 * JSON 接口客户端。4xx/5xx 不抛异常：正文是 JSON 对象时照常返回，便于读取平台的业务错误码
 * （飞书扫码的等待态和业务错误都这样返回）。网络错误照常抛出。
 */
interface HttpJson {
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpJsonResponse

    suspend fun post(
        url: String,
        contentType: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpJsonResponse
}

fun interface HttpJsonGet {
    suspend fun get(url: String): JsonObject
}

/** JDK WebSocket：拼接分片、串行发送（并发 sendText 会抛 IllegalStateException）。 */
class JdkWebSocketTransport(
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(NETWORK_TIMEOUT).build(),
) : FrameTransport {
    private val sendLock = Mutex()

    @Volatile
    private var socket: WebSocket? = null

    override suspend fun open(
        url: String,
        listener: FrameListener,
    ) {
        val assembler = TextFrameAssembler()
        val binary = BinaryFrameAssembler()
        val future =
            client.newWebSocketBuilder()
                .connectTimeout(NETWORK_TIMEOUT)
                .buildAsync(
                    URI(url),
                    object : WebSocket.Listener {
                        override fun onText(
                            webSocket: WebSocket,
                            data: CharSequence,
                            last: Boolean,
                        ): CompletionStage<*>? {
                            assembler.accept(data, last)?.let(listener::onText)
                            webSocket.request(1)
                            return null
                        }

                        override fun onBinary(
                            webSocket: WebSocket,
                            data: ByteBuffer,
                            last: Boolean,
                        ): CompletionStage<*>? {
                            binary.accept(data, last)?.let(listener::onBinary)
                            webSocket.request(1)
                            return null
                        }

                        override fun onPong(
                            webSocket: WebSocket,
                            message: ByteBuffer,
                        ): CompletionStage<*>? {
                            listener.onPong()
                            webSocket.request(1)
                            return null
                        }

                        override fun onClose(
                            webSocket: WebSocket,
                            statusCode: Int,
                            reason: String,
                        ): CompletionStage<*>? {
                            listener.onClosed("$statusCode $reason")
                            return null
                        }

                        override fun onError(
                            webSocket: WebSocket,
                            error: Throwable,
                        ) {
                            listener.onClosed(error.message ?: error.javaClass.simpleName)
                        }
                    },
                )
        socket =
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation {
                    // 协程被取消，但 JDK 握手可能仍在进行或已完成——挂回调确保晚到的连接被关闭
                    future.thenAccept { ws -> ws.abort() }
                }
                future.whenComplete { ws, ex ->
                    if (cont.isCancelled) return@whenComplete
                    if (ex != null) {
                        val cause = (ex as? CompletionException)?.cause ?: ex
                        if (cause is WebSocketHandshakeException) {
                            cont.resumeWith(Result.failure(handshakeRejected(cause)))
                        } else {
                            cont.resumeWith(Result.failure(cause))
                        }
                    } else {
                        cont.resume(ws) { _, _, _ -> ws.abort() }
                    }
                }
            }
    }

    override suspend fun sendBinary(bytes: ByteArray) {
        sendLock.withLock {
            val current = socket ?: throw IOException("WebSocket 未连接")
            current.sendBinary(ByteBuffer.wrap(bytes), true).await()
        }
    }

    override suspend fun sendPing() {
        sendLock.withLock {
            val current = socket ?: throw IOException("WebSocket 未连接")
            current.sendPing(ByteBuffer.allocate(0)).await()
        }
    }

    private fun handshakeRejected(e: WebSocketHandshakeException): WebSocketHandshakeRejected {
        val response = e.response
        val headers =
            response.headers().map().entries
                .mapNotNull { (key, values) -> values.firstOrNull()?.let { key.lowercase() to it } }
                .toMap()
        return WebSocketHandshakeRejected(response.statusCode(), headers)
    }

    override suspend fun send(text: String) {
        sendLock.withLock {
            val current = socket ?: throw IOException("WebSocket 未连接")
            current.sendText(text, true).await()
        }
    }

    override fun close() {
        socket?.abort()
        socket = null
    }
}

class JdkHttpJsonGet(
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(NETWORK_TIMEOUT).build(),
) : HttpJsonGet {
    override suspend fun get(url: String): JsonObject {
        val request = HttpRequest.newBuilder(URI(url)).timeout(NETWORK_TIMEOUT).GET().build()
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        if (response.statusCode() !in 200..299) throw IOException("HTTP ${response.statusCode()}")
        val root = JsonParser.parseString(response.body())
        if (!root.isJsonObject) throw IOException("返回的不是 JSON 对象")
        return root.asJsonObject
    }
}

/** 不显式设代理：JDK 走默认 ProxySelector，IDE 中即 IDE 的代理设置。 */
class JdkHttpJson(
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(NETWORK_TIMEOUT).build(),
) : HttpJson {
    override suspend fun get(
        url: String,
        headers: Map<String, String>,
    ): HttpJsonResponse = execute(HttpRequest.newBuilder(URI(url)).GET(), headers)

    override suspend fun post(
        url: String,
        contentType: String,
        body: String,
        headers: Map<String, String>,
    ): HttpJsonResponse =
        execute(
            HttpRequest.newBuilder(URI(url)).header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofString(body)),
            headers,
        )

    private suspend fun execute(
        builder: HttpRequest.Builder,
        headers: Map<String, String>,
    ): HttpJsonResponse {
        headers.forEach { (name, value) -> builder.header(name, value) }
        val response = client.sendAsync(builder.timeout(NETWORK_TIMEOUT).build(), HttpResponse.BodyHandlers.ofString()).await()
        val json = runCatching { JsonParser.parseString(response.body()) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        return HttpJsonResponse(response.statusCode(), json)
    }
}

sealed interface DownloadResult {
    data class Ok(
        val path: Path,
        /** 响应头 Content-Disposition 里的文件名，未清理。 */
        val fileName: String?,
    ) : DownloadResult

    data object TooLarge : DownloadResult

    data class Failed(val reason: String) : DownloadResult
}

/** 流式下载到临时文件。超过上限中止并删除临时文件。不得抛出 CancellationException 以外的异常。 */
fun interface HttpFileDownload {
    suspend fun download(
        url: String,
        maxBytes: Long,
        headers: Map<String, String>,
    ): DownloadResult
}

suspend fun HttpFileDownload.download(
    url: String,
    maxBytes: Long,
): DownloadResult = download(url, maxBytes, emptyMap())

/**
 * 不显式设代理：JDK 走默认 ProxySelector，IDE 中即 IDE 的代理设置。
 * [timeout] 限制整次下载（响应头加正文）；超时返回 `Failed("timeout")`，调用方的取消照常抛出。
 */
class JdkHttpFileDownload(
    /**
     * 自动跟随跳转是安全的：JDK HttpClient 跳到不同 scheme 或 host:port 时会去掉 Authorization、Cookie 等请求头
     * （jdk.internal.net.http.HttpRequestImpl.newInstanceForRedirection），调用方的令牌不会被转发给别的主机；见 HttpFileDownloadTest。
     */
    private val client: HttpClient =
        HttpClient.newBuilder().connectTimeout(NETWORK_TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build(),
    private val timeout: Duration = Duration.ofSeconds(60),
    /** 临时文件目录；null 用系统临时目录。测试传入独立目录以检查残留。 */
    private val tempDir: Path? = null,
) : HttpFileDownload {
    override suspend fun download(
        url: String,
        maxBytes: Long,
        headers: Map<String, String>,
    ): DownloadResult =
        withContext(Dispatchers.IO) {
            // 只有本层的超时会得到 null；外层取消照常以 CancellationException 抛出
            withTimeoutOrNull(timeout.toMillis()) { fetch(url, maxBytes, headers) } ?: DownloadResult.Failed("timeout")
        }

    private suspend fun fetch(
        url: String,
        maxBytes: Long,
        headers: Map<String, String>,
    ): DownloadResult {
        val response =
            try {
                val builder = HttpRequest.newBuilder(URI(url)).timeout(timeout).GET()
                headers.forEach { (name, value) -> builder.header(name, value) }
                val request = builder.build()
                client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return failure(e)
            }
        return response.body().use { input ->
            if (response.statusCode() !in 200..299) return DownloadResult.Failed("HTTP ${response.statusCode()}")
            if (response.headers().firstValueAsLong("Content-Length").orElse(-1) > maxBytes) return DownloadResult.TooLarge
            var file: Path? = null
            var kept = false
            try {
                val part = if (tempDir != null) Files.createTempFile(tempDir, "imux-", ".part") else Files.createTempFile("imux-", ".part")
                file = part
                // 阻塞的 read 不响应协程取消：runInterruptible 在取消时中断读线程，read 随即以 IOException 返回
                val withinLimit = runInterruptible { copyAtMost(input, part, maxBytes) }
                if (!withinLimit) return DownloadResult.TooLarge
                kept = true
                DownloadResult.Ok(part, contentDispositionFileName(response.headers().firstValue("Content-Disposition").orElse(null)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // 因取消被中断时按取消处理，不当作下载失败
                currentCoroutineContext().ensureActive()
                failure(e)
            } finally {
                if (!kept) file?.let { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }

    /** 请求级超时（只管等响应头）与整体超时一样报 "timeout"。 */
    private fun failure(e: Exception): DownloadResult.Failed =
        DownloadResult.Failed(if (e is HttpTimeoutException) "timeout" else e.javaClass.simpleName)

    /** 把正文写入 [file]；超过 [maxBytes] 时停止并返回 false。 */
    private fun copyAtMost(
        input: InputStream,
        file: Path,
        maxBytes: Long,
    ): Boolean =
        Files.newOutputStream(file).use { output ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            var read = input.read(buffer)
            while (read >= 0) {
                total += read
                if (total > maxBytes) return@use false
                output.write(buffer, 0, read)
                read = input.read(buffer)
            }
            true
        }
}

/** 取 Content-Disposition 里的文件名：优先 RFC 5987 的 `filename*=charset'语言'百分号编码`，其次 `filename=`。 */
fun contentDispositionFileName(header: String?): String? {
    if (header == null) return null
    val parts = header.split(';').map(String::trim)
    parts.firstOrNull { it.startsWith("filename*=", ignoreCase = true) }?.let { part ->
        val value = part.substringAfter('=').trim().removeSurrounding("\"")
        // charset'lang'value：按前两个单引号切分，语言标签可为空
        val quoted = value.split('\'', limit = 3)
        val charset = if (quoted.size == 3) quoted[0].ifEmpty { "UTF-8" } else "UTF-8"
        val encoded = if (quoted.size == 3) quoted[2] else value
        runCatching { URLDecoder.decode(encoded.replace("+", "%2B"), charset) }.getOrNull()?.takeIf(String::isNotBlank)?.let { return it }
    }
    return parts.firstOrNull { it.startsWith("filename=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.trim()
        ?.removeSurrounding("\"")
        ?.takeIf(String::isNotBlank)
        ?.let(::decodePercentUtf8)
}

private val PERCENT_ESCAPE = Regex("%[0-9A-Fa-f]{2}")

/**
 * 企业微信等平台会把中文文件名百分号编码后直接放进普通的 `filename=`（实测“微信图片_…jpg”）。
 * 含 `%XX` 且能严格按 UTF-8 解出时用解码结果，否则原样返回，避免误伤本来就含 `%` 的文件名。
 */
private fun decodePercentUtf8(value: String): String {
    if (!PERCENT_ESCAPE.containsMatchIn(value)) return value
    val bytes = java.io.ByteArrayOutputStream()
    var index = 0
    while (index < value.length) {
        val c = value[index]
        if (c == '%' && PERCENT_ESCAPE.matchesAt(value, index)) {
            bytes.write(value.substring(index + 1, index + 3).toInt(16))
            index += 3
        } else {
            bytes.write(c.toString().toByteArray(Charsets.UTF_8))
            index++
        }
    }
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray()))
            .toString()
            .takeIf(String::isNotBlank) ?: value
    } catch (_: java.nio.charset.CharacterCodingException) {
        value
    }
}

/** 建立连接、单次 HTTP 请求的超时。 */
private val NETWORK_TIMEOUT: Duration = Duration.ofSeconds(15)
