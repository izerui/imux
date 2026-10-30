package com.github.izerui.imux.bridge.channel

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 渠道只做收发：不认识 Agent、终端、会话记录。新增渠道时这条测试自动覆盖。 */
class ChannelIsolationSourceTest {
    @Test
    fun `渠道代码不依赖终端与 Agent`() {
        val files = File("src/main/kotlin/com/github/izerui/imux/bridge/channel").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("没有找到渠道源码", files.isNotEmpty())
        val forbidden =
            listOf("AgentType", "com.github.izerui.imux.bridge.terminal", "com.github.izerui.imux.bridge.transcript", "TerminalHost", "SessionMonitor")
        files.forEach { file ->
            val code = SourceCode(file.path).normalized
            forbidden.forEach { word -> assertTrue("${file.name} 引用了 $word", !code.contains(word)) }
        }
    }

    @Test
    fun `渠道子包之间互不引用`() {
        val root = File("src/main/kotlin/com/github/izerui/imux/bridge/channel")
        val channels = root.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }
        assertTrue("没有找到渠道子包：$channels", channels.containsAll(listOf("wecom", "feishu", "dingtalk", "weixin")))
        channels.forEach { own ->
            File(root, own).walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val code = SourceCode(file.path).normalized
                (channels - own).forEach { other ->
                    assertTrue("${file.name} 引用了渠道 $other", !code.contains("com.github.izerui.imux.bridge.channel.$other."))
                }
            }
        }
    }
}
