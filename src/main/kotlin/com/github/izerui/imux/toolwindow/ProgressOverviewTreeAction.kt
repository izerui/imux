package com.github.izerui.imux.toolwindow

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.overview.showProgressOverviewPopup
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import java.awt.Component

internal fun progressOverviewAction(
    project: Project,
    sessionId: String,
    owner: Component,
): DumbAwareAction =
    object : DumbAwareAction(
        ImuxBundle.message("action.overview.text"),
        ImuxBundle.message("action.overview.description"),
        AllIcons.Actions.Preview,
    ) {
        override fun actionPerformed(event: AnActionEvent) {
            showProgressOverviewPopup(project, sessionId, owner)
        }
    }
