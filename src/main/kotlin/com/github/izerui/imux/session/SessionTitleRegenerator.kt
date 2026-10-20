package com.github.izerui.imux.session

import com.github.izerui.imux.model.AgentSession
import com.github.izerui.imux.model.AgentType
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

internal data class SessionTranscriptMessage(
    val role: String,
    val text: String,
    val hiddenFromTerminal: Boolean = false,
    /** Claude 压缩上下文后生成的摘要，以 user 身份写入，但不是用户说的话。 */
    val compactSummary: Boolean = false,
)

/**
 * 用户亲手输入的消息。排除隐藏的元数据、压缩摘要与 Claude 的中断标记——
 * 它们在会话文件里都是 user 角色，拿去当任务或起标题会张冠李戴。
 */
internal fun SessionTranscriptMessage.isUserInput(): Boolean =
    role == "user" && !hiddenFromTerminal && !compactSummary && !text.startsWith(CLAUDE_INTERRUPTED_PREFIX)

internal fun sessionTranscriptMessages(
    session: AgentSession,
    maxLines: Int,
    maxMessages: Int,
    maxMessageChars: Int,
): List<SessionTranscriptMessage> {
    val messages = mutableListOf<SessionTranscriptMessage>()
    Files.newBufferedReader(session.filePath).useLines { lines ->
        for (line in lines.take(maxLines)) {
            transcriptMessage(line, session.agentType, maxMessageChars)?.let(messages::add)
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
    return transcriptMessage(
        parseableLine,
        agentType,
        maxMessageChars,
        firstTextSegmentOnly = true,
    )
}

internal fun transcriptMessage(
    line: String,
    agentType: AgentType,
    maxMessageChars: Int,
    navigatorVisibleTextOnly: Boolean = false,
    includeToolContent: Boolean = false,
    firstTextSegmentOnly: Boolean = false,
    maxLineChars: Int = MAX_JSON_LINE_CHARS,
): SessionTranscriptMessage? {
    if (line.length > maxLineChars) return null
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
            if (firstTextSegmentOnly && role == "assistant") {
                firstSegmentContentText(message.get("content"))
            } else if (firstTextSegmentOnly || navigatorVisibleTextOnly) {
                navigatorContentText(message.get("content"))
            } else if (includeToolContent) {
                contentTextWithTools(message.get("content"))
            } else {
                contentText(message.get("content"))
            }
        )?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (role == "user" && text.startsWith("<") && text.endsWith(">")) return null
    // Codex 把 AGENTS.md 作为一条 user 消息注入会话开头，它不是用户说的话
    if (agentType == AgentType.CODEX && role == "user" && text.startsWith(CODEX_AGENTS_INSTRUCTIONS_PREFIX)) return null
    val normalized =
        (if (firstTextSegmentOnly) stripMarkdownForMatching(text) else text)
            .replace('\n', ' ')
            .replace('\t', ' ')
            .replace(Regex("\\s+"), " ")
            .take(maxMessageChars)
    return SessionTranscriptMessage(
        role,
        normalized,
        hiddenFromTerminal =
            agentType == AgentType.CLAUDE &&
                role == "user" &&
                root.get("isMeta")?.takeIf { it.isJsonPrimitive }?.asBoolean == true,
        compactSummary =
            agentType == AgentType.CLAUDE &&
                role == "user" &&
                root.get("isCompactSummary")?.takeIf { it.isJsonPrimitive }?.asBoolean == true,
    )
}

private fun contentText(element: JsonElement?): String? = contentText(element) { true }

private fun contentTextWithTools(element: JsonElement?): String? =
    contentTextWithTools(element) { true }

private fun contentTextWithTools(
    element: JsonElement?,
    includeText: (String) -> Boolean,
): String? =
    when {
        element == null || element.isJsonNull -> null
        element.isJsonPrimitive && element.asJsonPrimitive.isString ->
            element.asString.takeIf(includeText)
        element.isJsonArray ->
            element.asJsonArray
                .mapNotNull { contentTextWithTools(it, includeText) }
                .joinToString("\n")
                .takeIf(String::isNotBlank)
        element.isJsonObject -> {
            val obj = element.asJsonObject
            val type = obj.string("type")
            when (type) {
                "tool_use" -> {
                    val name = obj.string("name") ?: "unknown_tool"
                    val input = obj.get("input")?.toString()?.take(MAX_TOOL_CONTENT_CHARS) ?: ""
                    "[tool_use: $name] $input"
                }
                "tool_result", "function_call_output" -> {
                    val toolContent = contentTextWithTools(obj.get("content"), includeText)
                        ?: contentTextWithTools(obj.get("output"), includeText)
                        ?: obj.string("text")
                    toolContent?.take(MAX_TOOL_CONTENT_CHARS)?.let { "[tool_result] $it" }
                }
                else ->
                    contentTextWithTools(obj.get("text"), includeText)
                        ?: contentTextWithTools(obj.get("content"), includeText)
            }
        }
        else -> null
    }

private const val MAX_TOOL_CONTENT_CHARS = 2_000

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

/**
 * 提取一段在终端中连续出现的文本，不跨越 tool 调用。
 *
 * 助手回复的 content 数组常常是 [text, tool_use, tool_result, text, ...]。
 * 工具输出在终端中占据大量篇幅，把所有 text 块拼起来的结果在终端文档中不连续，
 * 导航匹配会找不到。取第一段连续文本——已有文本后遇到 tool 块就停止，还没收集到
 * 文本时跳过 tool 块继续往后找（处理 [tool_use, text] 的情况）。
 */
private fun firstSegmentContentText(element: JsonElement?): String? =
    when {
        element == null || element.isJsonNull -> null
        element.isJsonPrimitive && element.asJsonPrimitive.isString -> {
            val trimmed = element.asString.trim()
            if (
                trimmed == IMAGE_CLOSE_TAG ||
                (trimmed.startsWith(IMAGE_OPEN_TAG_PREFIX) && trimmed.endsWith(">"))
            ) {
                null
            } else {
                element.asString
            }
        }
        element.isJsonArray -> {
            val parts = mutableListOf<String>()
            for (item in element.asJsonArray) {
                if (item.isJsonObject) {
                    val type = item.asJsonObject.string("type")
                    if (type == "tool_use" || type == "tool_result" || type == "function_call_output") {
                        if (parts.isNotEmpty()) break
                        continue
                    }
                }
                firstSegmentContentText(item)?.let(parts::add)
            }
            parts.joinToString("\n").takeIf(String::isNotBlank)
        }
        element.isJsonObject -> {
            val obj = element.asJsonObject
            val type = obj.string("type")
            if (type == "tool_result" || type == "function_call_output") return null
            firstSegmentContentText(obj.get("text"))
                ?: firstSegmentContentText(obj.get("content"))
        }
        else -> null
    }

/**
 * 剥除 markdown 格式标记，让 transcript 文本与终端渲染后的文本匹配。
 *
 * CLI 在终端中渲染 markdown——反引号、代码围栏、粗体/斜体星号等标记被剥除，
 * 只保留可见文字和样式。原始 transcript 包含这些标记，直接做子串搜索会失败。
 *
 * 使用 IntelliJ 262+ 平台自带的 commonmark TextContentRenderer 做标准 markdown
 * 到纯文本转换，完整覆盖所有 markdown 语法。平台库不可用时降级为正则剥除。
 */
internal fun stripMarkdownForMatching(text: String): String =
    text
        .replace(Regex("```\\w*\\s*"), " ")
        .replace("```", " ")
        .replace('`', ' ')
        .replace("**", " ")
        .replace("__", " ")
        .replace("~~", " ")
        .replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")

internal fun redactNavigatorImagePayloads(line: String): String {
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
internal const val MAX_JSON_LINE_CHARS = 128_000
private const val MAX_MESSAGES = 6
private const val TITLE_RECENT_USER_MESSAGES = 8
private const val TITLE_MESSAGE_CHARS = 500
private const val TITLE_ASSISTANT_REPLY_CHARS = 400
private const val TITLE_TAIL_BYTES = 256L * 1024
private const val MAX_TITLE_TAIL_BYTES = 4L * 1024 * 1024
private const val MAX_NAVIGATOR_MESSAGES = 200
private const val MAX_NAVIGATOR_MESSAGE_CHARS = 1_000
private const val NAVIGATOR_TAIL_BYTES = 4L * 1024 * 1024
private const val DATA_KEY = "\"data\""
private const val IMAGE_URL_KEY = "\"image_url\""
private const val REDACTED_IMAGE_PAYLOAD = "<image-payload>"
private const val IMAGE_OPEN_TAG_PREFIX = "<image "
private const val IMAGE_CLOSE_TAG = "</image>"
private const val CODEX_AGENTS_INSTRUCTIONS_PREFIX = "# AGENTS.md instructions"
private const val CLAUDE_INTERRUPTED_PREFIX = "[Request interrupted by user"
private const val MAX_CLI_OUTPUT_CHARS = 16_000
private const val TITLE_TIMEOUT_SECONDS = 60L
private const val READ_DRAIN_TIMEOUT_SECONDS = 2L
private const val SQLITE_BUSY_TIMEOUT_MS = 5_000
