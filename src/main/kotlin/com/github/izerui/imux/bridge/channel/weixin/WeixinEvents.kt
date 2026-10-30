package com.github.izerui.imux.bridge.channel.weixin

import com.github.izerui.imux.bridge.core.AttachmentKind
import com.github.izerui.imux.bridge.json.obj
import com.github.izerui.imux.bridge.json.objects
import com.github.izerui.imux.bridge.json.primitiveText
import com.github.izerui.imux.bridge.json.string
import com.google.gson.JsonObject
import java.nio.charset.StandardCharsets
import java.util.Base64

/** 消息里的一个附件：只记下载所需信息，路由器放行后才下载。[aesKeyHex] 为 16 字节密钥的小写 hex；图片可能没有（明文）。 */
data class WeixinMediaRef(
    val kind: AttachmentKind,
    val fullUrl: String?,
    val encryptQueryParam: String?,
    val aesKeyHex: String?,
    val fileName: String?,
) {
    override fun toString(): String = "WeixinMediaRef(kind=$kind, fileName=$fileName)"
}

/** 一条用户单聊消息的解析结果。 */
data class WeixinMessage(
    val messageId: String?,
    val fromUserId: String,
    val createTimeMillis: Long?,
    val contextToken: String?,
    val text: String?,
    val media: List<WeixinMediaRef>,
    val quote: String?,
) {
    override fun toString(): String = "WeixinMessage(messageId=$messageId, fromUserId=$fromUserId, media=${media.size}, quote=${quote != null})"
}

/** 微信消息解析（官方 api/types.ts、media-download.ts、pic-decrypt.ts）。纯函数。 */
object WeixinEvents {
    private const val MESSAGE_TYPE_USER = 1
    private const val ITEM_TEXT = 1
    private const val ITEM_IMAGE = 2
    private const val ITEM_VOICE = 3
    private const val ITEM_FILE = 4
    private const val ITEM_VIDEO = 5
    private val HEX32 = Regex("[0-9a-f]{32}")

    /** 只接受用户发来的单聊消息（message_type == 1 且 group_id 为空）；无文字无附件返回 null。 */
    fun message(json: JsonObject): WeixinMessage? {
        if (json.primitiveText("message_type")?.toIntOrNull() != MESSAGE_TYPE_USER) return null
        if (!json.primitiveText("group_id").isNullOrEmpty()) return null
        val from = json.string("from_user_id")?.takeIf(String::isNotEmpty) ?: return null
        val items = json.objects("item_list")
        val text = items.mapNotNull(::itemText).joinToString("\n").takeIf(String::isNotBlank)
        val media = items.mapNotNull(::mediaRef)
        if (text == null && media.isEmpty()) return null
        return WeixinMessage(
            // message_id 是 uint64，Gson 按原文取字符串，不会丢精度；没有时用 seq 兜底（DeepTutor 做法）
            messageId = json.primitiveText("message_id")?.takeIf(String::isNotEmpty) ?: json.primitiveText("seq")?.let { "seq:$it" },
            fromUserId = from,
            createTimeMillis = json.primitiveText("create_time_ms")?.toLongOrNull(),
            contextToken = json.string("context_token")?.takeIf(String::isNotEmpty),
            text = text,
            media = media,
            quote = items.firstNotNullOfOrNull(::quote),
        )
    }

    /** 16 字节密钥的 hex（图片 image_item.aeskey）。 */
    fun hexKey(hex: String): String? = hex.trim().lowercase().takeIf { it.matches(HEX32) }

    /** media.aes_key：base64 解码后 16 字节即密钥，或 32 个 hex 字符（官方 pic-decrypt.ts:40-52）。 */
    fun base64Key(value: String): String? {
        val decoded = runCatching { Base64.getDecoder().decode(value.trim()) }.getOrNull() ?: return null
        return when (decoded.size) {
            16 -> decoded.joinToString("") { "%02x".format(it) }
            32 -> hexKey(String(decoded, StandardCharsets.US_ASCII))
            else -> null
        }
    }

    private fun type(item: JsonObject): Int? = item.primitiveText("type")?.toIntOrNull()

    private fun itemText(item: JsonObject): String? =
        when (type(item)) {
            ITEM_TEXT -> item.obj("text_item")?.string("text")
            ITEM_VOICE -> item.obj("voice_item")?.string("text")
            else -> null
        }?.takeIf(String::isNotBlank)

    private fun mediaRef(item: JsonObject): WeixinMediaRef? =
        when (type(item)) {
            ITEM_IMAGE ->
                item.obj("image_item")?.let { image ->
                    // 图片优先用 image_item.aeskey；都没有时为明文（官方 media-download.ts:43-63）
                    val key = image.string("aeskey")?.let(::hexKey) ?: image.obj("media")?.string("aes_key")?.let(::base64Key)
                    ref(AttachmentKind.IMAGE, image.obj("media"), key, null, keyRequired = false)
                }

            // 有转写就当文字，不再下载音频
            ITEM_VOICE ->
                item.obj("voice_item")?.takeIf { it.string("text").isNullOrBlank() }?.let { voice ->
                    ref(AttachmentKind.AUDIO, voice.obj("media"), mediaKey(voice), null, keyRequired = true)
                }

            ITEM_FILE ->
                item.obj("file_item")?.let { file ->
                    ref(AttachmentKind.FILE, file.obj("media"), mediaKey(file), file.string("file_name"), keyRequired = true)
                }

            ITEM_VIDEO -> item.obj("video_item")?.let { video -> ref(AttachmentKind.VIDEO, video.obj("media"), mediaKey(video), null, keyRequired = true) }
            else -> null
        }

    private fun mediaKey(holder: JsonObject): String? = holder.obj("media")?.string("aes_key")?.let(::base64Key)

    /** 非图片缺密钥时官方不下载，这里也不作为附件。 */
    private fun ref(
        kind: AttachmentKind,
        media: JsonObject?,
        keyHex: String?,
        fileName: String?,
        keyRequired: Boolean,
    ): WeixinMediaRef? {
        media ?: return null
        val fullUrl = media.string("full_url")?.takeIf(String::isNotEmpty)
        val param = media.string("encrypt_query_param")?.takeIf(String::isNotEmpty)
        if (fullUrl == null && param == null) return null
        if (keyRequired && keyHex == null) return null
        return WeixinMediaRef(kind, fullUrl, param, keyHex, fileName?.takeIf(String::isNotEmpty))
    }

    /** 被引用消息的文字；取不到用摘要标题。只带 svr_id 的新版引用不查（官方需本地缓存）。 */
    private fun quote(item: JsonObject): String? {
        val ref = item.obj("ref_msg") ?: return null
        val quoted = ref.obj("message_item")?.let(::itemText)
        return (quoted ?: ref.string("title"))?.trim()?.takeIf(String::isNotEmpty)
    }
}
