package com.github.izerui.imux.bridge

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 空闲时发停止只停后台任务：插件只知道停止键发出去了，不知道有没有后台任务、停没停成功，
 * 回复只说「已发送指令」，不能说成「已停止」。
 */
class StopRepliesTextTest {
    private fun reply(suffix: String) =
        File("src/main/resources/messages/ImuxBundle$suffix.properties").readLines()
            .single { it.startsWith("bridge.reply.backgroundStopped=") }

    @Test
    fun `中英文的只停后台回复都只说已发送指令，不说已停止`() {
        assertTrue(reply("_zh_CN"), reply("_zh_CN").contains("已发送") && !reply("_zh_CN").contains("已停止") && !reply("_zh_CN").contains("已顺带停止"))
        assertTrue(reply(""), reply("").contains("sent") && !reply("").contains("have been stopped"))
    }
}
