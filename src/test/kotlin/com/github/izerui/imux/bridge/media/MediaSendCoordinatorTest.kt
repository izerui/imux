package com.github.izerui.imux.bridge.media

import com.github.izerui.imux.bridge.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class MediaSendCoordinatorTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Sender(
        override val mediaCapabilities: Map<MediaKind, MediaCapability> = MediaKind.entries.associateWith { MediaCapability(true, 1024) },
    ) : MediaSender {
        val uploads = mutableListOf<Pair<String, MediaKind>>()
        val sends = mutableListOf<MediaKind>()
        var beforeUpload: suspend (PreparedMedia) -> Unit = {}
        var outcome: (MediaKind) -> MediaAttempt = { MediaAttempt.Accepted() }
        var beforeResult: suspend () -> Unit = {}
        override suspend fun uploadMedia(chatId: String, file: PreparedMedia, kind: MediaKind): UploadedMedia {
            uploads += file.fileName to kind
            beforeUpload(file)
            return UploadedMedia(kind, emptyMap())
        }
        override suspend fun sendMedia(chatId: String, file: PreparedMedia, uploaded: UploadedMedia, commit: () -> Boolean): MediaAttempt {
            if (!commit()) return MediaAttempt.Rejected(MediaRejected("binding_changed", canFallback = false))
            sends += uploaded.kind
            beforeResult()
            return outcome(uploaded.kind)
        }
    }

    private class Route(override val sender: Sender) : MediaRoute {
        override val channel = "test"
        override val chatId = "owner"
        var valid = true
        override fun isCurrent() = valid
        override fun commit(mark: () -> Unit): Boolean {
            if (!valid) return false
            mark()
            return true
        }
    }

    private fun request(name: String = "test.txt", kind: MediaKind = MediaKind.IMAGE): MediaRequest {
        val file = tmp.newFile(name)
        file.writeText("test content")
        return MediaRequest(file.absolutePath, kind)
    }

    @Test fun `图片明确拒绝后文件成功返回实际发送形式`() = runBlocking {
        val sender = Sender().apply {
            outcome = { if (it == MediaKind.IMAGE) MediaAttempt.Rejected(MediaRejected("unsupported_format")) else MediaAttempt.Accepted("id") }
        }
        val coordinator = MediaSendCoordinator(this)
        try {
            val result = coordinator.enqueue(request(), Route(sender)).await()
            assertEquals("accepted", result.outcome)
            assertEquals(MediaKind.FILE, result.deliveredAs)
            assertTrue(result.fallbackUsed)
            assertEquals("unsupported_format", result.primaryError)
            assertEquals(listOf(MediaKind.IMAGE, MediaKind.FILE), sender.sends)
        } finally { coordinator.close() }
    }

    @Test fun `图片发送结果不明不回退文件`() = runBlocking {
        val sender = Sender().apply { outcome = { MediaAttempt.Unknown } }
        val coordinator = MediaSendCoordinator(this)
        try {
            assertEquals("unknown", coordinator.enqueue(request(), Route(sender)).await().outcome)
            assertEquals(listOf(MediaKind.IMAGE), sender.sends)
            assertEquals(1, sender.uploads.size)
        } finally { coordinator.close() }
    }

    @Test fun `普通文件拒绝不递归重发`() = runBlocking {
        val sender = Sender().apply { outcome = { MediaAttempt.Rejected(MediaRejected("denied")) } }
        val coordinator = MediaSendCoordinator(this)
        try {
            assertEquals("rejected", coordinator.enqueue(request(kind = MediaKind.FILE), Route(sender)).await().outcome)
            assertEquals(listOf(MediaKind.FILE), sender.sends)
        } finally { coordinator.close() }
    }

    @Test fun `固定按文件交付的视频被拒后不重复尝试文件`() = runBlocking {
        val sender = Sender(mapOf(
            MediaKind.VIDEO to MediaCapability(true, 1024, deliveryAs = MediaKind.FILE),
            MediaKind.FILE to MediaCapability(true, 1024),
        )).apply { outcome = { MediaAttempt.Rejected(MediaRejected("platform_rejected")) } }
        val coordinator = MediaSendCoordinator(this)
        try {
            val result = coordinator.enqueue(request(kind = MediaKind.VIDEO), Route(sender)).await()
            assertEquals("rejected", result.outcome)
            assertEquals("platform_rejected", result.reason)
            assertFalse(result.fallbackUsed)
            assertEquals(listOf(MediaKind.FILE), sender.sends)
            assertEquals(listOf(MediaKind.FILE), sender.uploads.map { it.second })
        } finally { coordinator.close() }
    }

    @Test fun `先到调用阻塞上传时后到调用按顺序等待`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sender = Sender().apply { beforeUpload = { if (it.fileName == "first.txt") { entered.complete(Unit); release.await() } } }
        val coordinator = MediaSendCoordinator(this)
        try {
            val first = coordinator.enqueue(request("first.txt", MediaKind.FILE), Route(sender))
            entered.await()
            val second = coordinator.enqueue(request("second.txt", MediaKind.FILE), Route(sender))
            assertFalse(second.isCompleted)
            assertEquals(listOf("first.txt"), sender.uploads.map { it.first })
            release.complete(Unit)
            assertEquals("accepted", first.await().outcome)
            assertEquals("accepted", second.await().outcome)
            assertEquals(listOf("first.txt", "second.txt"), sender.uploads.map { it.first })
        } finally { coordinator.close() }
    }

    @Test fun `上传后解绑不提交原消息也不回退`() = runBlocking {
        val sender = Sender()
        val route = Route(sender)
        sender.beforeUpload = { route.valid = false }
        val coordinator = MediaSendCoordinator(this)
        try {
            assertEquals("rejected", coordinator.enqueue(request(), route).await().outcome)
            assertTrue(sender.sends.isEmpty())
            assertEquals(1, sender.uploads.size)
        } finally { coordinator.close() }
    }

    @Test fun `取消首个请求后下一个请求仍能发送`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val sender = Sender().apply { beforeUpload = { if (it.fileName == "first.txt") { entered.complete(Unit); CompletableDeferred<Unit>().await() } } }
        val coordinator = MediaSendCoordinator(this)
        try {
            val first = coordinator.enqueue(request("first.txt", MediaKind.FILE), Route(sender))
            entered.await()
            val second = coordinator.enqueue(request("second.txt", MediaKind.FILE), Route(sender))
            first.cancel()
            assertEquals("accepted", withTimeout(2000) { second.await() }.outcome)
            assertEquals(listOf(MediaKind.FILE), sender.sends)
        } finally { coordinator.close() }
    }

    @Test fun `排队项过期后不会打开文件`() = runBlocking {
        var clock = 0L
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sender = Sender().apply { beforeUpload = { entered.complete(Unit); release.await() } }
        val coordinator = MediaSendCoordinator(this, timeoutMillis = 1000, nanoTime = { clock })
        try {
            val first = coordinator.enqueue(request("first.txt", MediaKind.FILE), Route(sender))
            entered.await()
            val second = coordinator.enqueue(MediaRequest("/not-present-at-all", MediaKind.FILE), Route(sender))
            clock = 2_000_000_000
            release.complete(Unit)
            first.await()
            assertEquals("timeout", second.await().reason)
            assertEquals(1, sender.uploads.size)
        } finally { coordinator.close() }
    }

    @Test fun `冻结文件允许项目外路径且清理不删除原文件`() = runBlocking {
        val request = request(kind = MediaKind.FILE)
        val preparer = MediaFilePreparer(tmp.newFolder("snapshots").toPath())
        val prepared = preparer.prepare(request.path, null, 1024)
        assertEquals("test content", Files.readString(prepared.path))
        assertNotEquals(request.path, prepared.path.toString())
        prepared.deleteSnapshot()
        assertFalse(Files.exists(prepared.path))
        assertTrue(Files.exists(java.nio.file.Path.of(request.path)))
    }

    @Test fun `消息已进入提交阶段时取消返回未知且不回退`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val sender = Sender().apply {
            beforeResult = { entered.complete(Unit); CompletableDeferred<Unit>().await() }
        }
        val coordinator = MediaSendCoordinator(this)
        try {
            val pending = coordinator.enqueue(request(), Route(sender))
            entered.await()
            coordinator.cancel(pending)
            assertEquals("unknown", pending.await().outcome)
            assertEquals(listOf(MediaKind.IMAGE), sender.sends)
            assertEquals(1, sender.uploads.size)
        } finally { coordinator.close() }
    }
}
