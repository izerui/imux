package com.github.izerui.imux.bridge.channel

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletionStage
import kotlin.coroutines.cancellation.CancellationException

interface FrameListener {
    fun onText(text: String)

    fun onClosed(reason: String)
}

/** 文本帧传输。渠道连接只依赖这个端口，测试用假实现。 */
interface FrameTransport {
    suspend fun open(
        url: String,
        listener: FrameListener,
    )

    suspend fun send(text: String)

    fun close()
}

fun interface HttpJsonGet {
    suspend fun get(url: String): JsonObject
}

/** JDK WebSocket：拼接分片、串行发送（并发 sendText 会抛 IllegalStateException）。 */
class JdkWebSocketTransport(
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
) : FrameTransport {
    private val sendLock = Mutex()

    @Volatile
    private var socket: WebSocket? = null

    override suspend fun open(
        url: String,
        listener: FrameListener,
    ) {
        val assembler = TextFrameAssembler()
        socket =
            client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
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
                ).await()
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
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
) : HttpJsonGet {
    override suspend fun get(url: String): JsonObject {
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(15)).GET().build()
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        if (response.statusCode() !in 200..299) throw IOException("HTTP ${response.statusCode()}")
        val root = JsonParser.parseString(response.body())
        if (!root.isJsonObject) throw IOException("返回的不是 JSON 对象")
        return root.asJsonObject
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
    ): DownloadResult
}

/** 不显式设代理：JDK 走默认 ProxySelector，IDE 中即 IDE 的代理设置。 */
class JdkHttpFileDownload(
    private val client: HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build(),
    private val timeout: Duration = Duration.ofSeconds(60),
    /** 临时文件目录；null 用系统临时目录。测试传入独立目录以检查残留。 */
    private val tempDir: Path? = null,
) : HttpFileDownload {
    override suspend fun download(
        url: String,
        maxBytes: Long,
    ): DownloadResult =
        withContext(Dispatchers.IO) {
            val response =
                try {
                    val request = HttpRequest.newBuilder(URI(url)).timeout(timeout).GET().build()
                    client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@withContext DownloadResult.Failed(e.javaClass.simpleName)
                }
            response.body().use { input ->
                if (response.statusCode() !in 200..299) return@withContext DownloadResult.Failed("HTTP ${response.statusCode()}")
                if (response.headers().firstValueAsLong("Content-Length").orElse(-1) > maxBytes) return@withContext DownloadResult.TooLarge
                val file = if (tempDir != null) Files.createTempFile(tempDir, "imux-", ".part") else Files.createTempFile("imux-", ".part")
                try {
                    var total = 0L
                    var tooLarge = false
                    Files.newOutputStream(file).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > maxBytes) {
                                tooLarge = true
                                break
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                    if (tooLarge) {
                        Files.deleteIfExists(file)
                        return@withContext DownloadResult.TooLarge
                    }
                    DownloadResult.Ok(file, contentDispositionFileName(response.headers().firstValue("Content-Disposition").orElse(null)))
                } catch (e: CancellationException) {
                    Files.deleteIfExists(file)
                    throw e
                } catch (e: IOException) {
                    Files.deleteIfExists(file)
                    DownloadResult.Failed(e.javaClass.simpleName)
                }
            }
        }
}

/** 取 Content-Disposition 里的文件名：优先 RFC 5987 的 `filename*=charset''百分号编码`，其次 `filename=`。 */
fun contentDispositionFileName(header: String?): String? {
    if (header == null) return null
    val parts = header.split(';').map(String::trim)
    parts.firstOrNull { it.startsWith("filename*=", ignoreCase = true) }?.let { part ->
        val value = part.substringAfter('=').trim().removeSurrounding("\"")
        val charset = value.substringBefore("''", "UTF-8").ifEmpty { "UTF-8" }
        val encoded = value.substringAfter("''", value)
        runCatching { URLDecoder.decode(encoded.replace("+", "%2B"), charset) }.getOrNull()?.takeIf(String::isNotBlank)?.let { return it }
    }
    return parts.firstOrNull { it.startsWith("filename=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.trim()
        ?.removeSurrounding("\"")
        ?.takeIf(String::isNotBlank)
}
