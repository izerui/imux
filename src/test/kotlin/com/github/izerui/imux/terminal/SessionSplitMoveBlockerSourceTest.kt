package com.github.izerui.imux.terminal

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 会话窗口不支持分屏：标签右键的 Split and Move 不认 FORBID_TAB_SPLIT，要对会话隐藏。 */
class SessionSplitMoveBlockerSourceTest {
    private val blocker = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/SessionSplitMoveBlocker.kt")
    private val pluginXml = SourceCode("src/main/resources/META-INF/plugin.xml")

    @Test
    fun `替换的是平台的移动式分屏动作，并已注册`() {
        assertEquals(setOf("MoveTabRight", "MoveTabDown"), SessionSplitMoveBlocker.MOVE_ACTION_IDS)
        assertTrue(
            blocker.compact(pluginXml.source).contains(
                blocker.compact("""<dynamicActionConfigurationCustomizer implementation="com.github.izerui.imux.terminal.SessionSplitMoveBlocker"/>"""),
            ),
        )
    }

    @Test
    fun `会话标签上隐藏，快捷键也不执行`() {
        val update = blocker.compact(blocker.bodyAfter("override fun update(e: AnActionEvent)", '{'))
        assertTrue(update.contains("if(targetsSession(e))e.presentation.isEnabledAndVisible=false"))
        // 快捷键不经过菜单的 update 隐藏，执行时也要挡
        val perform = blocker.compact(blocker.bodyAfter("override fun actionPerformed(e: AnActionEvent)", '{'))
        assertTrue(perform.contains("if(!targetsSession(e))super.actionPerformed(e)"))
    }

    @Test
    fun `卸载时换回原动作`() {
        val unregister = blocker.compact(blocker.bodyAfter("override fun unregisterActions(actionManager: ActionManager)", '{'))
        assertTrue(unregister.contains("replaceAction(id,it.delegate)"))
    }

    @Test
    fun `按右键的标签判断，不用平台内部 API`() {
        val target = blocker.compact(blocker.bodyAfter("fun targetsSession(e: AnActionEvent): Boolean", '{'))
        // 右键的不一定是选中的标签：getTargetInfo 在右键时给被点的那个
        assertTrue(target.contains("tabs.targetInfo?.component"))
        // Marketplace 验证会把 EditorWindow 的内部方法标成问题；在去掉注释的源码上查，注释里提到不算
        assertFalse(blocker.normalized.contains("getContextFile"))
        assertFalse(blocker.normalized.contains("fileEditor.impl."))
    }
}
