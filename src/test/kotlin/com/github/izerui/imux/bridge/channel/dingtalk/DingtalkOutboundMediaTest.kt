package com.github.izerui.imux.bridge.channel.dingtalk

import com.github.izerui.imux.bridge.channel.FakeMediaHttp
import com.github.izerui.imux.bridge.channel.MediaHttpResponse
import com.github.izerui.imux.bridge.core.*
import com.github.izerui.imux.bridge.media.MediaRoute
import com.github.izerui.imux.bridge.media.MediaSendCoordinator
import com.github.izerui.imux.bridge.mcp.mobileMediaCapabilitiesJson
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DingtalkOutboundMediaTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun json(text: String) = JsonParser.parseString(text).asJsonObject

    @Test fun `文件上传使用旧版令牌而发送使用新版请求头`() = runBlocking {
        val http = DingtalkFakeHttp { url, _ ->
            200 to when {
                url.contains("/gettoken") -> """{"errcode":0,"access_token":"old"}"""
                url.endsWith("/accessToken") -> """{"accessToken":"new","expireIn":7200}"""
                else -> """{"processQueryKey":"q","invalidStaffIdList":[],"flowControlledStaffIdList":[]}"""
            }
        }
        val media = FakeMediaHttp().apply { answer = { MediaHttpResponse(200, json("""{"errcode":0,"media_id":"media"}"""), emptyMap()) } }
        val api = DingtalkApi("app", "secret", http, media)
        val path = tmp.newFile("report.pdf").apply { writeText("pdf") }.toPath()
        val file = PreparedMedia(path, "report.pdf", 3, "pdf")
        val uploaded = uploadDingtalkMedia(api, file, MediaKind.FILE)
        assertTrue(media.calls.single().url.contains("access_token=old&type=file"))
        assertEquals("media", media.calls.single().field)
        assertTrue(media.calls.single().headers.isEmpty())
        val (key, param) = dingtalkMediaMessage(file, uploaded)
        val result = api.send("owner", param, key) { true }
        assertTrue(result is DingtalkResult.Ok)
        val sent = http.calls.last()
        assertEquals("new", sent.headers[DingtalkApi.TOKEN_HEADER])
        val body = json(sent.body!!)
        assertEquals("sampleFile", body["msgKey"].asString)
        assertEquals("owner", body.getAsJsonArray("userIds").single().asString)
        assertEquals("media", json(body["msgParam"].asString)["mediaId"].asString)
    }

    @Test fun `视频请求不读封面也不取时长并直接发送原名文件`() = runBlocking {
        val http = DingtalkFakeHttp { url, _ ->
            200 to when {
                url.contains("/gettoken") -> """{"errcode":0,"access_token":"old"}"""
                url.endsWith("/accessToken") -> """{"accessToken":"new","expireIn":7200}"""
                else -> """{"processQueryKey":"q"}"""
            }
        }
        val media = FakeMediaHttp().apply {
            answer = { MediaHttpResponse(200, json("""{"errcode":0,"media_id":"file-media"}"""), emptyMap()) }
        }
        val api = DingtalkApi("app", "secret", http, media)
        val bytes = byteArrayOf(0, 0, 0, 20) + "ftypmp42-video".toByteArray()
        val file = tmp.newFile("demo.mp4").apply { writeBytes(bytes) }
        val sender = object : MediaSender {
            override val mediaCapabilities = dingtalkMediaCapabilities
            override suspend fun uploadMedia(chatId: String, file: PreparedMedia, kind: MediaKind) = uploadDingtalkMedia(api, file, kind)
            override suspend fun sendMedia(chatId: String, file: PreparedMedia, uploaded: UploadedMedia, commit: () -> Boolean): MediaAttempt {
                val (key, param) = dingtalkMediaMessage(file, uploaded)
                return when (val sent = api.send(chatId, param, key, commit)) {
                    is DingtalkResult.Ok -> MediaAttempt.Accepted()
                    is DingtalkResult.Error -> MediaAttempt.Rejected(dingtalkMediaError(sent.code))
                    is DingtalkResult.Network -> MediaAttempt.Unknown
                }
            }
        }
        val route = object : MediaRoute {
            override val channel = "dingtalk"
            override val chatId = "owner"
            override val sender = sender
            override fun isCurrent() = true
            override fun commit(mark: () -> Unit): Boolean { mark(); return true }
        }
        val coordinator = MediaSendCoordinator(this)
        try {
            val result = coordinator.enqueue(
                MediaRequest(file.absolutePath, MediaKind.VIDEO, thumbnailPath = "/missing-cover.png"), route,
            ).await()
            assertEquals("accepted", result.outcome)
            assertEquals(MediaKind.VIDEO, result.requestedKind)
            assertEquals(MediaKind.FILE, result.deliveredAs)
            assertEquals("sent_as_file", result.reason)
            assertFalse(result.fallbackUsed)
            assertNull(result.primaryError)
            assertEquals(1, media.calls.size)
            assertTrue(media.calls.single().url.endsWith("type=file"))
            assertEquals("demo.mp4", media.calls.single().name)
            assertArrayEquals(bytes, media.calls.single().bytes)
            val sent = json(http.calls.last().body!!)
            assertEquals("sampleFile", sent["msgKey"].asString)
            val params = json(sent["msgParam"].asString)
            assertEquals("demo.mp4", params["fileName"].asString)
            assertEquals("mp4", params["fileType"].asString)
            assertFalse(params.has("picMediaId"))
            assertFalse(params.has("duration"))
        } finally { coordinator.close() }
    }

    @Test fun `钉钉视频能力明确声明文件交付且没有必填元数据`() {
        val capability = mobileMediaCapabilitiesJson(dingtalkMediaCapabilities).getAsJsonObject("video")
        assertTrue(capability["supported"].asBoolean)
        assertFalse(capability["native"].asBoolean)
        assertEquals("file", capability["delivery_as"].asString)
        assertEquals(0, capability.getAsJsonArray("required_fields").size())
    }

    @Test fun `HTTP 成功仍返回被限流的收件人`() = runBlocking {
        val http = DingtalkFakeHttp { url, _ ->
            200 to if (url.endsWith("/accessToken")) """{"accessToken":"token","expireIn":7200}"""
            else """{"processQueryKey":"q","flowControlledStaffIdList":["owner"]}"""
        }
        val api = DingtalkApi("app", "secret", http)
        val result = api.send("owner", "{}", "sampleImageMsg") { true } as DingtalkResult.Ok
        assertEquals(listOf("owner"), result.value.flowControlledStaffIds)
    }

    @Test fun `最终消息服务端错误和空成功体都不能报已接受`() = runBlocking {
        var serverError = true
        val http = DingtalkFakeHttp { url, _ ->
            if (url.endsWith("/accessToken")) 200 to """{"accessToken":"token","expireIn":7200}"""
            else if (serverError) 500 to """{"code":"system.error"}""" else 200 to "{}"
        }
        val api = DingtalkApi("app", "secret", http)
        assertTrue(api.send("owner", "{}", "sampleFile") { true } is DingtalkResult.Network)
        serverError = false
        assertTrue(api.send("owner", "{}", "sampleFile") { true } is DingtalkResult.Network)
        assertEquals(2, http.calls.count { it.url.endsWith("/batchSend") })
    }
}
