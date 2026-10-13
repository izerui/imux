package com.github.izerui.imux.toolwindow

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTitleRegenerationSourceTest {
    private val tree = SourceCode("src/main/kotlin/com/github/izerui/imux/toolwindow/AgentSessionTree.kt")

    @Test
    fun `会话列表右键菜单与重命名快捷键都接入人工重命名`() {
        val code = tree.compactArgs(tree.normalized)
        assertTrue(
            "左侧会话列表右键菜单必须提供人工重命名",
            code.contains(tree.compactArgs("actions.add(renameSessionTitleAction(project, source))")),
        )
        assertTrue(
            "会话列表必须响应平台重命名快捷键",
            code.contains(tree.compactArgs("registerCustomShortcutSet(CommonShortcuts.getRename(), tree, parentDisposable)")),
        )
    }
}
