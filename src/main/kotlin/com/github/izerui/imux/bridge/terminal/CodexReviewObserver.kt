package com.github.izerui.imux.bridge.terminal

import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.terminal.OutputModelBinding
import com.intellij.openapi.application.EDT
import com.intellij.terminal.frontend.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.plugins.terminal.view.TerminalContentChangeEvent
import org.jetbrains.plugins.terminal.view.TerminalOutputModelListener

/** Reconciled on the EDT. Each binding has one reader and at most one pending refresh. */
internal class CodexReviewObserver(
    private val scope: CoroutineScope,
    private val port: ImuxTerminalSessionPort,
    private val publish: suspend (Binding, suspend () -> String?) -> Unit,
) {
    private data class Watch(val view: TerminalView, val job: Job)
    private val watches = mutableMapOf<Binding, Watch>()

    fun update(bindings: List<Binding>) {
        val live = bindings.mapNotNull { binding -> port.reviewView(binding)?.let { binding to it } }.toMap()
        watches.entries.removeIf { (binding, watch) ->
            val obsolete = live[binding] !== watch.view || !watch.job.isActive
            if (obsolete) {
                watch.job.cancel()
                port.forgetReviewScreen(binding)
            }
            obsolete
        }
        for ((binding, view) in live) {
            watches.getOrPut(binding) {
                Watch(view, scope.launch(Dispatchers.EDT) { observe(binding, view) })
            }
        }
    }

    private suspend fun observe(binding: Binding, view: TerminalView) = coroutineScope {
        val refresh = Channel<Unit>(Channel.CONFLATED)
        val listener = OutputModelBinding(object : TerminalOutputModelListener {
            override fun afterContentChanged(event: TerminalContentChangeEvent) {
                refresh.trySend(Unit)
            }
        })
        launch {
            view.outputModels.active.collect {
                listener.activeModelChanged(view.outputModels.active.value)
                refresh.trySend(Unit)
            }
        }
        try {
            for (signal in refresh) {
                withContext(Dispatchers.IO) {
                    publish(binding) {
                        withContext(Dispatchers.EDT) {
                            if (port.reviewView(binding) === view) port.reviewScreen(binding, view) else null
                        }
                    }
                }
            }
        } finally {
            listener.unbind()
            refresh.close()
        }
    }
}
