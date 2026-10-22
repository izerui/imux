package com.github.izerui.imux.clarification

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.DialogWrapper.IdeModalityType
import javax.swing.JComponent
import javax.swing.JPanel

internal class ClarificationDialog(
    project: Project,
    private val service: ClarificationService,
    private val flow: ClarificationFlow,
    title: String,
) : DialogWrapper(project, null, true, IdeModalityType.MODELESS) {
    @Volatile var sessionKey: String = service.currentKeyOf(flow).orEmpty()

    init {
        this.title = title
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel()
}
