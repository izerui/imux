package com.github.izerui.imux.bridge.channel.dingtalk

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class DingtalkApiTest {
    private var clock = 1_000_000L
    private val tokens = AtomicInteger()

    @Volatile
    private var sendAnswer: Pair<Int, String> = 200 to """{"processQueryKey":"q","invalidStaffIdList":[],"flowControlledStaffIdList":[]}"""

    private val http =
        DingtalkFakeHttp { url, _ ->
            when {
                url.endsWith("/v1.0/oauth2/accessToken") -> 200 to """{"accessToken":"T${tokens.incrementAndGet()}","expireIn":7200}"""
                url.endsWith("/v1.0/gateway/connections/open") -> 200 to """{"endpoint":"wss://wss-open-connection.dingtalk.com:443/connect","ticket":"a+b/c"}"""
                url.endsWith("/v1.0/robot/oToMessages/batchSend") -> sendAnswer
                url.endsWith("/v1.0/robot/messageFiles/download") -> 200 to """{"downloadUrl":"https://oss/x"}"""
                else -> 404 to "{}"
            }
        }

    private val api = DingtalkApi("cid", "sec", http) { clock }

    @Test
    fun `令牌缓存，剩余不足十分钟才重取`() =
        runBlocking {
            assertEquals(DingtalkResult.Ok("T1"), api.token())
            clock += 6_000_000
            assertEquals(DingtalkResult.Ok("T1"), api.token())
            clock += 700_000
            assertEquals(DingtalkResult.Ok("T2"), api.token())
            val body = JsonParser.parseString(http.calls("/v1.0/oauth2/accessToken").first().body).asJsonObject
            assertEquals("cid", body["appKey"].asString)
            assertEquals("sec", body["appSecret"].asString)
        }

    @Test
    fun `建连请求与地址拼接`() =
        runBlocking {
            val endpoint = (api.openConnection() as DingtalkResult.Ok).value
            assertEquals("wss://wss-open-connection.dingtalk.com:443/connect?ticket=a%2Bb%2Fc", endpoint.url())
            val call = http.calls("/v1.0/gateway/connections/open").single()
            assertEquals("application/json", call.headers["Accept"])
            val body = JsonParser.parseString(call.body).asJsonObject
            assertEquals("cid", body["clientId"].asString)
            assertEquals("sec", body["clientSecret"].asString)
            val subscription = body["subscriptions"].asJsonArray.single().asJsonObject
            assertEquals("CALLBACK", subscription["type"].asString)
            assertEquals("/v1.0/im/bot/messages/get", subscription["topic"].asString)
        }

    @Test
    fun `发送请求体与两个名单`() =
        runBlocking {
            val param = DingtalkApi.markdownParam("标题", "正文")
            assertEquals(DingtalkResult.Ok(DingtalkSendOutcome(emptyList(), emptyList())), api.send("u1", param))
            val call = http.calls("/v1.0/robot/oToMessages/batchSend").single()
            assertEquals("T1", call.headers[DingtalkApi.TOKEN_HEADER])
            val body = JsonParser.parseString(call.body).asJsonObject
            assertEquals("cid", body["robotCode"].asString)
            assertEquals("u1", body["userIds"].asJsonArray.single().asString)
            assertEquals("sampleMarkdown", body["msgKey"].asString)
            assertEquals(param, body["msgParam"].asString)
            val msgParam = JsonParser.parseString(body["msgParam"].asString).asJsonObject
            assertEquals("标题", msgParam["title"].asString)
            assertEquals("正文", msgParam["text"].asString)
            sendAnswer = 200 to """{"invalidStaffIdList":["u1"],"flowControlledStaffIdList":["u2"]}"""
            assertEquals(DingtalkResult.Ok(DingtalkSendOutcome(listOf("u1"), listOf("u2"))), api.send("u1", param))
        }

    @Test
    fun `令牌失效时重取并只重试一次`() =
        runBlocking {
            sendAnswer = 401 to """{"code":"InvalidAuthentication","message":"x"}"""
            val result = api.send("u1", "{}")
            assertEquals(DingtalkResult.Error(401, "InvalidAuthentication", "x"), result)
            assertEquals(2, http.calls("/v1.0/oauth2/accessToken").size)
            assertEquals(2, http.calls("/v1.0/robot/oToMessages/batchSend").size)
        }

    @Test
    fun `业务错误与网络错误`() =
        runBlocking {
            sendAnswer = 403 to """{"code":"Forbidden.AccessDenied.ApiCountLimitForOrg","message":"quota"}"""
            assertEquals(DingtalkResult.Error(403, "Forbidden.AccessDenied.ApiCountLimitForOrg", "quota"), api.send("u1", "{}"))
            sendAnswer = 502 to "<html>bad gateway</html>"
            assertEquals(DingtalkResult.Network("HTTP 502"), api.send("u1", "{}"))
            val broken = DingtalkApi("cid", "sec", DingtalkFakeHttp { _, _ -> throw IOException("reset") })
            assertEquals(DingtalkResult.Network("reset"), broken.openConnection())
        }

    @Test
    fun `换附件下载地址`() =
        runBlocking {
            assertEquals(DingtalkResult.Ok("https://oss/x"), api.downloadUrl("d1"))
            val call = http.calls("/v1.0/robot/messageFiles/download").single()
            assertEquals("T1", call.headers[DingtalkApi.TOKEN_HEADER])
            val body = JsonParser.parseString(call.body).asJsonObject
            assertEquals("d1", body["downloadCode"].asString)
            assertEquals("cid", body["robotCode"].asString)
        }

    @Test
    fun `错误码分类`() {
        assertTrue(DingtalkCodes.isRateLimited("Forbidden.AccessDenied.QpsLimitForAppkeyAndApi"))
        assertTrue(DingtalkCodes.isRateLimited("Forbidden.AccessDenied.QpsLimitForApi"))
        assertTrue(DingtalkCodes.isRateLimited("send.too.fast"))
        assertFalse(DingtalkCodes.isRateLimited(DingtalkCodes.QUOTA_EXCEEDED))
        assertTrue(DingtalkCodes.isCredentialError(DingtalkResult.Error(400, "invalidClientIdOrSecret", "")))
        assertTrue(DingtalkCodes.isCredentialError(DingtalkResult.Error(401, "x", "")))
        assertFalse(DingtalkCodes.isCredentialError(DingtalkResult.Error(403, DingtalkCodes.QUOTA_EXCEEDED, "")))
        assertTrue(DingtalkCodes.isTokenInvalid(DingtalkResult.Error(400, "token.notExisted", "")))
        assertTrue(DingtalkCodes.isTokenInvalid(DingtalkResult.Error(401, "x", "")))
    }

    @Test
    fun `标题取第一行有字的内容，去掉标记，最多二十字`() {
        assertEquals("结论", DingtalkApi.title("## 结论\n正文"))
        assertEquals("fun main() = Unit", DingtalkApi.title("```kotlin\nfun main() = Unit\n```"))
        assertEquals("要点一", DingtalkApi.title("\n- **要点一**"))
        assertEquals("一二三四五六七八九十一二三四五六七八九十", DingtalkApi.title("一二三四五六七八九十一二三四五六七八九十多出来的"))
        assertEquals("imux", DingtalkApi.title("  \n"))
    }

    @Test
    fun `连接地址的字符串不带 ticket`() {
        val endpoint = DingtalkEndpoint("wss://x/connect", "secret-ticket")
        assertFalse(endpoint.toString().contains("secret-ticket"))
        assertTrue(endpoint.url().contains("secret-ticket"))
    }
}
