package com.github.izerui.imux.bridge.media

import com.github.izerui.imux.bridge.core.MediaAttempt
import com.github.izerui.imux.bridge.core.MediaKind
import com.github.izerui.imux.bridge.core.MediaRejected
import com.github.izerui.imux.bridge.core.MediaRequest
import com.github.izerui.imux.bridge.core.MediaResult
import com.github.izerui.imux.bridge.core.MediaSender
import com.github.izerui.imux.bridge.core.PreparedMedia
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/** 快照必须在入队时捕获；[commit] 与绑定更新共享短同步边界，不执行网络 IO。 */
interface MediaRoute {
    val channel: String
    val chatId: String
    val sender: MediaSender
    fun isCurrent(): Boolean
    fun commit(mark: () -> Unit): Boolean
}

/**
 * 一次终端运行一条 FIFO。请求接纳和入队同步完成，慢文件准备不会改变调用顺序。
 * 取消只作用于对应请求；终端关闭则取消 worker 和剩余请求，不持久化、不补发。
 */
class MediaSendCoordinator(
    private val scope: CoroutineScope,
    private val files: MediaFilePreparer = MediaFilePreparer(),
    private val timeoutMillis: Long = 120_000,
    private val waitForTimeout: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private class Entry(
        val request: MediaRequest,
        val route: MediaRoute,
        val deadline: Long,
        val result: CompletableDeferred<MediaResult>,
    ) {
        @Volatile var operation: Job? = null
        var timer: Job? = null
        /** 只在协调器 lock 内访问，与结果完成共用同一个提交边界。 */
        var submitted = false
    }

    private val lock = Any()
    private val pending = LinkedHashSet<Entry>()
    private val queue = Channel<Entry>(Channel.UNLIMITED)
    private var closed = false
    private val worker = scope.launch {
        try {
            for (entry in queue) {
                if (!entry.result.isActive) {
                    entry.timer?.cancel()
                    synchronized(lock) { pending.remove(entry) }
                    continue
                }
                val operation = scope.async(start = CoroutineStart.LAZY) {
                    try {
                        val remaining = (entry.deadline - nanoTime()) / 1_000_000
                        if (remaining <= 0) return@async MediaResult.rejected(entry.request.kind, "timeout")
                        withTimeoutOrNull(remaining) { execute(entry) }
                            ?: interrupted(entry, "timeout")
                    } catch (_: CancellationException) {
                        interrupted(entry, "cancelled")
                    } catch (e: MediaRejected) {
                        MediaResult.rejected(entry.request.kind, e.detail)
                    } catch (_: Exception) {
                        interrupted(entry, "file_or_transfer_failed")
                    }
                }
                entry.operation = operation
                if (!entry.result.isActive || !entry.route.isCurrent()) operation.cancel()
                try {
                    val outcome = operation.await()
                    synchronized(lock) { entry.result.complete(outcome) }
                } catch (_: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    synchronized(lock) { entry.result.complete(interrupted(entry, "cancelled")) }
                } finally {
                    entry.timer?.cancel()
                    synchronized(lock) { pending.remove(entry) }
                }
            }
        } finally {
            synchronized(lock) {
                pending.forEach { it.timer?.cancel(); it.operation?.cancel(); it.result.complete(interrupted(it, "cancelled")) }
                pending.clear()
                closed = true
            }
        }
    }

    fun enqueue(request: MediaRequest, route: MediaRoute): Deferred<MediaResult> = synchronized(lock) {
        val result = CompletableDeferred<MediaResult>()
        if (closed) {
            result.complete(MediaResult.rejected(request.kind, "session_closed"))
        } else {
            val entry = Entry(request, route, nanoTime() + timeoutMillis * 1_000_000, result)
            pending.add(entry)
            result.invokeOnCompletion { if (result.isCancelled) entry.operation?.cancel() }
            queue.trySend(entry)
            // 等候项也按自己的总时限完成，不等前一个请求出队。
            entry.timer = scope.launch {
                waitForTimeout(timeoutMillis)
                synchronized(lock) {
                    if (result.isActive) {
                        result.complete(interrupted(entry, "timeout"))
                        entry.operation?.cancel()
                    }
                }
            }
        }
        result
    }

    fun invalidate() = synchronized(lock) {
        pending.filterNot { it.route.isCurrent() }.forEach {
            it.result.complete(interrupted(it, "binding_changed"))
            it.operation?.cancel()
        }
    }

    fun cancel(result: Deferred<MediaResult>) = synchronized(lock) {
        pending.firstOrNull { it.result === result }?.let {
            it.result.complete(interrupted(it, "cancelled"))
            it.operation?.cancel()
        }
    }

    fun close() {
        synchronized(lock) { closed = true; queue.close() }
        worker.cancel()
    }

    private fun interrupted(entry: Entry, reason: String) = synchronized(lock) {
        if (entry.submitted) MediaResult("unknown", entry.request.kind, channel = entry.route.channel, reason = "delivery_unknown")
        else MediaResult.rejected(entry.request.kind, reason)
    }

    private suspend fun execute(entry: Entry): MediaResult {
        val request = entry.request
        val route = entry.route
        if (!route.isCurrent()) return MediaResult.rejected(request.kind, "binding_changed")
        val capabilities = route.sender.mediaCapabilities
        val deliveryKind = capabilities[request.kind]?.deliveryAs ?: request.kind
        val possible = listOfNotNull(capabilities[deliveryKind], capabilities[MediaKind.FILE]).filter { it.supported }
        if (possible.isEmpty()) return MediaResult.rejected(request.kind, "unsupported")
        val maxBytes = possible.map { it.maxBytes ?: 100L * 1024 * 1024 }.max()
        var file: PreparedMedia? = null
        try {
            file = files.prepare(request.path, request.fileName, maxBytes, request.durationMillis)
            var preparationError: MediaRejected? = null
            if (deliveryKind == MediaKind.VIDEO && request.thumbnailPath != null) {
                try {
                    val thumb = files.prepare(request.thumbnailPath, null, 10L * 1024 * 1024)
                    if (thumb.format !in setOf("jpg", "png", "gif", "bmp", "webp")) {
                        thumb.deleteSnapshot()
                        throw MediaRejected("invalid_thumbnail")
                    }
                    file = file.copy(thumbnail = thumb)
                } catch (e: MediaRejected) {
                    preparationError = MediaRejected("invalid_thumbnail", e.code)
                }
            }
            val primary = preparationError?.let { MediaAttempt.Rejected(it) } ?: attempt(entry, file, deliveryKind)
            if (primary is MediaAttempt.Rejected && primary.error.canFallback && deliveryKind != MediaKind.FILE) {
                if (!route.isCurrent()) return MediaResult.rejected(request.kind, "binding_changed")
                val fallback = attempt(entry, file, MediaKind.FILE)
                return result(entry, file, fallback, MediaKind.FILE, primary.error.code)
            }
            return result(entry, file, primary, deliveryKind)
        } finally {
            file?.deleteSnapshot()
        }
    }

    private suspend fun attempt(entry: Entry, file: PreparedMedia, kind: MediaKind): MediaAttempt {
        if (!entry.route.isCurrent()) return MediaAttempt.Rejected(MediaRejected("binding_changed", canFallback = false))
        synchronized(lock) {
            if (!entry.result.isActive || closed) return MediaAttempt.Rejected(MediaRejected("cancelled", canFallback = false))
            entry.submitted = false
        }
        return try {
            checkMediaCapability(file, kind, entry.route.sender.mediaCapabilities[kind])
            val uploaded = entry.route.sender.uploadMedia(entry.route.chatId, file, kind)
            if (!entry.route.isCurrent()) throw MediaRejected("binding_changed", canFallback = false)
            val result = entry.route.sender.sendMedia(entry.route.chatId, file, uploaded) {
                // 固定顺序：协调器锁 -> 绑定锁，与 invalidate 一致；此处不执行网络 IO。
                // 取消/超时若先完成结果，不能再提交；若提交先占用此边界，中断只能报告 unknown。
                synchronized(lock) {
                    entry.result.isActive && !closed && entry.route.commit { entry.submitted = true }
                }
            }
            synchronized(lock) {
                if (result is MediaAttempt.Rejected) entry.submitted = false
                if (result == MediaAttempt.Unknown && !entry.submitted) MediaAttempt.Rejected(MediaRejected("request_not_sent", canFallback = false))
                else result
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaRejected) {
            synchronized(lock) { if (entry.submitted) MediaAttempt.Unknown else MediaAttempt.Rejected(e) }
        } catch (_: Exception) {
            synchronized(lock) { if (entry.submitted) MediaAttempt.Unknown else MediaAttempt.Rejected(MediaRejected("upload_failed")) }
        }
    }

    private fun result(entry: Entry, file: PreparedMedia, attempt: MediaAttempt, asKind: MediaKind, primary: String? = null): MediaResult =
        MediaResult(
            outcome = when (attempt) {
                is MediaAttempt.Accepted -> "accepted"
                is MediaAttempt.Rejected -> "rejected"
                MediaAttempt.Unknown -> "unknown"
            },
            requestedKind = entry.request.kind,
            deliveredAs = asKind.takeIf { attempt is MediaAttempt.Accepted },
            channel = entry.route.channel,
            fileName = file.fileName,
            reason = when (attempt) {
                is MediaAttempt.Accepted -> if (asKind == MediaKind.FILE && entry.request.kind != MediaKind.FILE) "sent_as_file" else "sent"
                is MediaAttempt.Rejected -> attempt.error.detail
                MediaAttempt.Unknown -> "delivery_unknown"
            },
            fallbackUsed = primary != null,
            primaryError = primary,
            messageId = (attempt as? MediaAttempt.Accepted)?.messageId,
        )
}
