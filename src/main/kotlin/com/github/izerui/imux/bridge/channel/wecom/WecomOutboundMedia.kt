package com.github.izerui.imux.bridge.channel.wecom

import com.github.izerui.imux.bridge.core.*
import com.github.izerui.imux.bridge.json.string
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.util.Base64
import kotlin.coroutines.coroutineContext

internal val wecomMediaCapabilities = mapOf(
    MediaKind.IMAGE to MediaCapability(true, 10L * 1024 * 1024, setOf("png", "jpg", "gif")),
    MediaKind.VIDEO to MediaCapability(true, 10L * 1024 * 1024, setOf("mp4")),
    MediaKind.FILE to MediaCapability(true, 20L * 1024 * 1024),
)

internal fun wecomMediaFrame(command: String, reqId: String, body: JsonObject): String =
    JsonObject().apply {
        addProperty("cmd", command)
        add("headers", JsonObject().apply { addProperty("req_id", reqId) })
        add("body", body)
    }.toString()

internal suspend fun uploadWecomMedia(
    file: PreparedMedia,
    kind: MediaKind,
    exchange: suspend (String, JsonObject) -> WecomFrame.Ack?,
): UploadedMedia = withContext(Dispatchers.IO) {
    if (file.size < 5) throw MediaRejected("file_too_small")
    val type = kind.name.lowercase()
    suspend fun request(command: String, body: JsonObject): JsonObject {
        val ack = exchange(command, body) ?: throw MediaRejected("upload_timeout")
        if (ack.errcode != 0) throw MediaRejected(ack.errcode.toString(), canFallback = ack.errcode != 846607)
        return ack.body ?: throw MediaRejected("missing_upload_result")
    }
    val uploadId = request("aibot_upload_media_init", JsonObject().apply {
        addProperty("type", type)
        addProperty("filename", file.fileName)
        addProperty("total_size", file.size)
        addProperty("total_chunks", (file.size + CHUNK_BYTES - 1) / CHUNK_BYTES)
    }).string("upload_id") ?: throw MediaRejected("missing_upload_id")
    Files.newInputStream(file.path).use { input ->
        var index = 0
        while (true) {
            coroutineContext.ensureActive()
            val bytes = input.readNBytes(CHUNK_BYTES)
            if (bytes.isEmpty()) break
            val ack = exchange("aibot_upload_media_chunk", JsonObject().apply {
                addProperty("upload_id", uploadId)
                addProperty("chunk_index", index++)
                addProperty("base64_data", Base64.getEncoder().encodeToString(bytes))
            }) ?: throw MediaRejected("upload_timeout")
            if (ack.errcode != 0) throw MediaRejected(ack.errcode.toString(), canFallback = ack.errcode != 846607)
        }
    }
    val mediaId = request("aibot_upload_media_finish", JsonObject().apply { addProperty("upload_id", uploadId) })
        .string("media_id") ?: throw MediaRejected("missing_media_id")
    UploadedMedia(kind, mapOf("media_id" to mediaId))
}

internal fun wecomMediaBody(chatId: String, uploaded: UploadedMedia): JsonObject =
    JsonObject().apply {
        val type = uploaded.kind.name.lowercase()
        addProperty("chatid", chatId)
        addProperty("chat_type", 1)
        addProperty("msgtype", type)
        add(type, JsonObject().apply { addProperty("media_id", uploaded.fields.getValue("media_id")) })
    }

private const val CHUNK_BYTES = 512 * 1024
