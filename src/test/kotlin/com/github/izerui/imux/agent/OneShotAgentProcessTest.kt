package com.github.izerui.imux.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 进程清理：中断时继续等待、超时保留未退出状态、shell 退出后仍能找到子进程。 */
class OneShotAgentProcessTest {
    @Test
    fun `destroyAndAwait 在线程带中断标志时仍等到进程退出并保留中断`() {
        val process = ProcessBuilder(AgentTestProcesses.command("block")).start()
        assertTrue(process.isAlive)
        Thread.currentThread().interrupt()
        try {
            destroyAndAwait(process)
            assertFalse("返回时进程应已退出", process.isAlive)
            assertTrue("中断标志应被恢复", Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    // ── 清理：中断与超时 ──

    /**
     * 可控的假进程：收到 destroyForcibly 后过 [exitDelayMs] 毫秒才退出（null 表示永不退出）。
     * waitFor 的语义与真实进程一致：已退出立即返回 true，超时参数 ≤ 0 不阻塞，阻塞中被中断抛 InterruptedException。
     */
    private class ControlledProcess(
        private val exitDelayMs: Long?,
        private val childHandles: () -> List<ProcessHandle> = { emptyList() },
    ) : Process() {
        private val exited = CountDownLatch(1)
        val waitingStarted = CountDownLatch(1)
        val blockingWaits = AtomicInteger(0)
        @Volatile var exitedAtNanos = 0L

        override fun destroyForcibly(): Process {
            if (exitDelayMs != null) {
                Thread {
                    Thread.sleep(exitDelayMs)
                    exitedAtNanos = System.nanoTime()
                    exited.countDown()
                }.apply { isDaemon = true; start() }
            }
            return this
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (exited.count == 0L) return true
            if (timeout <= 0) return false
            blockingWaits.incrementAndGet()
            waitingStarted.countDown()
            return exited.await(timeout, unit)
        }

        override fun isAlive(): Boolean = exited.count != 0L
        override fun descendants(): java.util.stream.Stream<ProcessHandle> = childHandles().stream()
        override fun pid(): Long = -1
        override fun waitFor(): Int { exited.await(); return 0 }
        override fun exitValue(): Int = if (isAlive) throw IllegalThreadStateException() else 0
        override fun destroy() { destroyForcibly() }
        override fun getOutputStream(): java.io.OutputStream = java.io.OutputStream.nullOutputStream()
        override fun getInputStream(): java.io.InputStream = java.io.InputStream.nullInputStream()
        override fun getErrorStream(): java.io.InputStream = java.io.InputStream.nullInputStream()
    }

    @Test
    fun `清理已开始等待时再被中断不会提前返回并在退出后恢复中断`() {
        val process = ControlledProcess(exitDelayMs = 400)
        val result = AtomicReference<Boolean?>(null)
        val returnedAt = java.util.concurrent.atomic.AtomicLong()
        val interruptedAfter = AtomicBoolean(false)

        val worker = Thread {
            result.set(destroyAndAwait(process, TimeUnit.SECONDS.toNanos(5)))
            returnedAt.set(System.nanoTime())
            interruptedAfter.set(Thread.currentThread().isInterrupted)
        }.apply { isDaemon = true; start() }

        // 模拟另一段失败触发 shutdownNow()：在清理已阻塞等待时中断
        assertTrue("清理应已开始等待", process.waitingStarted.await(5, TimeUnit.SECONDS))
        worker.interrupt()
        worker.join(10_000)

        assertEquals("进程退出后应返回 true", true, result.get())
        assertTrue("不应在进程退出前返回", returnedAt.get() >= process.exitedAtNanos && process.exitedAtNanos != 0L)
        assertTrue("中断应打断过一次阻塞等待，并在之后继续等待", process.blockingWaits.get() >= 2)
        assertTrue("返回后应恢复中断标志", interruptedAfter.get())
    }

    @Test
    fun `截止时间内未退出时返回 false 并保留进程引用`() {
        val process = ControlledProcess(exitDelayMs = null)
        val tracked = TrackedProcess(process)
        val ref = AtomicReference<TrackedProcess?>(tracked)

        assertFalse(destroyAndAwait(process, TimeUnit.MILLISECONDS.toNanos(100)))
        assertFalse("未退出时不应清空引用", releaseProcess(tracked, ref, TimeUnit.MILLISECONDS.toNanos(100)))
        assertTrue(ref.get() === tracked)

        val exitedRef = AtomicReference<TrackedProcess?>(TrackedProcess(ControlledProcess(exitDelayMs = 0)))
        val survivors = cleanupProcesses(listOf(ref, exitedRef), TimeUnit.MILLISECONDS.toNanos(500))
        assertEquals("兜底清理应返回仍未退出的进程", listOf(tracked), survivors)
        assertTrue("未退出进程的引用仍保留", ref.get() === tracked)
        assertNull("已退出进程的引用应清空", exitedRef.get())
    }

    @Test
    fun `进程未确认退出前调用登记不会被注销`() {
        val registry = java.util.concurrent.ConcurrentLinkedQueue<AtomicReference<TrackedProcess?>>()
        val stuck = TrackedProcess(ControlledProcess(exitDelayMs = null))

        runTracked(registry) { ref -> ref.set(stuck) }
        assertEquals("引用仍指向未退出进程，应保持登记供兜底清理", 1, registry.size)
        assertTrue(registry.single().get() === stuck)

        registry.clear()
        runTracked(registry) { ref -> ref.set(null) }
        assertTrue("进程已退出（引用清空）应注销", registry.isEmpty())

        try {
            runTracked(registry) { ref -> ref.set(stuck); throw OneShotAgentTimeoutException(1) }
        } catch (_: OneShotAgentTimeoutException) {
        }
        assertEquals("调用抛异常时未退出进程也应保持登记", 1, registry.size)
    }

    /** 可控的子进程句柄：首次 destroyForcibly 后过 [exitDelayMs] 毫秒才退出。 */
    private class ControlledHandle(private val pid: Long, private val exitDelayMs: Long) : ProcessHandle {
        private val exit = java.util.concurrent.CompletableFuture<ProcessHandle>()
        private val destroyed = AtomicBoolean(false)
        val destroyCalls = AtomicInteger(0)

        override fun destroyForcibly(): Boolean {
            destroyCalls.incrementAndGet()
            if (destroyed.compareAndSet(false, true)) {
                Thread {
                    Thread.sleep(exitDelayMs)
                    exit.complete(this)
                }.apply { isDaemon = true; start() }
            }
            return true
        }

        override fun destroy(): Boolean = destroyForcibly()
        override fun isAlive(): Boolean = !exit.isDone
        override fun onExit(): java.util.concurrent.CompletableFuture<ProcessHandle> = exit
        override fun pid(): Long = pid
        override fun parent(): java.util.Optional<ProcessHandle> = java.util.Optional.empty()
        override fun children(): java.util.stream.Stream<ProcessHandle> = java.util.stream.Stream.empty()
        override fun descendants(): java.util.stream.Stream<ProcessHandle> = java.util.stream.Stream.empty()
        override fun info(): ProcessHandle.Info = ProcessHandle.current().info()
        override fun supportsNormalTermination(): Boolean = true
        override fun compareTo(other: ProcessHandle): Int = pid.compareTo(other.pid())
    }

    @Test
    fun `shell 已退出而 CLI 子进程首次等待超时后第二次清理仍能找到它直到退出才注销`() {
        // 第一次清理时还能从 shell 找到 CLI 子进程；shell 退出后子进程脱离父进程，再查 descendants 就是空的
        val cli = ControlledHandle(pid = 4242, exitDelayMs = 600)
        val lookups = AtomicInteger(0)
        val shell = ControlledProcess(exitDelayMs = 0) { if (lookups.incrementAndGet() == 1) listOf(cli) else emptyList() }
        val tracked = TrackedProcess(shell)
        val ref = AtomicReference<TrackedProcess?>(tracked)
        val registry = java.util.concurrent.ConcurrentLinkedQueue(listOf(ref))

        // 第一次清理：shell 很快退出，CLI 子进程 100ms 内没退出
        val firstSurvivors = cleanupProcesses(registry, TimeUnit.MILLISECONDS.toNanos(100))
        assertEquals("第一次清理应报告未退出", listOf(tracked), firstSurvivors)
        assertFalse("shell 已退出", shell.isAlive)
        assertTrue("CLI 子进程仍在", cli.isAlive)
        assertTrue("引用应保留", ref.get() === tracked)
        assertFalse("shell 退出但子进程仍在，不能算已退出", tracked.isExited)

        // 服务层登记：只看 shell 是否存活会把它藏起来
        val unexited = UnexitedProcesses()
        unexited.addAll(firstSurvivors)
        assertEquals("未退出列表应包含这棵进程树", listOf(tracked), unexited.snapshot())

        // 第二次清理：shell 已经找不到子进程，只能靠首次捕获的句柄
        unexited.retry(TimeUnit.SECONDS.toNanos(5))
        assertTrue("第二次清理前 shell 已查不到子进程", lookups.get() >= 2)
        assertFalse("第二次清理应等到 CLI 子进程退出", cli.isAlive)
        assertTrue("对已捕获句柄应再次发出销毁", cli.destroyCalls.get() >= 2)
        assertTrue("确认退出后应从未退出列表移除", unexited.snapshot().isEmpty())

        // 通过引用的清理路径同样要到子进程退出才清空登记
        assertTrue("进程树退出后兜底清理不再报告", cleanupProcesses(registry, TimeUnit.SECONDS.toNanos(1)).isEmpty())
        assertNull("确认退出后才清空引用", ref.get())
    }

    @Test
    fun `子进程首次等待超时后经引用的第二次清理也能找到它`() {
        val cli = ControlledHandle(pid = 4343, exitDelayMs = 600)
        val lookups = AtomicInteger(0)
        val shell = ControlledProcess(exitDelayMs = 0) { if (lookups.incrementAndGet() == 1) listOf(cli) else emptyList() }
        val tracked = TrackedProcess(shell)
        val ref = AtomicReference<TrackedProcess?>(tracked)

        assertFalse("首次释放应因子进程未退出返回 false", releaseProcess(tracked, ref, TimeUnit.MILLISECONDS.toNanos(100)))
        assertTrue("引用应保留", ref.get() === tracked)

        val survivors = cleanupProcesses(listOf(ref), TimeUnit.SECONDS.toNanos(5))
        assertTrue("第二次清理应等到子进程退出：$survivors", survivors.isEmpty())
        assertFalse(cli.isAlive)
        assertNull("子进程退出后才清空引用", ref.get())
    }
}
