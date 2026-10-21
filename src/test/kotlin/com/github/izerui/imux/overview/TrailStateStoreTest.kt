package com.github.izerui.imux.overview

import com.github.izerui.imux.agent.AgentProgressEvent
import com.github.izerui.imux.agent.AgentProgressKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TrailStateStoreTest {
    private companion object {
        const val TAB = "tab-1"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cache = mutableMapOf<String, CachedView>()
    private val available = mutableSetOf("s1", "s2")
    private val notified = CopyOnWriteArrayList<String>()

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun result(text: String, stale: Boolean = false) =
        OverviewResult(listOf(OverviewNode(NodeSymbol.MAIN, text)), Instant.now(), stale = stale)

    private fun store(
        generate: (String, (AgentProgressEvent) -> Unit) -> OverviewResult = { _, _ -> result("新") },
        progressText: (AgentProgressEvent) -> String? = { it.subject.ifEmpty { null } },
        loadCached: (String) -> CachedView? = { cache[it] },
        onEdt: (() -> Unit) -> Unit = { it() },
    ) = TrailStateStore(
        scope = scope,
        loadCached = loadCached,
        isAvailable = { it in available },
        generate = generate,
        errorText = { it.message ?: "error" },
        onEdt = onEdt,
        progressText = progressText,
    ).also { s -> s.addListener { notified += it } }

    private fun TrailStateStore.await(key: String) = runBlocking { jobFor(key)?.join() }

    @Test
    fun `初始状态按可用性与缓存派生`() {
        cache["s2"] = CachedView(result("旧"), stale = true)
        val store = store()

        assertEquals(TrailState.Empty, store.state("s1"))
        assertEquals(TrailState.Ready(cache["s2"]!!.result, stale = true), store.state("s2"))
        assertEquals(TrailState.Unavailable, store.state("pending-1"))
    }

    @Test
    fun `生成成功后为 Ready 并通知该会话`() {
        val store = store()
        store.generate("s1", TAB)
        store.await("s1")

        val state = store.state("s1") as TrailState.Ready
        assertEquals("新", state.result.nodes.single().text)
        assertTrue(notified.all { it == "s1" } && notified.isNotEmpty())
    }

    @Test
    fun `生成中重复触发只调用一次`() {
        val calls = AtomicInteger()
        val release = CountDownLatch(1)
        val store = store(generate = { _, _ ->
            calls.incrementAndGet()
            release.await(5, TimeUnit.SECONDS)
            result("新")
        })

        store.generate("s1", TAB)
        store.generate("s1", TAB)
        assertTrue(store.state("s1") is TrailState.Loading)
        release.countDown()
        store.await("s1")

        assertEquals(1, calls.get())
    }

    @Test
    fun `失败时保留之前的结果`() {
        cache["s1"] = CachedView(result("旧"), stale = false)
        val store = store(generate = { _, _ -> throw IllegalStateException("CLI 退出码 1") })

        store.generate("s1", TAB)
        store.await("s1")

        val failed = store.state("s1") as TrailState.Failed
        assertEquals("CLI 退出码 1", failed.message)
        assertEquals("旧", failed.previous!!.nodes.single().text)
    }

    @Test
    fun `无旧结果时失败不带 previous`() {
        val store = store(generate = { _, _ -> throw IllegalStateException("boom") })
        store.generate("s1", TAB)
        store.await("s1")

        assertNull((store.state("s1") as TrailState.Failed).previous)
    }

    @Test
    fun `取消后恢复生成前状态且晚到结果不覆盖`() {
        cache["s2"] = CachedView(result("旧"), stale = false)
        val started = CountDownLatch(1)
        val store = store(generate = { _, _ ->
            started.countDown()
            try {
                Thread.sleep(10_000)
            } catch (_: InterruptedException) {
            }
            result("晚到")
        })

        store.generate("s1", TAB)
        store.generate("s2", TAB)
        started.await(5, TimeUnit.SECONDS)
        store.cancel("s1")
        store.cancel("s2")
        runBlocking { kotlinx.coroutines.delay(200) }

        assertEquals(TrailState.Empty, store.state("s1"))
        assertEquals("旧", (store.state("s2") as TrailState.Ready).result.nodes.single().text)
        assertNull(store.jobFor("s1"))
    }

    @Test
    fun `不同会话互不影响`() {
        val release = CountDownLatch(1)
        val store = store(generate = { key, _ ->
            if (key == "s1") release.await(5, TimeUnit.SECONDS)
            result(key)
        })

        store.generate("s1", TAB)
        store.generate("s2", TAB)
        store.await("s2")

        assertTrue(store.state("s1") is TrailState.Loading)
        assertEquals("s2", (store.state("s2") as TrailState.Ready).result.nodes.single().text)
        release.countDown()
        store.await("s1")
    }

    @Test
    fun `进度事件更新 Loading 文案，无文案的事件保留上一条`() {
        val step = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = store(generate = { _, onProgress ->
            onProgress(AgentProgressEvent(AgentProgressKind.TOOL_STARTED, "读取 a.jsonl"))
            onProgress(AgentProgressEvent(AgentProgressKind.TOOL_FINISHED))
            step.countDown()
            release.await(5, TimeUnit.SECONDS)
            result("新")
        })

        store.generate("s1", TAB)
        step.await(5, TimeUnit.SECONDS)
        assertEquals("读取 a.jsonl", (store.state("s1") as TrailState.Loading).progress)
        release.countDown()
        store.await("s1")
    }

    @Test
    fun `生成后会话有新内容时 Ready 变为过期`() {
        val sessionFile: Path = Files.createTempFile("session", ".jsonl")
        sessionFile.toFile().deleteOnExit()
        Files.writeString(sessionFile, "content")
        val snapshot = Files.size(sessionFile)
        val store = store(
            generate = { _, _ -> result("新") },
            loadCached = { key ->
                if (key != "s1") null
                else CachedView(result("新"), stale = Files.size(sessionFile) != snapshot)
            },
        )

        store.generate("s1", TAB)
        store.await("s1")
        assertEquals(false, (store.state("s1") as TrailState.Ready).stale)

        Files.writeString(sessionFile, "content + more")
        assertEquals(true, (store.state("s1") as TrailState.Ready).stale)
    }

    @Test
    fun `标签页关闭时取消任务并清掉记录`() {
        val release = CountDownLatch(1)
        val store = store(generate = { _, _ ->
            release.await(5, TimeUnit.SECONDS)
            result("新")
        })
        store.generate("s1", TAB)
        val job = store.jobFor("s1")!!
        store.releaseOwner(TAB)
        release.countDown()
        runBlocking { kotlinx.coroutines.delay(200) }

        assertTrue(job.isCancelled)
        assertEquals(TrailState.Empty, store.state("s1"))
    }

    @Test
    fun `标签页关闭时通知仍在监听的面板刷新，不会停在加载中`() {
        val release = CountDownLatch(1)
        val store = store(generate = { _, _ ->
            release.await(5, TimeUnit.SECONDS)
            result("新")
        })
        store.generate("s1", TAB)
        store.generate("s2", TAB)
        notified.clear()

        store.releaseOwner(TAB)
        release.countDown()

        assertTrue("每个被清理的会话都要通知：$notified", notified.containsAll(listOf("s1", "s2")))
        assertEquals("刷新后看到的不再是加载中", TrailState.Empty, store.state("s1"))
        assertEquals(TrailState.Empty, store.state("s2"))
    }

    @Test
    fun `clear 或 new 换 key 后关闭标签页，迁移前仍在运行的任务也被取消`() {
        val release = CountDownLatch(1)
        val store = store(generate = { _, _ ->
            release.await(5, TimeUnit.SECONDS)
            result("新")
        })
        // 旧 key 上开始生成；随后 /clear 让标签页换到 s2，又在新会话上生成一次
        store.generate("s1", TAB)
        val migrated = store.jobFor("s1")!!
        store.generate("s2", TAB)
        val current = store.jobFor("s2")!!

        store.releaseOwner(TAB)
        release.countDown()
        runBlocking { kotlinx.coroutines.delay(200) }

        assertTrue("迁移前的任务必须一起取消", migrated.isCancelled)
        assertTrue(current.isCancelled)
        assertNull(store.jobFor("s1"))
        assertNull(store.jobFor("s2"))
        assertEquals(TrailState.Empty, store.state("s1"))
    }

    @Test
    fun `换 key 本身不中断生成，迁移前的任务照常完成`() {
        val release = CountDownLatch(1)
        val store = store(generate = { key, _ ->
            if (key == "s1") release.await(5, TimeUnit.SECONDS)
            result(key)
        })
        store.generate("s1", TAB)
        store.generate("s2", TAB)
        store.await("s2")
        release.countDown()
        store.await("s1")

        assertEquals("s1", (store.state("s1") as TrailState.Ready).result.nodes.single().text)
        assertEquals("s2", (store.state("s2") as TrailState.Ready).result.nodes.single().text)
    }

    @Test
    fun `关闭一个标签页不影响其他标签页的任务`() {
        val release = CountDownLatch(1)
        val store = store(generate = { _, _ ->
            release.await(5, TimeUnit.SECONDS)
            result("新")
        })
        store.generate("s1", TAB)
        store.generate("s2", "tab-2")

        store.releaseOwner(TAB)
        assertTrue(store.state("s2") is TrailState.Loading)
        release.countDown()
        store.await("s2")

        assertTrue(store.state("s2") is TrailState.Ready)
    }

    /** 回调先排队、由测试手动执行，模拟 EDT 尚未处理 invokeLater 的那段时间。 */
    private class QueuedEdt {
        private val queue = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
        val onEdt: (() -> Unit) -> Unit = { queue += it }

        fun drain() {
            while (true) (queue.poll() ?: return)()
        }
    }

    @Test
    fun `后台已结束但回写未执行时再次生成不会另起任务`() {
        val edt = QueuedEdt()
        val calls = AtomicInteger()
        val store = store(generate = { _, _ -> calls.incrementAndGet(); result("新") }, onEdt = edt.onEdt)

        store.generate("s1", TAB)
        store.await("s1")
        assertTrue("回写还没执行，仍是 Loading", store.state("s1") is TrailState.Loading)

        store.generate("s1", TAB)
        edt.drain()

        assertEquals("同一会话只能有一次生成", 1, calls.get())
        assertEquals("新", (store.state("s1") as TrailState.Ready).result.nodes.single().text)
        assertNull(store.jobFor("s1"))
    }

    @Test
    fun `后台已结束但回写未执行时再次生成再取消，恢复到生成前状态而不是一直转圈`() {
        val edt = QueuedEdt()
        val store = store(generate = { _, _ -> result("新") }, onEdt = edt.onEdt)

        store.generate("s1", TAB)
        store.await("s1")
        store.generate("s1", TAB)
        store.cancel("s1")
        edt.drain()

        assertEquals("取消后回到第一次生成前的状态，迟到的回写被丢弃", TrailState.Empty, store.state("s1"))
        assertNull(store.jobFor("s1"))

        store.generate("s1", TAB)
        store.await("s1")
        edt.drain()
        assertTrue("取消后可以重新生成", store.state("s1") is TrailState.Ready)
    }

    @Test
    fun `非用户取消导致的 CancellationException 不会让状态停在 Loading`() {
        cache["s1"] = CachedView(result("旧"), stale = false)
        val edt = QueuedEdt()
        val store = store(
            generate = { _, _ -> throw java.util.concurrent.CancellationException("被外部中止") },
            onEdt = edt.onEdt,
        )

        store.generate("s1", TAB)
        store.await("s1")
        edt.drain()

        assertEquals("旧", (store.state("s1") as TrailState.Ready).result.nodes.single().text)
        assertNull("任务登记应被清掉，之后还能再生成", store.jobFor("s1"))
    }

    @Test
    fun `不可用会话不启动生成`() {
        val calls = AtomicInteger()
        val store = store(generate = { _, _ -> calls.incrementAndGet(); result("新") })
        store.generate("pending-1", TAB)

        assertNull(store.jobFor("pending-1"))
        assertEquals(0, calls.get())
    }
}
