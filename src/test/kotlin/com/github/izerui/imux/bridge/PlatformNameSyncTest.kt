package com.github.izerui.imux.bridge

import com.github.izerui.imux.bridge.core.RobotId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class PlatformNameSyncTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val names = ConcurrentHashMap<RobotId, String>()
    private val writes = CopyOnWriteArrayList<Pair<RobotId, String>>()
    private val changes = AtomicInteger()

    /** 写入与校验时是否都持有锁；任何一次不持有就记下。 */
    private val unlocked = CopyOnWriteArrayList<String>()

    private val sync =
        PlatformNameSync(
            scope,
            lock,
            write = { id, name ->
                if (!Thread.holdsLock(lock)) unlocked += "write"
                writes += id to name
                names.put(id, name) != name
            },
            changed = { changes.incrementAndGet() },
        )

    private val robot = RobotId("r")

    private fun current(): () -> Boolean = {
        if (!Thread.holdsLock(lock)) unlocked += "isCurrent"
        true
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `上线后写入平台名称并通知界面`() =
        runBlocking {
            withTimeout(2_000) { sync.sync(robot, current()) { "我的助手" }.join() }
            assertEquals("我的助手", names[robot])
            assertEquals(1, changes.get())
            assertTrue(unlocked.toString(), unlocked.isEmpty())
        }

    @Test
    fun `同一机器人并发请求时，先发的请求后返回会被丢弃`() =
        runBlocking {
            val older = CompletableDeferred<String?>()
            val newer = CompletableDeferred<String?>()
            val first = sync.sync(robot, current()) { older.await() }
            val second = sync.sync(robot, current()) { newer.await() }
            withTimeout(2_000) {
                newer.complete("新名")
                second.join()
                older.complete("旧名")
                first.join()
            }
            assertEquals("新名", names[robot])
            assertEquals(listOf(robot to "新名"), writes)
            assertEquals(1, changes.get())
        }

    @Test
    fun `最新请求取不到名称时，较早请求的结果也不采用`() =
        runBlocking {
            val older = CompletableDeferred<String?>()
            val first = sync.sync(robot, current()) { older.await() }
            withTimeout(2_000) {
                sync.sync(robot, current()) { null }.join()
                older.complete("旧名")
                first.join()
            }
            assertTrue(writes.isEmpty())
            assertEquals(0, changes.get())
        }

    @Test
    fun `不同机器人的请求互不作废`() =
        runBlocking {
            val other = RobotId("o")
            val gate = CompletableDeferred<String?>()
            val first = sync.sync(robot, current()) { gate.await() }
            withTimeout(2_000) {
                sync.sync(other, current()) { "另一个" }.join()
                gate.complete("我的助手")
                first.join()
            }
            assertEquals("我的助手", names[robot])
            assertEquals("另一个", names[other])
        }

    @Test
    fun `请求返回时连接已被替换就不写入`() =
        runBlocking {
            var replaced = false
            val gate = CompletableDeferred<String?>()
            val job =
                sync.sync(robot, {
                    if (!Thread.holdsLock(lock)) unlocked += "isCurrent"
                    !replaced
                }) { gate.await() }
            withTimeout(2_000) {
                // 连接的替换与校验共用同一把锁：持锁替换后再放行请求
                synchronized(lock) { replaced = true }
                gate.complete("旧连接的名字")
                job.join()
            }
            assertTrue(writes.isEmpty())
            assertEquals(0, changes.get())
            assertTrue(unlocked.toString(), unlocked.isEmpty())
        }

    @Test
    fun `取不到名称或名称没变时不通知界面`() =
        runBlocking {
            names[robot] = "我的助手"
            withTimeout(2_000) {
                sync.sync(robot, current()) { null }.join()
                sync.sync(robot, current()) { "我的助手" }.join()
            }
            assertEquals(0, changes.get())
        }

    @Test
    fun `校验与写入在锁内进行，持锁期间请求结果只能等待`() =
        runBlocking {
            val job = sync.sync(robot, current()) { "我的助手" }
            synchronized(lock) {
                Thread.sleep(100)
                // 锁被占着，请求虽已返回也写不进去
                assertTrue(writes.isEmpty())
            }
            withTimeout(2_000) { job.join() }
            assertEquals("我的助手", names[robot])
            assertTrue(unlocked.toString(), unlocked.isEmpty())
        }
}
