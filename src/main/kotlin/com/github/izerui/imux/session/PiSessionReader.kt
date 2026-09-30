package com.github.izerui.imux.session

import com.github.izerui.imux.model.AgentSession
import com.github.izerui.imux.model.AgentType
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.useLines

/**
 * 读取 pi 的会话库。
 *
 * 布局：<agentDir>/sessions/<cwd 编码>/<时间戳>_<session-uuid>.jsonl
 *
 * 与 Claude 一样是「一个项目一个目录」，可由项目路径直接算出，
 * 不必像 Codex 那样扫全库再按首行 cwd 归属。
 *
 * 例外是指定了会话目录（`PI_CODING_AGENT_SESSION_DIR` 或设置里的 `sessionDir`）：
 * 所有项目的会话平铺在同一个目录里，归属只能靠首行 cwd——[readOne] 本来就逐个比对
 * cwd，读法不变。优先级见 [sessionDir]。
 *
 * 构造器接收 agentDir 而非硬编码 ~/.pi/agent，一是 `PI_CODING_AGENT_DIR` 可以改它，
 * 二是测试能指向临时目录。
 */
class PiSessionReader(
    private val agentDir: Path,
    /** `PI_CODING_AGENT_SESSION_DIR`。 */
    private val customSessionDir: Path? = null,
    /** 展开设置里 `~` 开头的路径用。 */
    private val userHome: Path = Path.of(System.getProperty("user.home")),
) {

    /**
     * 目录名编码，与 pi 的 `dist/core/session-manager.js` 保持一致：
     *
     * ```js
     * const safePath = `--${resolvedCwd.replace(/^[/\\]/, "").replace(/[/\\:]/g, "-")}--`;
     * ```
     *
     * 只有路径分隔符与盘符冒号被替换，`.`、`_`、`-` 都原样保留——
     * 这点与 Claude 的编码不同（那边连 `.` 也换），错一个字符就整个目录读不到。
     */
    fun projectDirName(projectPath: String): String =
        buildString(projectPath.length + 4) {
            append("--")
            for (ch in projectPath.removePrefix("/").removePrefix("\\")) {
                append(if (ch == '/' || ch == '\\' || ch == ':') '-' else ch)
            }
            append("--")
        }

    /**
     * 该项目的会话所在目录，优先级与 pi 启动时一致：
     * `PI_CODING_AGENT_SESSION_DIR` > 设置里的 `sessionDir` > `<agentDir>/sessions/<cwd 编码>`。
     *
     * 命令行的 `--session-dir` 优先级最高，但只对那一次启动生效，imux 无从得知。
     *
     * 设置文件每次都重读，不缓存：用户改了设置，下一轮扫描就该跟上。两个小 JSON 文件，
     * 与随后列目录、读会话文件的开销相比可以忽略。
     */
    fun sessionDir(projectPath: String): Path =
        customSessionDir
            ?: piSettingsSessionDir(
                globalJson = readText { agentDir.resolve("settings.json") },
                projectJson = readText { Path.of(projectPath).resolve(".pi").resolve("settings.json") },
                projectPath = projectPath,
                userHome = userHome,
            )
            ?: agentDir.resolve("sessions").resolve(projectDirName(projectPath))

    /** 不存在、读不了一律当作没有这份设置，与 pi 把读取失败的设置当成空对象一致。 */
    private fun readText(file: () -> Path): String? =
        runCatching { file().takeIf { Files.isRegularFile(it) }?.let(Files::readString) }.getOrNull()

    fun read(projectPath: String): List<AgentSession> {
        val dir = sessionDir(projectPath)
        if (!Files.isDirectory(dir)) return emptyList()

        return Files.list(dir).use { stream ->
            stream.toList()
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jsonl") }
                .mapNotNull { readOne(it, projectPath) }
        }
    }

    private fun readOne(file: Path, projectPath: String): AgentSession? = runCatching {
        val head = firstLine(file) ?: return null
        if (!head.contains(SESSION_HEAD_MARKER)) return null

        // 编码把 '/' 和 '-' 映射到同一个字符，/Users/demo/a-b 与 /Users/demo/a/b
        // 会落进同一个目录名。首行的 cwd 是原始路径，用它排除撞进来的会话。
        val cwd = JsonLineScanner.stringValue(head, "cwd") ?: return null
        if (cwd != projectPath) return null

        val id = JsonLineScanner.stringValue(head, "id") ?: return null
        if (!hasUserMessage(file)) return null

        AgentSession(
            id = id,
            // 标题优先取会话显示名，否则取最后一条用户消息
            title = sessionName(file)?.let(::truncate)
                ?: lastUserMessage(file)
                ?: truncate(id),
            agentType = AgentType.PI,
            // 与另外两个 reader 同一口径：优先用记录自带的时刻而非 mtime，
            // 理由见 lastTimestampOf 的注释。
            // `/name` 与 imux 的标题重新生成都会追加 session_info；那只是显示元数据，
            // 不能把一个几周没说话的会话顶到“刚刚”。活动时间只认非命名记录。
            lastActiveAt =
                lastTimestampOf(file) { line ->
                    JsonLineScanner.topLevelStringValue(line, "type") != SESSION_INFO_TYPE
                } ?: Files.getLastModifiedTime(file).toInstant(),
            createdAt = creationTimeOf(file),
            filePath = file,
        )
    }.onFailure { LOG.warn("跳过无法解析的 pi 会话文件 $file", it) }.getOrNull()

    private fun firstLine(file: Path): String? = file.useLines { it.firstOrNull() }

    /**
     * 取最后一条 session_info 的 name。
     *
     * 必须是最后一条：`/name` 可以改多次，每次都往文件里追加一条新的 session_info，
     * 取首条就会一直显示改名前的旧标题。
     *
     * 逐行扫描而非 JSON 反序列化，理由同 [ClaudeSessionReader.extractTitle]：
     * 会话文件单行可达数 MB，为一个标题解析整行不划算。
     */
    private fun sessionName(file: Path): String? {
        var name: String? = null
        file.useLines { lines ->
            for (line in lines) {
                if (!line.contains(SESSION_INFO_MARKER)) continue
                val reported = JsonLineScanner.stringValue(line, "name") ?: continue
                name = reported.trim().takeIf { it.isNotEmpty() }
            }
        }
        return name
    }

    /**
     * 会话是否有过用户消息。
     *
     * pi 启动后会立刻写 session header；若用户没发过消息就关闭，这个文件无法由 CLI
     * 恢复成有意义的对话，因此排除这种只有头部的空会话。
     */
    private fun hasUserMessage(file: Path): Boolean = file.useLines { lines ->
        lines.any { userMessageText(it) != null }
    }

    /** 从文件尾部取最后一条用户消息，与 CLI 的 resume 列表对齐。 */
    private fun lastUserMessage(file: Path): String? =
        scanTail(file) { lines ->
            lines.asReversed()
                .mapNotNull(::userMessageText)
                .map { it.replace('\n', ' ').replace('\t', ' ').trim() }
                .firstOrNull { it.isNotEmpty() }
        }?.let(::truncate)

    private fun userMessageText(line: String): String? {
        if (!line.contains(USER_ROLE_MARKER)) return null
        if (JsonLineScanner.topLevelStringValue(line, "type") != MESSAGE_TYPE) return null
        if (JsonLineScanner.objectStringValue(line, "message", "role") != USER_ROLE) return null
        return JsonLineScanner.objectStringValue(line, "message", "content")
            ?: JsonLineScanner.stringValueInObject(line, "message", "text")
    }

    private fun truncate(text: String): String =
        if (text.length <= TITLE_MAX) text else text.take(TITLE_MAX) + "…"

    private companion object {
        val LOG = logger<PiSessionReader>()

        const val TITLE_MAX = 60

        const val SESSION_HEAD_MARKER = "\"type\":\"session\""
        const val SESSION_INFO_MARKER = "\"type\":\"session_info\""
        const val SESSION_INFO_TYPE = "session_info"
        const val USER_ROLE_MARKER = "\"role\":\"user\""
        const val MESSAGE_TYPE = "message"
        const val USER_ROLE = "user"
    }
}

