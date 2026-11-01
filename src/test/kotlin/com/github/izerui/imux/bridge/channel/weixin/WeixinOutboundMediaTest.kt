package com.github.izerui.imux.bridge.channel.weixin

import com.github.izerui.imux.bridge.channel.FakeMediaHttp
import com.github.izerui.imux.bridge.channel.MediaHttpResponse
import com.github.izerui.imux.bridge.core.*
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class WeixinOutboundMediaTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun json(text: String) = JsonParser.parseString(text).asJsonObject

    @Test fun `文件加密上传后按官方密钥格式构造附件并带回上下文`() = runBlocking {
        val http = WeixinFakeHttp { _, url, _ ->
            200 to if (url.endsWith("getuploadurl")) """{"ret":0,"upload_full_url":"https://cdn.test/upload"}""" else """{"ret":0}"""
        }
        val media = FakeMediaHttp().apply { answer = { MediaHttpResponse(200, null, mapOf("x-encrypted-param" to "download")) } }
        val api = WeixinApi(http)
        val bytes = ByteArray(32) { it.toByte() }
        val path = tmp.newFile("audio.mp3").apply { writeBytes(bytes) }.toPath()
        val file = PreparedMedia(path, "audio.mp3", bytes.size.toLong(), "mp3")
        val uploaded = uploadWeixinMedia(api, media, "https://api.test", "token", "owner", file, MediaKind.FILE)
        val request = json(http.calls.first().body!!)
        assertEquals(3, request["media_type"].asInt)
        assertEquals("owner", request["to_user_id"].asString)
        assertEquals(48, request["filesize"].asInt)
        assertTrue(media.calls.single().headers.isEmpty())
        val hex = String(Base64.getDecoder().decode(uploaded.fields.getValue("aes_key")))
        assertEquals(request["aeskey"].asString, hex)
        val key = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val decrypt = Cipher.getInstance("AES/ECB/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES")) }
        assertArrayEquals(bytes, decrypt.doFinal(media.calls.single().bytes))
        val item = weixinMediaItem(file, uploaded)
        assertEquals(4, item["type"].asInt)
        assertEquals("audio.mp3", item.getAsJsonObject("file_item")["file_name"].asString)
        assertEquals("32", item.getAsJsonObject("file_item")["len"].asString)
        assertTrue(api.sendMediaItem("https://api.test", "token", "owner", item, "context") { true } is WeixinResult.Ok)
        val sent = json(http.calls.last().body!!).getAsJsonObject("msg")
        assertEquals("context", sent["context_token"].asString)
        assertEquals("owner", sent["to_user_id"].asString)
        assertArrayEquals(bytes, Files.readAllBytes(path))
    }

    @Test fun `图片和视频分别产生对应的消息项`() {
        val file = PreparedMedia(tmp.newFile("media").toPath(), "media", 16, "mp4")
        val fields = mapOf("encrypt_query_param" to "d", "aes_key" to "k", "cipher_size" to "32")
        val image = weixinMediaItem(file, UploadedMedia(MediaKind.IMAGE, fields))
        assertEquals(2, image["type"].asInt)
        assertEquals(32, image.getAsJsonObject("image_item")["mid_size"].asInt)
        val video = weixinMediaItem(file, UploadedMedia(MediaKind.VIDEO, fields))
        assertEquals(5, video["type"].asInt)
        assertEquals(32, video.getAsJsonObject("video_item")["video_size"].asInt)
    }

    @Test fun `上传缺少下载凭证不能当作发送成功`() = runBlocking {
        val http = WeixinFakeHttp { _, _, _ -> 200 to """{"upload_full_url":"https://cdn.test/upload"}""" }
        val media = FakeMediaHttp().apply { answer = { MediaHttpResponse(200, null, emptyMap()) } }
        val path = tmp.newFile("file").apply { writeText("contents") }.toPath()
        try {
            uploadWeixinMedia(WeixinApi(http), media, "https://api.test", "token", "owner", PreparedMedia(path, "file", 8, ""), MediaKind.FILE)
            fail("缺凭证必须失败")
        } catch (e: MediaRejected) {
            assertEquals("cdn_upload_failed", e.code)
            assertEquals(1, http.calls.size)
            assertTrue(http.calls.single().url.endsWith("getuploadurl"))
        }
    }

    @Test fun `最终媒体消息的服务端错误不触发重试`() = runBlocking {
        val http = WeixinFakeHttp { _, _, _ -> 503 to """{"ret":-1,"errmsg":"unavailable"}""" }
        val result = WeixinApi(http).sendMediaItem("https://api.test", "token", "owner", json("""{"type":4}"""), null) { true }
        assertTrue(result is WeixinResult.Network)
        assertEquals(1, http.calls.size)
    }
}
