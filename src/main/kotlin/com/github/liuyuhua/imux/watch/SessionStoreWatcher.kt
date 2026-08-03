package com.github.liuyuhua.imux.watch

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 监听 CLI 会话库的变化，变化时回调。
 *
 * 为什么是轮询而非 java.nio 的 WatchService：
 * WatchService 只监听单层目录，而 codex 的会话落在 sessions/YYYY/MM/DD/ 下，
 * 监听 sessions 根目录收不到任何事件。要覆盖就得递归注册并对新建目录动态补注册，
 * 复杂度和出错面都明显更大。轮询只需 stat 少量目录，逻辑简单且可单测。
 *
 * 成本被刻意限制：claude 侧只看本项目那一个目录，codex 侧只看最近两天的日期目录
 * ——新会话必然落在这些位置。历史目录不参与轮询。
 */
class SessionStoreWatcher(
    private val claudeHome: Path,
    private val codexHome: Path,
    private val claudeProjectDirName: String,
    private val onChange: () -> Unit,
    private val today: () -> LocalDate = { LocalDate.now() },
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) : Disposable {

    private val lastSignature = AtomicReference<String?>(null)
    private var future: Future<*>? = null

    fun start() {
        lastSignature.set(signature())
        future = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            ::tick,
            intervalMs,
            intervalMs,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun tick() {
        val current = runCatching { signature() }.getOrNull() ?: return
        if (lastSignature.getAndSet(current) == current) return
        ApplicationManager.getApplication().invokeLater { onChange() }
    }

    /** 被监听目录的廉价指纹：文件名 + 大小 + 修改时间。任一变化即视为会话库有更新。 */
    fun signature(): String = watchedDirs()
        .filter { Files.isDirectory(it) }
        .flatMap { dir ->
            Files.list(dir).use { stream ->
                stream.toList()
                    .filter { it.fileName.toString().endsWith(".jsonl") }
                    .map { file ->
                        val attrs = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes::class.java)
                        "${file.fileName}:${attrs.size()}:${attrs.lastModifiedTime().toMillis()}"
                    }
            }
        }
        .sorted()
        .joinToString("|")

    fun watchedDirs(): List<Path> {
        val day = today()
        val codexSessions = codexHome.resolve("sessions")
        return listOf(
            claudeHome.resolve("projects").resolve(claudeProjectDirName),
            codexSessions.resolve(datePath(day)),
            codexSessions.resolve(datePath(day.minusDays(1))),
        )
    }

    private fun datePath(date: LocalDate): String =
        "%04d/%02d/%02d".format(date.year, date.monthValue, date.dayOfMonth)

    override fun dispose() {
        future?.cancel(false)
        future = null
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 3000L
    }
}
