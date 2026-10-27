package com.github.izerui.imux.terminal

import com.github.izerui.imux.model.AgentType
import com.intellij.ui.RowIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhoneTabIconTest {
    @Test
    fun `连着手机时多一格手机标记，且实例被缓存`() {
        val plain = terminalTabIcon(AgentType.CODEX, running = false, unread = true, phoneConnected = false)
        val phone = terminalTabIcon(AgentType.CODEX, running = false, unread = true, phoneConnected = true)
        assertNotSame(plain, phone)
        assertSame(phone, terminalTabIcon(AgentType.CODEX, running = false, unread = true, phoneConnected = true))
        assertTrue(phone is RowIcon)
        assertEquals(3, (phone as RowIcon).iconCount)
    }

    @Test
    fun `不连手机时与原来完全相同`() {
        assertSame(
            terminalTabIcon(AgentType.CLAUDE, running = false, unread = false),
            terminalTabIcon(AgentType.CLAUDE, running = false, unread = false, phoneConnected = false),
        )
    }

    @Test
    fun `手机图标提供深浅两份且形状一致`() {
        val light = File("src/main/resources/icons/phone.svg").readText()
        val dark = File("src/main/resources/icons/phone_dark.svg").readText()
        assertTrue(light.contains("#6C707E") && !light.contains("#CED0D6"))
        assertTrue(dark.contains("#CED0D6") && !dark.contains("#6C707E"))
        assertEquals(light.replace("#6C707E", "C"), dark.replace("#CED0D6", "C"))
    }
}
