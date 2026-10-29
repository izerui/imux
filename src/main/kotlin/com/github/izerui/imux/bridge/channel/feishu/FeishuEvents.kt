package com.github.izerui.imux.bridge.channel.feishu

import com.github.izerui.imux.bridge.core.AttachmentKind
import com.github.izerui.imux.bridge.json.obj
import com.github.izerui.imux.bridge.json.objects
import com.github.izerui.imux.bridge.json.string
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** 消息里的一个附件引用；[resourceType] 是下载接口的 type 参数（image / file）。 */
data class FeishuMediaRef(
    val kind: AttachmentKind,
    val fileKey: String,
    val resourceType: String,
    val fileName: String?,
)

/** 本人单聊发来的一条消息。[text] 为 null 表示没有文字；[parentId] 是被引用消息的 id。 */
data class FeishuMessage(
    val messageId: String,
    val senderOpenId: String,
    val text: String?,
    val media: List<FeishuMediaRef>,
    val parentId: String?,
)

/** 飞书事件解析（docs/superpowers/specs/2026-10-28-feishu-channel-design.md 第 6 节）。纯函数。 */
object FeishuEvents {
    private const val RECEIVE = "im.message.receive_v1"

    /** 只接受用户在单聊里发来的消息；其他事件、群聊、机器人发送、无法解析或既无文字也无附件时返回 null。 */
    fun parseMessage(payload: String): FeishuMessage? {
        val root = parseObject(payload) ?: return null
        if (root.obj("header")?.string("event_type") != RECEIVE) return null
        val event = root.obj("event") ?: return null
        val sender = event.obj("sender") ?: return null
        if (sender.string("sender_type") != "user") return null
        val openId = sender.obj("sender_id")?.string("open_id")?.takeIf(String::isNotEmpty) ?: return null
        val message = event.obj("message") ?: return null
        if (message.string("chat_type") != "p2p") return null
        val messageId = message.string("message_id")?.takeIf(String::isNotEmpty) ?: return null
        val content = message.string("content")?.let(::parseObject) ?: return null
        val (text, media) = contentOf(message.string("message_type"), content)
        if (text == null && media.isEmpty()) return null
        return FeishuMessage(messageId, openId, text, media, message.string("parent_id")?.takeIf(String::isNotEmpty))
    }

    /** 被引用消息的文字：只取文本与富文本，其余返回 null。 */
    fun quoteText(
        messageType: String?,
        content: String?,
    ): String? {
        if (messageType != "text" && messageType != "post") return null
        return content?.let(::parseObject)?.let { contentOf(messageType, it).first }
    }

    private fun contentOf(
        type: String?,
        content: JsonObject,
    ): Pair<String?, List<FeishuMediaRef>> =
        when (type) {
            "text" -> content.string("text")?.takeIf(String::isNotBlank) to emptyList()
            "post" -> post(content)
            "image" -> null to listOfNotNull(
                content.string("image_key")?.let { FeishuMediaRef(AttachmentKind.IMAGE, it, "image", null) })

            "file" -> null to listOfNotNull(
                content.string("file_key")
                    ?.let { FeishuMediaRef(AttachmentKind.FILE, it, "file", content.string("file_name")) })

            "media" -> null to listOfNotNull(
                content.string("file_key")
                    ?.let { FeishuMediaRef(AttachmentKind.VIDEO, it, "file", content.string("file_name")) })

            "audio" -> null to listOfNotNull(
                content.string("file_key")?.let { FeishuMediaRef(AttachmentKind.AUDIO, it, "file", null) })

            else -> null to emptyList()
        }

    /** 标题与每段的文字按顺序拼接，段之间换行；`img` 生成图片附件。 */
    private fun post(content: JsonObject): Pair<String?, List<FeishuMediaRef>> {
        val body = postBody(content) ?: return null to emptyList()
        val lines = mutableListOf<String>()
        body.string("title")?.takeIf(String::isNotBlank)?.let(lines::add)
        val media = mutableListOf<FeishuMediaRef>()
        body.get("content")?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.forEach { paragraph ->
            val line = StringBuilder()
            paragraph.objects().forEach { element ->
                when (element.string("tag")) {
                    "text", "a", "md", "code_block" -> element.string("text")?.let(line::append)
                    "at" -> element.string("user_name")?.let { line.append('@').append(it) }
                    "img" -> element.string("image_key")
                        ?.let { media += FeishuMediaRef(AttachmentKind.IMAGE, it, "image", null) }
                }
            }
            if (line.isNotBlank()) lines += line.toString()
        }
        return lines.joinToString("\n").takeIf(String::isNotBlank) to media
    }

    /** 富文本可能外面再包一层 `post` 或语言键（如 `zh_cn`）。 */
    private fun postBody(content: JsonObject): JsonObject? {
        val unwrapped = content.obj("post") ?: content
        if (unwrapped.has("content")) return unwrapped
        return unwrapped.entrySet()
            .firstNotNullOfOrNull { (_, value) -> value.takeIf { it.isJsonObject }?.asJsonObject?.takeIf { it.has("content") } }
    }

    private fun parseObject(text: String): JsonObject? =
        runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
}
