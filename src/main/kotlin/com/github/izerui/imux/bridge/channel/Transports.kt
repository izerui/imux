package com.github.izerui.imux.bridge.channel

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage

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
