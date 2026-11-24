package com.github.izerui.imux.toolwindow

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 双击 ⌘ / Alt 新建会话只活在 plugin.xml 里：动作没登记、少声明一个 keymap、手势写错，
 * 编译和其它单测都发现不了，用户看到的只是「连按两下没反应」。
 */
class NewAgentSessionShortcutTest {
    private val actionTag: String by lazy {
        val xml = File("src/main/resources/META-INF/plugin.xml").readText()
        Regex("""<action\s[^>]*id="imux\.NewAgentSession"[\s\S]*?</action>""").find(xml)?.value
            ?: error("plugin.xml 未注册 imux.NewAgentSession")
    }

    private fun gesture(
        keystroke: String,
        keymap: String,
    ) = """<keyboard-gesture-shortcut keystroke="$keystroke" modifier="dblClick" keymap="$keymap"/>"""

    @Test
    fun `新建会话动作指向正确的类`() {
        assertTrue(actionTag, actionTag.contains("""class="${NewAgentSessionAction::class.java.name}""""))
    }

    @Test
    fun `两个 Mac 基础键位都绑了双击 Command`() {
        assertTrue("Mac OS X 10.5+（新版 macOS 默认键位）未绑双击 ⌘", actionTag.contains(gesture("meta META", "Mac OS X 10.5+")))
        assertTrue("Mac OS X（旧版 macOS 键位）未绑双击 ⌘", actionTag.contains(gesture("meta META", "Mac OS X")))
    }

    @Test
    fun `Windows 与 Linux 共用的根键位绑了双击 Alt`() {
        assertTrue("\$default 未绑双击 Alt", actionTag.contains(gesture("alt ALT", "\$default")))
    }

    @Test
    fun `菜单在窗口正中弹出`() {
        val action =
            SourceCode("src/main/kotlin/com/github/izerui/imux/toolwindow/NewAgentSessionAction.kt").normalized
        assertTrue("快捷键应调用居中弹出", action.contains("NewSessionPopup.showCentered("))
        val popup = SourceCode("src/main/kotlin/com/github/izerui/imux/toolwindow/NewSessionPopup.kt").normalized
        assertTrue(
            "居中弹出应使用平台的 showCenteredInCurrentWindow",
            popup.substringAfter("fun showCentered(").substringBefore("private fun create(")
                .contains(".showCenteredInCurrentWindow(project)"),
        )
    }
}
