package com.github.izerui.imux.bridge.channel.feishu

import com.github.izerui.imux.bridge.core.AttachmentKind
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeishuEventsTest {
    private fun event(
        messageType: String,
        content: JsonObject,
        chatType: String = "p2p",
        senderType: String = "user",
        eventType: String = "im.message.receive_v1",
        parentId: String? = null,
    ): String {
        val message =
            JsonObject().apply {
                addProperty("message_id", "om_1")
                addProperty("chat_type", chatType)
                addProperty("message_type", messageType)
                addProperty("content", content.toString())
                parentId?.let { addProperty("parent_id", it) }
            }
        val sender =
            JsonObject().apply {
                add("sender_id", JsonObject().apply { addProperty("open_id", "ou_me") })
                addProperty("sender_type", senderType)
            }
        return JsonObject().apply {
            addProperty("schema", "2.0")
            add("header", JsonObject().apply { addProperty("event_type", eventType) })
            add("event", JsonObject().apply { add("sender", sender); add("message", message) })
        }.toString()
    }

    private fun obj(vararg pairs: Pair<String, String>) =
        JsonObject().apply { pairs.forEach { addProperty(it.first, it.second) } }

    @Test
    fun `文本消息`() {
        assertEquals(
            FeishuMessage("om_1", "ou_me", "跑一下单测", emptyList(), null),
            FeishuEvents.parseMessage(event("text", obj("text" to "跑一下单测")))
        )
    }

    @Test
    fun `图片、文件、视频、语音各生成一个附件`() {
        assertEquals(
            listOf(FeishuMediaRef(AttachmentKind.IMAGE, "img_1", "image", null)),
            FeishuEvents.parseMessage(event("image", obj("image_key" to "img_1")))!!.media
        )
        assertEquals(
            listOf(FeishuMediaRef(AttachmentKind.FILE, "f_1", "file", "报告.pdf")),
            FeishuEvents.parseMessage(event("file", obj("file_key" to "f_1", "file_name" to "报告.pdf")))!!.media
        )
        assertEquals(
            listOf(FeishuMediaRef(AttachmentKind.VIDEO, "v_1", "file", "a.mp4")),
            FeishuEvents.parseMessage(
                event(
                    "media",
                    obj("file_key" to "v_1", "image_key" to "c", "file_name" to "a.mp4")
                )
            )!!.media
        )
        assertEquals(
            listOf(FeishuMediaRef(AttachmentKind.AUDIO, "a_1", "file", null)),
            FeishuEvents.parseMessage(event("audio", obj("file_key" to "a_1")))!!.media
        )
    }

    @Test
    fun `富文本拼接文字并收集图片，兼容语言外壳`() {
        val content =
            com.google.gson.JsonParser.parseString(
                """{"title":"标题","content":[[{"tag":"text","text":"看"},{"tag":"a","text":"链接","href":"h"}],[{"tag":"img","image_key":"img_2"}],[{"tag":"code_block","text":"x=1"}]]}""",
            ).asJsonObject
        val plain = FeishuEvents.parseMessage(event("post", content))!!
        assertEquals("标题\n看链接\nx=1", plain.text)
        assertEquals(listOf(FeishuMediaRef(AttachmentKind.IMAGE, "img_2", "image", null)), plain.media)
        val wrapped = JsonObject().apply { add("zh_cn", content) }
        assertEquals("标题\n看链接\nx=1", FeishuEvents.parseMessage(event("post", wrapped))!!.text)
    }

    @Test
    fun `带出引用的 parent_id`() {
        assertEquals(
            "om_0",
            FeishuEvents.parseMessage(event("text", obj("text" to "这个呢"), parentId = "om_0"))!!.parentId
        )
    }

    @Test
    fun `群聊、机器人发送、其他事件、不支持的类型都忽略`() {
        assertNull(FeishuEvents.parseMessage(event("text", obj("text" to "x"), chatType = "group")))
        assertNull(FeishuEvents.parseMessage(event("text", obj("text" to "x"), senderType = "app")))
        assertNull(FeishuEvents.parseMessage(event("text", obj("text" to "x"), eventType = "im.chat.updated_v1")))
        assertNull(FeishuEvents.parseMessage(event("sticker", obj("file_key" to "s"))))
        assertNull(FeishuEvents.parseMessage("not json"))
    }

    @Test
    fun `引用文字只取文本与富文本`() {
        assertEquals("原话", FeishuEvents.quoteText("text", """{"text":"原话"}"""))
        assertNull(FeishuEvents.quoteText("image", """{"image_key":"i"}"""))
        assertNull(FeishuEvents.quoteText("text", null))
    }

    @Test
    fun `域名按 id 取，未知时默认飞书`() {
        assertEquals(FeishuDomain.LARK, FeishuDomain.of("lark"))
        assertEquals(FeishuDomain.FEISHU, FeishuDomain.of(null))
        assertEquals("https://open.larksuite.com", FeishuDomain.LARK.openBase)
    }
}
