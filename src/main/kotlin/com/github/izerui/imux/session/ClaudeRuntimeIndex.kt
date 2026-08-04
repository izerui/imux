package com.github.izerui.imux.session

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path

/**
 * 一个正在运行的 Claude 进程所对应的会话。
 *
 * @param kind `interactive` 或 `bg`（后台 agent）
 * @param status `idle` 表示等待用户输入，其余（如 `busy`）表示正在干活
 */
data class ClaudeRuntimeSession(
    val sessionId: String,
    val pid: Long,
    val kind: String?,
    val status: String?,
    val cwd: String?,
) {
    val isBackground: Boolean get() = kind == "bg"

    /** 正在干活。resume 一个忙碌的后台会话会被 CLI 拒绝。 */
    val isBusy: Boolean get() = status != null && status != "idle"
}

/**
 * 读取 `~/.claude/sessions/` 下的每个 json——Claude Code 为每个运行中的进程写的运行态文件。
 *
 * （注意：注释里不要写 `sessions` 加斜杠星号的通配路径。Kotlin 的块注释可以嵌套，
 * 那个斜杠星号会开启一个嵌套注释，导致外层 KDoc 永远闭合不了。）
 *
 * 这是「会话此刻在干什么」的一手信息，由 CLI 自己维护并实时更新。它同时解决三件事：
 *
 * 1. `kind=bg` 且 `status!=idle` 时 resume 会被 CLI 拒绝
 *    （`Session ... is currently running as a background agent`），可提前拦住
 * 2. `status` 的 `busy -> idle` 跃迁就是「这一轮跑完了」，比从会话文件反推可靠
 * 3. 文件存在即进程存在，可据此显示运行中标记
 *
 * 只覆盖**活着的进程**（本机实测 13 个文件对应几百个历史会话），因此是补充而非替代。
 * 进程崩溃可能留下残留文件，故按 pid 校验存活。
 */
class ClaudeRuntimeIndex(
    private val claudeHome: Path,
    private val isProcessAlive: (Long) -> Boolean = ::defaultIsProcessAlive,
) {

    fun load(): Map<String, ClaudeRuntimeSession> {
        val dir = claudeHome.resolve("sessions")
        if (!Files.isDirectory(dir)) return emptyMap()

        return runCatching {
            Files.list(dir).use { stream ->
                stream.toList()
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                    .mapNotNull { readOne(it) }
                    .filter { isProcessAlive(it.pid) }
                    .associateBy { it.sessionId }
            }
        }.getOrElse {
            LOG.warn("读取 Claude 运行态目录失败", it)
            emptyMap()
        }
    }

    private fun readOne(file: Path): ClaudeRuntimeSession? = runCatching {
        val record = JsonParser.parseString(Files.readString(file)).asJsonObject
        val sessionId = record.stringOf("sessionId")?.takeIf { it.isNotEmpty() } ?: return null
        val pid = record.longOf("pid") ?: return null

        ClaudeRuntimeSession(
            sessionId = sessionId,
            pid = pid,
            kind = record.stringOf("kind"),
            status = record.stringOf("status"),
            cwd = record.stringOf("cwd"),
        )
    }.getOrNull()

    private fun JsonObject.stringOf(key: String): String? {
        val element = get(key) ?: return null
        if (!element.isJsonPrimitive) return null
        return runCatching { element.asString.trim() }.getOrNull()
    }

    /** pid 在该文件里是字符串，但不排除将来变成数字，两种都接受。 */
    private fun JsonObject.longOf(key: String): Long? {
        val element = get(key) ?: return null
        if (!element.isJsonPrimitive) return null
        return runCatching { element.asLong }.getOrNull()
            ?: runCatching { element.asString.trim().toLong() }.getOrNull()
    }

    private companion object {
        val LOG = logger<ClaudeRuntimeIndex>()
    }
}

private fun defaultIsProcessAlive(pid: Long): Boolean =
    runCatching { ProcessHandle.of(pid).map { it.isAlive }.orElse(false) }.getOrDefault(false)
