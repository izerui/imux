package com.github.izerui.imux.bridge.channel.feishu

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class FeishuApiTest {
    private var clock = 0L
    private val tokens = AtomicInteger()

    private fun api(route: suspend (String, String, String?) -> Pair<Int, String>): Pair<FeishuApi, FakeHttpJson> {
        val http =
            FakeHttpJson { method, url, body ->
                if (url.endsWith("/tenant_access_token/internal")) {
                    200 to """{"code":0,"tenant_access_token":"T${tokens.incrementAndGet()}","expire":7200}"""
                } else {
                    route(method, url, body)
                }
            }
        return FeishuApi("cli_1", "sec", FeishuDomain.FEISHU, http) { clock } to http
    }

    @Test
    fun `取长连接地址与客户端配置`() =
        runBlocking {
            val (api, http) =
                api { _, _, _ -> 200 to """{"code":0,"data":{"URL":"wss://x/ws?device_id=d&service_id=7","ClientConfig":{"PingInterval":90,"ReconnectInterval":60,"ReconnectNonce":10,"ReconnectCount":-1}}}""" }
            val endpoint = (api.endpoint() as FeishuResult.Ok).value
            assertEquals("wss://x/ws?device_id=d&service_id=7", endpoint.url)
            assertEquals(FeishuClientConfig(-1, 60, 10, 90), endpoint.config)
            val call = http.calls.single()
            assertEquals("https://open.feishu.cn/callback/ws/endpoint", call.url)
            assertEquals("cli_1", JsonParser.parseString(call.body).asJsonObject["AppID"].asString)
        }

    @Test
    fun `长连接地址不出现在 toString 里`() {
        val endpoint = FeishuEndpoint("wss://x/ws?service_id=7&access_key=secret&ticket=t", FeishuClientConfig(-1, 60, 10, 90))
        val text = endpoint.toString()
        assertTrue(text, "secret" !in text && "wss://" !in text)
        assertTrue(text, "pingIntervalSeconds=90" in text)
    }

    @Test
    fun `长连接地址的业务错误与网络错误分开返回`() =
        runBlocking {
            assertEquals(
                FeishuResult.Error(10015, "wrong app secret"),
                api { _, _, _ -> 200 to """{"code":10015,"msg":"wrong app secret"}""" }.first.endpoint()
            )
            assertTrue(api { _, _, _ -> 502 to "<html>" }.first.endpoint() is FeishuResult.Network)
        }

    @Test
    fun `令牌缓存到剩余不足 10 分钟才重新获取`() =
        runBlocking {
            val (api, _) = api { _, _, _ -> 200 to """{"code":0}""" }
            assertEquals(FeishuResult.Ok("T1"), api.token())
            clock = (7200 - 601) * 1_000L
            assertEquals(FeishuResult.Ok("T1"), api.token())
            clock = (7200 - 599) * 1_000L
            assertEquals(FeishuResult.Ok("T2"), api.token())
        }

    @Test
    fun `发送带令牌；令牌无效时重取并只重试一次`() =
        runBlocking {
            val sends = AtomicInteger()
            val (api, http) = api { _, _, _ -> if (sends.incrementAndGet() == 1) 400 to """{"code":99991663,"msg":"invalid"}""" else 200 to """{"code":0}""" }
            assertEquals(FeishuResult.Ok(Unit), api.send(FeishuApi.markdownBody("ou_me", "**完成**")))
            val sendCalls = http.calls.filter { it.url.contains("/im/v1/messages") }
            assertEquals(listOf("Bearer T1", "Bearer T2"), sendCalls.map { it.authorization })
            assertEquals(
                "https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=open_id",
                sendCalls.first().url
            )

            val always = AtomicInteger()
            val (stubborn, _) = api { _, _, _ -> always.incrementAndGet(); 400 to """{"code":99991663,"msg":"invalid"}""" }
            assertEquals(FeishuResult.Error(99991663, "invalid"), stubborn.send("{}"))
            assertEquals(2, always.get())
        }

    @Test
    fun `Markdown 正文放在富文本的 md 标签里`() {
        val body = JsonParser.parseString(FeishuApi.markdownBody("ou_me", "# 标题\n\"引号\"")).asJsonObject
        assertEquals("ou_me", body["receive_id"].asString)
        assertEquals("post", body["msg_type"].asString)
        val content = JsonParser.parseString(body["content"].asString).asJsonObject
        val md = content["zh_cn"].asJsonObject["content"].asJsonArray[0].asJsonArray[0].asJsonObject
        assertEquals("md", md["tag"].asString)
        assertEquals("# 标题\n\"引号\"", md["text"].asString)
    }

    @Test
    fun `取引用消息的文字，失败时为 null`() =
        runBlocking {
            val (api, http) = api { _, _, _ -> 200 to """{"code":0,"data":{"items":[{"msg_type":"text","body":{"content":"{\"text\":\"原话\"}"}}]}}""" }
            assertEquals("原话", api.messageText("om_0"))
            assertEquals("https://open.feishu.cn/open-apis/im/v1/messages/om_0", http.calls.last().url)
            assertNull(api { _, _, _ -> 200 to """{"code":230002,"msg":"x"}""" }.first.messageText("om_0"))
        }

    @Test
    fun `资源地址按消息、文件键与类型拼接`() {
        val (api, _) = api { _, _, _ -> 200 to "{}" }
        assertEquals(
            "https://open.feishu.cn/open-apis/im/v1/messages/om_1/resources/f_1?type=file",
            api.resourceUrl("om_1", "f_1", "file")
        )
    }
}
