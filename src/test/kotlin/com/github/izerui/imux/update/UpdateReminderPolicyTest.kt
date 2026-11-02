package com.github.izerui.imux.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateReminderPolicyTest {
    @Test
    fun `远端更新时提醒`() {
        assertTrue(shouldRemind("1.3.3", "1.3.4", null, null))
    }

    @Test
    fun `版本相同或更旧时不提醒`() {
        assertFalse(shouldRemind("1.3.3", "1.3.3", null, null))
        assertFalse(shouldRemind("1.3.3", "1.3.1", null, null))
    }

    @Test
    fun `按数字比较而非字典序`() {
        assertTrue(shouldRemind("1.3.9", "1.3.10", null, null))
    }

    @Test
    fun `没有远端版本时不提醒`() {
        assertFalse(shouldRemind("1.3.3", null, null, null))
    }

    @Test
    fun `已忽略的版本不提醒，更新的版本照常提醒`() {
        assertFalse(shouldRemind("1.3.3", "1.3.4", "1.3.4", null))
        assertTrue(shouldRemind("1.3.3", "1.3.5", "1.3.4", null))
    }

    @Test
    fun `本次运行已提醒过的版本不再提醒`() {
        assertFalse(shouldRemind("1.3.3", "1.3.4", null, "1.3.4"))
        assertTrue(shouldRemind("1.3.3", "1.3.5", null, "1.3.4"))
    }
}
