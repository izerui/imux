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

    override fun plan(plan: String) = ImuxBundle.message("bridge.status.plan", plan)

    override fun dialogOpen() = ImuxBundle.message("bridge.reply.dialogOpen")

    override fun question(
        listing: String,
        number: Int,
        total: Int,
    ) = if (total > 1) {
        ImuxBundle.message("bridge.status.question.multi", listing, number, total)
    } else {
        ImuxBundle.message("bridge.status.question.single", listing)
    }

    override fun multiSelectMark() = ImuxBundle.message("bridge.status.question.multiSelect")

    override fun promptAnsweredLocally(step: String, answer: String?) =
        if (answer == null) promptHandledLocally(step) else ImuxBundle.message("bridge.status.promptAnsweredLocally", answer)

    override fun planApprovedLocally(step: String) = ImuxBundle.message("bridge.status.planApprovedLocally", step)

    override fun promptHandledLocally(step: String) = ImuxBundle.message("bridge.status.promptHandledLocally", step)

    override fun promptRejected() = ImuxBundle.message("bridge.reply.promptRejected")

    override fun released(session: String) = ImuxBundle.message("bridge.status.released", session)

    override fun suspended(session: String) = ImuxBundle.message("bridge.status.suspended", session)

    override fun transcriptRestarted() = ImuxBundle.message("bridge.status.transcriptRestarted")

    override fun turnWithoutReply() = ImuxBundle.message("bridge.status.turnWithoutReply")

    override fun turnFailed(reason: String) =
        if (reason.isBlank()) ImuxBundle.message("bridge.status.turnFailedWithoutReason")
        else ImuxBundle.message("bridge.status.turnFailed", reason)

    override fun turnInterrupted() = ImuxBundle.message("bridge.status.turnInterrupted")

    override fun backgroundRunning(tasks: List<String?>) =
        ImuxBundle.message(
            "bridge.status.backgroundRunning",
            tasks.joinToString("\n") { "• " + (it ?: ImuxBundle.message("bridge.status.backgroundTask")) },
        )

    override fun connected(
        project: String,
        agent: String,
        sessionTitle: String,
    ) = ImuxBundle.message("bridge.status.connected", "$TITLE_ICON $project", agent, sessionTitle)

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

    override fun hookPermission(listing: String) = ImuxBundle.message("bridge.status.hookPermission", listing)

    override fun hookQueued(count: Int) = ImuxBundle.message("bridge.status.hookQueued", count)

    override fun hookWordsForwarded(step: String) = ImuxBundle.message("bridge.reply.hookWordsForwarded", step)

    override fun hookPlanRevising() = ImuxBundle.message("bridge.reply.hookPlanRevising")

    override fun hookPlanChoose() = ImuxBundle.message("bridge.reply.hookPlanChoose")

    override fun hookAnswerFirst() = ImuxBundle.message("bridge.reply.hookAnswerFirst")

    override fun hookAllowedLocally(step: String) = ImuxBundle.message("bridge.status.hookAllowedLocally", step)

    override fun hookDeniedLocally(step: String) = ImuxBundle.message("bridge.status.hookDeniedLocally", step)

    override fun hookExpired(step: String) = ImuxBundle.message("bridge.status.hookExpired", step)

    override fun hookTooLate(step: String) = ImuxBundle.message("bridge.reply.hookTooLate", step)
}
