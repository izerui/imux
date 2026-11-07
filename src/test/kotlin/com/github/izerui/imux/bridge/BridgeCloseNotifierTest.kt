package com.github.izerui.imux.bridge

import com.github.izerui.imux.bridge.core.BindResult
import com.github.izerui.imux.bridge.core.BindingRegistry
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.core.SessionTarget
import com.github.izerui.imux.model.AgentType
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class BridgeCloseNotifierTest {
    private fun notice(id: String): ClosingBinding {
        val registry = BindingRegistry()
        val binding = (registry.bind(RobotId(id), SessionTarget("/p", id, AgentType.CODEX, id)) as BindResult.Bound).binding
        return ClosingBinding(binding, id, "project · Codex · $id")
    }

    @Test(timeout = 5_000)
    fun `关闭回调返回前发完提示且后续关闭信号不重复发送`() {
        val notice = notice("r1")
        val sent = ConcurrentLinkedQueue<ClosingBinding>()
        val notifier = BridgeCloseNotifier(
            send = {
                delay(10)
                sent += it
            },
            onFailure = { throw AssertionError(it) },
            onTimeout = { throw AssertionError("不应超时") },
        )
        notifier.notifyOnce(listOf(notice))
        assertEquals(listOf(notice), sent.toList())
        notifier.notifyOnce(listOf(notice))
        assertEquals(listOf(notice), sent.toList())
    }

    @Test(timeout = 5_000)
    fun `一条连接挂起不挡住另一条通知且到期取消发送并允许关闭`() {
        val slow = notice("slow")
        val fast = notice("fast")
        val sent = ConcurrentLinkedQueue<ClosingBinding>()
        val cancelled = AtomicBoolean()
        var timeouts = 0
        val notifier = BridgeCloseNotifier(
            send = {
                if (it == slow) {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.set(true)
                    }
                }
                sent += it
            },
            onFailure = { throw AssertionError(it) },
            onTimeout = { timeouts++ },
            timeoutMs = 500,
        )
        notifier.notifyOnce(listOf(slow, fast))
        assertEquals(listOf(fast), sent.toList())
        assertTrue("超时的发送必须被取消", cancelled.get())
        assertEquals(1, timeouts)
        notifier.notifyOnce(listOf(slow, fast))
        assertEquals("超时后不重新等待或重复发送", listOf(fast), sent.toList())
        assertEquals(1, timeouts)
    }

    @Test(timeout = 5_000)
    fun `发送异常不阻止其他连接通知且不抛给关闭流程`() {
        val failed = notice("failed")
        val healthy = notice("healthy")
        val sent = ConcurrentLinkedQueue<ClosingBinding>()
        val errors = ConcurrentLinkedQueue<Exception>()
        val failure = IllegalStateException("send failed")
        val notifier = BridgeCloseNotifier(
            send = {
                if (it == failed) throw failure
                sent += it
            },
            onFailure = { errors += it },
            onTimeout = { throw AssertionError("不应超时") },
        )
        notifier.notifyOnce(listOf(failed, healthy))
        assertEquals(listOf(healthy), sent.toList())
        assertEquals(listOf(failure), errors.toList())
    }
}
