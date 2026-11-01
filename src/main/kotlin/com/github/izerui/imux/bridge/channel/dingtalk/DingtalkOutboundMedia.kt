package com.github.izerui.imux.bridge.channel.dingtalk

import com.github.izerui.imux.bridge.core.*
import com.google.gson.JsonObject

internal val dingtalkMediaCapabilities = mapOf(
    MediaKind.IMAGE to MediaCapability(true, 20L * 1024 * 1024, setOf("jpg", "gif", "png", "bmp")),
    // MP4 按用户要求尝试作为普通文件；官方清单没有保证接受，最终以平台回执为准，不改后缀。
    MediaKind.FILE to MediaCapability(true, 20L * 1024 * 1024, setOf("xlsx", "pdf", "zip", "rar", "doc", "docx", "mp4")),
    MediaKind.VIDEO to MediaCapability(true, 20L * 1024 * 1024, setOf("mp4"), deliveryAs = MediaKind.FILE),
)

internal fun dingtalkMediaError(code: String): MediaRejected = MediaRejected(
    code, canFallback = code !in DingtalkCodes.NO_PERMISSION && code !in DingtalkCodes.CREDENTIALS_INVALID &&
        code != DingtalkCodes.QUOTA_EXCEEDED && !DingtalkCodes.isRateLimited(code) &&
        code !in DingtalkCodes.TOKEN_INVALID && code !in setOf("binding_changed", "40014", "42001", "40001"),
)

internal suspend fun uploadDingtalkMedia(api: DingtalkApi, file: PreparedMedia, kind: MediaKind): UploadedMedia {
    suspend fun upload(source: PreparedMedia, type: String): String =
        when (val result = api.uploadMediaFile(source, type)) {
            is DingtalkResult.Ok -> result.value
            is DingtalkResult.Error -> throw dingtalkMediaError(result.code)
            is DingtalkResult.Network -> throw MediaRejected("upload_failed")
        }
    val deliveryKind = if (kind == MediaKind.VIDEO) MediaKind.FILE else kind
    return UploadedMedia(deliveryKind, mapOf("mediaId" to upload(file, deliveryKind.name.lowercase())))
}

internal fun dingtalkMediaMessage(file: PreparedMedia, uploaded: UploadedMedia): Pair<String, String> {
    val body = JsonObject()
    val key = when (uploaded.kind) {
        MediaKind.IMAGE -> {
            body.addProperty("photoURL", uploaded.fields.getValue("mediaId"))
            "sampleImageMsg"
        }
        MediaKind.FILE, MediaKind.VIDEO -> {
            body.addProperty("mediaId", uploaded.fields.getValue("mediaId"))
            body.addProperty("fileName", file.fileName)
            body.addProperty("fileType", file.format)
            "sampleFile"
        }
    }
    return key to body.toString()
}
