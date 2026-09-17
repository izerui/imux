package com.github.izerui.imux.monitor

import com.github.izerui.imux.turn.TurnNotifier
import java.util.concurrent.ConcurrentHashMap

/**
 * 未读状态的持有者：「轮次刚完成、用户还没回来看」的会话 id。
 *
 * 从 [SessionMonitor] 中提取：未读集合、标记与清除、以及因此而需要推送的
 * 图标与窗口标题更新。三个回调以函数注入，使本类完全脱离平台 API，可直接测试。
 */
internal class UnreadTracker(
    private val updateOpenTabIcons: (sessionIds: Set<String>?) -> Unit,
    private val updateFrameTitle: () -> Unit,
    private val notifyListeners: () -> Unit,
) {
    private val unread = ConcurrentHashMap.newKeySet<String>()

    fun hasUnread(): Boolean = unread.isNotEmpty()

    fun unreadCount(): Int = unread.size

    fun isUnread(sessionId: String): Boolean = sessionId in unread

    fun markUnread(sessionId: String) {
        if (unread.add(sessionId)) {
            updateOpenTabIcons(setOf(sessionId))
            updateFrameTitle()
            notifyListeners()
        }
    }

    fun clearUnread(sessionId: String) {
        TurnNotifier.dismiss(sessionId)
        if (unread.remove(sessionId)) {
            updateOpenTabIcons(setOf(sessionId))
            updateFrameTitle()
            notifyListeners()
        }
    }
}
