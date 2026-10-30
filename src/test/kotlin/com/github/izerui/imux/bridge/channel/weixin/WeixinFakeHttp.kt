package com.github.izerui.imux.bridge.channel.weixin

import com.github.izerui.imux.bridge.channel.HttpJson
import com.github.izerui.imux.bridge.channel.HttpJsonResponse
import com.google.gson.JsonParser
import java.util.concurrent.CopyOnWriteArrayList

/** 微信测试用假 HTTP：按地址返回 JSON，记录方法、地址、正文与请求头。route 抛异常即模拟网络错误或超时。 */
class WeixinFakeHttp(
    private val route: suspend (method: String, url: String, body: String?) -> Pair<Int, String>,
) : HttpJson {
    data class Call(
        val method: String,
        val url: String,
        val body: String?,
        val headers: Map<String, String>,
    )

    val calls = CopyOnWriteArrayList<Call>()

    fun calls(part: String) = calls.filter { part in it.url }

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
    ): HttpJsonResponse = answer("GET", url, null, headers)

    override suspend fun post(
        url: String,
        contentType: String,
        body: String,
        headers: Map<String, String>,
    ): HttpJsonResponse = answer("POST", url, body, headers)

    private suspend fun answer(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
    ): HttpJsonResponse {
        calls += Call(method, url, body, headers)
        val (status, text) = route(method, url, body)
        val json = runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        return HttpJsonResponse(status, json)
    }
}
