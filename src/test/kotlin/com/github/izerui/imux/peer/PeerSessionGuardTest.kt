package com.github.izerui.imux.peer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class PeerSessionGuardTest {

    private fun PeerSessionGuard.start(gen: Long = 1L): PeerRun =
        tryStart(gen, currentGeneration = { gen })!!.run

    @Test
    fun `首次 tryStart 返回新运行`() {
        val guard = PeerSessionGuard()
        val run = guard.start()
        assertNotNull(run)
        assertTrue(guard.isActive(run))
    }

    @Test
    fun `运行期间同代 tryStart 取消旧运行并返回新运行`() {
        val guard = PeerSessionGuard()
        val run1 = guard.start(1)

        val run2 = guard.start(1)
        assertNotSame("应是不同的 run", run1, run2)
        assertTrue("旧 run 应被取消", run1.cancelled.get())
        assertFalse("旧 run 不再 active", guard.isActive(run1))
        assertTrue("新 run 应 active", guard.isActive(run2))
    }

    @Test
    fun `generation 不匹配时 tryStart 返回 null 且不取消当前 run`() {
        val guard = PeerSessionGuard()
        val currentGen = AtomicLong(1)
        val run1 = guard.tryStart(1, currentGeneration = { currentGen.get() })!!.run

        currentGen.set(2)
        val result2 = guard.tryStart(1, currentGeneration = { currentGen.get() })
        assertNull("generation 不匹配应返回 null", result2)
        assertFalse("当前 run 不应被取消", run1.cancelled.get())
        assertTrue("当前 run 仍应 active", guard.isActive(run1))
    }

    @Test
    fun `旧代事件不会取消新代 run`() {
        val guard = PeerSessionGuard()
        val currentGen = AtomicLong(1)

        val run1 = guard.tryStart(1, currentGeneration = { currentGen.get() })!!.run

        currentGen.set(2)
        guard.cancel()
        val run2 = guard.tryStart(2, currentGeneration = { currentGen.get() })!!.run

        // 旧事件持有 generation=1，但 currentGeneration 已变为 2
        val staleResult = guard.tryStart(1, currentGeneration = { currentGen.get() })
        assertNull("旧代事件不应启动", staleResult)
        assertFalse("新代 run 不应被取消", run2.cancelled.get())
        assertTrue("新代 run 仍应 active", guard.isActive(run2))
    }

    @Test
    fun `cancelAndDetach 摘下的 run 的 killProcess 不影响后续新 run`() {
        val guard = PeerSessionGuard()
        val run1 = guard.start(1)
        val oldProcess = stubProcess()
        run1.attach(oldProcess)

        val detached = guard.cancelAndDetach()
        assertSame("detached 应是 run1", run1, detached)
        assertTrue("run1 应被标记取消", run1.cancelled.get())
        assertFalse("run1 不再 active", guard.isActive(run1))

        val run2 = guard.start(1)
        assertNotSame("新 run 应是不同实例", detached, run2)
        val newProcess = stubProcess()
        run2.attach(newProcess)

        detached?.killProcess()

        assertTrue("旧进程应被终止", oldProcess.destroyed.get())
        assertFalse("新进程不应被终止", newProcess.destroyed.get())
        assertFalse("新 run 不应被取消", run2.cancelled.get())
        assertTrue("新 run 应仍 active", guard.isActive(run2))
    }

    private fun stubProcess(): StubProcess = StubProcess()

    private class StubProcess : Process() {
        val destroyed = java.util.concurrent.atomic.AtomicBoolean(false)
        override fun getOutputStream(): java.io.OutputStream = java.io.OutputStream.nullOutputStream()
        override fun getInputStream(): java.io.InputStream = java.io.InputStream.nullInputStream()
        override fun getErrorStream(): java.io.InputStream = java.io.InputStream.nullInputStream()
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() { destroyed.set(true) }
        override fun destroyForcibly(): Process { destroyed.set(true); return this }
        override fun toHandle(): ProcessHandle = throw UnsupportedOperationException()
        override fun descendants(): java.util.stream.Stream<ProcessHandle> = java.util.stream.Stream.empty()
    }

    @Test
    fun `onFinished 清理 activeRun`() {
        val guard = PeerSessionGuard()
        val run = guard.start()
        guard.onFinished(run)
        assertFalse(guard.isActive(run))
    }

    @Test
    fun `取消后可以重新启动`() {
        val guard = PeerSessionGuard()
        val run1 = guard.start()
        guard.cancel()
        assertTrue("取消应标记 cancelled", run1.cancelled.get())

        val run2 = guard.start()
        assertNotNull("取消后应能重新启动", run2)
    }

    @Test
    fun `isReviewing 跟随 reviewing 标记`() {
        val guard = PeerSessionGuard()
        assertFalse(guard.isReviewing)

        val run = guard.start()
        assertFalse(guard.isReviewing)

        run.reviewing.set(true)
        assertTrue(guard.isReviewing)

        guard.onFinished(run)
        assertFalse(guard.isReviewing)
    }

    @Test
    fun `连续多次同代 tryStart 只有最后一个 active`() {
        val guard = PeerSessionGuard()
        val run1 = guard.start(1)
        val run2 = guard.start(1)
        val run3 = guard.start(1)

        assertTrue("run1 应被取消", run1.cancelled.get())
        assertTrue("run2 应被取消", run2.cancelled.get())
        assertFalse("run3 不应被取消", run3.cancelled.get())
        assertTrue("只有 run3 应 active", guard.isActive(run3))
    }

    @Test
    fun `并发-两个同代 tryStart 各拿到不同 run 且只有一个 active`() {
        val guard = PeerSessionGuard()
        val run0 = guard.start(1)

        val go = CountDownLatch(1)
        val result1 = AtomicReference<StartResult?>()
        val result2 = AtomicReference<StartResult?>()

        val t1 = Thread {
            go.await()
            result1.set(guard.tryStart(1, currentGeneration = { 1L }))
        }
        val t2 = Thread {
            go.await()
            result2.set(guard.tryStart(1, currentGeneration = { 1L }))
        }

        t1.start()
        t2.start()
        go.countDown()

        t1.join(5000)
        t2.join(5000)

        val r1 = result1.get()!!.run
        val r2 = result2.get()!!.run
        assertNotSame(r1, r2)
        assertTrue("原始 run 应被取消", run0.cancelled.get())
        assertTrue(
            "最终只有一个 run active",
            guard.isActive(r1) xor guard.isActive(r2),
        )
    }

    @Test
    fun `并发-取消与 tryStart 不冲突`() {
        for (i in 0 until 100) {
            val guard = PeerSessionGuard()
            val run = guard.start(1)

            val go = CountDownLatch(1)
            val newResult = AtomicReference<StartResult?>()

            val starter = Thread {
                go.await()
                newResult.set(guard.tryStart(1, currentGeneration = { 1L }))
            }
            val canceller = Thread {
                go.await()
                guard.cancel()
            }

            starter.start()
            canceller.start()
            go.countDown()

            starter.join(5000)
            canceller.join(5000)

            assertTrue("原始 run 应被取消", run.cancelled.get())
            val nr = newResult.get()?.run
            if (nr != null && guard.isActive(nr)) {
                assertFalse("active 的 run 不应被 cancel 标记", nr.cancelled.get())
            }
        }
    }
}
