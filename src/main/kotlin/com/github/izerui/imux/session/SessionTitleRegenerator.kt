package com.github.izerui.imux.session

import com.github.izerui.imux.model.AgentSession
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.terminal.ShellDialect
import com.github.izerui.imux.terminal.dialectOf
import com.github.izerui.imux.terminal.quote
import com.github.izerui.imux.terminal.shellArgs
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class SessionTitleRegenerator(
    private val userHome: Path,
    private val shell: String,
    private val runCli: (List<String>, Path, Long) -> String = ::runTitleCli,
    private val timeoutSeconds: Long = TITLE_TIMEOUT_SECONDS,
) {
    fun regenerate(
        session: AgentSession,
        projectPath: String,
    ): String {
        val prompt = titleGenerationPrompt(session)
        val command = titleGenerationCommand(shell, session.agentType, projectPath, prompt)
        val output = runCli(command, Path.of(projectPath), timeoutSeconds)
        val title = normalizeGeneratedTitle(output) ?: error("CLI 没有返回可用标题")
        writeGeneratedTitle(session, title, userHome)
        return title
    }
}

internal fun titleGenerationCommand(
    shell: String,
    agentType: AgentType,
    projectPath: String,
    prompt: String,
): List<String> {
    val dialect = dialectOf(shell)
    val script =
        when (agentType) {
            AgentType.CLAUDE -> {
                "claude -p --safe-mode --tools ${quote(dialect, "")} --no-session-persistence " +
                    quote(dialect, prompt)
            }

            AgentType.CODEX -> {
                "codex exec --ephemeral --skip-git-repo-check --sandbox read-only --color never " +
                    "-C ${quote(dialect, projectPath)} ${quote(dialect, prompt)}"
            }

            AgentType.PI -> {
                "pi -p --no-session --no-tools ${quote(dialect, prompt)}"
            }
        }
    return listOf(shell) + shellArgs(dialect) + script
}

internal fun titleGenerationPrompt(session: AgentSession): String {
    val conversation = conversationExcerpt(session)
    return """
        Generate a concise title for this coding-agent session.

        Rules:
        - Use the same language as the conversation.
        - Describe the concrete task or topic.
        - Prefer 4-10 words and never exceed $MAX_TITLE_CHARS characters.
        - Return only the title, with no quotes, prefix, markdown, or explanation.

        Current title: ${session.title}

        <conversation>
        $conversation
        </conversation>
        """.trimIndent()
}

