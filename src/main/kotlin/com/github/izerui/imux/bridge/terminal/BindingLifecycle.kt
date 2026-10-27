package com.github.izerui.imux.bridge.terminal

import com.github.izerui.imux.bridge.core.Binding

sealed interface TabChange {
    data object Keep : TabChange

    data class Reattach(val tabId: String) : TabChange

    data object Release : TabChange
}

/**
 * 标签集合变化后，一条绑定该怎么处理。
 *
 * TerminalHost.closeSession 不区分"用户关标签""CLI 退出""项目关闭"，这里按
 * [projectClosing] 与 [seenTabs]（本服务实例亲眼见过打开的标签）自行区分，
 * 不改 closeSession 的语义。拖动、分屏由平台的 CLOSING_TO_REOPEN 保证不触发关闭。
 *
 * @param openTabs 当前打开的 tabId → sessionKey
 */
fun tabChangeFor(
    binding: Binding,
    openTabs: Map<String, String>,
    seenTabs: Set<String>,
    projectClosing: Boolean,
): TabChange {
    val target = binding.target
    if (target.tabId in openTabs) return TabChange.Keep
    target.sessionId
        ?.let { sessionId -> openTabs.entries.firstOrNull { it.value == sessionId }?.key }
        ?.let { return TabChange.Reattach(it) }
    if (projectClosing || target.tabId !in seenTabs) return TabChange.Keep
    return TabChange.Release
}
