package com.github.izerui.imux.bridge

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.bridge.core.BridgeTexts
import com.github.izerui.imux.turn.waitingReason

internal object BundleBridgeTexts : BridgeTexts {
    override fun unbound() = ImuxBundle.message("bridge.reply.unbound")

    override fun offline() = ImuxBundle.message("bridge.reply.offline")

    override fun rebound() = ImuxBundle.message("bridge.reply.rebound")

    override fun notReady() = ImuxBundle.message("bridge.reply.notReady")

    override fun emptyInput() = ImuxBundle.message("bridge.reply.emptyInput")

    override fun localInputLabel() = ImuxBundle.message("bridge.label.localInput")


    // CLI 给的是 `input needed` 这类英文原因，译成与 IDE 提醒一致的说法
    override fun waiting(reason: String?) = ImuxBundle.message("bridge.status.waiting", waitingReason(reason))

    override fun released(sessionTitle: String) = ImuxBundle.message("bridge.status.released", sessionTitle)

    override fun transcriptRestarted() = ImuxBundle.message("bridge.status.transcriptRestarted")

    override fun backgroundRunning(tasks: List<String?>) =
        ImuxBundle.message(
            "bridge.status.backgroundRunning",
            tasks.joinToString("\n") { "• " + (it ?: ImuxBundle.message("bridge.status.backgroundTask")) },
        )

    override fun connected(
        project: String,
        agent: String,
        sessionTitle: String,
    ) = ImuxBundle.message("bridge.status.connected", project, agent, sessionTitle)

    // 配对回复由机器人管理直接发出，不经过路由器，所以不在 BridgeTexts 接口里
    fun pairingSucceeded() = ImuxBundle.message("bridge.pairing.succeeded")

    fun pairingExpired() = ImuxBundle.message("bridge.pairing.expired")

    fun pairingLocked() = ImuxBundle.message("bridge.pairing.locked")

    override fun attachmentLine(path: String) = ImuxBundle.message("bridge.attachment.line", path)

    override fun attachmentsOnly(paths: List<String>) =
        if (paths.size == 1) {
            ImuxBundle.message("bridge.attachment.only", paths.single())
        } else {
            ImuxBundle.message("bridge.attachment.onlyMany") + "\n" + paths.joinToString("\n") { attachmentLine(it) }
        }

    override fun attachmentFailed() = ImuxBundle.message("bridge.reply.attachmentFailed")

    override fun attachmentTooLarge() = ImuxBundle.message("bridge.reply.attachmentTooLarge")
}
