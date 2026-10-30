package com.github.izerui.imux.bridge.channel.weixin

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException
import java.util.Base64
import java.util.Random

class WeixinApiTest {
    @Volatile
    private var answer: Pair<Int, String> = 200 to """{"ret":0}"""

    private val http = WeixinFakeHttp { _, _, _ -> answer }
    private val api = WeixinApi(http, http, Random(1))

    @Test
    fun `公共请求头与 Bearer`() {
        val plain = api.headers(null)
        assertEquals("ilink_bot_token", plain["AuthorizationType"])
        assertEquals("bot", plain["iLink-App-Id"])
        assertEquals("132106", plain["iLink-App-ClientVersion"])
        assertNull(plain["Authorization"])
        assertFalse("Content-Type 由请求方法自己设置", plain.containsKey("Content-Type"))
        val uin = String(Base64.getDecoder().decode(plain["X-WECHAT-UIN"]))
        assertTrue("uin 是十进制 uint32：$uin", uin.matches(Regex("\\d+")) && uin.toLong() in 0..0xFFFFFFFFL)
        assertEquals("Bearer T", api.headers("T")["Authorization"])
    }

    @Test
    fun `取码用 GET，返回票据与二维码链接`() =
        runBlocking {
            answer = 200 to """{"qrcode":"q1","qrcode_img_content":"https://liteapp.weixin.qq.com/q/abc"}"""
            assertEquals(WeixinResult.Ok(WeixinQrCode("q1", "https://liteapp.weixin.qq.com/q/abc")), api.qrCode())
            val call = http.calls.single()
            assertEquals("GET", call.method)
            assertEquals("https://ilinkai.weixin.qq.com/ilink/bot/get_bot_qrcode?bot_type=3", call.url)
            answer = 200 to """{"qrcode":"q1"}"""
            assertTrue(api.qrCode() is WeixinResult.Network)
        }

    @Test
    fun `扫码状态解析`() =
        runBlocking {
            answer = 200 to """{"status":"confirmed","bot_token":"BT","ilink_bot_id":"b@im.bot","baseurl":"https://x.weixin.qq.com","ilink_user_id":"u@im.wechat"}"""
            val status = (api.qrCodeStatus("https://h", "q 1") as WeixinResult.Ok).value
            assertEquals(WeixinLoginStatus("confirmed", null, "BT", "b@im.bot", "https://x.weixin.qq.com", "u@im.wechat"), status)
            assertEquals("https://h/ilink/bot/get_qrcode_status?qrcode=q+1", http.calls.single().url)
            assertFalse("toString 不含 token", status.toString().contains("BT"))
            answer = 200 to """{"status":"scaned_but_redirect","redirect_host":"sh.weixin.qq.com"}"""
            assertEquals("sh.weixin.qq.com", (api.qrCodeStatus("https://h", "q") as WeixinResult.Ok).value.redirectHost)
        }

    @Test
    fun `getupdates 请求体与结果`() =
        runBlocking {
            answer = 200 to """{"ret":0,"msgs":[{"message_id":1},{"message_id":2}],"get_updates_buf":"c2"}"""
            val updates = (api.updates("https://b", "T", "c1") as WeixinResult.Ok).value
            assertEquals(2, updates.messages.size)
            assertEquals("c2", updates.cursor)
            val call = http.calls.single()
            assertEquals("https://b/ilink/bot/getupdates", call.url)
            assertEquals("Bearer T", call.headers["Authorization"])
            val body = JsonParser.parseString(call.body).asJsonObject
            assertEquals("c1", body["get_updates_buf"].asString)
            assertEquals("2.4.10", body["base_info"].asJsonObject["channel_version"].asString)
            answer = 200 to """{"ret":0,"msgs":[],"get_updates_buf":""}"""
            assertNull("空游标视为没有新游标", (api.updates("https://b", "T", "c2") as WeixinResult.Ok).value.cursor)
        }

    @Test
    fun `WeixinUpdates 的 toString 不含原始消息`() {
        val updates = WeixinUpdates(listOf(JsonParser.parseString("""{"context_token":"CT-SECRET","text":"正文"}""").asJsonObject), "c9")
        val text = updates.toString()
        assertFalse(text, text.contains("CT-SECRET") || text.contains("正文") || text.contains("c9"))
        assertEquals("WeixinUpdates(messages=1, cursor=***)", text)
        assertEquals("WeixinUpdates(messages=0, cursor=null)", WeixinUpdates(emptyList(), null).toString())
    }

