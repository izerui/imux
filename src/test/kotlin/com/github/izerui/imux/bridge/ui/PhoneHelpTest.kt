package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.SourceCode
import com.github.izerui.imux.bridge.core.HookDecision
import com.github.izerui.imux.bridge.core.HookReplies
import com.github.izerui.imux.bridge.core.TerminalKeys
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Properties

/** 手机端使用说明：两个菜单都能打开，各语言的说明与实际认的词对得上。 */
class PhoneHelpTest {
    private val phoneTool = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/ui/PhoneTool.kt")

    @Test
    fun `已连接和未连接的菜单都有使用说明`() {
        assertTrue(phoneTool.compact(phoneTool.bodyAfter("private fun showMenu(", '{')).contains("helpAction()"))
        assertTrue(phoneTool.compact(phoneTool.bodyAfter("private fun showConnectMenu(", '{')).contains("helpAction()"))
    }

    @Test
    fun `每种语言写的词手机上都真的认`() {
        val bundles = File("src/main/resources/messages").listFiles { f -> f.name.startsWith("ImuxBundle") }!!
        assertTrue(bundles.size >= 10)
        bundles.forEach { file ->
            val props = Properties().apply { file.reader(Charsets.UTF_8).use(::load) }
            fun words(key: String) = props.getProperty(key).split('|').map(String::trim).filter(String::isNotEmpty)
            fun recognized(key: String, check: (String) -> Boolean) =
                words(key).also { assertTrue("${file.name} $key 为空", it.isNotEmpty()) }.forEach {
                    assertTrue("${file.name} $key 里的「$it」手机上不认", check(it))
                }

            recognized("bridge.help.stop.keys") { TerminalKeys.isStop(it) }
            recognized("bridge.help.permission.allow.keys") { HookReplies.permission(it) is HookDecision.Allow }
            recognized("bridge.help.permission.deny.keys") { HookReplies.permission(it) == HookReplies.permission("deny") }
            recognized("bridge.help.plan.deny.keys") { HookReplies.plan(it) == HookReplies.plan("no") }
            // 各语言共用的英文词必须都在
            assertTrue(words("bridge.help.stop.keys").containsAll(listOf("stop", "esc")))
            // 不带参数也可能经过 MessageFormat，花括号会被吃掉
            props.stringPropertyNames().filter { it.startsWith("bridge.help.") }.forEach {
                assertTrue("${file.name} $it 不能有花括号", '{' !in props.getProperty(it))
            }
        }
    }
}
