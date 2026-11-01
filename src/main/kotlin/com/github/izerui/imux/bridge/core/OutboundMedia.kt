package com.github.izerui.imux.bridge.core

import java.nio.file.Path

enum class MediaKind { IMAGE, VIDEO, FILE }

data class MediaCapability(
    val supported: Boolean,
    val maxBytes: Long?,
    val formats: Set<String> = emptySet(),
    val requiredFields: Set<String> = emptySet(),
    val localLimit: Boolean = false,
    /** 渠道固定采用的交付形式；null 表示沿用请求类型，不是失败后的回退。 */
    val deliveryAs: MediaKind? = null,
)

data class MediaRequest(
    val path: String,
    val kind: MediaKind,
    val fileName: String? = null,
    val durationMillis: Long? = null,
    val thumbnailPath: String? = null,
)

data class PreparedMedia(
    val path: Path,
    val fileName: String,
    val size: Long,
    val format: String,
    val durationMillis: Long? = null,
    val thumbnail: PreparedMedia? = null,
)

/** 平台上传结果只在当前调用内使用，不落盘、不打印资源凭据。 */
class UploadedMedia(
    val kind: MediaKind,
    val fields: Map<String, String>,
) {
    override fun toString(): String = "UploadedMedia(kind=$kind)"
}

class MediaRejected(
    val code: String,
    val detail: String = code,
    val canFallback: Boolean = true,
) : Exception(code)

sealed interface MediaAttempt {
    data class Accepted(val messageId: String? = null) : MediaAttempt
    data class Rejected(val error: MediaRejected) : MediaAttempt
    data object Unknown : MediaAttempt
}

/**
 * 上传与消息提交分开：等待限频结束后、写出最终请求前必须调用 [commit]。
 * 返回 false 时不能发送；进入提交阶段后未取得明确拒绝回执只能报告 Unknown。
 */
interface MediaSender {
    val mediaCapabilities: Map<MediaKind, MediaCapability>

    suspend fun uploadMedia(chatId: String, file: PreparedMedia, kind: MediaKind): UploadedMedia

    suspend fun sendMedia(
        chatId: String,
        file: PreparedMedia,
        uploaded: UploadedMedia,
        commit: () -> Boolean,
    ): MediaAttempt
}

data class MediaResult(
    val outcome: String,
    val requestedKind: MediaKind,
    val deliveredAs: MediaKind? = null,
    val channel: String? = null,
    val fileName: String? = null,
    val reason: String? = null,
    val fallbackUsed: Boolean = false,
    val primaryError: String? = null,
    val messageId: String? = null,
) {
    companion object {
        fun rejected(kind: MediaKind, reason: String) = MediaResult("rejected", kind, reason = reason)
    }
}
