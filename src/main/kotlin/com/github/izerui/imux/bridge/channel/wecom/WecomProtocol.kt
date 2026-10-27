package com.github.izerui.imux.bridge.channel.wecom

import com.github.izerui.imux.bridge.core.AttachmentKind
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Random

/** 消息里的一个媒体：只记引用，下载与解密由 [WecomAttachment] 按需进行。 */
data class WecomMediaRef(
    val kind: AttachmentKind,
    val url: String,
    val aesKey: String,
)

sealed interface WecomFrame {
    data class Ack(
        val reqId: String,
        val errcode: Int,
        val errmsg: String?,
    ) : WecomFrame

    /**
     * [text] 为 null 表示没有文字；[media] 为消息里的图片、文件、视频引用；
     * [quote] 为用户引用的那条消息里的文字，引用的是图片或文件时为 null。
     */
    data class Message(
        val reqId: String,
        val msgId: String,
        val single: Boolean,
        val fromUserId: String,
        val text: String?,
        val media: List<WecomMediaRef> = emptyList(),
        val quote: String? = null,
    ) : WecomFrame

    data object Kicked : WecomFrame

    /** 事件回调。回复欢迎语要用这个事件的 [reqId]；事件可能不带 chattype，此时按单聊处理。 */
    data class Event(
        val eventType: String?,
        val reqId: String = "",
        val msgId: String = "",
        val single: Boolean = true,
        val fromUserId: String = "",
    ) : WecomFrame

    data object Unknown : WecomFrame
}

/** 企业微信智能机器人长连接的帧格式，见 docs/channel-protocols.md 第 1 节。 */
object WecomProtocol {
    const val URL = "wss://openws.work.weixin.qq.com"

    fun reqId(
        cmd: String,
        nowMillis: Long,
        random: Random,
    ): String = "${cmd}_${nowMillis}_${"%08x".format(random.nextInt())}"

    fun subscribe(
        reqId: String,
        botId: String,
        secret: String,
    ): String =
        frame("aibot_subscribe", reqId) {
            addProperty("bot_id", botId)
            addProperty("secret", secret)
        }

    fun ping(reqId: String): String = frame("ping", reqId, body = null)

    /** 单聊主动推送。chat_type 必须显式为 1，否则优先按群聊解析。 */
    fun sendMarkdown(
        reqId: String,
        userId: String,
        content: String,
    ): String =
        frame("aibot_send_msg", reqId) {
            addProperty("chatid", userId)
            addProperty("chat_type", 1)
            addProperty("msgtype", "markdown")
            add("markdown", JsonObject().apply { addProperty("content", content) })
        }

    /** 回复进入会话事件的欢迎语，[reqId] 必须是该事件的 req_id；只支持纯文本。 */
    fun respondWelcome(
        reqId: String,
        content: String,
    ): String =
        frame("aibot_respond_welcome_msg", reqId) {
            addProperty("msgtype", "text")
            add("text", JsonObject().apply { addProperty("content", content) })
        }

    fun parse(text: String): WecomFrame {
        val root = runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return WecomFrame.Unknown
        return try {
            doParse(root)
        } catch (_: RuntimeException) {
            WecomFrame.Unknown
        }
    }

    private fun doParse(root: JsonObject): WecomFrame {
        val reqId = root.obj("headers")?.str("req_id").orEmpty()
        val cmd = root.str("cmd")
        if (cmd == null) {
            val errcode = root["errcode"]?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.let { p ->
                when {
                    p.isNumber -> p.asInt
                    p.isString -> p.asString.toIntOrNull()
                    else -> null
                }
            } ?: return WecomFrame.Unknown
            return WecomFrame.Ack(reqId, errcode, root.str("errmsg"))
        }
        val body = root.obj("body") ?: return WecomFrame.Unknown
        return when (cmd) {
            "aibot_msg_callback" -> message(reqId, body)
            "aibot_event_callback" -> {
                val eventType = body.obj("event")?.str("eventtype")
                if (eventType == "disconnected_event") {
                    WecomFrame.Kicked
                } else {
                    WecomFrame.Event(
                        eventType = eventType,
                        reqId = reqId,
                        msgId = body.str("msgid").orEmpty(),
                        single = body.str("chattype").let { it == null || it == "single" },
                        fromUserId = body.obj("from")?.str("userid").orEmpty(),
                    )
                }
            }
            else -> WecomFrame.Unknown
        }
    }

    private fun message(
        reqId: String,
        body: JsonObject,
    ): WecomFrame {
        val compat = body.has("compat_info")
        val text = if (compat) null else textOf(body)
        val quote = if (compat) null else body.obj("quote")?.let(::textOf)?.takeIf(String::isNotBlank)
        val media =
            if (body.has("compat_info")) {
                emptyList()
            } else {
                when (body.str("msgtype")) {
                    "image" -> listOfNotNull(mediaRef(AttachmentKind.IMAGE, body.obj("image")))
                    "file" -> listOfNotNull(mediaRef(AttachmentKind.FILE, body.obj("file")))
                    "video" -> listOfNotNull(mediaRef(AttachmentKind.VIDEO, body.obj("video")))
                    "mixed" ->
                        body.obj("mixed")?.get("msg_item")?.takeIf(JsonElement::isJsonArray)?.asJsonArray
                            ?.mapNotNull { item -> item.takeIf(JsonElement::isJsonObject)?.asJsonObject }
                            ?.filter { it.str("msgtype") == "image" }
                            ?.mapNotNull { mediaRef(AttachmentKind.IMAGE, it.obj("image")) }
                            .orEmpty()
                    else -> emptyList()
                }
            }
        return WecomFrame.Message(
            reqId = reqId,
            msgId = body.str("msgid").orEmpty(),
            single = body.str("chattype") == "single",
            fromUserId = body.obj("from")?.str("userid").orEmpty(),
            text = text,
            media = media,
            quote = quote,
        )
    }

    /** 消息或引用体里的文字：文本、语音转写、图文混排中的文字按原顺序拼接。 */
    private fun textOf(body: JsonObject): String? =
        when (body.str("msgtype")) {
            "text" -> body.obj("text")?.str("content")
            "voice" -> body.obj("voice")?.str("content")
            "mixed" ->
                body.obj("mixed")?.get("msg_item")?.takeIf(JsonElement::isJsonArray)?.asJsonArray
                    ?.mapNotNull { item -> item.takeIf(JsonElement::isJsonObject)?.asJsonObject }
                    ?.filter { it.str("msgtype") == "text" }
                    ?.mapNotNull { it.obj("text")?.str("content") }
                    ?.joinToString("\n")
                    ?.takeIf(String::isNotEmpty)
            else -> null
        }

    private fun mediaRef(
        kind: AttachmentKind,
        obj: JsonObject?,
    ): WecomMediaRef? {
        val url = obj?.str("url")?.takeIf(String::isNotBlank) ?: return null
        val aesKey = obj.str("aeskey")?.takeIf(String::isNotBlank) ?: return null
        return WecomMediaRef(kind, url, aesKey)
    }

    private fun frame(
        cmd: String,
        reqId: String,
        body: (JsonObject.() -> Unit)?,
    ): String =
        JsonObject().apply {
            addProperty("cmd", cmd)
            add("headers", JsonObject().apply { addProperty("req_id", reqId) })
            if (body != null) add("body", JsonObject().apply(body))
        }.toString()

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
}
