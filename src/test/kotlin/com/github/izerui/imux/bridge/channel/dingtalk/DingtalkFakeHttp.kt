package com.github.izerui.imux.bridge.channel.dingtalk

import com.github.izerui.imux.bridge.channel.HttpJson
import com.github.izerui.imux.bridge.channel.HttpJsonResponse
import com.google.gson.JsonParser
import java.util.concurrent.CopyOnWriteArrayList

/** 钉钉测试用的假 HTTP：按地址返回 JSON，记录每次请求的方法、地址、正文与请求头。route 抛异常即模拟网络错误。 */
class DingtalkFakeHttp(
    private val route: suspend (url: String, body: String?) -> Pair<Int, String>,
) : HttpJson {
    data class Call(
        val method: String,
        val url: String,
        val body: String?,
        val headers: Map<String, String>,
    )

    val calls = CopyOnWriteArrayList<Call>()

    fun calls(suffix: String) = calls.filter { it.url.endsWith(suffix) }

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
        val (status, text) = route(url, body)
        val json = runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        return HttpJsonResponse(status, json)
    }
}
