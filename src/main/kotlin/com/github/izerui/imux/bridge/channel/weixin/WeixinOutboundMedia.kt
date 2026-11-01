package com.github.izerui.imux.bridge.channel.weixin

import com.github.izerui.imux.bridge.channel.MediaHttp
import com.github.izerui.imux.bridge.core.*
import com.github.izerui.imux.bridge.json.string
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

internal val weixinMediaCapabilities = mapOf(
    MediaKind.IMAGE to MediaCapability(true, 100L * 1024 * 1024, setOf("jpg", "png", "gif", "webp", "bmp"), localLimit = true),
    MediaKind.VIDEO to MediaCapability(true, 100L * 1024 * 1024, setOf("mp4"), localLimit = true),
    MediaKind.FILE to MediaCapability(true, 100L * 1024 * 1024, localLimit = true),
)

internal suspend fun uploadWeixinMedia(
    api: WeixinApi, http: MediaHttp, baseUrl: String, token: String, chatId: String, file: PreparedMedia, kind: MediaKind,
): UploadedMedia = withContext(Dispatchers.IO) {
    val key = ByteArray(16).also { SecureRandom().nextBytes(it) }
    val keyHex = key.joinToString("") { "%02x".format(it) }
    val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
    val encrypted = Files.createTempFile("imux-media-", ".encrypted")
    try {
        val digest = MessageDigest.getInstance("MD5")
        Files.newInputStream(file.path).use { input ->
            CipherOutputStream(Files.newOutputStream(encrypted), cipher).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    output.write(buffer, 0, n)
                }
            }
        }
        val fileKey = UUID.randomUUID().toString().replace("-", "")
        val payload = JsonObject().apply {
            addProperty("filekey", fileKey)
            addProperty("media_type", when (kind) { MediaKind.IMAGE -> 1; MediaKind.VIDEO -> 2; MediaKind.FILE -> 3 })
            addProperty("to_user_id", chatId)
            addProperty("rawsize", file.size)
            addProperty("rawfilemd5", digest.digest().joinToString("") { "%02x".format(it) })
            addProperty("filesize", Files.size(encrypted))
            addProperty("no_need_thumb", true)
            addProperty("aeskey", keyHex)
        }
        val response = when (val result = api.mediaUploadUrl(baseUrl, token, payload)) {
            is WeixinResult.Ok -> result.value
            is WeixinResult.Error -> throw MediaRejected(result.code, "weixin_rejected:${result.code}", canFallback = false)
            is WeixinResult.Network -> throw MediaRejected("upload_failed")
        }
        val url = response.string("upload_full_url")?.takeIf(String::isNotBlank) ?: response.string("upload_param")?.let {
            "https://novac2c.cdn.weixin.qq.com/c2c/upload?encrypted_query_param=${URLEncoder.encode(it, Charsets.UTF_8)}&filekey=$fileKey"
        } ?: throw MediaRejected("missing_upload_url")
        val uploaded = http.binary(url, encrypted)
        val param = uploaded.headers["x-encrypted-param"]?.takeIf(String::isNotEmpty)
        if (uploaded.status != 200 || param == null) throw MediaRejected("cdn_upload_failed")
        UploadedMedia(kind, mapOf(
            "encrypt_query_param" to param,
            // 官方发送器编码的是 hex 字符串的 UTF-8 字节，不是原始 16 字节。
            "aes_key" to Base64.getEncoder().encodeToString(keyHex.toByteArray(Charsets.UTF_8)),
            "cipher_size" to Files.size(encrypted).toString(),
        ))
    } finally {
        Files.deleteIfExists(encrypted)
    }
}

internal fun weixinMediaItem(file: PreparedMedia, uploaded: UploadedMedia): JsonObject = JsonObject().apply {
    val (type, field) = when (uploaded.kind) {
        MediaKind.IMAGE -> 2 to "image_item"
        MediaKind.VIDEO -> 5 to "video_item"
        MediaKind.FILE -> 4 to "file_item"
    }
    addProperty("type", type)
    add(field, JsonObject().apply {
        add("media", JsonObject().apply {
            addProperty("encrypt_query_param", uploaded.fields.getValue("encrypt_query_param"))
            addProperty("aes_key", uploaded.fields.getValue("aes_key"))
            addProperty("encrypt_type", 1)
        })
        when (uploaded.kind) {
            MediaKind.IMAGE -> addProperty("mid_size", uploaded.fields.getValue("cipher_size").toLong())
            MediaKind.VIDEO -> addProperty("video_size", uploaded.fields.getValue("cipher_size").toLong())
            MediaKind.FILE -> {
                addProperty("file_name", file.fileName)
                addProperty("len", file.size.toString())
            }
        }
    })
}
