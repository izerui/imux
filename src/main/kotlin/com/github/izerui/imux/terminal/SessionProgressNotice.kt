package com.github.izerui.imux.terminal

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.progress.ProgressSnapshot
import com.intellij.icons.AllIcons
import com.intellij.ui.AnimatedIcon

internal fun sessionProgressNotice(sessionKey: String, snapshot: ProgressSnapshot): Notice? {
    if (!snapshot.active) return null
    val state = ImuxBundle.message(when {
        snapshot.waiting -> "progress.waiting"
        snapshot.progress == null -> "progress.unknown"
        else -> "progress.running"
    })
    val progress = snapshot.progress
    val stateWithPercent = progress?.let { "$state ${it.percent}%" } ?: state
    return Notice(
        key = "progress:$sessionKey",
        tone = NoticeTone.INFO,
        text = listOfNotNull(stateWithPercent, progress?.message).joinToString(" · "),
        icon = if (snapshot.waiting) AllIcons.Actions.Pause else AnimatedIcon.Default.INSTANCE,
        closable = false,
        progress = NoticeProgress(progress?.percent),
    )
}
