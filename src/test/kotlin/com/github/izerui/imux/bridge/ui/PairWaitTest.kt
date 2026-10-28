package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.bridge.channel.ChannelKind
import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.robot.RobotRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

class PairWaitTest {
    private val now = 1_000_000L
    private val robot = RobotRecord(RobotId("r1"), "bot", ChannelKind.WECOM, pairCode = "123456", pairCodeExpiresAtMillis = now + 60_000)

    @Test
    fun `机器人被删除时结束等待`() = assertEquals(PairWait.GONE, pairWait(null, now, ConnectionStatus.Online))

    @Test
    fun `配对码有效、连接正常或还在连接时继续等待`() {
        assertEquals(PairWait.WAITING, pairWait(robot, now, ConnectionStatus.Online))
        assertEquals(PairWait.WAITING, pairWait(robot, now, ConnectionStatus.Connecting))
        assertEquals(PairWait.WAITING, pairWait(robot, now, null))
    }

    @Test
    fun `认主后完成`() = assertEquals(PairWait.PAIRED, pairWait(robot.copy(ownerSenderId = "u", ownerChatId = "u"), now, ConnectionStatus.Online))

    @Test
    fun `猜错次数过多配对码被作废时不再显示等待`() = assertEquals(PairWait.INVALIDATED, pairWait(robot.copy(pairCode = null), now, ConnectionStatus.Online))

    @Test
    fun `配对码过期时不再显示等待`() = assertEquals(PairWait.EXPIRED, pairWait(robot, now + 60_001, ConnectionStatus.Online))

    @Test
    fun `连接失败、被接管或已停止时收不到配对码`() {
        assertEquals(PairWait.DISCONNECTED, pairWait(robot, now, ConnectionStatus.Failed("40001 invalid secret")))
        assertEquals(PairWait.DISCONNECTED, pairWait(robot, now, ConnectionStatus.Kicked))
        assertEquals(PairWait.DISCONNECTED, pairWait(robot, now, ConnectionStatus.Stopped))
    }

    @Test
    fun `判断有效后立刻到期且没有任何事件：仍按到期检查改显示已过期，不会一直显示有效配对码`() =
        runBlocking {
            val expiresAt = robot.pairCodeExpiresAtMillis
            // 第一次判断恰好在到期时刻（仍有效），之后时间已越过到期点；版本号始终不变
            val times = ArrayDeque(listOf(expiresAt))
            val shown = CopyOnWriteArrayList<PairWait>()
            val job =
                launch(Dispatchers.Default) {
                    awaitPairing(MutableStateFlow(0L), { times.removeFirstOrNull() ?: (expiresAt + 1) }, { robot }, { ConnectionStatus.Online }) { wait, _ ->
                        shown += wait
                    }
                }
            withTimeout(2_000) { while (PairWait.EXPIRED !in shown) delay(5) }
            job.cancel()
            assertEquals(listOf(PairWait.WAITING, PairWait.EXPIRED), shown.toList())
        }

    @Test
    fun `认主、机器人被删除时结束；状态变化推进版本后重新判断`() =
        runBlocking {
            val revision = MutableStateFlow(0L)
            var record: RobotRecord? = robot
            val shown = CopyOnWriteArrayList<PairWait>()
            val result =
                async(Dispatchers.Default) {
                    awaitPairing(revision, { now }, { record }, { ConnectionStatus.Online }) { wait, _ -> shown += wait }
                }
            withTimeout(2_000) { while (shown.isEmpty()) delay(5) }
            record = robot.copy(ownerSenderId = "u", ownerChatId = "u")
            revision.value = 1
            assertEquals(PairWait.PAIRED, withTimeout(2_000) { result.await() })

            record = null
            assertEquals(PairWait.GONE, awaitPairing(revision, { now }, { record }, { null }) { _, _ -> })
        }

    @Test
    fun `配对码失效后不安排到期检查，只等事件（例如重新生成）`() =
        runBlocking {
            val revision = MutableStateFlow(0L)
            var record = robot.copy(pairCode = null)
            val shown = CopyOnWriteArrayList<PairWait>()
            val job =
                launch(Dispatchers.Default) {
                    awaitPairing(revision, { now }, { record }, { ConnectionStatus.Online }) { wait, _ -> shown += wait }
                }
            withTimeout(2_000) { while (shown.isEmpty()) delay(5) }
            delay(50)
            assertEquals("没有事件就不重复判断", listOf(PairWait.INVALIDATED), shown.toList())
            record = robot
            revision.value = 1
            withTimeout(2_000) { while (PairWait.WAITING !in shown) delay(5) }
            job.cancel()
        }

    @Test
    fun `续码事件先处理、页面随后改回等待、排队的状态刷新再执行：最终显示连接异常`() =
        runBlocking {
            // 手动执行的“EDT”：后台的判断结果排进来，按顺序执行
            val edt = ConcurrentLinkedQueue<() -> Unit>()
            val rendered = mutableListOf<Pair<PairWait, ConnectionStatus?>>()
            val view = PairStateView { wait, status -> rendered += wait to status }
            val failed = ConnectionStatus.NoCredentials
            view.show(PairWait.DISCONNECTED, failed)

            // 续码后配对码有效，但连接仍是失败：后台判断出 DISCONNECTED，投递到 EDT 排队
            val job =
                launch(Dispatchers.Default) {
                    awaitPairing(MutableStateFlow(1L), { now }, { robot }, { failed }) { wait, status -> edt += { view.offer(wait, status) } }
                }
            withTimeout(2_000) { while (edt.isEmpty()) delay(5) }
            // EDT 上 renew() 接着把页面改回“等待”（showPair），然后才轮到排队的刷新
            view.show(PairWait.WAITING, null)
            while (true) (edt.poll() ?: break).invoke()
            job.cancel()

            assertEquals(PairWait.DISCONNECTED to failed, rendered.last())
        }

    @Test
    fun `显示状态相同不重复刷新；只有失败原因变了也刷新`() {
        val rendered = mutableListOf<Pair<PairWait, ConnectionStatus?>>()
        val view = PairStateView { wait, status -> rendered += wait to status }
        view.offer(PairWait.DISCONNECTED, ConnectionStatus.Failed("a"))
        view.offer(PairWait.DISCONNECTED, ConnectionStatus.Failed("a"))
        view.offer(PairWait.DISCONNECTED, ConnectionStatus.Failed("b"))
        assertEquals(
            listOf(PairWait.DISCONNECTED to ConnectionStatus.Failed("a"), PairWait.DISCONNECTED to ConnectionStatus.Failed("b")),
            rendered,
        )
    }
}
