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
            AgentType.CLAUDE ->
                "claude -p --safe-mode --tools ${quote(dialect, "")} --no-session-persistence " +
                    quote(dialect, prompt)

            AgentType.CODEX ->
                "codex exec --ephemeral --skip-git-repo-check --sandbox read-only --color never " +
                    "-C ${quote(dialect, projectPath)} ${quote(dialect, prompt)}"

            AgentType.PI ->
                "pi -p --no-session --no-tools ${quote(dialect, prompt)}"
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
                    addProperty("id", UUID.randomUUID().toString().replace("-", "").take(8))
                    addProperty("parentId", parentId)
                    addProperty("timestamp", Instant.now().toString())
                    addProperty("name", title)
                },
            )
        }
    }
}

private fun conversationExcerpt(session: AgentSession): String {
    val excerpts = mutableListOf<String>()
    Files.newBufferedReader(session.filePath).useLines { lines ->
        for (line in lines.take(MAX_TRANSCRIPT_LINES)) {
            if (line.length > MAX_JSON_LINE_CHARS) continue
            val message = messageExcerpt(line, session.agentType) ?: continue
            excerpts += "${message.first.uppercase()}: ${message.second.take(MAX_MESSAGE_CHARS)}"
            if (excerpts.size >= MAX_MESSAGES) break
        }
    }
    return excerpts.joinToString("\n\n").take(MAX_CONVERSATION_CHARS)
        .ifBlank { "TITLE: ${session.title}" }
}

private fun messageExcerpt(
    line: String,
    agentType: AgentType,
): Pair<String, String>? {
    val root = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: return null
    val message =
        when (agentType) {
            AgentType.CLAUDE, AgentType.PI -> root.getAsJsonObject("message")
            AgentType.CODEX ->
                root
                    .takeIf { it.string("type") == "response_item" }
                    ?.getAsJsonObject("payload")
        } ?: return null
    val role = message.string("role")?.takeIf { it == "user" || it == "assistant" } ?: return null
    val text = contentText(message.get("content"))?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (role == "user" && text.startsWith("<") && text.endsWith(">")) return null
    return role to text.replace('\n', ' ').replace('\t', ' ').replace(Regex("\\s+"), " ")
}

private fun contentText(element: JsonElement?): String? =
    when {
        element == null || element.isJsonNull -> null
        element.isJsonPrimitive && element.asJsonPrimitive.isString -> element.asString
        element.isJsonArray ->
            element.asJsonArray
                .mapNotNull(::contentText)
                .joinToString("\n")
                .takeIf(String::isNotBlank)

        element.isJsonObject -> {
            val objectValue = element.asJsonObject
            val type = objectValue.string("type")
            if (type == "tool_result" || type == "function_call_output") return null
            contentText(objectValue.get("text")) ?: contentText(objectValue.get("content"))
        }

        else -> null
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
private const val MAX_CLI_OUTPUT_CHARS = 16_000
private const val TITLE_TIMEOUT_SECONDS = 60L
private const val READ_DRAIN_TIMEOUT_SECONDS = 2L
private const val SQLITE_BUSY_TIMEOUT_MS = 5_000
