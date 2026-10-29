package com.github.izerui.imux.bridge.channel.dingtalk

import com.github.izerui.imux.bridge.core.AttachmentKind
import com.github.izerui.imux.bridge.json.flag
import com.github.izerui.imux.bridge.json.obj
import com.github.izerui.imux.bridge.json.objects
import com.github.izerui.imux.bridge.json.primitiveText
import com.github.izerui.imux.bridge.json.string
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Stream 推送帧。[headers]、[data] 是帧里的原值：系统 ping 要原样回传（同官方 Node SDK）。
 * 机器人消息的 data 是消息 JSON 的字符串，见 [dataText]。
 */
data class DingtalkFrame(
    val type: String?,
    val headers: JsonObject?,
    val data: JsonElement?,
) {
    val topic: String? get() = headers?.string("topic")

    val messageId: String? get() = headers?.string("messageId")

    /** data 是字符串时的内容，其余情况为 null。 */
    val dataText: String? get() = data?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}

/** 消息里的一个附件：只记下载码，路由器放行后才换地址下载。 */
data class DingtalkMediaRef(
    val kind: AttachmentKind,
    val downloadCode: String,
    val fileName: String?,
) {
    /** 下载码可直接换出文件地址，不进日志或异常消息。 */
    override fun toString(): String = "DingtalkMediaRef(kind=$kind, downloadCode=***, fileName=$fileName)"
}

/** 一条机器人消息的解析结果。 */
sealed interface DingtalkInbound {
    data class Message(
        val msgId: String,
        val staffId: String,
        val text: String?,
        val media: List<DingtalkMediaRef>,
        val quote: String?,
    ) : DingtalkInbound

    /** 没有 senderStaffId：机器人可能未发布，既无法回复也无法配对。 */
    data class MissingStaffId(val msgId: String) : DingtalkInbound

    /** Webhook&Stream 用量超额：消息只剩错误码，没有内容。 */
    data class QuotaExceeded(val errorCode: String) : DingtalkInbound
}

/**
 * 钉钉 Stream 协议的帧与机器人消息（官方《Stream模式协议接入说明》《消息发送与接收类型》；
 * 引用字段只见于官方连接器源码）。纯函数。
 */
object DingtalkEvents {
    const val TYPE_SYSTEM = "SYSTEM"
    const val TYPE_CALLBACK = "CALLBACK"
    const val TOPIC_PING = "ping"
    const val TOPIC_DISCONNECT = "disconnect"
    const val TOPIC_BOT_MESSAGE = "/v1.0/im/bot/messages/get"

    private const val SINGLE_CHAT = "1"
    private const val RECEIVE_QUOTA_EXCEEDED = "20001"

    /** 同官方连接器 socketCallBackResponse(messageId, {success: true})。 */
    private val ACK_DATA =
        JsonObject().apply { add("response", JsonObject().apply { addProperty("success", true) }) }.toString()

    /** 解析整帧；解析不了或不是对象时为 null，由调用方丢弃。 */
    fun frame(text: String): DingtalkFrame? {
        val root = parseObject(text) ?: return null
        return DingtalkFrame(root.string("type"), root.obj("headers"), root.get("data"))
    }

    /** 机器人消息的确认，同官方 Node SDK send：headers 只有 contentType 与 messageId，data 为 {"response":{"success":true}} 的字符串。 */
    fun ack(messageId: String): String =
        JsonObject().apply {
            addProperty("code", 200)
            add(
                "headers",
                JsonObject().apply {
                    addProperty("contentType", "application/json")
                    addProperty("messageId", messageId)
                },
            )
            addProperty("message", "OK")
            addProperty("data", ACK_DATA)
        }.toString()

