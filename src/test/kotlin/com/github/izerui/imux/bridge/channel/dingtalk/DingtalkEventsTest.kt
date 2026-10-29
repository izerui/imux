package com.github.izerui.imux.bridge.channel.dingtalk

import com.github.izerui.imux.bridge.core.AttachmentKind
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DingtalkEventsTest {
    private fun data(
        msgtype: String,
        body: JsonObject.() -> Unit,
        staffId: String? = "u1",
        conversationType: String = "1",
    ) = JsonObject().apply {
        addProperty("conversationType", conversationType)
        addProperty("msgId", "m1")
        staffId?.let { addProperty("senderStaffId", it) }
        addProperty("senderId", "enc")
        addProperty("robotCode", "cid")
        addProperty("msgtype", msgtype)
        body()
    }.toString()

    private fun content(vararg pairs: Pair<String, Any>): JsonObject.() -> Unit = {
        add("content", JsonObject().apply { pairs.forEach { (k, v) -> if (v is Boolean) addProperty(k, v) else addProperty(k, v.toString()) } })
    }

    private fun textBody(
        text: String,
        replied: JsonObject? = null,
    ): JsonObject.() -> Unit = {
        add(
            "text",
            JsonObject().apply {
                addProperty("content", text)
                replied?.let {
                    addProperty("isReplyMsg", true)
                    add("repliedMsg", it)
                }
            },
        )
    }

    @Test
    fun `解析推送帧：headers 与 data 原样保留`() {
        val frame =
            DingtalkEvents.frame(
                """{"specVersion":"1.0","type":"CALLBACK","headers":{"topic":"/v1.0/im/bot/messages/get","messageId":"h1","time":"1"},"data":"{\"a\":1}"}""",
            )!!
        assertEquals("CALLBACK", frame.type)
        assertEquals("/v1.0/im/bot/messages/get", frame.topic)
        assertEquals("h1", frame.messageId)
        assertEquals(JsonParser.parseString("""{"topic":"/v1.0/im/bot/messages/get","messageId":"h1","time":"1"}"""), frame.headers)
        assertEquals("""{"a":1}""", frame.dataText)
        // data 不是字符串时没有文本，但原值保留
        val objectData = DingtalkEvents.frame("""{"type":"SYSTEM","headers":{"topic":"ping"},"data":{"k":1}}""")!!
        assertNull(objectData.dataText)
        assertEquals(JsonParser.parseString("""{"k":1}"""), objectData.data)
    }

    @Test
    fun `解析不了或不是对象的帧为空`() {
        for (text in listOf("not json", "", "[]", "123", "\"x\"", "null", "{", "[".repeat(100_000))) {
            assertNull(text.take(20), DingtalkEvents.frame(text))
        }
    }

    @Test
    fun `机器人消息确认的格式同官方连接器：200、OK、data 为 response success`() {
        val ack = JsonParser.parseString(DingtalkEvents.ack("h1")).asJsonObject
        assertEquals(200, ack["code"].asInt)
        assertEquals(
            JsonParser.parseString("""{"contentType":"application/json","messageId":"h1"}"""),
            ack["headers"],
        )
        assertEquals("OK", ack["message"].asString)
        assertEquals("""{"response":{"success":true}}""", ack["data"].asString)
    }

    @Test
    fun `ping 回应同官方 Node SDK：headers 与 data 原样`() {
        val frame = DingtalkEvents.frame("""{"type":"SYSTEM","headers":{"topic":"ping","messageId":"p1","time":"9"},"data":"{\"opaque\":\"x\"}"}""")!!
        val pong = JsonParser.parseString(DingtalkEvents.pong(frame)).asJsonObject
        assertEquals(200, pong["code"].asInt)
        assertEquals(frame.headers, pong["headers"])
        assertEquals("OK", pong["message"].asString)
        assertEquals("""{"opaque":"x"}""", pong["data"].asString)
        // 没有 data 就不带 data（同 JSON.stringify 略去 undefined）
        val bare = DingtalkEvents.frame("""{"type":"SYSTEM","headers":{"topic":"ping"}}""")!!
        assertFalse(JsonParser.parseString(DingtalkEvents.pong(bare)).asJsonObject.has("data"))
    }

    @Test
    fun `文本消息`() {
        assertEquals(
            DingtalkInbound.Message("m1", "u1", "你好", emptyList(), null),
            DingtalkEvents.message(data("text", textBody("你好"))),
        )
    }

    @Test
    fun `富文本拼接文字，图片作为附件`() {
        val body: JsonObject.() -> Unit = {
            val items =
                com.google.gson.JsonArray().apply {
                    add(JsonObject().apply { addProperty("text", "第一段") })
                    add(JsonObject().apply { addProperty("type", "picture"); addProperty("downloadCode", "p1") })
                    add(JsonObject().apply { addProperty("text", "第二段") })
                }
            add("content", JsonObject().apply { add("richText", items) })
        }
        assertEquals(
            DingtalkInbound.Message("m1", "u1", "第一段第二段", listOf(DingtalkMediaRef(AttachmentKind.IMAGE, "p1", null)), null),
            DingtalkEvents.message(data("richText", body)),
        )
    }

    @Test
    fun `图片、视频、文件作为附件`() {
        assertEquals(
            listOf(DingtalkMediaRef(AttachmentKind.IMAGE, "d1", null)),
            (DingtalkEvents.message(data("picture", content("downloadCode" to "d1"))) as DingtalkInbound.Message).media,
        )
        assertEquals(
            listOf(DingtalkMediaRef(AttachmentKind.IMAGE, "pd", null)),
            (DingtalkEvents.message(data("picture", content("pictureDownloadCode" to "pd"))) as DingtalkInbound.Message).media,
        )
        assertEquals(
            listOf(DingtalkMediaRef(AttachmentKind.VIDEO, "v1", null)),
            (DingtalkEvents.message(data("video", content("downloadCode" to "v1", "duration" to "3"))) as DingtalkInbound.Message).media,
        )
        assertEquals(
            listOf(DingtalkMediaRef(AttachmentKind.FILE, "f1", "报告.pdf")),
            (DingtalkEvents.message(data("file", content("downloadCode" to "f1", "fileName" to "报告.pdf"))) as DingtalkInbound.Message).media,
        )
    }

    @Test
    fun `语音有转写时作为文字，没有时作为音频附件`() {
        assertEquals(
            DingtalkInbound.Message("m1", "u1", "跑一下单测", emptyList(), null),
            DingtalkEvents.message(data("audio", content("downloadCode" to "a1", "recognition" to "跑一下单测"))),
        )
        assertEquals(
            DingtalkInbound.Message("m1", "u1", null, listOf(DingtalkMediaRef(AttachmentKind.AUDIO, "a1", null)), null),
            DingtalkEvents.message(data("audio", content("downloadCode" to "a1", "recognition" to " "))),
        )
    }

    @Test
    fun `引用文字：content 为对象或字符串都取得到，引用图片时为空`() {
        val asObject = JsonObject().apply { addProperty("msgType", "text"); add("content", JsonObject().apply { addProperty("text", "原话") }) }
        assertEquals("原话", (DingtalkEvents.message(data("text", textBody("这个呢", asObject))) as DingtalkInbound.Message).quote)
        val asString = JsonObject().apply { addProperty("msgType", "text"); addProperty("content", """{"text":"原话"}""") }
        assertEquals("原话", (DingtalkEvents.message(data("text", textBody("这个呢", asString))) as DingtalkInbound.Message).quote)
        val picture = JsonObject().apply { addProperty("msgType", "picture"); add("content", JsonObject().apply { addProperty("downloadCode", "x") }) }
        assertNull((DingtalkEvents.message(data("text", textBody("这个呢", picture))) as DingtalkInbound.Message).quote)
    }

    @Test
    fun `引用富文本时拼接其中文字`() {
        val replied =
            JsonObject().apply {
                addProperty("msgType", "richText")
                add(
                    "content",
                    JsonObject().apply {
                        add(
                            "richText",
                            com.google.gson.JsonArray().apply {
                                add(JsonObject().apply { addProperty("text", "甲") })
                                add(JsonObject().apply { addProperty("text", "乙") })
                            },
                        )
                    },
                )
            }
        assertEquals("甲乙", (DingtalkEvents.message(data("text", textBody("看这个", replied))) as DingtalkInbound.Message).quote)
    }

    @Test
    fun `群聊、不支持的类型、空文字都忽略`() {
        assertNull(DingtalkEvents.message(data("text", textBody("你好"), conversationType = "2")))
        assertNull(DingtalkEvents.message(data("unknownMsgType", content("unknownMsgType" to "暂不支持"))))
        assertNull(DingtalkEvents.message(data("text", textBody("  "))))
        assertNull(DingtalkEvents.message("not json"))
    }

    @Test
    fun `没有 staffId 时单独标出`() {
        assertEquals(DingtalkInbound.MissingStaffId("m1"), DingtalkEvents.message(data("text", textBody("你好"), staffId = null)))
        assertEquals(DingtalkInbound.MissingStaffId("m1"), DingtalkEvents.message(data("text", textBody("你好"), staffId = "")))
    }

    @Test
    fun `超额消息只剩错误码`() {
        assertEquals(
            DingtalkInbound.QuotaExceeded("20001"),
            DingtalkEvents.message("""{"conversationType":"1","errorCode":20001,"errorMessage":"受调用量超量影响"}"""),
        )
    }

    @Test
    fun `其他错误码不算额度用完，按无内容忽略`() {
        assertNull(DingtalkEvents.message("""{"conversationType":"1","errorCode":50001,"errorMessage":"其他"}"""))
        assertNull(DingtalkEvents.message("""{"conversationType":"1","errorCode":"20002"}"""))
    }

    @Test
    fun `附件引用的字符串不带下载码`() {
        val text = DingtalkMediaRef(AttachmentKind.FILE, "secret-code", "a.pdf").toString()
        assertFalse(text.contains("secret-code"))
        assertTrue(text.contains("a.pdf"))
    }
}