/**
 * pi 设置里的 `sessionDir`，没有配置时返回 null。
 *
 * 与 pi 的 `SettingsManager` 对齐（`dist/core/settings-manager.js`）：
 * - 全局设置 `<agentDir>/settings.json` 与项目设置 `<cwd>/.pi/settings.json` 按
 *   `deepMergeSettings` 合并，项目里**只要有这个键**就覆盖全局——哪怕值是 `""` 或
 *   `null`，那时结果是「没配置」，而不是退回全局的值
 * - 解析失败的文件当作空对象，另一份照常生效
 * - 值为空串或不是字符串，视为没配置
 * - `getSessionDir` 用 `normalizePath` 展开 `~`、`~/`（Windows 上还有 `~\`）与
 *   `file://`，相对路径原样交给 Node，按 pi 进程的 cwd 解析。imux 总在项目目录里启动
 *   pi，所以按 [projectPath] 解析
 * - Windows 上先将 Git Bash / MSYS、WSL、Cygwin 的盘符路径转为原生路径
 */
internal fun piSettingsSessionDir(
    globalJson: String?,
    projectJson: String?,
    projectPath: String,
    userHome: Path,
    isWindows: Boolean = SystemInfo.isWindows,
): Path? {
    val project = parseSettings(projectJson)
    val value =
        if (project?.has(SESSION_DIR_KEY) == true) {
            project.get(SESSION_DIR_KEY)
        } else {
            parseSettings(globalJson)?.get(SESSION_DIR_KEY)
        }
    val raw =
        value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?.takeIf { it.isNotEmpty() }
            ?: return null
    return runCatching {
        val path =
            when {
                raw == "~" -> userHome
                raw.startsWith("~/") || raw.startsWith("~\\") -> userHome.resolve(raw.substring(2))
                raw.startsWith("file://") -> Path.of(URI(raw))
                else -> Path.of(normalizePiWindowsShellPath(raw, isWindows))
            }
        (if (path.isAbsolute) path else Path.of(projectPath).resolve(path)).normalize()
    }.getOrNull()
}

private const val SESSION_DIR_KEY = "sessionDir"

private val PI_WINDOWS_SHELL_PATH = Regex("""^/(?:mnt/|cygdrive/)?([a-z])(?:/(.*))?$""", RegexOption.IGNORE_CASE)

/** 对齐 pi 的 normalizeWindowsShellPath；UNC 与含反斜杠的输入不属于 shell 盘符路径。 */
internal fun normalizePiWindowsShellPath(
    path: String,
    isWindows: Boolean,
): String {
    if (!isWindows || !path.startsWith("/") || path.startsWith("//") || '\\' in path) return path
    val match = PI_WINDOWS_SHELL_PATH.matchEntire(path) ?: return path
    return "${match.groupValues[1].uppercase()}:\\${match.groupValues[2].replace('/', '\\')}"
}

private fun parseSettings(json: String?): JsonObject? =
    json?.let {
        runCatching { JsonParser.parseString(it.removePrefix("\uFEFF")) }
            .getOrNull()
            ?.takeIf { element -> element.isJsonObject }
            ?.asJsonObject
    }
