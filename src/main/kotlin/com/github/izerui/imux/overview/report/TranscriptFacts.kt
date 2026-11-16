package com.github.izerui.imux.overview.report

import com.github.izerui.imux.model.AgentType
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * 逐行扫会话记录到 [limitBytes]（只认以换行结束的完整行，与轨迹的截止位置一致），得出报告里由插件统计的事实：
 * 首末时间戳之差（分钟，向下取整）与改动过的文件。坏行跳过。
 *
 * 改动文件只认编辑工具：Claude Code 的 Edit / MultiEdit / Write / NotebookEdit，Codex 的 apply_patch，Pi 的 edit / write。
 * 经 shell 命令改的文件统计不到，是已知的低估。工程目录内的写相对路径，其余保留原样。
 */
internal fun transcriptFacts(agentType: AgentType, file: Path, limitBytes: Long, projectRoot: Path?): ReportFacts {
    var first: Instant? = null
    var last: Instant? = null
    val files = LinkedHashSet<String>()
    forEachCompleteLine(file, limitBytes) { line ->
        val root = runCatching { JsonParser.parseString(line) }.getOrNull() as? JsonObject ?: return@forEachCompleteLine
        root.string("timestamp")?.let { runCatching { Instant.parse(it) }.getOrNull() }?.let { time ->
            if (first == null) first = time
            last = time
        }
        val paths = when (agentType) {
            AgentType.CLAUDE -> claudeEdits(root)
            AgentType.CODEX -> codexEdits(root)
            AgentType.PI -> piEdits(root)
        }
        paths.forEach { files += display(it, projectRoot) }
    }
    val duration = first?.let { start -> last?.let { end -> Duration.between(start, end).toMinutes() } }
    return ReportFacts(duration, files.toList())
}

private val CLAUDE_EDIT_TOOLS = setOf("Edit", "MultiEdit", "Write")
private val PI_EDIT_TOOLS = setOf("edit", "write")
private val PATCH_HEADERS = listOf("*** Add File: ", "*** Update File: ", "*** Delete File: ", "*** Move to: ")

private fun claudeEdits(root: JsonObject): List<String> =
    contentBlocks(root, "tool_use").mapNotNull { block ->
        val input = block.get("input") as? JsonObject ?: return@mapNotNull null
        when (block.string("name")) {
            in CLAUDE_EDIT_TOOLS -> input.string("file_path")
            "NotebookEdit" -> input.string("notebook_path")
            else -> null
        }
    }

private fun piEdits(root: JsonObject): List<String> =
    contentBlocks(root, "toolCall")
        .filter { it.string("name") in PI_EDIT_TOOLS }
        .mapNotNull { (it.get("arguments") as? JsonObject)?.string("path") }

/** apply_patch 的补丁正文：custom_tool_call 在 `input`，function_call 在 `arguments`（JSON 字符串，取其 `input`）。 */
private fun codexEdits(root: JsonObject): List<String> {
    if (root.string("type") != "response_item") return emptyList()
    val payload = root.get("payload") as? JsonObject ?: return emptyList()
    if (payload.string("name") != "apply_patch") return emptyList()
    val patch = when (payload.string("type")) {
        "custom_tool_call" -> payload.string("input")
        "function_call" -> payload.string("arguments")?.let { arguments ->
            (runCatching { JsonParser.parseString(arguments) }.getOrNull() as? JsonObject)?.string("input") ?: arguments
        }
        else -> null
    } ?: return emptyList()
    return patch.lineSequence().mapNotNull { line ->
        PATCH_HEADERS.firstOrNull(line::startsWith)?.let { line.removePrefix(it).trim().ifEmpty { null } }
    }.toList()
}

private fun contentBlocks(root: JsonObject, type: String): List<JsonObject> {
    val content = (root.get("message") as? JsonObject)?.get("content") as? com.google.gson.JsonArray ?: return emptyList()
    return content.filterIsInstance<JsonObject>().filter { it.string("type") == type }
}

private fun display(path: String, projectRoot: Path?): String {
    val root = projectRoot?.toAbsolutePath()?.normalize() ?: return path
    val target = runCatching { Path.of(path).normalize() }.getOrNull() ?: return path
    if (!target.isAbsolute || !target.startsWith(root) || target == root) return path
    return root.relativize(target).joinToString("/")
}

private fun JsonObject.string(key: String): String? =
    get(key)?.takeIf(JsonElement::isJsonPrimitive)?.takeIf { it.asJsonPrimitive.isString }?.asString

/** 按字节逐行读到 [limitBytes]，只交出以换行结束的完整行。 */
private fun forEachCompleteLine(file: Path, limitBytes: Long, action: (String) -> Unit) {
    val line = ByteArrayOutputStream()
    var offset = 0L
    Files.newInputStream(file).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (offset < limitBytes) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), limitBytes - offset).toInt())
            if (read < 0) break
            for (i in 0 until read) {
                val b = buffer[i]
                if (b == '\n'.code.toByte()) {
                    action(line.toString(Charsets.UTF_8))
                    line.reset()
                } else {
                    line.write(b.toInt())
                }
            }
            offset += read
        }
    }
}
