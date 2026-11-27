package com.github.izerui.imux.terminal

import com.github.izerui.imux.session.SessionUsage

internal class SessionInfoSnapshot {
    var usage = SessionUsage()
        private set
    var branch: String? = null
        private set
    var readFailed = false
        private set

    fun update(usage: SessionUsage?, branch: String?) {
        if (usage != null) this.usage = usage
        this.branch = branch
        readFailed = usage == null
    }
}
