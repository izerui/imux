package com.github.izerui.imux.terminal

import com.github.izerui.imux.progress.TurnProgressStore
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.VisualPosition
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.plugins.terminal.view.TerminalContentChangeEvent
import org.jetbrains.plugins.terminal.view.TerminalOutputModelListener

/** Recognize the live Codex 0.172 approval/question chrome, never tool-call duration or arbitrary history. */
internal fun codexWaitingForInput(screen: String): Boolean {
    val lines = ANSI_STYLE.replace(screen, "").lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    val footer = lines.lastOrNull()?.lowercase() ?: return false
    val body = lines.dropLast(1)
    val selected = body.any { SELECTED_OPTION.containsMatchIn(it) }
    val approvalFooter = "enter" in footer && "esc" in footer && "confirm" in footer && "cancel" in footer
    if (approvalFooter && selected) {
        return body.any { OPTION_YES.containsMatchIn(it) } && body.any { OPTION_NO.containsMatchIn(it) }
    }
    return ("enter" in footer || "↵" in footer) && SUBMIT_ANSWER.containsMatchIn(footer) &&
        body.any { it.startsWith("› ") || it.startsWith("❯ ") }
}

private val ANSI_STYLE = Regex("""\x1b\[[0-9;:]*m""")
private val SELECTED_OPTION = Regex("""^[›❯>]\s*\d+[.)]\s+""")
private val OPTION_YES = Regex("""^(?:[›❯>]\s*)?\d+[.)]\s+Yes\b""")
private val OPTION_NO = Regex("""^(?:[›❯>]\s*)?\d+[.)]\s+No\b""")
private val SUBMIT_ANSWER = Regex("""submit(?: all)? answers?""")
/** Uses the public output-model events and follows alternate-buffer switches with the editor lifetime. */
internal class CodexInputWaiting(
    project: Project,
    file: AgentTerminalVirtualFile,
    parent: Disposable,
) {
    init {
        val store = TurnProgressStore.getInstance(project)
        val refresh = Channel<Unit>(Channel.CONFLATED)
        val view = file.terminalView
        val job = view.coroutineScope.launch(Dispatchers.EDT) {
            coroutineScope {
                val binding = OutputModelBinding(object : TerminalOutputModelListener {
                    override fun afterContentChanged(event: TerminalContentChangeEvent) {
                        refresh.trySend(Unit)
                    }
                })
                launch {
                    view.outputModels.active.collect {
                        binding.activeModelChanged(it)
                        refresh.trySend(Unit)
                    }
                }
                try {
                    for (signal in refresh) {
                        val models = view.outputModels
                        val model = models.active.value
                        val start = if (model === models.alternative) model.startOffset else {
                            val rows = view.gridSize?.rows?.takeIf { it > 0 } ?: continue
                            val editor = TerminalHost.getInstance(project).currentTerminalEditor(view)
                            if (editor != null && !editor.isDisposed && editor.document.textLength == model.textLength) {
                                val bottom = editor.offsetToVisualPosition(model.textLength).line
                                val top = VisualPosition((bottom - rows + 1).coerceAtLeast(0), 0)
                                model.startOffset + editor.visualPositionToOffset(top).toLong()
                            } else {
                                model.getStartOfLine(model.firstLineIndex + (model.lineCount - rows).coerceAtLeast(0).toLong())
                            }
                        }
                        val screen = model.getText(start, model.endOffset).toString()
                        store.setWaiting(file.sessionKey, codexWaitingForInput(screen))
                    }
                } finally {
                    binding.unbind()
                    refresh.close()
                }
            }
        }
        store.listen(parent) { refresh.trySend(Unit) }
        TerminalHost.getInstance(project).addSessionKeyMigratedListener(parent) { _, _ -> refresh.trySend(Unit) }
        Disposer.register(parent, Disposable { job.cancel() })
    }
}
