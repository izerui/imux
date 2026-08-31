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
    fun `换 key 时必须关闭重复目标并继续迁移`() {
        val host = File("src/main/kotlin/com/github/izerui/imux/terminal/TerminalHost.kt").readText()

        assertTrue(
            "异步探测跑着的时候用户可能自己打开了新会话。直接覆盖会把那个终端从账上抹掉" +
                "却不关闭：进程继续跑，标签页还开着，之后针对该 key 的关闭动作会作用到错的终端上",
            host.contains("discardDuplicateTarget(newKey,"),
        )
        assertTrue(
            "目标已打开时不能仅仅放弃迁移，否则用户点击的新标签页会因 resume 冲突立即退出，" +
                "旧终端也会永久留在旧 key 上",
            host.contains("private fun discardDuplicateTarget("),
        )
        assertTrue(
            "如果重复目标正是用户刚点击的标签页，关闭它以后必须激活迁移后的源终端，" +
                "否则界面会退回之前的代码编辑器，看起来仍像点击没有打开",
            host.contains("if (activateMigrated) FileEditorManager.getInstance(project).openFile(file, true)"),
        )
    }

    @Test
    fun `关闭标签页必须按虚拟文件实例清理`() {
        val host = File("src/main/kotlin/com/github/izerui/imux/terminal/TerminalHost.kt").readText()
        val editor =
            File(
                "src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt",
            ).readText()

        assertTrue(
            "重复目标的 editor 可能在源终端迁移完成后才 dispose；只按 key 清理会误杀迁移后的源终端",
            host.contains("fun closeSession(file: AgentTerminalVirtualFile)"),
        )
        assertTrue(
            "只有当前映射仍属于被关闭的虚拟文件时，才能删除该 key 的 view 与 watcher",
            host.contains("files.remove(key, file)"),
        )
        assertTrue(
            "editor dispose 必须把自身实例交给宿主做身份校验",
            editor.contains("closeSession(virtualFile)"),
        )
        assertTrue(
            "关闭过的会话再次监控时必须跳过上次被强杀留下的半轮状态",
            host.contains("rememberClosedSession(key)") &&
                host.contains("inferInitialState = !reopenedAfterClose"),
        )
        assertTrue(
            "关闭标签后必须立即清掉窗口标题中的 running 计数",
            editor.contains(".sessionClosed(sessionKey)"),
        )
    }

    @Test
    fun `迁移失败时不启动轮次监控`() {
        val applier =
            File(
                "src/main/kotlin/com/github/izerui/imux/monitor/SessionDriftApplier.kt",
            ).readText()

        // 行为本身由 SessionDriftApplierTest 直接断言（迁移失败时既不挂监控也不清未读），
        // 这里只守住「失败分支必须提前返回，不往下做收尾动作」这个源码形态
        assertTrue(
            "迁移没成还挂监控，就是把轮次盯到一个不归这个终端管的会话上",
            applier.contains("rememberFailedRebind(drift)") &&
                Regex("""rememberFailedRebind\(drift\)\s*\n\s*return false""")
                    .containsMatchIn(applier),
        )
    }

    @Test
    fun `探测失败时保留重试次数`() {
        val monitor =
            File(
                "src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt",
            ).readText()

        assertTrue(
            "/clear 只产生一次无主会话，触发器被一次性消费掉就再没有下一次了，" +
                "终端会永久停在旧 id 上而且失败是静默的",
            monitor.contains("if (migrated) driftProbeAttempts.set(0) else driftProbeAttempts.decrementAndGet()"),
        )
        assertTrue(
            "正在探测时必须原样保留重试次数，不能先 drain 再发现走不下去",
            monitor.contains("if (!probing.compareAndSet(false, true)) return"),
        )
        assertTrue(
            "探测期间若又出现新会话，旧探测完成时不能把新触发器的重试次数清零",
            monitor.contains("if (driftProbeGeneration.get() == generation)"),
        )
    }

    @Test
    fun `探测允许 daemon 接管并在应用前复核`() {
        val monitor =
            File(
                "src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt",
            ).readText()
        val applier =
            File(
                "src/main/kotlin/com/github/izerui/imux/monitor/SessionDriftApplier.kt",
            ).readText()

        assertTrue(
            "daemon 接管后的用户会话也标成 bg，必须进入探测并由歧义闸门决定是否迁移",
            monitor.contains("claudeDriftPids(runtimeSessions)"),
        )
        assertTrue(
            "探测是异步的，结果落地前标签页可能已经关掉或被重新打开成另一个终端",
            applier.contains("stillApplicable(drifts, openTabs())"),
        )
    }

    @Test
    fun `工具窗口必须订阅迁移事件并迁移选中`() {
        val factory =
            File(
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
