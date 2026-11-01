package com.github.izerui.imux.bridge.channel.feishu

import com.github.izerui.imux.bridge.channel.FakeMediaHttp
import com.github.izerui.imux.bridge.channel.MediaHttpResponse
import com.github.izerui.imux.bridge.core.*
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FeishuOutboundMediaTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun json(text: String) = JsonParser.parseString(text).asJsonObject

    @Test fun `图片与普通文件各自调用实际上传接口并使用对应资源键`() = runBlocking {
        val http = FakeHttpJson { _, _, _ -> 200 to """{"code":0,"tenant_access_token":"token","expire":7200}""" }
        val upload = FakeMediaHttp().apply {
            answer = { call ->
                MediaHttpResponse(200, json(if (call.field == "image") """{"code":0,"data":{"image_key":"img"}}""" else """{"code":0,"data":{"file_key":"file"}}"""), emptyMap())
            }
        }
        val api = FeishuApi("app", "secret", FeishuDomain.FEISHU, http, upload)
        val path = tmp.newFile("report.txt").apply { writeText("contents") }.toPath()
        val file = PreparedMedia(path, "report.txt", 8, "txt")
        val image = uploadFeishuMedia(api, file, MediaKind.IMAGE)
        assertTrue(upload.calls[0].url.endsWith("/images"))
        assertEquals(mapOf("image_type" to "message"), upload.calls[0].fields)
        assertEquals("image", upload.calls[0].field)
        assertEquals("img", image.fields["image_key"])
        val document = uploadFeishuMedia(api, file, MediaKind.FILE)
        assertTrue(upload.calls[1].url.endsWith("/files"))
        assertEquals("stream", upload.calls[1].fields["file_type"])
        assertEquals("file", upload.calls[1].field)
        assertEquals("file", document.fields["file_key"])
        assertArrayEquals("contents".toByteArray(), upload.calls[1].bytes)
        assertEquals("Bearer token", upload.calls[1].headers["Authorization"])
    }

    @Test fun `视频没有封面仍按官方可选字段发送`() = runBlocking {
        val http = FakeHttpJson { _, _, _ -> 200 to """{"code":0,"tenant_access_token":"token","expire":7200}""" }
        val upload = FakeMediaHttp().apply { answer = { MediaHttpResponse(200, json("""{"code":0,"data":{"file_key":"video"}}"""), emptyMap()) } }
        val api = FeishuApi("app", "secret", FeishuDomain.FEISHU, http, upload)
        val path = tmp.newFile("demo.mp4").toPath()
        val uploaded = uploadFeishuMedia(api, PreparedMedia(path, "demo.mp4", 0, "mp4", 1500), MediaKind.VIDEO)
        assertEquals("mp4", upload.calls.single().fields["file_type"])
        assertEquals("1500", upload.calls.single().fields["duration"])
        val body = json(feishuMediaBody("owner", uploaded))
        assertEquals("media", body["msg_type"].asString)
        assertEquals("owner", body["receive_id"].asString)
        assertEquals("""{"file_key":"video"}""", body["content"].asString)
    }

    @Test fun `最终发送守卫拒绝后不会调用消息接口`() = runBlocking {
        val http = FakeHttpJson { _, _, _ -> 200 to """{"code":0,"tenant_access_token":"token","expire":7200}""" }
        val api = FeishuApi("app", "secret", FeishuDomain.FEISHU, http)
        val result = api.sendMediaBody("{}") { false }
        assertEquals(FeishuResult.Error(-1, "binding_changed"), result)
        assertEquals(1, http.calls.size)
        assertTrue(http.calls.single().url.endsWith("/tenant_access_token/internal"))
    }

    @Test fun `最终消息返回服务端错误时不能认作确定拒绝`() = runBlocking {
        val http = FakeHttpJson { _, url, _ ->
            if (url.endsWith("/tenant_access_token/internal")) 200 to """{"code":0,"tenant_access_token":"token","expire":7200}"""
            else 500 to """{"code":1000040343,"msg":"internal error"}"""
        }
        val api = FeishuApi("app", "secret", FeishuDomain.FEISHU, http)
        assertTrue(api.sendMediaBody("{}") { true } is FeishuResult.Network)
        assertEquals(1, http.calls.count { it.url.contains("/im/v1/messages?") })
    }

    @Test fun `媒体发送令牌明确失效后刷新一次并再次检查绑定`() = runBlocking {
        var tokens = 0
        var sends = 0
        var claims = 0
        val http = FakeHttpJson { _, url, _ ->
            if (url.endsWith("/tenant_access_token/internal")) 200 to """{"code":0,"tenant_access_token":"T${++tokens}","expire":7200}"""
            else if (++sends == 1) 401 to """{"code":99991663,"msg":"expired"}"""
            else 200 to """{"code":0,"data":{"message_id":"sent"}}"""
        }
        val api = FeishuApi("app", "secret", FeishuDomain.FEISHU, http)
        assertEquals(FeishuResult.Ok("sent"), api.sendMediaBody("{}") { claims++; true })
        assertEquals(2, tokens)
        assertEquals(2, sends)
        assertEquals(2, claims)
        assertEquals(listOf("Bearer T1", "Bearer T2"), http.calls.filter { it.url.contains("/im/v1/messages?") }.map { it.authorization })
    }
}
