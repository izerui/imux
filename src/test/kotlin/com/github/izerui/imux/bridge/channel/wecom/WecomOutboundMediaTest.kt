package com.github.izerui.imux.bridge.channel.wecom

import com.github.izerui.imux.bridge.core.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

class WecomOutboundMediaTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `文件按分片上传完成后才返回可发送的素材`() = runBlocking {
        val content = ByteArray(512 * 1024 + 17) { (it % 251).toByte() }
        val path = tmp.newFile("report.zip").apply { writeBytes(content) }.toPath()
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val uploaded = uploadWecomMedia(PreparedMedia(path, "report.zip", content.size.toLong(), "zip"), MediaKind.FILE) { cmd, body ->
            calls += cmd to body
            WecomFrame.Ack("r", 0, null, JsonObject().apply {
                if (cmd.endsWith("_init")) addProperty("upload_id", "u")
                if (cmd.endsWith("_finish")) addProperty("media_id", "m")
            })
        }
        assertEquals(listOf("aibot_upload_media_init", "aibot_upload_media_chunk", "aibot_upload_media_chunk", "aibot_upload_media_finish"), calls.map { it.first })
        assertEquals(2, calls[0].second["total_chunks"].asInt)
        assertEquals("file", calls[0].second["type"].asString)
        val first = Base64.getDecoder().decode(calls[1].second["base64_data"].asString)
        val second = Base64.getDecoder().decode(calls[2].second["base64_data"].asString)
        assertArrayEquals(content, first + second)
        assertEquals(0, calls[1].second["chunk_index"].asInt)
        assertEquals(1, calls[2].second["chunk_index"].asInt)
        val body = wecomMediaBody("owner", uploaded)
        assertEquals(1, body["chat_type"].asInt)
        assertEquals("m", body.getAsJsonObject("file")["media_id"].asString)
    }

    @Test fun `素材回应保留正文且旧文本回应仍可解析`() {
        val media = WecomProtocol.parse("""{"headers":{"req_id":"r"},"errcode":0,"body":{"upload_id":"u"}}""") as WecomFrame.Ack
        assertEquals("u", media.body!!["upload_id"].asString)
        val text = WecomProtocol.parse("""{"headers":{"req_id":"t"},"errcode":0,"errmsg":"ok"}""")
        assertEquals(WecomFrame.Ack("t", 0, "ok"), text)
    }

    @Test fun `上传分片失败不继续完成素材`() = runBlocking {
        val path = tmp.newFile("file").apply { writeText("contents") }.toPath()
        val commands = mutableListOf<String>()
        try {
            uploadWecomMedia(PreparedMedia(path, "file", 8, ""), MediaKind.FILE) { command, _ ->
                commands += command
                if (command.endsWith("_init")) WecomFrame.Ack("r", 0, null, JsonParser.parseString("""{"upload_id":"u"}""").asJsonObject)
                else WecomFrame.Ack("r", 846607, "limited")
            }
            fail("应拒绝上传")
        } catch (e: MediaRejected) {
            assertFalse(e.canFallback)
            assertEquals(listOf("aibot_upload_media_init", "aibot_upload_media_chunk"), commands)
        }
    }
}
