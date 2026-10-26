package com.github.izerui.imux.bridge.terminal

import org.jetbrains.plugins.terminal.view.TerminalSendTextBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class TerminalSubmitTest {
    private fun builder(
        calls: MutableList<String>,
        accept: Boolean,
    ): TerminalSendTextBuilder {
        lateinit var proxy: TerminalSendTextBuilder
        proxy =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(TerminalSendTextBuilder::class.java)) { _, method, args ->
                calls += method.name + (args?.joinToString(prefix = "(", postfix = ")") ?: "")
                when (method.name) {
                    "trySend" -> accept
                    "send" -> null
                    else -> proxy
                }
            } as TerminalSendTextBuilder
        return proxy
    }

    @Test
    fun `一次发送正文并提交，要求括号粘贴模式`() {
        val calls = mutableListOf<String>()
        assertTrue(submitText(builder(calls, accept = true), "修一下"))
        assertEquals(listOf("requireBracketedPasteMode", "shouldExecute", "trySend(修一下)"), calls)
    }

    @Test
    fun `终端未开启括号粘贴时如实返回未送出`() {
        assertFalse(submitText(builder(mutableListOf(), accept = false), "x"))
    }
}
