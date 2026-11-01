package com.github.izerui.imux.bridge.channel

import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path

class FakeMediaHttp : MediaHttp {
    data class Call(val url: String, val fields: Map<String, String>, val field: String?, val name: String?, val bytes: ByteArray, val headers: Map<String, String>)
    val calls = mutableListOf<Call>()
    var answer: (Call) -> MediaHttpResponse = { MediaHttpResponse(200, JsonObject(), emptyMap()) }
    override suspend fun multipart(url: String, fields: Map<String, String>, field: String, path: Path, name: String, headers: Map<String, String>): MediaHttpResponse {
        val call = Call(url, fields, field, name, Files.readAllBytes(path), headers)
        calls += call
        return answer(call)
    }
    override suspend fun binary(url: String, path: Path): MediaHttpResponse {
        val call = Call(url, emptyMap(), null, null, Files.readAllBytes(path), emptyMap())
        calls += call
        return answer(call)
    }
}
