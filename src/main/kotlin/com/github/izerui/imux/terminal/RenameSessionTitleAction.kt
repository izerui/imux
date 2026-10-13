package com.github.izerui.imux.terminal

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.model.AgentSession
import com.github.izerui.imux.monitor.SessionMonitor
import com.github.izerui.imux.session.normalizeManualTitle
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import kotlinx.coroutines.Job
import javax.swing.JButton
import javax.swing.JComponent

internal fun renameSessionTitleAction(
    project: Project,
    session: AgentSession,
): DumbAwareAction =
    object : DumbAwareAction(
        ImuxBundle.message("action.rename.title.text"),
        ImuxBundle.message("action.rename.title.description"),
        AllIcons.Actions.Edit,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = !SessionMonitor.getInstance(project).isRenamingTitle(session.id)
        }

        override fun actionPerformed(event: AnActionEvent) {
            promptRenameSessionTitle(project, session)
        }
    }

/**
 * 弹出以 CLI 存着的完整标题为初值的重命名框，确认后交给 [SessionMonitor.renameTitle] 写回 CLI。
 *
 * 初值不能用 [AgentSession.title]，原因见 [com.github.izerui.imux.session.storedTitle]。
 */
internal fun promptRenameSessionTitle(
    project: Project,
    session: AgentSession,
) {
    val monitor = SessionMonitor.getInstance(project)
    monitor.loadEditableTitle(session) { original ->
        val dialog = RenameSessionTitleDialog(project, session, original)
        if (dialog.showAndGet()) monitor.renameTitle(session, dialog.enteredTitle, original)
    }
}

/**
 * 手动输入与模型生成合在同一个框里：「自动生成」只把候选标题填进输入框，
 * 用户还可以再改，点确定才写回——生成得不满意不会悄悄覆盖原标题。
 */
private class RenameSessionTitleDialog(
    private val project: Project,
    private val session: AgentSession,
    original: String,
) : DialogWrapper(project) {
    private val titleField = JBTextField(original)
    private val generateButton =
        JButton(ImuxBundle.message("action.generate.title.text"), AllIcons.Actions.Refresh).apply {
            toolTipText = ImuxBundle.message("action.generate.title.description")
            addActionListener { generate() }
        }
    private var generation: Job? = null

    val enteredTitle: String get() = titleField.text

    init {
        title = ImuxBundle.message("action.rename.title.text")
        // 关闭对话框即取消生成：CLI 进程随之被杀，结果也不会回填到已销毁的组件上。
        Disposer.register(disposable) { generation?.cancel() }
        init()
        initValidation()
    }

    override fun createCenterPanel(): JComponent =
        panel {
            row(ImuxBundle.message("dialog.rename.title.message")) {
                cell(titleField)
                    .columns(COLUMNS_LARGE)
                    .align(AlignX.FILL)
                    .resizableColumn()
                    .focused()
                cell(generateButton)
            }
        }

    override fun getPreferredFocusedComponent(): JComponent = titleField

    override fun doValidate(): ValidationInfo? =
        if (normalizeManualTitle(titleField.text) == null) {
            ValidationInfo(ImuxBundle.message("dialog.rename.title.empty"), titleField)
        } else {
            null
        }

    private fun generate() {
        setGenerating(true)
        setErrorText(null)
        generation =
            SessionMonitor.getInstance(project).generateTitle(
                session,
                // 按钮回调跑在对话框的模态状态里，结果要回到同一个模态状态才能更新对话框
                ModalityState.current(),
            ) { result ->
                setGenerating(false)
                result
                    .onSuccess {
                        titleField.text = it
                        titleField.requestFocusInWindow()
                    }.onFailure {
                        setErrorText(
                            ImuxBundle.message(
                                "notification.title.failed",
                                it.message ?: ImuxBundle.message("notification.title.unknown.error"),
                            ),
                            generateButton,
                        )
                    }
            }
    }

    private fun setGenerating(generating: Boolean) {
        generateButton.isEnabled = !generating
        generateButton.icon = if (generating) AnimatedIcon.Default.INSTANCE else AllIcons.Actions.Refresh
        generateButton.disabledIcon = generateButton.icon
        titleField.isEnabled = !generating
        isOKActionEnabled = !generating
    }
}

/** Adds session-title renaming (manual input or model generation) to the terminal content context menu. */
class RenameSessionTitleAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        event.presentation.text = ImuxBundle.message("action.rename.title.text")
        event.presentation.description = ImuxBundle.message("action.rename.title.description")
        event.presentation.icon = AllIcons.Actions.Edit
        val session = sourceSession(event)
        event.presentation.isEnabledAndVisible =
            session != null &&
                !SessionMonitor.getInstance(event.project!!).isRenamingTitle(session.id)
    }

    override fun actionPerformed(event: AnActionEvent) {
        val session = sourceSession(event) ?: return
        promptRenameSessionTitle(event.project!!, session)
    }

    private fun sourceSession(event: AnActionEvent): AgentSession? {
        val project = event.project ?: return null
        val terminalView = event.getData(TerminalView.DATA_KEY) ?: return null
        val (_, sessionId) = TerminalHost.getInstance(project).sessionIdentityFor(terminalView) ?: return null
        return SessionMonitor.getInstance(project).model.sessionOf(sessionId)
    }
}
