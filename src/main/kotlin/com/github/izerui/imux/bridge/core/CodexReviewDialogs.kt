package com.github.izerui.imux.bridge.core

import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class ReviewReply { NO_MENU, ACCEPTED, REJECTED, OFFLINE }

/** Calls for the same robot run under the router's submit lock. */
internal class CodexReviewDialogs(
    private val port: TerminalSessionPort,
    private val texts: BridgeTexts,
    private val deliveryScope: CoroutineScope? = null,
    private val deliver: suspend (Binding, String, () -> Boolean) -> Boolean,
) {
    private data class Dialog(
        val binding: Binding,
        val menu: CodexReviewMenu,
        val reference: String = "[imux-${UUID.randomUUID().toString().takeLast(12)}]",
        @Volatile var delivered: Boolean = false,
        @Volatile var pushing: Boolean = false,
        @Volatile var answered: Boolean = false,
        @Volatile var closed: Boolean = false,
    )

    private val dialogs = ConcurrentHashMap<RobotId, Dialog>()
    private val deliveryLocks = ConcurrentHashMap<RobotId, Mutex>()

    private suspend fun dispatch(binding: Binding, work: suspend () -> Unit) {
        val scope = deliveryScope
        if (scope == null) work() else scope.launch {
            deliveryLocks.computeIfAbsent(binding.robotId) { Mutex() }.withLock { work() }
        }
    }

    fun closedByStop(binding: Binding, menu: CodexReviewMenu) {
        dialogs[binding.robotId]?.takeIf { it.binding == binding && !it.closed && it.menu.title == menu.title }
            ?.answered = true
    }

    fun forget(binding: Binding) {
        dialogs.computeIfPresent(binding.robotId) { _, dialog -> dialog.takeUnless { it.binding == binding } }
    }

    private suspend fun send(binding: Binding, text: String) = dispatch(binding) { deliver(binding, text) { true } }

    suspend fun observe(binding: Binding, menu: CodexReviewMenu?) = prepare(binding, menu)?.invoke()

    /** Capture the transition under the submit lock. The observer delivers its notices after releasing it. */
    fun prepare(binding: Binding, menu: CodexReviewMenu?): (suspend () -> Unit)? {
        val previous = dialogs[binding.robotId]?.takeIf { it.binding == binding }
        if (menu == null) {
            if (previous != null && !previous.closed) {
                val notify = synchronized(previous) {
                    previous.closed = true
                    previous.delivered && !previous.answered
                }
                if (notify) return { send(binding, texts.reviewClosed(previous.menu.title)) }
            }
            return null
        }
        var closedTitle: String? = null
        val dialog = if (previous != null && !previous.closed && previous.menu.sameChoices(menu)) {
            previous
        } else {
            if (previous != null && !previous.closed && previous.menu.title != menu.title) {
                synchronized(previous) {
                    previous.closed = true
                    if (previous.delivered && !previous.answered) closedTitle = previous.menu.title
                }
            }
            Dialog(binding, menu).also { dialogs[binding.robotId] = it }
        }
        if (dialog.pushing || dialog.delivered) return null
        dialog.pushing = true
        val work: suspend () -> Unit = {
            try {
                closedTitle?.let { deliver(binding, texts.reviewClosed(it)) { true } }
                val accepted = deliver(binding, "${dialog.reference} ${listing(menu)}") {
                    dialogs[binding.robotId] === dialog && !dialog.closed && !dialog.answered
                }
                val closedDuringSend = synchronized(dialog) {
                    dialog.delivered = accepted
                    accepted && dialog.closed && !dialog.answered
                }
                if (closedDuringSend) deliver(binding, texts.reviewClosed(menu.title)) { true }
            } finally {
                dialog.pushing = false
            }
        }
        return { dispatch(binding, work) }
    }

    suspend fun answer(binding: Binding, reply: String, textOnly: Boolean, quote: String? = null): ReviewReply {
        val previous = dialogs[binding.robotId]?.takeIf { it.binding == binding }
        val quotedReference = quote?.let { REFERENCE.find(it)?.value }
        val current = when (val screen = port.readReview(binding.target)) {
            is ReviewScreen.Open -> screen.menu
            ReviewScreen.Closed -> null
            ReviewScreen.Unavailable -> {
                if (previous?.closed != false && quotedReference == null) return ReviewReply.NO_MENU
                if (!port.reviewTargetOnline(binding.target)) return ReviewReply.OFFLINE
                send(binding, texts.notReady())
                return ReviewReply.REJECTED
            }
        }
        if (quotedReference != null && (current == null || previous?.reference != quotedReference || previous.closed)) {
            if (!port.reviewTargetOnline(binding.target)) return ReviewReply.OFFLINE
            observe(binding, current)
            send(binding, texts.reviewChangedHint)
            return ReviewReply.REJECTED
        }
        if (current == null) {
            if (previous == null) return ReviewReply.NO_MENU
            if (!port.reviewTargetOnline(binding.target)) {
                forget(binding)
                return ReviewReply.OFFLINE
            }
            observe(binding, null)
            forget(binding)
            // A delayed answer to a closed prompt must not turn into a new Codex task.
            if (previous.menu.isCustom || previous.answered || !textOnly || !previous.delivered || previous.menu.keysFor(reply) == null) return ReviewReply.NO_MENU
            send(binding, texts.reviewChangedHint)
            return ReviewReply.REJECTED
        }
        val alreadyDelivered = previous?.let { it.delivered && !it.closed && it.menu.sameChoices(current) } == true
        if (!alreadyDelivered && previous?.delivered == true) send(binding, texts.reviewChangedHint)
        observe(binding, current)
        if (!alreadyDelivered) return ReviewReply.REJECTED
        if (!quote.isNullOrBlank() && quotedReference != previous.reference) {
            send(binding, texts.reviewChangedHint)
            return ReviewReply.REJECTED
        }
        if (previous.answered) {
            send(binding, texts.reviewChangedHint)
            return ReviewReply.REJECTED
        }
        if (!textOnly || current.keysFor(reply) == null) {
            if (!previous.pushing) {
                previous.pushing = true
                dispatch(binding) {
                    try {
                        deliver(binding, "${previous.reference} ${listing(current)}") {
                            dialogs[binding.robotId] === previous && !previous.closed && !previous.answered
                        }
                    } finally {
                        previous.pushing = false
                    }
                }
            }
            return ReviewReply.REJECTED
        }
        if (!port.answerReviewMenu(binding, current, reply)) {
            if (!port.reviewTargetOnline(binding.target)) return ReviewReply.OFFLINE
            send(binding, texts.reviewChangedHint)
            return ReviewReply.REJECTED
        }
        previous.answered = current.isCustom || TerminalKeys.normalizeReply(reply).toIntOrNull() != null
        return ReviewReply.ACCEPTED
    }

    private companion object {
        val REFERENCE = Regex("""\[imux-[0-9a-f]{12}]""")
    }

    private fun listing(menu: CodexReviewMenu): String = buildString {
        appendLine(menu.title)
        menu.options.forEachIndexed { index, label -> appendLine("${index + 1}. $label") }
        append(when {
            menu.hasCustomDraft -> texts.reviewDraftHint
            menu.isCustom -> texts.reviewTextHint
            else -> texts.reviewChoiceHint
        })
        if (menu.searchQuery != null) {
            appendLine()
            append(texts.reviewSearchHint)
        }
    }
}
