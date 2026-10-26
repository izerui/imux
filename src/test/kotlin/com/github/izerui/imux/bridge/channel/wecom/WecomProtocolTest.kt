package com.github.izerui.imux.bridge.channel.wecom

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class WecomProtocolTest {
    private fun obj(text: String) = JsonParser.parseString(text).asJsonObject

    @Test
    fun `订阅帧带 bot_id 与 secret`() {
        val frame = obj(WecomProtocol.subscribe("r1", "B", "S"))
        assertEquals("aibot_subscribe", frame["cmd"].asString)
        assertEquals("r1", frame["headers"].asJsonObject["req_id"].asString)
        assertEquals("B", frame["body"].asJsonObject["bot_id"].asString)
        assertEquals("S", frame["body"].asJsonObject["secret"].asString)
    }

    @Test
    fun `主动推送显式单聊并使用 markdown`() {
        val body = obj(WecomProtocol.sendMarkdown("r2", "zhangsan", "你好\n`x`"))["body"].asJsonObject
        assertEquals("zhangsan", body["chatid"].asString)
        assertEquals(1, body["chat_type"].asInt)
        assertEquals("markdown", body["msgtype"].asString)
        assertEquals("你好\n`x`", body["markdown"].asJsonObject["content"].asString)
    }

    @Test
    fun `req_id 以命令名开头且唯一`() {
        val random = Random(1)
        val a = WecomProtocol.reqId("ping", 1000, random)
        val b = WecomProtocol.reqId("ping", 1000, random)
        assertTrue(a.startsWith("ping_1000_"))
        assertTrue(a != b)
    }

    @Test
    fun `解析确认帧`() {
        assertEquals(WecomFrame.Ack("r1", 0, "ok"), WecomProtocol.parse("""{"headers":{"req_id":"r1"},"errcode":0,"errmsg":"ok"}"""))
        assertEquals(WecomFrame.Ack("r2", 846607, "too many"), WecomProtocol.parse("""{"headers":{"req_id":"r2"},"errcode":846607,"errmsg":"too many"}"""))
    }

    @Test
    fun `解析单聊文本、语音与图文混排`() {
        val text =
            """{"cmd":"aibot_msg_callback","headers":{"req_id":"R"},"body":{"msgid":"m1","aibotid":"A","chattype":"single","from":{"userid":"u1"},"msgtype":"text","text":{"content":"跑一下单测"}}}"""
        assertEquals(WecomFrame.Message("R", "m1", true, "u1", "跑一下单测"), WecomProtocol.parse(text))
        val voice =
            """{"cmd":"aibot_msg_callback","headers":{"req_id":"R"},"body":{"msgid":"m2","chattype":"single","from":{"userid":"u1"},"msgtype":"voice","voice":{"content":"帮我检查代码"}}}"""
        assertEquals("帮我检查代码", (WecomProtocol.parse(voice) as WecomFrame.Message).text)
        val mixed =
            """{"cmd":"aibot_msg_callback","headers":{"req_id":"R"},"body":{"msgid":"m3","chattype":"single","from":{"userid":"u1"},"msgtype":"mixed","mixed":{"msg_item":[{"msgtype":"text","text":{"content":"看图"}},{"msgtype":"image","image":{"url":"u"}},{"msgtype":"text","text":{"content":"修一下"}}]}}}"""
        assertEquals("看图\n修一下", (WecomProtocol.parse(mixed) as WecomFrame.Message).text)
    }

    @Test
    fun `群聊、图片和兼容来源的消息没有可提交的正文`() {
        val group =
            """{"cmd":"aibot_msg_callback","headers":{"req_id":"R"},"body":{"msgid":"m","chatid":"g","chattype":"group","from":{"userid":"u"},"msgtype":"text","text":{"content":"hi"}}}"""
        assertEquals(false, (WecomProtocol.parse(group) as WecomFrame.Message).single)
        val image =
            """{"cmd":"aibot_msg_callback","headers":{"req_id":"R"},"body":{"msgid":"m","chattype":"single","from":{"userid":"u"},"msgtype":"image","image":{"url":"x","aeskey":"k"}}}"""
        assertNull((WecomProtocol.parse(image) as WecomFrame.Message).text)
        val compat =
            """{"cmd":"aibot_msg_callback","headers":{"req_id":"R"},"body":{"msgid":"m","chattype":"single","from":{"userid":"u"},"msgtype":"text","text":{"content":"hi"},"compat_info":{"source":"ai_assistant"}}}"""
        assertNull((WecomProtocol.parse(compat) as WecomFrame.Message).text)
    }

    @Test
    fun `被踢事件与其它事件`() {
        val kicked =
            """{"cmd":"aibot_event_callback","headers":{"req_id":"R"},"body":{"msgid":"m","msgtype":"event","event":{"eventtype":"disconnected_event"}}}"""
        assertEquals(WecomFrame.Kicked, WecomProtocol.parse(kicked))
        val enter =
            """{"cmd":"aibot_event_callback","headers":{"req_id":"R"},"body":{"msgtype":"event","from":{"userid":"u"},"event":{"eventtype":"enter_chat"}}}"""
        assertEquals(WecomFrame.Event("enter_chat"), WecomProtocol.parse(enter))
    }

    @Test
    fun `无法解析的内容返回 Unknown`() {
        assertEquals(WecomFrame.Unknown, WecomProtocol.parse("not json"))
        assertEquals(WecomFrame.Unknown, WecomProtocol.parse("""{"cmd":"something_new"}"""))
    }

    @Test
    fun `errcode 为字符串数字仍解析为 Ack`() {
        assertEquals(WecomFrame.Ack("r1", 0, "ok"), WecomProtocol.parse("""{"headers":{"req_id":"r1"},"errcode":"0","errmsg":"ok"}"""))
    }

    @Test
    fun `errcode 为布尔值返回 Unknown`() {
        assertEquals(WecomFrame.Unknown, WecomProtocol.parse("""{"headers":{"req_id":"r1"},"errcode":true,"errmsg":"ok"}"""))
    }

    @Test
    fun `errcode 为非数字字符串返回 Unknown`() {
        assertEquals(WecomFrame.Unknown, WecomProtocol.parse("""{"headers":{"req_id":"r1"},"errcode":"oops","errmsg":"ok"}"""))
    }
}