internal fun normalizeGeneratedTitle(output: String): String? {
    val line =
        output
            .lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("```") }
            .lastOrNull()
            ?.replace(Regex("""^```(?:text)?\s*""", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("""^\s*(?:title|标题)\s*[:：]\s*""", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("""^\s*[-#*>]+\s*"""), "")
            ?.trim('"', '\'', '`', '“', '”', '‘', '’', '。', '.', '!', '！')
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return null
    return if (line.length <= MAX_TITLE_CHARS) line else line.take(MAX_TITLE_CHARS - 1).trimEnd() + "…"
}

internal fun writeGeneratedTitle(
    session: AgentSession,
    title: String,
    userHome: Path,
) {
    when (session.agentType) {
        AgentType.CLAUDE -> {
            appendJsonLine(
                session.filePath,
                JsonObject().apply {
                    addProperty("type", "custom-title")
                    addProperty("customTitle", title)
                    addProperty("sessionId", session.id)
                },
            )
        }

        AgentType.CODEX -> {
            val db = userHome.resolve(".codex/state_5.sqlite")
            check(Files.isRegularFile(db)) { "Codex 会话数据库不存在" }
            val config = SQLiteConfig().apply { setBusyTimeout(SQLITE_BUSY_TIMEOUT_MS) }
            SQLiteDataSource(config)
                .apply { url = "jdbc:sqlite:${db.toAbsolutePath()}" }
                .connection
                .use { connection ->
                    connection.prepareStatement("UPDATE threads SET name = ? WHERE id = ?").use { statement ->
                        statement.setString(1, title)
                        statement.setString(2, session.id)
                        check(statement.executeUpdate() == 1) { "Codex 会话不存在" }
                    }
                }
        }

        AgentType.PI -> {
            val parentId =
                scanTail(session.filePath) { lines ->
                    lines.asReversed().firstNotNullOfOrNull { JsonLineScanner.topLevelStringValue(it, "id") }
                } ?: error("Pi 会话缺少可挂接的末条记录")
            appendJsonLine(
                session.filePath,
                JsonObject().apply {
                    addProperty("type", "session_info")
                    addProperty(
                        "id",
                        UUID
                            .randomUUID()
                            .toString()
                            .replace("-", "")
                            .take(8),
                    )
                    addProperty("parentId", parentId)
                    addProperty("timestamp", Instant.now().toString())
                    addProperty("name", title)
                },
            )
        }
    }
}

private fun conversationExcerpt(session: AgentSession): String =
    sessionTranscriptMessages(
        session,
        maxLines = MAX_TRANSCRIPT_LINES,
        maxMessages = MAX_MESSAGES,
        maxMessageChars = MAX_MESSAGE_CHARS,
    ).joinToString("\n\n") { "${it.role.uppercase()}: ${it.text}" }
        .take(MAX_CONVERSATION_CHARS)
        .ifBlank { "TITLE: ${session.title}" }

internal data class SessionTranscriptMessage(
    val role: String,
    val text: String,
    val hiddenFromTerminal: Boolean = false,
)

internal fun sessionTranscriptMessages(
    session: AgentSession,
    maxLines: Int,
    maxMessages: Int,
    maxMessageChars: Int,
): List<SessionTranscriptMessage> {
    val messages = mutableListOf<SessionTranscriptMessage>()
    Files.newBufferedReader(session.filePath).useLines { lines ->
        for (line in lines.take(maxLines)) {
            parseTranscriptMessage(line, session.agentType, maxMessageChars)?.let(messages::add)
            if (messages.size >= maxMessages) break
        }
    }
    return messages
}

internal data class SessionExchange(
    val userText: String,
    val assistantReply: String,
)

/**
 * 会话尾部的若干轮「用户提问 + 助手回复」，供会话内导航的悬停卡片展示。
 *
 * 只带上助手回复的开头：导航时用户是靠「那一轮聊出了什么」认路的，
 * 只列提问的话，他得先回忆自己当时怎么问。
 */
internal fun recentExchanges(
    session: AgentSession,
    maxExchanges: Int = MAX_NAVIGATOR_MESSAGES,
): List<SessionExchange> =
    scanTail(
        session.filePath,
        initialTailBytes = NAVIGATOR_TAIL_BYTES,
        maxTailBytes = NAVIGATOR_TAIL_BYTES,
    ) { lines ->
        pairExchanges(
            lines.mapNotNull {
                parseNavigatorTranscriptMessage(it, session.agentType, MAX_NAVIGATOR_MESSAGE_CHARS)
            },
        ).takeLast(maxExchanges)
    }.orEmpty()

/**
 * 一条 user 开一轮，其后**第一条** assistant 作为该轮回复。
 *
 * 只取第一条：一轮里 CLI 常连发多条消息，后面的多是工具汇报和收尾语，
 * 开头那条才是对提问的正面回答。末轮还在生成时没有 assistant，回复留空。
 */
internal fun pairExchanges(messages: List<SessionTranscriptMessage>): List<SessionExchange> {
    val exchanges = mutableListOf<SessionExchange>()
    messages.forEach { message ->
        when {
            message.hiddenFromTerminal -> {
                Unit
            }

            message.role == "user" -> {
                exchanges += SessionExchange(message.text, "")
            }

            exchanges.lastOrNull()?.assistantReply?.isEmpty() == true -> {
                exchanges[exchanges.lastIndex] = exchanges.last().copy(assistantReply = message.text)
            }
        }
    }
    return exchanges
}

/**
 * 导航器允许图片记录本身超过普通 JSON 行上限，但不会把 Base64 交给 Gson。
 *
 * Claude 的图片数据在 `source.data`，Codex 在 `image_url`。先线性替换这些字符串载荷，
 * 替换后仍超过上限说明大的不是图片而是正文或未知结构，继续按普通保护规则丢弃。
 */
internal fun parseNavigatorTranscriptMessage(
    line: String,
    agentType: AgentType,
    maxMessageChars: Int,
): SessionTranscriptMessage? {
    val parseableLine =
        if (line.length <= MAX_JSON_LINE_CHARS) {
            line
        } else {
            redactNavigatorImagePayloads(line).takeIf { it.length <= MAX_JSON_LINE_CHARS } ?: return null
        }
    return parseTranscriptMessage(
        parseableLine,
        agentType,
        maxMessageChars,
        navigatorVisibleTextOnly = true,
    )
}

private fun parseTranscriptMessage(
    line: String,
    agentType: AgentType,
    maxMessageChars: Int,
    navigatorVisibleTextOnly: Boolean = false,
): SessionTranscriptMessage? {
    if (line.length > MAX_JSON_LINE_CHARS) return null
    val root = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: return null
    val message =
        when (agentType) {
            AgentType.CLAUDE, AgentType.PI -> {
                root.getAsJsonObject("message")
            }

            AgentType.CODEX -> {
                root
                    .takeIf { it.string("type") == "response_item" }
                    ?.getAsJsonObject("payload")
            }
        } ?: return null
    val role = message.string("role")?.takeIf { it == "user" || it == "assistant" } ?: return null
    val text =
        (
            if (navigatorVisibleTextOnly) {
                navigatorContentText(message.get("content"))
            } else {
                contentText(message.get("content"))
            }
        )?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (role == "user" && text.startsWith("<") && text.endsWith(">")) return null
    return SessionTranscriptMessage(
        role,
        text
            .replace('\n', ' ')
            .replace('\t', ' ')
            .replace(Regex("\\s+"), " ")
            .take(maxMessageChars),
        hiddenFromTerminal =
            agentType == AgentType.CLAUDE &&
                role == "user" &&
                root.get("isMeta")?.takeIf { it.isJsonPrimitive }?.asBoolean == true,
    )
}

private fun contentText(element: JsonElement?): String? = contentText(element) { true }

private fun navigatorContentText(element: JsonElement?): String? =
    contentText(element) { value ->
        val trimmed = value.trim()
        trimmed != IMAGE_CLOSE_TAG &&
            !(trimmed.startsWith(IMAGE_OPEN_TAG_PREFIX) && trimmed.endsWith(">"))
    }

private fun contentText(
    element: JsonElement?,
    includeText: (String) -> Boolean,
): String? =
    when {
        element == null || element.isJsonNull -> {
            null
        }

        element.isJsonPrimitive && element.asJsonPrimitive.isString -> {
            element.asString.takeIf(includeText)
        }

        element.isJsonArray -> {
            element.asJsonArray
                .mapNotNull { contentText(it, includeText) }
                .joinToString("\n")
                .takeIf(String::isNotBlank)
        }

        element.isJsonObject -> {
            val objectValue = element.asJsonObject
            val type = objectValue.string("type")
            if (type == "tool_result" || type == "function_call_output") return null
            contentText(objectValue.get("text"), includeText)
                ?: contentText(objectValue.get("content"), includeText)
        }

        else -> {
            null
        }
    }

private fun redactNavigatorImagePayloads(line: String): String {
    val result = StringBuilder(minOf(line.length, MAX_JSON_LINE_CHARS))
    var copiedUntil = 0
    var cursor = 0

    while (cursor < line.length) {
        if (line[cursor] != '"') {
            cursor++
            continue
        }
        val keyEnd = jsonStringEnd(line, cursor) ?: return line
        val key =
            when {
                line.regionMatches(cursor, DATA_KEY, 0, DATA_KEY.length) -> DATA_KEY
                line.regionMatches(cursor, IMAGE_URL_KEY, 0, IMAGE_URL_KEY.length) -> IMAGE_URL_KEY
                else -> null
            }
        if (key == null || keyEnd != cursor + key.length) {
            cursor = keyEnd
            continue
        }

        var valueStart = keyEnd
        while (valueStart < line.length && line[valueStart].isWhitespace()) valueStart++
        if (valueStart >= line.length || line[valueStart] != ':') {
            cursor = keyEnd
            continue
        }
        valueStart++
        while (valueStart < line.length && line[valueStart].isWhitespace()) valueStart++
        if (valueStart >= line.length || line[valueStart] != '"') {
            cursor = keyEnd
            continue
        }

        val valueEnd = jsonStringEnd(line, valueStart) ?: return line
        result.append(line, copiedUntil, valueStart + 1)
        result.append(REDACTED_IMAGE_PAYLOAD)
        result.append('"')
        copiedUntil = valueEnd
        cursor = valueEnd
    }

    if (copiedUntil == 0) return line
    result.append(line, copiedUntil, line.length)
    return result.toString()
}

/** 返回 JSON 字符串结束后的下标；只用于跳过图片载荷，不做反转义。 */
private fun jsonStringEnd(
    value: String,
    quoteIndex: Int,
): Int? {
    var cursor = quoteIndex + 1
    while (cursor < value.length) {
        when (value[cursor]) {
            '"' -> return cursor + 1
            '\\' -> cursor += 2
            else -> cursor++
        }
    }
    return null
}

private fun JsonObject.string(key: String): String? =
    get(key)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString

private fun appendJsonLine(
    file: Path,
    value: JsonObject,
) {
    check(Files.isRegularFile(file)) { "会话文件不存在" }
    val needsLeadingNewline =
        Files.size(file) > 0L &&
            FileChannel.open(file, StandardOpenOption.READ).use { channel ->
                val byte = ByteBuffer.allocate(1)
                channel.position(channel.size() - 1)
                channel.read(byte)
                byte.flip()
                byte.get().toInt().toChar() != '\n'
            }
    val text = (if (needsLeadingNewline) "\n" else "") + value.toString() + "\n"
    val bytes = StandardCharsets.UTF_8.encode(text)
    FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
        channel.lock().use {
            while (bytes.hasRemaining()) channel.write(bytes)
        }
    }
}

