package com.github.izerui.imux.bridge.media

import com.github.izerui.imux.bridge.core.MediaAttempt
import com.github.izerui.imux.bridge.core.MediaCapability
import com.github.izerui.imux.bridge.core.MediaKind
import com.github.izerui.imux.bridge.core.MediaRejected
import com.github.izerui.imux.bridge.core.MediaRequest
import com.github.izerui.imux.bridge.core.MediaSender
import com.github.izerui.imux.bridge.core.PreparedMedia
import com.github.izerui.imux.bridge.core.UploadedMedia
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class MediaCommitRaceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `提交资格已检查但尚未标记时取消必须返回未知`() = runBlocking {
        raceWithSubmission(timeout = false)
    }

    @Test
    fun `提交资格已检查但尚未标记时总超时必须返回未知`() = runBlocking {
        raceWithSubmission(timeout = true)
    }

    private suspend fun raceWithSubmission(timeout: Boolean) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bindingLock = Any()
        val commitEntered = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val sendAttempted = CountDownLatch(1)
        val interruptStarted = CountDownLatch(1)
        val interruptThread = AtomicReference<Thread>()
        val triggerTimeout = CompletableDeferred<Unit>()
        val timerReady = CompletableDeferred<Unit>()
        val sends = AtomicInteger()
        var cancellationThread: Thread? = null
        val sender = object : MediaSender {
            override val mediaCapabilities = mapOf(MediaKind.FILE to MediaCapability(true, 1024))
            override suspend fun uploadMedia(chatId: String, file: PreparedMedia, kind: MediaKind) =
                UploadedMedia(kind, emptyMap())

            override suspend fun sendMedia(
                chatId: String,
                file: PreparedMedia,
                uploaded: UploadedMedia,
                commit: () -> Boolean,
            ): MediaAttempt {
                if (!commit()) return MediaAttempt.Rejected(MediaRejected("binding_changed", canFallback = false))
                // 模拟提交后立刻同步写出请求；协程取消不能撤回这一段。
                sends.incrementAndGet()
                sendAttempted.countDown()
                awaitCancellation()
            }
        }
        val route = object : MediaRoute {
            override val channel = "test"
            override val chatId = "owner"
            override val sender = sender
            override fun isCurrent(): Boolean = synchronized(bindingLock) { true }
            override fun commit(mark: () -> Unit): Boolean = synchronized(bindingLock) {
                // 在 coordinator 的 isActive 判定之后、submitted 写入之前暂停。
                commitEntered.countDown()
                check(releaseCommit.await(10, TimeUnit.SECONDS)) { "commit barrier timed out" }
                mark()
                true
            }
        }
        val coordinator = MediaSendCoordinator(
            scope,
            timeoutMillis = 120_000,
            waitForTimeout = {
                timerReady.complete(Unit)
                triggerTimeout.await()
                interruptThread.set(Thread.currentThread())
                interruptStarted.countDown()
            },
        )
        val file = tmp.newFile("report.txt").apply { writeText("report") }
        try {
            val pending = coordinator.enqueue(MediaRequest(file.absolutePath, MediaKind.FILE), route)
            assertTrue("未进入提交检查窗口", commitEntered.await(5, TimeUnit.SECONDS))
            withTimeout(5_000) { timerReady.await() }
            if (timeout) {
                triggerTimeout.complete(Unit)
            } else {
                cancellationThread = thread(name = "imux-media-cancel-race") {
                    interruptThread.set(Thread.currentThread())
                    interruptStarted.countDown()
                    coordinator.cancel(pending)
                }
            }
            assertTrue("取消或超时路径未开始执行", interruptStarted.await(5, TimeUnit.SECONDS))
            withTimeout(5_000) {
                // 旧实现会先完成 rejected；修复后中断线程应阻塞在协调器锁上。
                // 以实际线程状态/结果为屏障，不用固定延时猜测交错是否发生。
                while (!pending.isCompleted && interruptThread.get()?.state != Thread.State.BLOCKED) yield()
            }
            releaseCommit.countDown()
            val result = withTimeout(5_000) { pending.await() }
            assertEquals("unknown", result.outcome)
            assertEquals("delivery_unknown", result.reason)
            assertFalse(result.fallbackUsed)
            assertTrue("可提交的请求必须确实走到模拟写出位置", sendAttempted.await(5, TimeUnit.SECONDS))
            assertEquals(1, sends.get())
        } finally {
            releaseCommit.countDown()
            triggerTimeout.cancel()
            cancellationThread?.join(5_000)
            coordinator.close()
            scope.cancel()
        }
    }
}
