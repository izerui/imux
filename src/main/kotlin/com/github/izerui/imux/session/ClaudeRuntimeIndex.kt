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
 * @param status 本机实测有三种：`busy` 正在干活、`shell` 回答已打完但派生的后台 shell
 *   还在跑、`idle` 等待用户输入
 */
data class ClaudeRuntimeSession(
    val sessionId: String,
    val pid: Long,
    val kind: String?,
    val status: String?,
    val cwd: String?,
    val waitingFor: String? = null,
) {
    val isBackground: Boolean get() = kind == "bg"

    /** 弹出了权限确认或选择框，这一轮停下等用户操作。 */
    val isWaiting: Boolean get() = status == WAITING

    /**
     * 这一轮还在跑。据此显示运行中标记、判定轮次完成。
     *
     * `shell` 不算：那时回答早打完了，只剩后台 shell 在收尾。实测状态序列是
     * `busy` -> `shell` -> `idle`，若把 `shell` 也算忙碌，用户眼里就是
     * 「明明执行完了还一直转菊花」，且品牌图标要等到后台任务结束才回来。
     *
     * `waiting` 同样不算：那是 CLI 弹出了权限确认或选择框，对话已经停下等用户操作。
     * 继续转菊花会让人以为它还在跑，而实际上不去点一下它永远不会动。
     */
    val isBusy: Boolean get() =
        status != null && status != "idle" && status != SHELL && status != WAITING

    /** 会话被这个进程占着。resume 一个未空闲的后台会话会被 CLI 拒绝。 */
    val isOccupied: Boolean get() = status != null && status != "idle"

    private companion object {
        const val SHELL = "shell"
        const val WAITING = "waiting"
    }
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

    /**
     * @param projectPath 只保留 cwd 等于它的进程；传 null 则不过滤。
     *
     * 必须按项目过滤：这个目录是全机器共享的，本机实测常年同时跑着五六个不同项目的
     * claude。不过滤的话，每个 IDE 窗口都会为所有项目的会话弹提醒，而那些会话不在
     * 本项目的列表里，连标题都查不到，只能显示一串会话 id。
     *
     * cwd 缺失的一律排除：判不出归属就不该认领。
     */
    fun load(projectPath: String? = null): Map<String, ClaudeRuntimeSession> {
        val dir = claudeHome.resolve("sessions")
        if (!Files.isDirectory(dir)) return emptyMap()

        return runCatching {
            Files.list(dir).use { stream ->
                stream.toList()
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                    .mapNotNull { readOne(it) }
                    .filter { projectPath == null || it.cwd == projectPath }
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
            waitingFor = record.stringOf("waitingFor"),
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