private fun runTitleCli(
    command: List<String>,
    cwd: Path,
    timeoutSeconds: Long,
): String {
    var process: Process? = null
    return try {
        val started =
            ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        process = started
        started.outputStream.close()

        val output = StringBuilder()
        val finishedReading = CountDownLatch(1)
        Thread {
            runCatching {
                started.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(2048)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        val room = MAX_CLI_OUTPUT_CHARS - output.length
                        if (room > 0) output.append(buffer, 0, minOf(room, count))
                    }
                }
            }
            finishedReading.countDown()
        }.apply {
            isDaemon = true
            name = "imux-session-title-reader"
        }.start()

        if (!started.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            error("CLI 生成标题超时")
        }
        finishedReading.await(READ_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        check(started.exitValue() == 0) { "CLI 生成标题失败（退出码 ${started.exitValue()}）" }
        output.toString()
    } finally {
        process?.descendants()?.forEach(ProcessHandle::destroyForcibly)
        process?.destroyForcibly()
    }
}

private const val MAX_TITLE_CHARS = 60
private const val MAX_TRANSCRIPT_LINES = 400
private const val MAX_JSON_LINE_CHARS = 128_000
private const val MAX_MESSAGES = 6
private const val MAX_MESSAGE_CHARS = 1_000
private const val MAX_CONVERSATION_CHARS = 6_000
private const val MAX_NAVIGATOR_MESSAGES = 200
private const val MAX_NAVIGATOR_MESSAGE_CHARS = 1_000
private const val NAVIGATOR_TAIL_BYTES = 4L * 1024 * 1024
private const val DATA_KEY = "\"data\""
private const val IMAGE_URL_KEY = "\"image_url\""
private const val REDACTED_IMAGE_PAYLOAD = "<image-payload>"
private const val IMAGE_OPEN_TAG_PREFIX = "<image "
private const val IMAGE_CLOSE_TAG = "</image>"
private const val MAX_CLI_OUTPUT_CHARS = 16_000
private const val TITLE_TIMEOUT_SECONDS = 60L
private const val READ_DRAIN_TIMEOUT_SECONDS = 2L
private const val SQLITE_BUSY_TIMEOUT_MS = 5_000
