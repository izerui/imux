package com.github.izerui.imux.bridge.channel.feishu

import com.github.izerui.imux.bridge.channel.HttpJson
import com.github.izerui.imux.bridge.channel.HttpJsonResponse
import com.google.gson.JsonParser
import java.util.concurrent.CopyOnWriteArrayList

/** 按请求返回 JSON；记录每次请求的方法、地址、正文与 Authorization 头。 */
class FakeHttpJson(
    private val route: suspend (method: String, url: String, body: String?) -> Pair<Int, String>,
) : HttpJson {
    data class Call(
        val method: String,
        val url: String,
        val body: String?,
        val authorization: String?,
    )

    val calls = CopyOnWriteArrayList<Call>()

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
        calls += Call(method, url, body, headers["Authorization"])
        val (status, text) = route(method, url, body)
        val json = runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        return HttpJsonResponse(status, json)
    }
}
