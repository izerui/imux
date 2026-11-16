package com.github.izerui.imux.bridge

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 空闲时发停止只停后台任务：插件看不准有没有后台任务（连上之前启动的、Codex 的后台终端都看不到），
 * 照样按停后台的键，回复不能说得像确实停掉了什么。
 */
class StopRepliesTextTest {
    private fun reply(suffix: String) =
        File("src/main/resources/messages/ImuxBundle$suffix.properties").readLines()
            .single { it.startsWith("bridge.reply.backgroundStopped=") }

    @Test
    fun `中英文的只停后台回复都写明是如果有才停`() {
        assertTrue(reply("_zh_CN"), reply("_zh_CN").contains("如果有"))
        assertTrue(reply(""), reply("").contains("if any"))
    }
}
