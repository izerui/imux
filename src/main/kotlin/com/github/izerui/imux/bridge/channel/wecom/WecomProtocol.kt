package com.github.izerui.imux.bridge.channel.wecom

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Random

sealed interface WecomFrame {
    data class Ack(
        val reqId: String,
        val errcode: Int,
        val errmsg: String?,
    ) : WecomFrame

    /** [text] 为 null 表示没有可提交给终端的正文（图片、文件、兼容来源等）。 */
    data class Message(
        val reqId: String,
        val msgId: String,
        val single: Boolean,
        val fromUserId: String,
        val text: String?,
    ) : WecomFrame

    data object Kicked : WecomFrame

    data class Event(val eventType: String?) : WecomFrame

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

    fun parse(text: String): WecomFrame {
        val root = runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return WecomFrame.Unknown
        val reqId = root.obj("headers")?.str("req_id").orEmpty()
        val cmd = root.str("cmd")
        if (cmd == null) {
            val errcode = root["errcode"]?.takeIf { it.isJsonPrimitive }?.asInt ?: return WecomFrame.Unknown
            return WecomFrame.Ack(reqId, errcode, root.str("errmsg"))
        }
        val body = root.obj("body") ?: return WecomFrame.Unknown
        return when (cmd) {
            "aibot_msg_callback" -> message(reqId, body)
            "aibot_event_callback" -> {
                val eventType = body.obj("event")?.str("eventtype")
                if (eventType == "disconnected_event") WecomFrame.Kicked else WecomFrame.Event(eventType)
            }
            else -> WecomFrame.Unknown
        }
    }

    private fun message(
        reqId: String,
        body: JsonObject,
    ): WecomFrame {
        val text =
            if (body.has("compat_info")) {
                null
            } else {
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
            }
        return WecomFrame.Message(
            reqId = reqId,
            msgId = body.str("msgid").orEmpty(),
            single = body.str("chattype") == "single",
            fromUserId = body.obj("from")?.str("userid").orEmpty(),
            text = text,
        )
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
