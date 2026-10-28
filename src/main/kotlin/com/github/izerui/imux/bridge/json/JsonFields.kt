package com.github.izerui.imux.bridge.json

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** 桥接各处读第三方 JSON（会话记录、企业微信协议、扫码接口）的取值方式；字段缺失或类型不对都返回空，不抛异常。 */

/** 字段是字符串时取出；数字、对象、null 都不算。 */
internal fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

/** 字段是任意基本类型时取其文字（数字也转成字符串）：扫码接口的 botid 不保证是字符串。 */
internal fun JsonObject.primitiveText(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

internal fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

internal fun JsonObject.flag(key: String): Boolean = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true

/** 字段是数组时取出其中的对象，其余元素忽略。 */
internal fun JsonObject.objects(key: String): List<JsonObject> = get(key).objects()

/** 元素是数组时取出其中的对象，其余元素忽略。 */
internal fun JsonElement?.objects(): List<JsonObject> =
    this?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }.orEmpty()
