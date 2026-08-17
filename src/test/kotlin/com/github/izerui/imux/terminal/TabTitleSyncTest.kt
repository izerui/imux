package com.github.izerui.imux.terminal

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * 标签页标题原先只在「新建会话绑定到真实 id」那一刻写一次，此后再也不更新。
 * 于是 CLI 事后生成的标题（claude 的 ai-title、codex 写进 sqlite 的标题）只进列表，
 * 标签页一直挂着最初那个——常常是 `# AGENTS.md instructions for …` 这类注入内容。
 */
class TabTitleSyncTest {

    @Test
    fun `虚拟文件展示名带类型前缀而标签标题与 hover 保持简洁`() {
        val file =
            AgentTerminalVirtualFile(
                name = "创建时标题",
                terminalView = proxy(),
                sessionKey = "s1",
                agentType = AgentType.CODEX,
                tabId = "tab-1",
            )
        file.tabTitle = "更新后的标题"
        val provider = AgentTerminalTabTitleProvider()

        assertEquals("codex: 更新后的标题", file.name)
        assertEquals("更新后的标题", provider.getEditorTabTitle(proxy(), file))
        assertEquals("更新后的标题", provider.getEditorTabTooltipHtml(proxy(), file)?.toString())
    }

    @Test
    fun `三种 Agent 使用各自的 CLI 名称作为展示前缀`() {
        AgentType.entries.forEach { agentType ->
            val file =
                AgentTerminalVirtualFile(
                    name = "会话标题",
                    terminalView = proxy(),
                    sessionKey = agentType.cli,
                    agentType = agentType,
                    tabId = "tab-${agentType.cli}",
                )

            assertEquals("${agentType.cli}: 会话标题", file.name)
        }
    }

    @Test
    fun `标题变了的标签页要更新`() {
        val stale = staleTabTitles(
            current = mapOf("s1" to "# AGENTS.md instructions for /path <I…"),
            latestTitleOf = { "分析工程结构" },
        )

        assertEquals(mapOf("s1" to "分析工程结构"), stale)
    }

    /** 每次扫描都会调到这里，标题没变还去刷新就是白费 EDT。 */
    @Test
    fun `标题没变的不参与更新`() {
        val stale = staleTabTitles(
            current = mapOf("s1" to "分析工程结构"),
            latestTitleOf = { "分析工程结构" },
        )

        assertTrue(stale.isEmpty())
    }

    /** 新建会话尚未绑定时标签页记在合成 key 下，查不到会话，保持原样。 */
    @Test
    fun `查不到会话的标签页保持原样`() {
        val stale = staleTabTitles(
            current = mapOf("pending-0" to "新会话"),
            latestTitleOf = { null },
        )

        assertTrue(stale.isEmpty())
    }

    @Test
    fun `多个标签页里只挑出变化的那个`() {
        val latest = mapOf("s1" to "旧标题", "s2" to "新标题")
        val stale = staleTabTitles(
            current = mapOf("s1" to "旧标题", "s2" to "占位"),
            latestTitleOf = latest::get,
        )

        assertEquals(mapOf("s2" to "新标题"), stale)
    }

    /** 标题为空不该把标签页刷成空白，那还不如留着旧的。 */
    @Test
    fun `空标题不覆盖已有标题`() {
        val stale = staleTabTitles(
            current = mapOf("s1" to "分析工程结构"),
            latestTitleOf = { "" },
        )

        assertTrue(stale.isEmpty())
    }

    private inline fun <reified T> proxy(): T =
        Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "toString" -> "test ${T::class.java.simpleName}"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                else -> error("Unexpected ${T::class.java.simpleName}.${method.name}")
            }
        } as T
}
