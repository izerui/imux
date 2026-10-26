package com.github.izerui.imux.bridge.core

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BindingRegistryTest {
    private val a = RobotId("a")
    private val b = RobotId("b")

    private fun target(tab: String) = SessionTarget("/p", tab, AgentType.CLAUDE, "s-$tab")

    @Test
    fun `一个终端只能被一个机器人绑定`() {
        val registry = BindingRegistry()
        registry.bind(a, target("t1"))
        val result = registry.bind(b, target("t1"))
        assertTrue(result is BindResult.TargetTaken)
        assertEquals(a, (result as BindResult.TargetTaken).by.robotId)
        assertNull(registry.bindingOf(b))
    }

    @Test
    fun `换绑返回旧绑定并递增 epoch`() {
        val registry = BindingRegistry()
        val first = (registry.bind(a, target("t1")) as BindResult.Bound).binding
        val second = registry.bind(a, target("t2")) as BindResult.Bound
        assertEquals(first, second.replaced)
        assertTrue(second.binding.epoch > first.epoch)
        assertFalse(registry.isCurrent(a, first.epoch))
        assertTrue(registry.isCurrent(a, second.binding.epoch))
        assertNull(registry.bindingForTab("t1"))
    }

    @Test
    fun `会话迁移与重新挂接保留 epoch`() {
        val registry = BindingRegistry()
        val bound = (registry.bind(a, target("t1")) as BindResult.Bound).binding
        val migrated = registry.migrateSession("t1", "s-new")!!
        assertEquals("s-new", migrated.target.sessionId)
        assertEquals(bound.epoch, migrated.epoch)
        val reattached = registry.reattach(a, "t9")!!
        assertEquals("t9", reattached.target.tabId)
        assertTrue(registry.isCurrent(a, bound.epoch))
    }

    @Test
    fun `解绑后不再是当前绑定`() {
        val registry = BindingRegistry()
        val bound = (registry.bind(a, target("t1")) as BindResult.Bound).binding
        assertEquals(bound, registry.unbind(a))
        assertFalse(registry.isCurrent(a, bound.epoch))
        assertTrue(registry.snapshot().isEmpty())
    }

    @Test
    fun `重新挂接到已被其他机器人持有的标签返回 null 且双方绑定不变`() {
        val registry = BindingRegistry()
        registry.bind(a, target("t1"))
        registry.bind(b, target("t2"))
        assertNull(registry.reattach(a, "t2"))
        assertEquals("t1", registry.bindingOf(a)!!.target.tabId)
        assertEquals(b, registry.bindingForTab("t2")!!.robotId)
    }

    @Test
    fun `未知机器人重新挂接和未知标签会话迁移返回 null`() {
        val registry = BindingRegistry()
        assertNull(registry.reattach(a, "t1"))
        assertNull(registry.migrateSession("t1", "s-new"))
    }
}
