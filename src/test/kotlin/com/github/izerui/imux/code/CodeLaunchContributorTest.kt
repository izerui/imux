package com.github.izerui.imux.code

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeLaunchContributorTest {
    @Test
    fun `凭证刚登记完启动就被取消时，登记的凭证当场撤销`() = runBlocking {
        val released = mutableListOf<String>()
        val job = CompletableDeferred<Job>()
        val task = async {
            acquireOnIo({
                // 模拟「登记完成、结果还没交回」这一刻标签被关
                runBlocking { job.await() }.cancel()
                "token"
            }) { released += it }
        }
        job.complete(task)
        val outcome = runCatching { task.await() }
        assertTrue(outcome.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals(listOf("token"), released)
    }

    @Test
    fun `正常取到时交回结果，不在这里释放`() = runBlocking {
        val released = mutableListOf<String>()
        assertEquals("token", acquireOnIo({ "token" }) { released += it })
        assertEquals(emptyList<String>(), released)
    }

    @Test
    fun `没取到时返回空，也没有要释放的`() = runBlocking {
        val released = mutableListOf<String>()
        assertEquals(null, acquireOnIo<String>({ null }) { released += it })
        assertEquals(emptyList<String>(), released)
    }
}
