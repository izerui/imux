package com.github.izerui.imux.toolwindow

/**
 * 记住一次「等目标出现再选中」的意图。
 *
 * 终端换 key 之后要把列表选中一并挪过去，但目标未必已经在列表里：pi 在 `/new` 的
 * 那一刻就上报，而列表要等下一轮扫描（约 3 秒）才看得见新会话。
 * [AgentSessionTree.revealSession] 这时定位不到目标、静默返回，
 * 而随后的 reload 只会拿旧 id 去恢复选中，等于把高亮按死在一个
 * 已经不属于这个终端的会话上。
 *
 * 于是先把目标记下来，每次列表重建时再试一次。
 *
 * **等待期间用户自己动了选中就作废**：抢选中比不跟随更烦人——他正看着的东西
 * 会在几秒后毫无征兆地跳走。作废是一次性的，不会等他点回来又突然生效。
 *
 * 只在 EDT 上访问（列表重建与迁移事件都在 EDT），因此不做同步。
 */
internal class DeferredSelection {

    private var target: String? = null
    private var selectedAtDefer: String? = null

    /** 登记一次待办：[target] 出现在列表里之后就选中它。后一次覆盖前一次。 */
    fun defer(selectedAtDefer: String?, target: String) {
        this.target = target
        this.selectedAtDefer = selectedAtDefer
    }

    /**
     * 目标已经可定位、且用户没动过选中，就交出目标并清空；否则返回 null。
     *
     * [locatable] 由调用方回答「这个 id 现在能不能在列表里找到」。
     */
    fun claim(selectedNow: String?, locatable: (String) -> Boolean): String? {
        val pending = target ?: return null

        if (selectedNow != selectedAtDefer) {
            // 用户自己选了别的，这次跟随作废
            clear()
            return null
        }
        if (!locatable(pending)) return null

        clear()
        return pending
    }

    fun clear() {
        target = null
        selectedAtDefer = null
    }
}
