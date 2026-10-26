package com.github.izerui.imux.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TailStartPolicyTest {
    private val now = Instant.parse("2026-10-26T12:00:00Z")
    private val service = TestableTerminalBridgeService()

    @Test
    fun `没有迁移记录时从末尾开始`() {
        assertFalse(service.readFromStart(now, migratedAt = null))
    }

    @Test
    fun `迁移后新建的会话从头读取`() {
        // 会话在迁移之后创建
        assertTrue(service.readFromStart(createdAt = now.plusSeconds(1), migratedAt = now))
    }

    @Test
    fun `迁移前 30 秒内创建的会话从头读取`() {
        // 会话比迁移早 30 秒，在 60 秒容差内
        assertTrue(service.readFromStart(createdAt = now.minusSeconds(30), migratedAt = now))
    }

    @Test
    fun `迁移前 10 分钟创建的旧会话从末尾开始`() {
        // resume 回旧会话，不应倾倒历史
        assertFalse(service.readFromStart(createdAt = now.minusSeconds(600), migratedAt = now))
    }

    /**
     * readFromStart 是 internal 的纯函数，这里直接构造一个最小实例来调用。
     * 由于 TerminalBridgeService 需要 Project 和 CoroutineScope 构造参数，
     * 而本项目没有平台 test-framework，用反射跳过构造函数创建实例。
     */
    private class TestableTerminalBridgeService {
        private val instance: TerminalBridgeService

        init {
            val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
            unsafe.isAccessible = true
            val u = unsafe.get(null) as sun.misc.Unsafe
            @Suppress("UNCHECKED_CAST")
            instance = u.allocateInstance(TerminalBridgeService::class.java) as TerminalBridgeService
        }

        fun readFromStart(createdAt: Instant, migratedAt: Instant?): Boolean =
            instance.readFromStart(createdAt, migratedAt)
    }
}
