package com.github.izerui.imux.bridge

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.bridge.core.BridgeTexts

internal object BundleBridgeTexts : BridgeTexts {
    override fun unbound() = ImuxBundle.message("bridge.reply.unbound")

    override fun offline() = ImuxBundle.message("bridge.reply.offline")

    override fun notReady() = ImuxBundle.message("bridge.reply.notReady")

    override fun emptyInput() = ImuxBundle.message("bridge.reply.emptyInput")

    override fun localInputLabel() = ImuxBundle.message("bridge.label.localInput")


    override fun waiting(reason: String?) =
        if (reason.isNullOrBlank()) {
            ImuxBundle.message("bridge.status.waitingNoReason")
        } else {
            ImuxBundle.message("bridge.status.waiting", reason)
        }

    override fun released(sessionTitle: String) = ImuxBundle.message("bridge.status.released", sessionTitle)

    override fun transcriptRestarted() = ImuxBundle.message("bridge.status.transcriptRestarted")

    override fun connected(
        project: String,
        agent: String,
        sessionTitle: String,
    ) = ImuxBundle.message("bridge.status.connected", project, agent, sessionTitle)

    override fun pairingSucceeded() = ImuxBundle.message("bridge.pairing.succeeded")

    override fun pairingExpired() = ImuxBundle.message("bridge.pairing.expired")
}
