package com.github.izerui.imux.bridge.channel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class WebSocketPingTest {
    @Test
    fun `没实现 ping 的传输调用时报不支持`() {
        val transport =
            object : FrameTransport {
                override suspend fun open(
                    url: String,
                    listener: FrameListener,
                ) = Unit

                override suspend fun send(text: String) = Unit

                override fun close() = Unit
            }
        assertThrows(UnsupportedOperationException::class.java) { runBlocking { transport.sendPing() } }
    }

    @Test
    fun `JDK 传输发出 ping，收到 pong 时回调`() =
        runBlocking {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                val opcode = CompletableFuture<Int>()
                thread(isDaemon = true) {
                    runCatching {
                        server.accept().use { socket ->
                            val input = DataInputStream(socket.getInputStream())
                            val output = socket.getOutputStream()
                            val key =
                                readRequest(input).lines()
                                    .first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                                    .substringAfter(':').trim()
                            val accept =
                                Base64.getEncoder().encodeToString(
                                    MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII)),
                                )
                            output.write(
                                "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n"
                                    .toByteArray(Charsets.US_ASCII),
                            )
                            output.flush()
                            // 客户端帧一定带掩码；ping 的负载很短，长度在一个字节内
                            val code = input.readUnsignedByte() and 0x0F
                            val length = input.readUnsignedByte() and 0x7F
                            val mask = ByteArray(4).also(input::readFully)
                            val payload = ByteArray(length).also(input::readFully)
                            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                            opcode.complete(code)
                            output.write(byteArrayOf(0x8A.toByte(), payload.size.toByte()) + payload)
                            output.flush()
                            input.read()
                        }
                    }.onFailure { opcode.completeExceptionally(it) }
                }
                val pong = CompletableDeferred<Unit>()
                val transport = JdkWebSocketTransport()
                transport.open(
                    "ws://127.0.0.1:${server.localPort}/",
                    object : FrameListener {
                        override fun onText(text: String) = Unit

                        override fun onPong() {
                            pong.complete(Unit)
                        }

                        override fun onClosed(reason: String) = Unit
                    },
                )
                transport.sendPing()
                assertEquals(0x9, opcode.get(5, TimeUnit.SECONDS))
                withTimeout(5_000) { pong.await() }
                transport.close()
            }
        }

    /**
     * 握手进行中取消协程后，晚到的 WebSocket 必须被关闭，不能泄漏。
     * 服务端延迟回 101；期间取消 open() 所在协程；放行后断言服务端在合理时间内看到连接关闭。
     */
    @Test
    fun `握手期间取消协程，晚到的连接被关闭不泄漏`() =
        runBlocking {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                // 屏障：测试控制何时回 101
                val gate = CountDownLatch(1)
                // 服务端收到握手请求后放行，测试再取消协程
                val requestReceived = CountDownLatch(1)
                // 服务端在握手完成后是否看到连接关闭（读到 EOF 或异常）
                val serverSawClose = CompletableFuture<Boolean>()

                thread(isDaemon = true) {
                    var handshakeSent = false
                    runCatching {
                        server.accept().use { socket ->
                            socket.soTimeout = 10_000
                            val input = DataInputStream(socket.getInputStream())
                            val output = socket.getOutputStream()
                            val key =
                                readRequest(input).lines()
                                    .first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                                    .substringAfter(':').trim()
                            val accept =
                                Base64.getEncoder().encodeToString(
                                    MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII)),
                                )
                            // 通知测试：握手请求已收到，可以取消了
                            requestReceived.countDown()
                            // 等测试放行再回 101
                            gate.await(10, TimeUnit.SECONDS)
                            output.write(
                                "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n"
                                    .toByteArray(Charsets.US_ASCII),
                            )
                            output.flush()
                            handshakeSent = true
                            // 读下一帧：如果客户端关闭连接，read 会返回 -1 或抛异常
                            val result = runCatching { input.read() }
                            // SocketTimeoutException 是读超时，不代表看到客户端关闭连接
                            serverSawClose.complete(
                                (result.isFailure && result.exceptionOrNull() !is java.net.SocketTimeoutException) ||
                                    result.getOrDefault(0) == -1,
                            )
                        }
                    }.onFailure { ex ->
                        // 只有 101 已发出后的 IO 异常才算"看到关闭"
                        if (handshakeSent) {
                            serverSawClose.complete(true)
                        } else {
                            serverSawClose.completeExceptionally(ex)
                        }
                    }
                }

                val transport = JdkWebSocketTransport()
                // 在子协程中调 open，然后取消
                val job: Job = launch {
                    transport.open(
                        "ws://127.0.0.1:${server.localPort}/",
                        object : FrameListener {
                            override fun onText(text: String) = Unit
                            override fun onClosed(reason: String) = Unit
                        },
                    )
                }
                // 等服务端确认已收到握手请求（客户端正在等 101 回复）
                withTimeout(5_000) {
                    while (!requestReceived.await(1, TimeUnit.MILLISECONDS)) {
                        yield()
                    }
                }
                job.cancel()
                // 放行：服务端回 101，握手完成
                gate.countDown()
                // 断言：服务端应该在合理时间内看到连接关闭
                assertTrue(
                    "服务端应看到连接关闭（泄漏的连接不会被关闭）",
                    serverSawClose.get(5, TimeUnit.SECONDS),
                )
                transport.close()
            }
        }

    /** 连接被拒时抛出的异常不应被 CompletionException 包裹，应是原始 IOException 系异常。 */
    @Test
    fun `连接失败抛原始异常而非 CompletionException`() =
        runBlocking {
            // 绑定后立即关闭，端口不可连接
            val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
            val transport = JdkWebSocketTransport()
            val ex =
                assertThrows(Exception::class.java) {
                    runBlocking {
                        transport.open(
                            "ws://127.0.0.1:$port/",
                            object : FrameListener {
                                override fun onText(text: String) = Unit
                                override fun onClosed(reason: String) = Unit
                            },
                        )
                    }
                }
            assertFalse(
                "应拆包 CompletionException，抛出原始异常（实际: ${ex.javaClass.simpleName}）",
                ex is CompletionException,
            )
            assertTrue(
                "应是 IOException 系异常（实际: ${ex.javaClass.simpleName}）",
                ex is IOException,
            )
            transport.close()
        }

    private fun readRequest(input: DataInputStream): String {
        val bytes = ByteArrayOutputStream()
        while (!bytes.toString(Charsets.US_ASCII).endsWith("\r\n\r\n")) bytes.write(input.readUnsignedByte())
        return bytes.toString(Charsets.US_ASCII)
    }

    private companion object {
        const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
}
