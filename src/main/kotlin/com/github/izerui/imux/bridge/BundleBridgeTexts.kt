package com.github.izerui.imux.bridge

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.bridge.core.BridgeTexts

internal object BundleBridgeTexts : BridgeTexts {
    override fun unbound() = ImuxBundle.message("bridge.reply.unbound")

    override fun offline() = ImuxBundle.message("bridge.reply.offline")

    override fun notReady() = ImuxBundle.message("bridge.reply.notReady")

    override fun emptyInput() = ImuxBundle.message("bridge.reply.emptyInput")

    override fun localInputLabel() = ImuxBundle.message("bridge.label.localInput")

    override fun completed(toolCalls: Int) = ImuxBundle.message("bridge.status.completed", toolCalls)

    override fun waiting(reason: String?) =
        if (reason.isNullOrBlank()) {
            ImuxBundle.message("bridge.status.waitingNoReason")
        } else {
            ImuxBundle.message("bridge.status.waiting", reason)
        }

    override fun released(sessionTitle: String) = ImuxBundle.message("bridge.status.released", sessionTitle)

    override fun transcriptRestarted() = ImuxBundle.message("bridge.status.transcriptRestarted")
}
