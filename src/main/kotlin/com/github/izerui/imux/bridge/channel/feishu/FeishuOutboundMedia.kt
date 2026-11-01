package com.github.izerui.imux.bridge.channel.feishu

import com.github.izerui.imux.bridge.core.*
import com.google.gson.JsonObject

internal val feishuMediaCapabilities = mapOf(
    MediaKind.IMAGE to MediaCapability(true, 10L * 1024 * 1024, setOf("jpg", "png", "gif", "webp", "bmp")),
    MediaKind.FILE to MediaCapability(true, 30L * 1024 * 1024),
    MediaKind.VIDEO to MediaCapability(true, 30L * 1024 * 1024, setOf("mp4")),
)

internal fun feishuMediaError(code: Int): MediaRejected = MediaRejected(
    code.toString(),
    canFallback = code !in FeishuCodes.TOKEN_INVALID && code !in FeishuCodes.APP_UNAVAILABLE &&
        code != FeishuCodes.QUOTA_EXCEEDED && code !in FeishuCodes.RATE_LIMITED && code !in FeishuCodes.NO_PERMISSION &&
        code !in setOf(-1, 230013, 230006, 230053),
)

internal suspend fun uploadFeishuMedia(api: FeishuApi, file: PreparedMedia, kind: MediaKind): UploadedMedia {
    suspend fun upload(source: PreparedMedia, image: Boolean, video: Boolean = false): String =
        when (val result = api.uploadMediaFile(source.path, source.fileName, image, video, source.durationMillis)) {
            is FeishuResult.Ok -> result.value
            is FeishuResult.Error -> throw feishuMediaError(result.code)
            is FeishuResult.Network -> throw MediaRejected("upload_failed")
        }
    val key = upload(file, kind == MediaKind.IMAGE, kind == MediaKind.VIDEO)
    val fields = mutableMapOf((if (kind == MediaKind.IMAGE) "image_key" else "file_key") to key)
    if (kind == MediaKind.VIDEO) file.thumbnail?.let { fields["image_key"] = upload(it, true) }
    return UploadedMedia(kind, fields)
}

internal fun feishuMediaBody(chatId: String, uploaded: UploadedMedia): String =
    JsonObject().apply {
        addProperty("receive_id", chatId)
        addProperty("msg_type", when (uploaded.kind) {
            MediaKind.IMAGE -> "image"
            MediaKind.VIDEO -> "media"
            MediaKind.FILE -> "file"
        })
        addProperty("content", JsonObject().apply { uploaded.fields.forEach { (k, v) -> addProperty(k, v) } }.toString())
    }.toString()
