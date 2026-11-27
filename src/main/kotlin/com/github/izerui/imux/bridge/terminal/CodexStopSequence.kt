package com.github.izerui.imux.bridge.terminal

import com.github.izerui.imux.bridge.core.CodexReviewMenu
import com.github.izerui.imux.bridge.core.StopResult
import com.github.izerui.imux.model.AgentType

internal class CodexStopSequence(turnRunning: Boolean, private val closed: (CodexReviewMenu) -> Unit) {
    private val keys = stopKeys(AgentType.CODEX, turnRunning).iterator()
    private var next = keys.next()
    private var awaitingClose: CodexReviewMenu? = null
    private var checks = 0
    var delayMs = 0L
        private set

    /** One live viewport read and at most one key on the same EDT turn. */
    fun advance(menu: CodexReviewMenu?, press: (String) -> Boolean): StopResult? {
        val previous = awaitingClose
        if (previous != null && previous.title != menu?.title) {
            closed(previous)
            awaitingClose = null
        }
        if (menu != null) {
            // Never confirm a picker opened after typing /stop. Wait for each Esc redraw
            // before deciding whether another level needs closing.
            if (next.text == "\r" || ++checks > 20) return StopResult.NOT_READY
            if (awaitingClose == null) {
                if (!press("\u001b")) return StopResult.NOT_READY
                awaitingClose = menu
            }
            delayMs = 100
            return null
        }
        if (!press(next.text)) return StopResult.NOT_READY
        if (!keys.hasNext()) return StopResult.SENT
        next = keys.next()
        delayMs = next.delayMs
        return null
    }
}
