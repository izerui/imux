package com.github.izerui.imux.terminal

import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project

/** Project-workspace state used to resume Imux tabs after the project is reopened. */
@Service(Service.Level.PROJECT)
@State(
    name = "ImuxRestorableSessionTabs",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
class RestorableSessionTabs : SerializablePersistentStateComponent<RestorableSessionTabs.State>(State()) {
    data class State(
        @JvmField val tabs: List<Tab> = emptyList(),
    )

    // XMLB 通过写字段还原集合元素；业务代码仍把 Tab 当作不可变值使用。
    data class Tab(
        @JvmField var agentId: String = "",
        @JvmField var sessionId: String = "",
        @JvmField var title: String = "",
    )

    fun tabs(): List<Tab> = state.tabs

    fun replace(tabs: List<Tab>) {
        val normalized =
            tabs
                .filter { it.agentId.isNotBlank() && it.sessionId.isNotBlank() }
                .distinctBy(Tab::sessionId)
        updateState { State(normalized) }
    }

    companion object {
        fun getInstance(project: Project): RestorableSessionTabs = project.getService(RestorableSessionTabs::class.java)
    }
}

internal class SessionTabRestorationState {
    var active: Boolean = false
        private set

    fun capture(tabs: List<RestorableSessionTabs.Tab>): List<RestorableSessionTabs.Tab> {
        check(!active) { "Session tab restoration is already active" }
        if (tabs.isEmpty()) return emptyList()

        active = true
        return tabs.map { it.copy() }
    }

    fun finish() {
        active = false
    }

    fun canPersist(
        projectClosing: Boolean,
        projectDisposed: Boolean,
    ): Boolean = !active && !projectClosing && !projectDisposed
}
