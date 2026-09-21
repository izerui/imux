package com.github.izerui.imux.peer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class PeerSessionGuardTest {
    @Test
    fun `首次 tryStart 返回新运行`() {
        val guard = PeerSessionGuard()
        val run = guard.tryStart()
        assertNotNull(run)
    }

    @Test
    fun `运行期间 tryStart 返回 null 并标记 pending`() {
        val guard = PeerSessionGuard()
        val run1 = guard.tryStart()
        assertNotNull(run1)

        val run2 = guard.tryStart()
        assertNull("运行中不应创建新 run", run2)
    }

    @Test
    fun `运行期间多次事件只补跑一次`() {
        val guard = PeerSessionGuard()
        val run1 = guard.tryStart()!!

        guard.tryStart()
        guard.tryStart()
        guard.tryStart()

        val rerun = guard.onFinished(run1)
        assertNotNull("应有一次补跑", rerun)

        val rerun2 = guard.onFinished(rerun!!)
        assertNull("不应有第二次补跑", rerun2)
    }

    @Test
    fun `无 pending 时 onFinished 不补跑`() {
        val guard = PeerSessionGuard()
        val run = guard.tryStart()!!
        val rerun = guard.onFinished(run)
        assertNull(rerun)
    }

    @Test
    fun `取消后不补跑`() {
        val guard = PeerSessionGuard()
        val run = guard.tryStart()!!

        guard.tryStart()

        guard.cancel()

        val rerun = guard.onFinished(run)
        assertNull("取消后不应补跑", rerun)
    }

    @Test
    fun `取消后可以重新启动`() {
        val guard = PeerSessionGuard()
        val run1 = guard.tryStart()!!
        guard.cancel()

        val run2 = guard.tryStart()
        assertNotNull("取消后应能重新启动", run2)
    }

    @Test
    fun `isActive 正确反映当前运行`() {
        val guard = PeerSessionGuard()
        val run = guard.tryStart()!!
        assertTrue(guard.isActive(run))

        guard.onFinished(run)
        assertFalse(guard.isActive(run))
    }

    @Test
    fun `isReviewing 跟随 reviewing 标记`() {
        val guard = PeerSessionGuard()
        assertFalse(guard.isReviewing)

        val run = guard.tryStart()!!
        assertFalse(guard.isReviewing)

        run.reviewing.set(true)
        assertTrue(guard.isReviewing)

        guard.onFinished(run)
        assertFalse(guard.isReviewing)
    }

    @Test
    fun `并发-运行结束与新事件交错不丢事件`() {
        val guard = PeerSessionGuard()
        val run1 = guard.tryStart()!!

        val beforeFinish = CountDownLatch(1)
        val afterPending = CountDownLatch(1)
        val rerunResult = AtomicReference<PeerRun?>()
        val eventResult = AtomicReference<PeerRun?>()

        val finisher = Thread {
            beforeFinish.await()
            rerunResult.set(guard.onFinished(run1))
        }
        val eventer = Thread {
            beforeFinish.await()
            eventResult.set(guard.tryStart())
            afterPending.countDown()
        }

        finisher.start()
        eventer.start()
        beforeFinish.countDown()

        finisher.join(5000)
        eventer.join(5000)

        val rerun = rerunResult.get()
        val directStart = eventResult.get()
        assertTrue(
            "补跑或直接启动至少有一个成功",
            rerun != null || directStart != null,
        )
    }

    @Test
    fun `并发-取消与补跑不冲突`() {
        val guard = PeerSessionGuard()
        val run1 = guard.tryStart()!!
        guard.tryStart()

        val go = CountDownLatch(1)
        val rerunResult = AtomicReference<PeerRun?>()
        val cancelled = AtomicBoolean(false)

        val finisher = Thread {
            go.await()
            rerunResult.set(guard.onFinished(run1))
        }
        val canceller = Thread {
            go.await()
            guard.cancel()
            cancelled.set(true)
        }

        finisher.start()
        canceller.start()
        go.countDown()

        finisher.join(5000)
        canceller.join(5000)

        val rerun = rerunResult.get()
        if (rerun != null) {
            assertTrue("补跑的 run 应该已被 cancel 标记", rerun.cancelled.get())
        }
    }

    @Test
    fun `并发-解绑等效取消后不补跑`() {
        for (i in 0 until 100) {
            val guard = PeerSessionGuard()
            val run = guard.tryStart()!!
            guard.tryStart()

            val go = CountDownLatch(1)
            val rerunResult = AtomicReference<PeerRun?>()

            val finisher = Thread {
                go.await()
                rerunResult.set(guard.onFinished(run))
            }
            val unbinder = Thread {
                go.await()
                guard.cancel()
            }

            finisher.start()
            unbinder.start()
            go.countDown()

            finisher.join(5000)
            unbinder.join(5000)

            val rerun = rerunResult.get()
            if (rerun != null) {
                assertTrue("如果有补跑 run，它应被 cancel 清理", rerun.cancelled.get())
            }
        }
    }
}
