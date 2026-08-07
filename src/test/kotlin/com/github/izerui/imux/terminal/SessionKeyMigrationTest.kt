package com.github.izerui.imux.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 终端换 key 之后，列表的选中态要跟着走。
 *
 * `/clear`、`/new` 不产生标签页切换事件，因此 `selectionChanged` 那条既有通路不会响；
 * 而 `reload()` 会用重绘前的选中 id 去 `restoreSelection`，等于把选中**主动按在**
 * 已经不属于这个终端的旧会话上。
 */
class SessionKeyMigrationTest {

    @Test
    fun `正在看这个终端时一定跟随到新会话`() {
        // 用户就在这个终端里敲的 /clear，列表理应指向他此刻在用的会话。
        // 树上眼下选中什么都不作数——焦点在终端里，选中早就可能被点走或被重绘弄丢，
        // 拿它当判据会静默失效。
        listOf<String?>(null, "旧id", "完全无关的会话").forEach { current ->
            assertEquals(
                "当前选中=$current 时也该跟随",
                "新id",
                selectionAfterMigration(current, from = "旧id", to = "新id", isActiveTab = true),
            )
        }
    }

    @Test
    fun `终端不在前台且选中的正是它时跟随`() {
        assertEquals(
            "新id",
            selectionAfterMigration("旧id", from = "旧id", to = "新id", isActiveTab = false),
        )
    }

    @Test
    fun `终端不在前台时不抢走别处的选中`() {
        // 用户可能正在列表里翻别的会话
        assertEquals(
            "无关会话",
            selectionAfterMigration("无关会话", from = "旧id", to = "新id", isActiveTab = false),
        )
    }

    @Test
    fun `终端不在前台且本就没有选中时不凭空造一个`() {
        assertNull(selectionAfterMigration(null, from = "旧id", to = "新id", isActiveTab = false))
    }

    @Test
    fun `新建会话绑定真实 id 时同样跟随`() {
        // pending 绑定走的是同一个 rebindKey：绑定后 pending 节点消失，
        // 不跟随的话选中会直接丢失
        assertEquals(
            "落盘后的真实id",
            selectionAfterMigration(
                current = "pending-0",
                from = "pending-0",
                to = "落盘后的真实id",
                isActiveTab = false,
            ),
        )
    }

    // ---- 接线：纯函数再对也要真的被调用 ----

    @Test
    fun `换 key 后必须广播迁移事件`() {
        val host = File("src/main/kotlin/com/github/izerui/imux/terminal/TerminalHost.kt").readText()

        assertTrue(
            "rebindKey 不广播的话，界面无从知道 key 变了，选中会一直停在旧会话上",
            host.contains("keyMigratedDispatcher.multicaster.sessionKeyMigrated("),
        )
    }

    @Test
    fun `工具窗口必须订阅迁移事件并迁移选中`() {
        val factory = File(
            "src/main/kotlin/com/github/izerui/imux/toolwindow/AgentToolWindowFactory.kt",
        ).readText()

        assertTrue(
            "订阅了才谈得上跟随",
            factory.contains("addSessionKeyMigratedListener("),
        )
        assertTrue(
            "收到事件要把选中挪过去",
            factory.contains("migrateSelection("),
        )
    }
}
