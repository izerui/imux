package com.github.izerui.imux.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TailStartPolicyTest {
    private val now = Instant.parse("2026-10-26T12:00:00Z")

    @Test
    fun `没有迁移记录时从末尾开始`() {
        assertFalse(readFromStart(now, migratedAt = null))
    }

    @Test
    fun `迁移后新建的会话从头读取`() {
        // 会话在迁移之后创建
        assertTrue(readFromStart(createdAt = now.plusSeconds(1), migratedAt = now))
    }

    @Test
    fun `迁移前 30 秒内创建的会话从头读取`() {
        // 会话比迁移早 30 秒，在 60 秒容差内
        assertTrue(readFromStart(createdAt = now.minusSeconds(30), migratedAt = now))
    }

    @Test
    fun `迁移前恰好 60 秒创建的会话从头读取`() {
        // 边界：createdAt == migratedAt - 60s，仍视为新会话
        assertTrue(readFromStart(createdAt = now.minusSeconds(60), migratedAt = now))
    }

    @Test
    fun `迁移前 10 分钟创建的旧会话从末尾开始`() {
        // resume 回旧会话，不应倾倒历史
        assertFalse(readFromStart(createdAt = now.minusSeconds(600), migratedAt = now))
    }

    @Test
    fun `同一绑定代次与会话时复用尾随器`() {
        assertTrue(reuseTail(entryEpoch = 3, entrySessionId = "s1", bindingEpoch = 3, bindingSessionId = "s1"))
    }

    @Test
    fun `换绑后代次不同时重建尾随器，不倒灌期间的历史`() {
        assertFalse(reuseTail(entryEpoch = 3, entrySessionId = "s1", bindingEpoch = 5, bindingSessionId = "s1"))
    }

    @Test
    fun `会话迁移后重建尾随器，拒绝迁移前建立的旧尾随器`() {
        assertFalse(reuseTail(entryEpoch = 3, entrySessionId = "s1", bindingEpoch = 3, bindingSessionId = "s2"))
    }

    @Test
    fun `绑定尚无会话时不复用`() {
        assertFalse(reuseTail(entryEpoch = 3, entrySessionId = "s1", bindingEpoch = 3, bindingSessionId = null))
    }
}
