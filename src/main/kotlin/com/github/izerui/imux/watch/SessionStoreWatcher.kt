package com.github.izerui.imux.watch

import com.intellij.openapi.Disposable
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
    /**
     * 刷新运行状态，与会话文件是否变化无关。
     *
     * 运行态（进程存活、忙碌与否）不体现在会话文件的指纹里，只能靠定时轮询。
     * 早先把它挂在 onChange 上，导致关闭标签页后标记要等下一次文件变化才消失。
     *
     * 触发节奏见 [fastTickWanted]。在调度线程上执行，可放心做 IO。
     */
    private val onTick: () -> Unit = {},
    /**
     * 是否需要密集轮询。为真时每轮都跑 [onTick]，否则只在慢周期跑。
     *
     * 由「有没有开着的标签页」决定：运行中标记只服务于自己正在用的会话，
     * 一个都没开时没人看标记，1 秒一轮纯属白耗电。
     */
    private val fastTickWanted: () -> Boolean = { false },
    private val today: () -> LocalDate = { LocalDate.now() },
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    /** 每多少个基础节拍做一次会话库全量比对。 */
    private val slowEveryTicks: Int = DEFAULT_SLOW_EVERY_TICKS,
) : Disposable {

    private val lastSignature = AtomicReference<String?>(null)
    private var future: Future<*>? = null
    private var ticks = 0L

    fun start() {
        lastSignature.set(signature())
        future = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            ::tick,
            intervalMs,
            intervalMs,
            TimeUnit.MILLISECONDS,
        )
    }

    /**
     * 基础节拍 1 秒，但两件事的节奏不同：
     *
     * - **运行状态**（[onTick]）要跟手，有标签页开着时每拍都跑
     * - **会话库全量比对**（[signature] + [onChange]）成本高得多——本机 620 个 codex
     *   会话文件，一次扫描 60–250ms——维持 3 秒一次即可，新会话晚两秒出现在列表里无所谓
     *
     * 两个回调都直接在本调度线程上跑，不再 invokeLater：它们要做文件 IO，
     * 挪到 EDT 上就是周期性卡顿。切回 EDT 由回调自己在需要时负责。
     */
    @org.jetbrains.annotations.VisibleForTesting
    internal fun tick() {
        ticks++
        val slowTurn = ticks % slowEveryTicks == 0L

        if (slowTurn || runCatching { fastTickWanted() }.getOrDefault(false)) {
            runCatching { onTick() }
        }

        if (!slowTurn) return
        val current = runCatching { signature() }.getOrNull() ?: return
        if (lastSignature.getAndSet(current) == current) return
        onChange()
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
        /** 基础节拍。运行状态的刷新粒度，也是标记出现的最大延迟。 */
        const val DEFAULT_INTERVAL_MS = 1000L

        /** 3 拍一次会话库全量比对，即维持原先的 3 秒节奏。 */
        const val DEFAULT_SLOW_EVERY_TICKS = 3
    }
}
