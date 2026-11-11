package com.github.izerui.imux.bridge.terminal

import com.intellij.terminal.frontend.view.TerminalViewSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 标签的 Running 状态会在打开之后变化；订阅随项目作用域和标签生命周期结束。 */
internal class SessionStateObserver(
    private val scope: CoroutineScope,
    private val changed: () -> Unit,
) {
    private data class Watch(val state: StateFlow<TerminalViewSessionState>, val job: Job)
    private val watches = mutableMapOf<String, Watch>()

    /** 调用方在 EDT 上提供当前标签快照；不缓存 TerminalView 或编辑器。 */
    fun update(states: Map<String, StateFlow<TerminalViewSessionState>>) {
        (watches.keys - states.keys).forEach { watches.remove(it)?.job?.cancel() }
        for ((tabId, state) in states) {
            if (watches[tabId]?.state === state) continue
            watches.remove(tabId)?.job?.cancel()
            watches[tabId] = Watch(state, scope.launch { state.collect { changed() } })
        }
    }
}
