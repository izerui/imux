package com.github.izerui.imux.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 标签右键「Split and Move Right/Down」不认 FORBID_TAB_SPLIT，执行时先关再开；
 * 漏了这层，点一下就会把正在跑的会话结束掉。
 */
class AgentSplitAndMoveGuardSourceTest {
    private val source: String by lazy {
        File("src/main/kotlin/com/github/izerui/imux/terminal/AgentSplitAndMoveGuard.kt").readText()
    }
    private val pluginXml: String by lazy {
        File("src/main/resources/META-INF/plugin.xml").readText()
    }

    @Test
    fun `只拦平台的移动式分屏动作`() {
        assertEquals(setOf("MoveTabRight", "MoveTabDown"), AgentSplitAndMoveGuard.MOVE_ACTION_IDS)
    }

    @Test
    fun `执行前标记为关闭重开，执行后清掉`() {
        val before = source.substringAfter("fun beforeActionPerformed").substringBefore("override fun")
        val after = source.substringAfter("fun afterActionPerformed").substringBefore("internal companion")
        assertTrue(before.contains("CLOSING_TO_REOPEN, true"))
        assertTrue(before.contains("moving = file"))
        // 右键的标签不一定是选中的那个，必须和平台动作取同一个文件
        assertTrue(before.contains("EditorWindow.DATA_KEY)?.getContextFile()"))
        // 执行完标签已移走，必须清执行前记下的文件，不能再从事件里取
        assertTrue(after.contains("moving?.putUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN, null)"))
        assertTrue(!after.contains("getContextFile"))
    }

    @Test
    fun `注册为应用级动作监听`() {
        assertTrue(
            pluginXml.contains(
                "<listener class=\"com.github.izerui.imux.terminal.AgentSplitAndMoveGuard\"\n" +
                    "                  topic=\"com.intellij.openapi.actionSystem.ex.AnActionListener\"/>",
            ),
        )
    }
}
