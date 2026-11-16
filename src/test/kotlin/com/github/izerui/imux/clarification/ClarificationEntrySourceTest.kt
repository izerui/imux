package com.github.izerui.imux.clarification

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertTrue
import org.junit.Test

class ClarificationEntrySourceTest {
    private val editor = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt")

    @Test
    fun `横幅里的先聊再做入口打开当前会话的窗口`() {
        assertTrue(editor.normalized.contains("ClarificationService.getInstance(project).open(virtualFile.sessionKey, virtualFile.tabId, virtualFile.agentType)"))
    }

    @Test
    fun `关闭标签页时按标签页身份清理先聊再做且不为此新建服务`() {
        val dispose = editor.bodyAfter("override fun dispose()", '{')
        assertTrue("按标签页身份清理", dispose.contains(".releaseOwner(virtualFile.tabId)"))
        assertTrue("项目关闭途中不能新建服务", dispose.contains("project.serviceIfCreated<ClarificationService>()?.releaseOwner(virtualFile.tabId)"))
    }

    @Test
    fun `先聊再做是图标组里固定的按钮`() {
        assertTrue(editor.normalized.contains("ToolPill(talkTool)"))
        assertTrue("任何地方都不隐藏它", !editor.normalized.contains("isEnabledAndVisible = false"))
    }

    @Test
    fun `讨论窗口开着时按钮显示讨论中并着绿色，开关窗口会刷新且不再弹顶部提示`() {
        val talk = editor.normalized.substringAfter("private val talkTool =").substringBefore("private val resultChip")
        assertTrue(talk.contains("label = if (open) ImuxBundle.message(\"clarify.tool.open\") else null"))
        assertTrue(talk.contains("tone = if (open) NoticeTone.SUCCESS else null"))
        assertTrue(talk.contains("\"clarify.status.open\""))
        val refresh = editor.bodyAfter("private fun refreshTalk()", '{')
        assertTrue(refresh.contains("ClarificationService.getInstance(project).isOpen(virtualFile.sessionKey)"))
        assertTrue("打开时不弹顶部提示：说明已写在讨论窗口里", !refresh.contains("flash(") && !editor.normalized.contains("clarify.notice.open"))
        assertTrue(editor.normalized.contains("ClarificationService.getInstance(project).addOpenListener(this)"))
        val service = SourceCode("src/main/kotlin/com/github/izerui/imux/clarification/ClarificationService.kt")
        assertTrue("打开时通知", service.bodyAfter("internal fun open(", '{').contains("openListeners.multicaster.openChanged(sessionKey)"))
        assertTrue("结束时通知", service.bodyAfter("private fun discard(", '{').contains("openListeners.multicaster.openChanged(sessionKey)"))
    }

    @Test
    fun `不提供不再提示`() {
        assertTrue(!editor.normalized.contains("peerBannerDismissed"))
        assertTrue(!editor.normalized.contains("action.peer.enable.dismiss"))
    }

    @Test
    fun `讨论窗口的说明写明聊完会把方案交给这个终端，各语言都有`() {
        val handoff = mapOf(
            "" to "terminal", "_zh_CN" to "聊完会把方案交给这个终端", "_zh_TW" to "聊完會把方案交給這個終端",
            "_ja" to "ターミナル", "_ko" to "터미널", "_de" to "Terminal", "_fr" to "terminal", "_es" to "terminal",
            "_pt_BR" to "terminal", "_ru" to "терминал",
        )
        for ((suffix, word) in handoff) {
            val file = java.io.File("src/main/resources/messages/ImuxBundle$suffix.properties")
            val comment = file.readLines().single { it.startsWith("clarify.input.comment=") }
            assertTrue("${file.name} 的说明应写明交给终端：$comment", comment.contains(word))
            assertTrue("${file.name} 不再保留打开提示", file.readLines().none { it.startsWith("clarify.notice.open=") })
        }
    }
}
