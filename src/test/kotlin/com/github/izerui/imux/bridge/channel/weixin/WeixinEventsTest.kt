package com.github.izerui.imux.bridge.channel.weixin

import com.github.izerui.imux.bridge.core.AttachmentKind
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class WeixinEventsTest {
    private fun msg(
        items: String,
        extra: String = "",
    ): JsonObject =
        JsonParser.parseString(
            """{"message_id":12345678901234567890,"from_user_id":"u@im.wechat","to_user_id":"b@im.bot","create_time_ms":1700000000000,"message_type":1,"context_token":"CT","item_list":[$items]$extra}""",
        ).asJsonObject

    private val key16 = ByteArray(16) { it.toByte() }
    private val keyHex = "000102030405060708090a0b0c0d0e0f"

    @Test
    fun `文本消息与顶层字段`() {
        val m = WeixinEvents.message(msg("""{"type":1,"text_item":{"text":"你好"}}"""))!!
        assertEquals("12345678901234567890", m.messageId)
        assertEquals("u@im.wechat", m.fromUserId)
        assertEquals(1700000000000L, m.createTimeMillis)
        assertEquals("CT", m.contextToken)
        assertEquals("你好", m.text)
        assertEquals(emptyList<WeixinMediaRef>(), m.media)
        assertNull(m.quote)
        assertFalse("toString 不含正文与 context_token", m.toString().contains("你好") || m.toString().contains("CT"))
    }

    @Test
    fun `多个 item 的文字按顺序换行拼接`() {
        val m = WeixinEvents.message(msg("""{"type":1,"text_item":{"text":"一"}},{"type":1,"text_item":{"text":"二"}}"""))!!
        assertEquals("一\n二", m.text)
    }

    @Test
    fun `语音有转写作为文字，没有作为音频附件`() {
        val withText = WeixinEvents.message(msg("""{"type":3,"voice_item":{"text":"跑一下单测","media":{"full_url":"https://cdn/v","aes_key":"x"}}}"""))!!
        assertEquals("跑一下单测", withText.text)
        assertEquals(emptyList<WeixinMediaRef>(), withText.media)
        val aesKey = Base64.getEncoder().encodeToString(key16)
        val noText = WeixinEvents.message(msg("""{"type":3,"voice_item":{"media":{"full_url":"https://cdn/v","aes_key":"$aesKey"}}}"""))!!
        assertNull(noText.text)
        assertEquals(listOf(WeixinMediaRef(AttachmentKind.AUDIO, "https://cdn/v", null, keyHex, null)), noText.media)
    }

    @Test
    fun `图片、文件、视频与三种密钥格式`() {
        val hexInBase64 = Base64.getEncoder().encodeToString(keyHex.toByteArray())
        val image = WeixinEvents.message(msg("""{"type":2,"image_item":{"aeskey":"${keyHex.uppercase()}","media":{"encrypt_query_param":"p+1"}}}"""))!!
        assertEquals(listOf(WeixinMediaRef(AttachmentKind.IMAGE, null, "p+1", keyHex, null)), image.media)
        val plainImage = WeixinEvents.message(msg("""{"type":2,"image_item":{"media":{"full_url":"https://cdn/i"}}}"""))!!
        assertEquals(listOf(WeixinMediaRef(AttachmentKind.IMAGE, "https://cdn/i", null, null, null)), plainImage.media)
        val file = WeixinEvents.message(msg("""{"type":4,"file_item":{"file_name":"报告.pdf","media":{"full_url":"https://cdn/f","aes_key":"$hexInBase64"}}}"""))!!
        assertEquals(listOf(WeixinMediaRef(AttachmentKind.FILE, "https://cdn/f", null, keyHex, "报告.pdf")), file.media)
        val video = WeixinEvents.message(msg("""{"type":5,"video_item":{"media":{"full_url":"https://cdn/m","aes_key":"$hexInBase64"}}}"""))!!
        assertEquals(AttachmentKind.VIDEO, video.media.single().kind)
    }

    @Test
    fun `非图片缺密钥或缺地址时不作为附件`() {
        assertNull(WeixinEvents.message(msg("""{"type":4,"file_item":{"file_name":"a","media":{"full_url":"https://cdn/f"}}}""")))
        assertNull(WeixinEvents.message(msg("""{"type":2,"image_item":{"media":{}}}""")))
    }

    @Test
    fun `引用取被引用的文字，取不到用标题`() {
        val quoted =
            WeixinEvents.message(
                msg("""{"type":1,"text_item":{"text":"这个呢"},"ref_msg":{"title":"摘要","message_item":{"type":1,"text_item":{"text":"原话"}}}}"""),
            )!!
        assertEquals("原话", quoted.quote)
        val titled = WeixinEvents.message(msg("""{"type":1,"text_item":{"text":"这个呢"},"ref_msg":{"title":"摘要"}}"""))!!
        assertEquals("摘要", titled.quote)
    }

    @Test
    fun `只收用户单聊消息；无内容丢弃`() {
        assertNull(WeixinEvents.message(msg("""{"type":1,"text_item":{"text":"x"}}""").apply { addProperty("message_type", 2) }))
        assertNull(WeixinEvents.message(msg("""{"type":1,"text_item":{"text":"x"}}""", ""","group_id":"g1"""")))
        assertNull(WeixinEvents.message(msg("""{"type":1,"text_item":{"text":"  "}}""")))
        assertNull(WeixinEvents.message(msg("""{"type":11}""")))
        val noId = msg("""{"type":1,"text_item":{"text":"x"}}""").apply { remove("message_id"); addProperty("seq", 7) }
        assertEquals("seq:7", WeixinEvents.message(noId)!!.messageId)
    }

    @Test
    fun `密钥解析`() {
        assertEquals(keyHex, WeixinEvents.hexKey(keyHex.uppercase()))
        assertNull(WeixinEvents.hexKey("xyz"))
        assertEquals(keyHex, WeixinEvents.base64Key(Base64.getEncoder().encodeToString(key16)))
        assertEquals(keyHex, WeixinEvents.base64Key(Base64.getEncoder().encodeToString(keyHex.toByteArray())))
        assertNull(WeixinEvents.base64Key(Base64.getEncoder().encodeToString(ByteArray(8))))
        assertNull(WeixinEvents.base64Key("not base64!!"))
    }
}