    @Test
    fun `sendmessage 请求体`() =
        runBlocking {
            answer = 200 to """{"ret":0}"""
            assertEquals(WeixinResult.Ok(Unit), api.send("https://b", "T", "u@im.wechat", "你好", "CT"))
            val msg = JsonParser.parseString(http.calls.single().body).asJsonObject["msg"].asJsonObject
            assertEquals("", msg["from_user_id"].asString)
            assertEquals("u@im.wechat", msg["to_user_id"].asString)
            assertEquals(2, msg["message_type"].asInt)
            assertEquals(2, msg["message_state"].asInt)
            assertEquals("CT", msg["context_token"].asString)
            assertTrue(msg["client_id"].asString.isNotEmpty())
            val item = msg["item_list"].asJsonArray.single().asJsonObject
            assertEquals(1, item["type"].asInt)
            assertEquals("你好", item["text_item"].asJsonObject["text"].asString)
            api.send("https://b", "T", "u@im.wechat", "再来", null)
            val second = JsonParser.parseString(http.calls.last().body).asJsonObject["msg"].asJsonObject
            assertFalse("没有 context_token 就不带（官方照发）", second.has("context_token"))
        }

    @Test
    fun `ret 与 errcode 判定`() =
        runBlocking {
            answer = 200 to """{"ret":-14,"errmsg":"session timeout"}"""
            val expired = api.send("https://b", "T", "u", "x", null) as WeixinResult.Error
            assertTrue(expired.expired)
            assertEquals("-14/0", expired.code)
            answer = 200 to """{"errcode":-14}"""
            assertTrue((api.send("https://b", "T", "u", "x", null) as WeixinResult.Error).expired)
            answer = 200 to """{"ret":-2,"errmsg":"unknown error"}"""
            val rejected = api.send("https://b", "T", "u", "x", null) as WeixinResult.Error
            assertFalse(rejected.expired)
            assertEquals("-2/0", rejected.code)
            assertEquals("unknown error", rejected.message)
            answer = 500 to "<html>"
            assertEquals(WeixinResult.Network("HTTP 500"), api.send("https://b", "T", "u", "x", null))
            answer = 502 to """{"errmsg":"bad gateway"}"""
            assertEquals("没有 ret、errcode 时显示 HTTP 状态", "HTTP 502", (api.send("https://b", "T", "u", "x", null) as WeixinResult.Error).code)
            answer = 400 to """{"ret":-1}"""
            assertEquals("-1/0", (api.send("https://b", "T", "u", "x", null) as WeixinResult.Error).code)
            answer = 200 to """{}"""
            assertEquals(WeixinResult.Ok(Unit), api.send("https://b", "T", "u", "x", null))
        }

    @Test
    fun `网络错误与超时`() =
        runBlocking {
            val timeoutApi = WeixinApi(WeixinFakeHttp { _, _, _ -> throw HttpTimeoutException("slow https://secret") }, WeixinFakeHttp { _, _, _ -> throw HttpTimeoutException("slow") })
            assertEquals(WeixinResult.Network("timeout", timeout = true), timeoutApi.updates("https://b", "T", ""))
            val brokenApi = WeixinApi(WeixinFakeHttp { _, _, _ -> throw IOException("reset https://secret") }, WeixinFakeHttp { _, _, _ -> throw IOException("x") })
            val network = brokenApi.send("https://b", "T", "u", "x", null) as WeixinResult.Network
            assertEquals("IOException", network.reason)
            assertFalse(network.timeout)
        }

    @Test
    fun `连接超时不当作长轮询超时，计入失败`() =
        runBlocking {
            val connectTimeout = WeixinFakeHttp { _, _, _ -> throw HttpConnectTimeoutException("connect https://secret") }
            assertEquals(WeixinResult.Network("HttpConnectTimeoutException"), WeixinApi(connectTimeout, connectTimeout).updates("https://b", "T", ""))
        }

    @Test
    fun `规范化 baseurl`() {
        assertEquals("https://ilinkai.weixin.qq.com", WeixinApi.normalizeBaseUrl(null))
        assertEquals("https://ilinkai.weixin.qq.com", WeixinApi.normalizeBaseUrl("  "))
        assertEquals("https://x.qq.com", WeixinApi.normalizeBaseUrl("x.qq.com/"))
        assertEquals("http://x.qq.com", WeixinApi.normalizeBaseUrl("http://x.qq.com"))
    }
}
