package com.github.izerui.imux.agent

import com.intellij.openapi.diagnostic.logger
import java.util.concurrent.atomic.AtomicReference

private val LOG = logger<TrackedProcess>()

/**
 * CLI 进程（通常是 shell）及清理过程中捕获到的全部子进程句柄。
 *
 * shell 退出后，仍未退出的 claude/codex/pi 子进程会脱离父进程，再调用 `process.descendants()` 就找不到了，
 * 所以每次清理都把新发现的子进程并入这里，已捕获的句柄一直保留到确认退出。
 */
internal class TrackedProcess(val process: Process) {
    private val descendants = java.util.concurrent.CopyOnWriteArraySet<ProcessHandle>()

    /** 并入当前还能从父进程找到的子进程；父进程已退出时找不到也不影响已捕获的句柄。 */
    fun captureDescendants() {
        runCatching { process.descendants().forEach(descendants::add) }
    }

    fun capturedDescendants(): Set<ProcessHandle> = descendants

    /**
     * 父进程存活期间定期并入子进程。CLI 的工具可能启动后台进程，CLI 一旦先退出，这些进程会被系统重新托管，
     * 退出后再查 `descendants()` 就找不到了；只有在父进程还活着时记下句柄，事后才能清理。
     *
     * 两次扫描之间启动又被托管的进程仍可能漏掉（间隔 [intervalMillis]）。没有用进程组：
     * Java 不能让子进程另起进程组，而结束当前进程组会连 IDE 一起结束。
     */
    fun watchDescendants(intervalMillis: Long = DESCENDANT_POLL_MILLIS): Thread =
        Thread({
            try {
                while (process.isAlive) {
                    captureDescendants()
                    process.waitFor(intervalMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
            } catch (_: InterruptedException) {
                // 调用方结束了：清理时还会再并入一次
            }
        }, "imux-agent-descendant-watcher").apply {
            isDaemon = true
            start()
        }

    fun unexitedDescendants(): List<ProcessHandle> = descendants.filter(ProcessHandle::isAlive)

    /** 父进程和所有已捕获的子进程都已退出才算退出。 */
    val isExited: Boolean get() = !process.isAlive && unexitedDescendants().isEmpty()

    override fun toString(): String =
        "pid ${runCatching { process.pid() }.getOrDefault(-1L)}" +
            unexitedDescendants().takeIf { it.isNotEmpty() }?.let { " 未退出子进程 ${it.map(ProcessHandle::pid)}" }.orEmpty()
}

/** 清理截止时仍未退出的进程树；[retry] 针对已捕获的句柄继续销毁和等待，确认退出才移除。 */
internal class UnexitedProcesses {
    private val pending = java.util.concurrent.ConcurrentLinkedQueue<TrackedProcess>()

    fun addAll(survivors: List<TrackedProcess>) {
        if (survivors.isEmpty()) return
        pending.addAll(survivors)
        LOG.warn("单次智能体调用：${survivors.size} 个 CLI 进程树在截止时间内未退出：$survivors")
    }

    fun retry(timeoutNanos: Long = PROCESS_EXIT_TIMEOUT_NANOS) {
        val batch = generateSequence { pending.poll() }.toList()
        addAll(batch.filterNot { destroyAndAwait(it, timeoutNanos) })
    }

    /** 父进程已退出但子进程仍在的也算未退出，不能被 isAlive 过滤掉。 */
    fun snapshot(): List<TrackedProcess> = pending.filterNot(TrackedProcess::isExited)
}

/** 销毁进程树并等待退出；确认退出后才清空 [processRef]，否则保留引用表示仍未退出。 */
internal fun releaseProcess(
    tracked: TrackedProcess,
    processRef: AtomicReference<TrackedProcess?>,
    timeoutNanos: Long = PROCESS_EXIT_TIMEOUT_NANOS,
): Boolean {
    val exited = destroyAndAwait(tracked, timeoutNanos)
    if (exited) processRef.compareAndSet(tracked, null)
    return exited
}

/** 每次 CLI 调用登记一个引用；调用结束时引用已清空（进程树确认退出）才注销。 */
internal fun <T> runTracked(
    registry: MutableCollection<AtomicReference<TrackedProcess?>>,
    block: (AtomicReference<TrackedProcess?>) -> T,
): T {
    val processRef = AtomicReference<TrackedProcess?>()
    registry.add(processRef)
    try {
        return block(processRef)
    } finally {
        if (processRef.get() == null) registry.remove(processRef)
    }
}

/** 兜底销毁仍登记的进程树，返回截止时间内仍未退出的（其引用保持不清空）。 */
internal fun cleanupProcesses(
    refs: Collection<AtomicReference<TrackedProcess?>>,
    timeoutNanos: Long = PROCESS_EXIT_TIMEOUT_NANOS,
): List<TrackedProcess> = refs.mapNotNull { ref ->
    val tracked = ref.get() ?: return@mapNotNull null
    tracked.takeUnless { releaseProcess(it, ref, timeoutNanos) }
}

internal fun destroyAndAwait(process: Process, timeoutNanos: Long = PROCESS_EXIT_TIMEOUT_NANOS): Boolean =
    destroyAndAwait(TrackedProcess(process), timeoutNanos)

/**
 * 强制结束 CLI 进程及其全部已捕获的子进程（shell 下的 claude/codex/pi），在同一截止时间内等待它们真正退出。
 *
 * 等待期间收到的中断（取消、调用方的线程池 shutdownNow 等）不会提前结束等待，
 * 只记下来，返回前恢复中断标志。返回 false 表示截止时仍有进程未退出，句柄留在 [tracked] 里供下次清理。
 */
internal fun destroyAndAwait(tracked: TrackedProcess, timeoutNanos: Long = PROCESS_EXIT_TIMEOUT_NANOS): Boolean {
    tracked.captureDescendants()
    val descendants = tracked.unexitedDescendants()
    descendants.forEach(ProcessHandle::destroyForcibly)
    tracked.process.destroyForcibly()
    val deadline = System.nanoTime() + timeoutNanos
    var interrupted = Thread.interrupted()
    try {
        while (true) {
            try {
                if (!tracked.process.waitFor(maxOf(deadline - System.nanoTime(), 0L), java.util.concurrent.TimeUnit.NANOSECONDS)) {
                    return false
                }
                return descendants.all { handle ->
                    try {
                        handle.onExit().get(maxOf(deadline - System.nanoTime(), 0L), java.util.concurrent.TimeUnit.NANOSECONDS)
                        true
                    } catch (_: java.util.concurrent.TimeoutException) {
                        false
                    }
                }
            } catch (_: InterruptedException) {
                // 继续等到同一截止时间；超过截止后 waitFor(0) 不再阻塞，循环必然结束
                interrupted = true
            }
        }
    } finally {
        if (interrupted) Thread.currentThread().interrupt()
    }
}

internal const val PROCESS_EXIT_TIMEOUT_SECONDS = 5L
internal const val DESCENDANT_POLL_MILLIS = 200L
internal val PROCESS_EXIT_TIMEOUT_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(PROCESS_EXIT_TIMEOUT_SECONDS)
