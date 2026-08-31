package com.github.izerui.imux.toolwindow

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTitleRegenerationSourceTest {
    private val tree = SourceCode("src/main/kotlin/com/github/izerui/imux/toolwindow/AgentSessionTree.kt")

    @Test
    fun `会话列表右键菜单接入标题重新生成动作`() {
        assertTrue(
            "左侧会话列表与终端正文必须复用同一个标题重新生成动作",
            tree.compactArgs(tree.normalized).contains(
                tree.compactArgs("actions.add(regenerateSessionTitleAction(project, source))"),
            ),
        )
    }
}
