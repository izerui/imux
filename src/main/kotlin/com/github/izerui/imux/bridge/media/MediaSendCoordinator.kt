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
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** 快照必须在接纳调用时捕获；[commit] 与绑定更新共享短同步边界，不执行网络 IO。 */
interface MediaRoute {
    val channel: String
    val chatId: String
    val sender: MediaSender
    fun isCurrent(): Boolean
    fun commit(mark: () -> Unit): Boolean
}

/**
 * 每次发送独立执行，不保证调用之间的发送顺序。
 * 取消只作用于对应请求；终端关闭则取消活动请求，不持久化、不补发。
 */
class MediaSendCoordinator(
    private val scope: CoroutineScope,
    private val files: MediaFilePreparer = MediaFilePreparer(),
    private val timeoutMillis: Long = 120_000,
    private val waitForTimeout: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    private class Entry(
        val request: MediaRequest,
        val route: MediaRoute,
        val result: CompletableDeferred<MediaResult>,
    ) {
        @Volatile var operation: Job? = null
        var timer: Job? = null
        /** 只在协调器 lock 内访问，与结果完成共用同一个提交边界。 */
        var submitted = false
    }

    private val lock = Any()
    private val pending = LinkedHashSet<Entry>()
    private var closed = false

    fun enqueue(request: MediaRequest, route: MediaRoute): Deferred<MediaResult> {
        val entry = synchronized(lock) { register(request, route) }
        entry.timer?.start()
        entry.operation?.start()
        return entry.result
    }

    /** 调用方持有 lock；只登记状态，协程由调用方在锁外启动。 */
    private fun register(request: MediaRequest, route: MediaRoute): Entry {
        val result = CompletableDeferred<MediaResult>()
        val entry = Entry(request, route, result)
        if (closed) {
            result.complete(MediaResult.rejected(request.kind, "session_closed"))
        } else {
            pending.add(entry)
            val operation = scope.launch(start = CoroutineStart.LAZY) {
                val outcome = try {
                    execute(entry)
                } catch (_: CancellationException) {
                    interrupted(entry, "cancelled")
                } catch (e: MediaRejected) {
                    MediaResult.rejected(request.kind, e.detail)
                } catch (_: Exception) {
                    interrupted(entry, "file_or_transfer_failed")
                }
                synchronized(lock) { result.complete(outcome) }
            }
            entry.operation = operation
            entry.timer = scope.launch(start = CoroutineStart.LAZY) {
                waitForTimeout(timeoutMillis)
                synchronized(lock) {
                    if (result.isActive) {
                        result.complete(interrupted(entry, "timeout"))
                        entry.operation?.cancel()
                    }
                }
            }
            // completion 也覆盖父 scope 在协程启动前取消的情况。
            operation.invokeOnCompletion {
                synchronized(lock) {
                    result.complete(interrupted(entry, "cancelled"))
                    entry.timer?.cancel()
                    pending.remove(entry)
                }
            }
            result.invokeOnCompletion { if (result.isCancelled) operation.cancel() }
        }
        return entry
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
        synchronized(lock) {
            closed = true
            pending.toList().forEach {
                it.result.complete(interrupted(it, "cancelled"))
                it.timer?.cancel()
                it.operation?.cancel()
            }
        }
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