    /** 系统 ping 的回应，同官方 Node SDK onSystem：headers、data 原样带回；帧里没有的字段也不带（同 JSON.stringify 略去 undefined）。 */
    fun pong(frame: DingtalkFrame): String =
        JsonObject().apply {
            addProperty("code", 200)
            frame.headers?.let { add("headers", it) }
            addProperty("message", "OK")
            frame.data?.let { add("data", it) }
        }.toString()

    fun message(data: String): DingtalkInbound? {
        val json = parseObject(data) ?: return null
        // 只有 20001 有据可查是收消息额度用完；其他错误码同样没有内容，按无内容忽略，不误报额度
        json.primitiveText("errorCode")?.let { return if (it == RECEIVE_QUOTA_EXCEEDED) DingtalkInbound.QuotaExceeded(it) else null }
        if (json.primitiveText("conversationType") != SINGLE_CHAT) return null
        val msgId = json.string("msgId")?.takeIf(String::isNotEmpty) ?: return null
        val staffId = json.string("senderStaffId")?.takeIf(String::isNotEmpty) ?: return DingtalkInbound.MissingStaffId(msgId)
        val content = json.objectOrJson("content")
        var text: String? = null
        var media: List<DingtalkMediaRef> = emptyList()
        var quoteContainer: JsonObject? = null
        when (json.string("msgtype")) {
            "text" -> {
                val body = json.obj("text")
                text = body?.string("content")
                quoteContainer = body
            }

            "richText" -> {
                text = richText(content)
                media = richImages(content)
                quoteContainer = content
            }

            "picture" -> media = listOfNotNull(content?.mediaRef(AttachmentKind.IMAGE, null))
            "video" -> media = listOfNotNull(content?.mediaRef(AttachmentKind.VIDEO, null))
            "file" -> media = listOfNotNull(content?.let { it.mediaRef(AttachmentKind.FILE, it.string("fileName")) })
            "audio" -> {
                // 平台给了转写就当作文字（与企业微信一致），没有才作为音频转交
                val recognition = content?.string("recognition")?.takeIf(String::isNotBlank)
                if (recognition != null) {
                    text = recognition
                } else {
                    media = listOfNotNull(content?.mediaRef(AttachmentKind.AUDIO, null))
                }
            }

            else -> return null
        }
        val body = text?.takeIf(String::isNotBlank)
        if (body == null && media.isEmpty()) return null
        return DingtalkInbound.Message(msgId, staffId, body, media, quote(quoteContainer))
    }

    /** 被引用消息的文字；引用的不是文字时为 null。 */
    private fun quote(container: JsonObject?): String? {
        if (container == null || !container.flag("isReplyMsg")) return null
        val replied = container.obj("repliedMsg") ?: return null
        val content = replied.objectOrJson("content")
        val text =
            when (replied.string("msgType") ?: "text") {
                "text" -> content?.string("text") ?: replied.string("text")
                "richText" -> richText(content)
                else -> null
            }
        return text?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun richText(content: JsonObject?): String? =
        content?.objects("richText").orEmpty().mapNotNull { it.string("text") }.joinToString("").takeIf(String::isNotBlank)

    private fun richImages(content: JsonObject?): List<DingtalkMediaRef> =
        content?.objects("richText").orEmpty()
            .filter { (it.string("type") ?: "picture") == "picture" }
            .mapNotNull { it.mediaRef(AttachmentKind.IMAGE, null) }

    private fun JsonObject.mediaRef(
        kind: AttachmentKind,
        fileName: String?,
    ): DingtalkMediaRef? {
        val code = (string("downloadCode") ?: string("pictureDownloadCode"))?.takeIf(String::isNotEmpty) ?: return null
        return DingtalkMediaRef(kind, code, fileName?.takeIf(String::isNotEmpty))
    }

    /** 字段可能是对象，也可能是对象的 JSON 字符串。 */
    private fun JsonObject.objectOrJson(key: String): JsonObject? = obj(key) ?: string(key)?.let(::parseObject)

    private fun parseObject(text: String): JsonObject? =
        runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
}
