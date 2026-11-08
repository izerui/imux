package com.github.izerui.imux.session

import com.github.izerui.imux.model.AgentSession
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.terminal.dialectOf
import com.github.izerui.imux.terminal.quote
import com.github.izerui.imux.terminal.shellArgs
import com.google.gson.JsonObject
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
    private val shell: String,
    private val runCli: (List<String>, Path, Long) -> String = ::runTitleCli,
    private val timeoutSeconds: Long = TITLE_TIMEOUT_SECONDS,
) {
    /** 只产出候选标题，不写回；写回由用户在重命名框里确认后走 [writeSessionTitle]。 */
    fun generate(
        session: AgentSession,
        projectPath: String,
    ): String {
        val prompt = titleGenerationPrompt(session)
        val command = titleGenerationCommand(shell, session.agentType, projectPath, prompt)
        val output = runCli(command, Path.of(projectPath), timeoutSeconds)
        return normalizeGeneratedTitle(output) ?: error("CLI 没有返回可用标题")
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

/**
 * 标题以最近的用户消息为准：长会话常常中途换题，只看开头几轮会让标题永远停在最早的任务上。
 * 首条用户消息只作背景。
 */
internal fun titleGenerationPrompt(session: AgentSession): String {
    val excerpt = titleExcerpt(session)
    val sections =
        buildList {
            excerpt.firstUserMessage?.let { add("<first_user_message>\n$it\n</first_user_message>") }
            if (excerpt.recentUserMessages.isNotEmpty()) {
                add(
                    excerpt.recentUserMessages
                        .mapIndexed { index, text -> "${index + 1}. $text" }
                        .joinToString("\n", "<recent_user_messages oldest_to_newest>\n", "\n</recent_user_messages>"),
                )
            }
            excerpt.latestAssistantReply?.let { add("<latest_assistant_reply>\n$it\n</latest_assistant_reply>") }
        }.ifEmpty { listOf("<current_title>${session.title}</current_title>") }
    return """
        |Generate a concise title for this coding-agent session.
        |
        |Rules:
        |- Name what the session is CURRENTLY about, judged mainly by the most recent user messages.
        |- The first user message is background only; if the topic has shifted since then, name the new topic.
        |- The current title may be outdated; do not just rephrase it.
        |- Use the same language as the user messages.
        |- Prefer 4-10 words and never exceed $MAX_TITLE_CHARS characters.
        |- Return only the title, with no quotes, prefix, markdown, or explanation.
        |
        |Current title (may be outdated): ${session.title}
        """.trimMargin() + "\n\n" + sections.joinToString("\n\n")
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

/**
 * 人工输入的标题只做最小清理：换行和连续空白折成单个空格，首尾去空。
 *
 * 不套 [normalizeGeneratedTitle] 的剥引号、去前缀、截断——那些是为了收拾模型的多余输出，
 * 用户亲手敲的每个字都是本意。
 */
internal fun normalizeManualTitle(input: String): String? =
    input
        .replace(Regex("\\s+"), " ")
        .trim()
        .takeIf(String::isNotEmpty)

/**
 * CLI 自己存着的完整标题，供重命名框做初值；没有存过标题时返回 null。
 *
 * [AgentSession.title] 不能直接用：Codex 与 Pi 的 reader 为了列表排得下会截到 60 字加省略号，
 * 拿它做初值，用户只改一个字就会把截断后的文本连同「…」写回去，后半截从此丢失。
 * Claude 的 custom-title / ai-title 读出时不截断，[AgentSession.title] 即原文，这里不再重读。
 */
internal fun storedTitle(
    session: AgentSession,
    homes: AgentHomes,
): String? =
    when (session.agentType) {
        AgentType.CLAUDE -> {
            null
        }

        AgentType.CODEX -> {
            CodexThreadIndex(homes.codex, homes.codexSqlite)
                .currentCatalog()
                ?.titles
                ?.get(session.id)
                ?.takeIf(String::isNotEmpty)
        }

        AgentType.PI -> {
            piSessionName(session.filePath)
        }
    }

internal fun writeSessionTitle(
    session: AgentSession,
    title: String,
    homes: AgentHomes,
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
            val catalog = CodexThreadIndex(homes.codex, homes.codexSqlite).currentCatalog()
                ?: error("Codex 会话数据库不存在")
            check(session.id in catalog.titles) { "Codex 会话不存在" }
            val config = SQLiteConfig().apply { setBusyTimeout(SQLITE_BUSY_TIMEOUT_MS) }
            if (catalog.source == CodexThreadIndex.Source.DEV) {
                SQLiteDataSource(config)
                    .apply { url = "jdbc:sqlite:${catalog.file.toAbsolutePath()}" }
                    .connection
                    .use { connection ->
                        connection.prepareStatement(
                            "UPDATE local_thread_catalog SET display_title = ? WHERE thread_id = ?",
                        ).use { statement ->
                            statement.setString(1, title)
                            statement.setString(2, session.id)
                            // 同一 thread_id 可属于多个 host；标题应同步写入这些记录。
                            check(statement.executeUpdate() > 0) { "Codex 会话不存在" }
                        }
                    }
            } else {
                SQLiteDataSource(config)
                    .apply { url = "jdbc:sqlite:${catalog.file.toAbsolutePath()}" }
                    .connection
                    .use { connection ->
                        connection.prepareStatement("UPDATE threads SET name = ? WHERE id = ?").use { statement ->
                            statement.setString(1, title)
                            statement.setString(2, session.id)
                            check(statement.executeUpdate() == 1) { "Codex 会话不存在" }
                        }
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

private data class TitleExcerpt(
    val firstUserMessage: String?,
    val recentUserMessages: List<String>,
    val latestAssistantReply: String?,
)

/**
 * 最近的用户消息从尾部读：会话再长，换题后的内容也一定在文件末尾，
 * 窗口逐步放大直到凑够 [TITLE_RECENT_USER_MESSAGES] 条或读到上限。
 */
private fun titleExcerpt(session: AgentSession): TitleExcerpt {
    var tailMessages = emptyList<SessionTranscriptMessage>()
    scanTail(session.filePath, TITLE_TAIL_BYTES, MAX_TITLE_TAIL_BYTES) { lines ->
        tailMessages =
            lines
                .mapNotNull { transcriptMessage(it, session.agentType, TITLE_MESSAGE_CHARS) }
                .filterNot(SessionTranscriptMessage::hiddenFromTerminal)
        tailMessages.takeIf { messages -> messages.count { it.isUserInput() } >= TITLE_RECENT_USER_MESSAGES }
    }
    val recent = tailMessages.filter { it.isUserInput() }.map { it.text }.takeLast(TITLE_RECENT_USER_MESSAGES)
    val first =
        sessionTranscriptMessages(session, MAX_TRANSCRIPT_LINES, MAX_MESSAGES, TITLE_MESSAGE_CHARS)
            .firstOrNull { it.isUserInput() }
            ?.text
            // 会话短到首条已在最近几条里时不重复给出
            ?.takeUnless { it in recent }
    return TitleExcerpt(
        firstUserMessage = first,
        recentUserMessages = recent,
        latestAssistantReply =
            tailMessages.lastOrNull { it.role == "assistant" }?.text?.take(TITLE_ASSISTANT_REPLY_CHARS),
    )
}

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
private const val MAX_MESSAGES = 6
private const val TITLE_RECENT_USER_MESSAGES = 8
private const val TITLE_MESSAGE_CHARS = 500
private const val TITLE_ASSISTANT_REPLY_CHARS = 400
private const val TITLE_TAIL_BYTES = 256L * 1024
private const val MAX_TITLE_TAIL_BYTES = 4L * 1024 * 1024
private const val MAX_CLI_OUTPUT_CHARS = 16_000
private const val TITLE_TIMEOUT_SECONDS = 60L
private const val READ_DRAIN_TIMEOUT_SECONDS = 2L
private const val SQLITE_BUSY_TIMEOUT_MS = 5_000
