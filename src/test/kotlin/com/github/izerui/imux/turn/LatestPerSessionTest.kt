package com.github.izerui.imux.turn

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同一会话先弹「等待确认」、再弹「已完成」，看过会话后原先只撤得掉最新一条，过时的「等待确认」一直留在通知面板里。
 */
class LatestPerSessionTest {
    private val expired = mutableListOf<String>()

    /** 假通知：过期时像平台的 whenExpired 一样回调 forget。 */
    private inner class Note(val name: String, val session: String) {
        fun expire() {
            expired += name
            store.forget(session, this)
        }
    }

    private val store = LatestPerSession<Note> { it.expire() }

    @Test
    fun `同一会话弹新提醒时旧的那条随即撤掉`() {
        val waiting = Note("等待确认", "s1")
        val done = Note("已完成", "s1")

        store.replace("s1", waiting)
        store.replace("s1", done)

        assertEquals(listOf("等待确认"), expired)
    }

    @Test
    fun `看过会话后撤掉的是最新那条，且只撤一次`() {
        store.replace("s1", Note("等待确认", "s1"))
        store.replace("s1", Note("已完成", "s1"))

        store.dismiss("s1")
        store.dismiss("s1")

        assertEquals(listOf("等待确认", "已完成"), expired)
    }

    @Test
    fun `旧提醒晚到的过期回调不会让新提醒撤不掉`() {
        val waiting = Note("等待确认", "s1")
        val done = Note("已完成", "s1")
        store.replace("s1", waiting)
        store.replace("s1", done)

        // 旧的那条再被平台回调一次（比如用户在通知面板里手动关掉）
        store.forget("s1", waiting)
        store.dismiss("s1")

        assertEquals(listOf("等待确认", "已完成"), expired)
    }

    @Test
    fun `不同会话的提醒互不影响`() {
        store.replace("s1", Note("s1 完成", "s1"))
        store.replace("s2", Note("s2 完成", "s2"))

        store.dismiss("s1")

        assertEquals(listOf("s1 完成"), expired)
    }

    /** 上面只验了这个小类；提醒入口不经它登记，旧提醒照样撤不掉。 */
    @Test
    fun `提醒入口弹出前经它登记，过期与点击只移除自己`() {
        val code = SourceCode("src/main/kotlin/com/github/izerui/imux/turn/TurnNotifier.kt")
        val notify = code.compact(code.bodyAfter("private fun notify(", '{'))

        val replace = notify.indexOf("active.replace(sessionId,notification)")
        assertTrue(notify, replace >= 0 && replace < notify.indexOf("notification.notify(project)"))
        assertTrue(notify, notify.contains("notification.whenExpired{active.forget(sessionId,notification)}"))
        assertTrue(notify, notify.contains("active.forget(sessionId,notification)openSession(project,sessionId)"))
    }
}
