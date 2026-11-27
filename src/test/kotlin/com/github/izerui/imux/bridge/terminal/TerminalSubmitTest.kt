package com.github.izerui.imux.bridge.terminal

import com.github.izerui.imux.model.AgentType
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

    @Test
    fun `停止的键原样写进终端，不用括号粘贴也不回车`() {
        val calls = mutableListOf<String>()
        sendKey(builder(calls, accept = true), "\u001b")
        assertEquals(listOf("send(\u001b)"), calls)
    }

    @Test
    fun `三个智能体在跑一轮时先按 Esc，再各自停后台任务`() {
        assertEquals(
            listOf(StopKey("\u001b"), StopKey("\u0018\u000b", 500), StopKey("\u0018\u000b", 300)),
            stopKeys(AgentType.CLAUDE, turnRunning = true),
        )
        assertEquals(
            listOf(StopKey("\u001b"), StopKey("/stop", 500), StopKey("\r", 300)),
            stopKeys(AgentType.CODEX, turnRunning = true),
        )
        assertEquals(listOf(StopKey("\u001b")), stopKeys(AgentType.PI, turnRunning = true))
    }

    @Test
    fun `三个智能体没在跑一轮时都不按 Esc，Pi 什么也不按`() {
        assertEquals(listOf(StopKey("\u0018\u000b"), StopKey("\u0018\u000b", 300)), stopKeys(AgentType.CLAUDE, turnRunning = false))
        assertEquals(listOf(StopKey("/stop"), StopKey("\r", 300)), stopKeys(AgentType.CODEX, turnRunning = false))
        assertEquals(emptyList<StopKey>(), stopKeys(AgentType.PI, turnRunning = false))
    }

    @Test
    fun `停止的键全部按出去才算已发送，中途终端没了按离线`() = kotlinx.coroutines.runBlocking {
        val keys = listOf(StopKey("/stop"), StopKey("\r", 300))
        val pressed = mutableListOf<String>()
        assertEquals(
            "全部按出去",
            com.github.izerui.imux.bridge.core.StopResult.SENT,
            pressStopKeys(keys, wait = {}) { pressed += it; true },
        )
        pressed.clear()
        assertEquals(
            "/stop 打进去了、回车前终端没了：指令没提交，不能说已发送",
            com.github.izerui.imux.bridge.core.StopResult.OFFLINE,
            pressStopKeys(keys, wait = {}) { key -> (key != "\r").also { if (it) pressed += key } },
        )
        assertEquals(listOf("/stop"), pressed)
        assertEquals(
            "第一个键就没按出去",
            com.github.izerui.imux.bridge.core.StopResult.OFFLINE,
            pressStopKeys(keys, wait = {}) { false },
        )
    }

    @Test
    fun `按键之间按约定等待`() = kotlinx.coroutines.runBlocking {
        val waited = mutableListOf<Long>()
        pressStopKeys(listOf(StopKey("\u001b"), StopKey("/stop", 500), StopKey("\r", 300)), wait = { waited += it }) { true }
        assertEquals(listOf(500L, 300L), waited)
    }

    @Test
    fun `终端端口的停止按 pressStopKeys 汇报结果`() {
        val port = com.github.izerui.imux.SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/terminal/ImuxTerminalSessionPort.kt")
        val stop = port.bodyAfter("override suspend fun stop( target: SessionTarget,", '{')
        assertTrue(stop, stop.contains("return pressStopKeys(keys, wait = { delay(it) })"))
        assertTrue("不能再有按到一半也算已发送的分支：$stop", !stop.contains("StopResult.SENT"))
    }
}
