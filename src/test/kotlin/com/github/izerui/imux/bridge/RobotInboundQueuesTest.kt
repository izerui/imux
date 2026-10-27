package com.github.izerui.imux.bridge

import com.github.izerui.imux.bridge.core.Arrival
import com.github.izerui.imux.bridge.core.InboundMessage
import com.github.izerui.imux.bridge.core.RobotId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Collections

class RobotInboundQueuesTest {
    private val anyArrival = Arrival(bindingEpoch = 1, switchGeneration = 1)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private fun message(
        robot: String,
        id: String,
    ) = InboundMessage(RobotId(robot), id, "u", "c", id)

    @Test
    fun `一个机器人的慢消息不耽误其它机器人，同一机器人保持到达顺序`() =
        runBlocking {
            val releaseA = CompletableDeferred<Unit>()
            val doneB = CompletableDeferred<Unit>()
            val doneA2 = CompletableDeferred<Unit>()
            val handled = Collections.synchronizedList(mutableListOf<String>())
            val queues =
                RobotInboundQueues(scope, onFailure = {}) { message, _ ->
                    if (message.externalMessageId == "a1") releaseA.await()
                    handled += message.externalMessageId
                    if (message.externalMessageId == "b1") doneB.complete(Unit)
                    if (message.externalMessageId == "a2") doneA2.complete(Unit)
                }
            queues.offer(arrival = anyArrival, message = message("A", "a1"))
            queues.offer(arrival = anyArrival, message = message("A", "a2"))
            queues.offer(arrival = anyArrival, message = message("B", "b1"))
            // A 的第一条还卡着，B 的消息已经处理完
            withTimeout(5_000) { doneB.await() }
            assertEquals(listOf("b1"), handled.toList())
            releaseA.complete(Unit)
            withTimeout(5_000) { doneA2.await() }
            assertEquals(listOf("b1", "a1", "a2"), handled.toList())
        }

    @Test
    fun `处理失败只记下，不中断该机器人后续消息`() =
        runBlocking {
            val failures = Collections.synchronizedList(mutableListOf<Throwable>())
            val done = CompletableDeferred<Unit>()
            val queues =
                RobotInboundQueues(scope, onFailure = { failures += it }) { message, _ ->
                    if (message.externalMessageId == "1") error("boom")
                    done.complete(Unit)
                }
            queues.offer(arrival = anyArrival, message = message("A", "1"))
            queues.offer(arrival = anyArrival, message = message("A", "2"))
            withTimeout(5_000) { done.await() }
            assertEquals(1, failures.size)
        }
}
