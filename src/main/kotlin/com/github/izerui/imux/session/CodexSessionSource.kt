package com.github.izerui.imux.session

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

/** 会话列表与进程探测必须用同一来源判据，不能把同进程的子任务认成用户的终端。 */
internal fun isInteractiveCodexSession(meta: String): Boolean =
    runCatching {
        val record = JsonParser.parseString(meta).asJsonObject
        if (record.get("type")?.asString != "session_meta") return false
        val payload = record.getAsJsonObject("payload") ?: return false
        if (payload.get("thread_source")?.asString == "subagent") return false
        val source = payload.get("source") ?: return true // 旧记录没有来源字段。
        source.isJsonPrimitive && source.asJsonPrimitive.isString && source.asString in setOf("cli", "vscode")
    }.getOrDefault(false)

/** 只读首行；写入未完成、文件已关闭删除或不可读时不猜测会话身份。 */
internal fun isInteractiveCodexRollout(path: String): Boolean =
    runCatching {
        Files.newBufferedReader(Path.of(path)).use { reader ->
            reader.readLine()?.let(::isInteractiveCodexSession) == true
        }
    }.getOrDefault(false)
