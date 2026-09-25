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

    /**
     * 窗口标题按会话数展示状态时，运行中的会话不能再重复计作未读。
     *
     * 这里只调整计数口径，不删除未读状态：会话停止运行后，之前没看的结果仍应重新
     * 出现在未读计数里。
     */
    fun unreadCount(excluding: Set<String> = emptySet()): Int =
        if (excluding.isEmpty()) unread.size else unread.count { it !in excluding }

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
