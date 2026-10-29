package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.bridge.BindingService
import com.github.izerui.imux.bridge.BridgeHub
import com.github.izerui.imux.bridge.RobotManager
import com.github.izerui.imux.bridge.SessionTitles
import com.github.izerui.imux.monitor.SessionMonitor
import com.github.izerui.imux.terminal.BannerColors
import com.github.izerui.imux.terminal.SessionDecorationsListener
import com.github.izerui.imux.terminal.StatusDotIcon
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import javax.swing.JTable
import javax.swing.table.AbstractTableModel

/** 设置 › 工具 › Imux › 通讯机器人。机器人管理与会话管理分开：这里只管机器人。 */
class RobotsConfigurable : BoundConfigurable(ImuxBundle.message("bridge.settings.displayName")) {
    private val model = RobotTableModel()
    private var uiDisposable: Disposable? = null
    private var table: JBTable? = null

    override fun createPanel(): DialogPanel {
        val table =
            object : JBTable(model) {
                override fun doLayout() {
                    if (tableHeader?.resizingColumn == null && columnModel.columnCount == 4) {
                        val shortWidth = width * 13 / 100
                        for (index in 0..2) columnModel.getColumn(index).preferredWidth = shortWidth
                        columnModel.getColumn(3).preferredWidth = width - shortWidth * 3
                    }
                    super.doLayout()
                }
            }.apply {
                setShowGrid(false)
                emptyText.text = ImuxBundle.message("bridge.settings.empty")
                columnModel.getColumn(STATUS_COLUMN).cellRenderer = StatusRenderer()
            }.also { this.table = it }
        val decorated =
            ToolbarDecorator.createDecorator(table)
                .setAddAction {
                    AddRobotDialog(null).show()
                    refresh()
                }
                .setRemoveAction { remove(table) }
                .setEditAction { rename(table) }
                .addExtraAction(
                    object : com.intellij.openapi.project.DumbAwareAction(
                        ImuxBundle.message("bridge.settings.unbind"),
                        null,
                        AllIcons.Actions.Cancel
                    ) {
                        override fun getActionUpdateThread() = ActionUpdateThread.EDT

                        override fun update(e: AnActionEvent) {
                            e.presentation.isEnabled = selected(table)?.session != null
                        }

                        override fun actionPerformed(e: AnActionEvent) = unbind(table)
                    },
                ).addExtraAction(
                    object : com.intellij.openapi.project.DumbAwareAction(
                        ImuxBundle.message("bridge.settings.reconnect"),
                        null,
                        AllIcons.Actions.Refresh
                    ) {
                        override fun getActionUpdateThread() = ActionUpdateThread.EDT

                        override fun update(e: AnActionEvent) {
                            e.presentation.isEnabled = selected(table) != null
                        }

                        override fun actionPerformed(e: AnActionEvent) = reconnect(table)
                    },
                ).addExtraAction(
                    object : com.intellij.openapi.project.DumbAwareAction(
                        ImuxBundle.message("bridge.settings.pair"),
                        null,
                        AllIcons.General.User
                    ) {
                        override fun getActionUpdateThread() = ActionUpdateThread.EDT

                        override fun update(e: AnActionEvent) {
                            e.presentation.isEnabled = selected(table)?.let {
                                RobotManager.getInstance().robot(it.id)?.owner() == null
                            } == true
                        }

                        override fun actionPerformed(e: AnActionEvent) = pair(table)
                    },
                ).createPanel()
        refresh()
        // 设置页可能多次创建面板：先停掉旧定时器，避免泄漏
        // 不定时轮询，按事件刷新：机器人、连接状态或绑定变化（与终端横幅、状态栏同一信号），
        // 以及会话列表变化（“当前连接的会话”一列显示会话标题，改名后要跟着变）
        uiDisposable?.let(Disposer::dispose)
        uiDisposable =
            Disposer.newDisposable("imux.robots.settings").also { disposable ->
                ApplicationManager.getApplication().messageBus.connect(disposable)
                    .subscribe(SessionDecorationsListener.TOPIC, SessionDecorationsListener { refresh() })
                ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }.forEach { project ->
                    SessionMonitor.getInstance(project).addListener(disposable) { refresh() }
                }
            }
        return panel {
            row { cell(decorated).align(Align.FILL) }.resizableRow()
            row { comment(ImuxBundle.message("bridge.settings.note")) }
        }
    }

    override fun disposeUIResources() {
        uiDisposable?.let(Disposer::dispose)
        uiDisposable = null
        table = null
        super.disposeUIResources()
    }

    private fun refresh() {
        val manager = RobotManager.getInstance()
        val hub = BridgeHub.getInstance()
        val table = this.table
        val selectedId = table?.let { selected(it)?.id }
        model.rows =
            robotRows(
                manager.robots(),
                statusOf = manager::statusOf,
                bindingOf = { hub.bindings.bindingOf(it) },
                titleOf = SessionTitles::of,
                labels = BundleRowLabels,
                bridgeEnabled = true,
            )
        // 增删机器人会整表重建：按 id 把选中放回去
        if (table != null && selectedId != null && selected(table)?.id != selectedId) {
            model.rows.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 }
                ?.let { table.setRowSelectionInterval(it, it) }
        }
    }

    private fun selected(table: JBTable): RobotRow? =
        table.selectedRow.takeIf { it >= 0 }?.let { model.rows.getOrNull(it) }

    private fun remove(table: JBTable) {
        val row = selected(table) ?: return
        val ok = Messages.showOkCancelDialog(
            ImuxBundle.message("bridge.settings.remove.confirm", row.name),
            ImuxBundle.message("bridge.settings.displayName"),
            Messages.getOkButton(),
            Messages.getCancelButton(),
            Messages.getWarningIcon()
        )
        if (ok != Messages.OK) return
        val manager = RobotManager.getInstance()
        manager.launch { manager.remove(row.id) }
    }

    private fun rename(table: JBTable) {
        val row = selected(table) ?: return
        val name = Messages.showInputDialog(
            ImuxBundle.message("bridge.settings.rename"),
            ImuxBundle.message("bridge.settings.displayName"),
            null,
            row.name,
            null
        )
        if (!name.isNullOrBlank()) RobotManager.getInstance().rename(row.id, name.trim())
        refresh()
    }

    private fun reconnect(table: JBTable) {
        val row = selected(table) ?: return
        RobotManager.getInstance().reconnect(row.id)
        refresh()
    }

    private fun pair(table: JBTable) {
        val row = selected(table) ?: return
        AddRobotDialog(null, pairRobotId = row.id).show()
        refresh()
    }

    private fun unbind(table: JBTable) {
        val row = selected(table) ?: return
        BindingService.unbind(row.id)
        refresh()
    }

    /** 状态列前加圆点，与横幅、原型一致。 */
    private inner class StatusRenderer : ColoredTableCellRenderer() {
        // 每秒刷新、每行都画，图标只建两份复用
        private val okDot = StatusDotIcon(BannerColors.ONLINE)
        private val attentionDot = StatusDotIcon(BannerColors.PROBLEM)

        override fun customizeCellRenderer(
            table: JTable,
            value: Any?,
            selected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ) {
            icon =
                when (model.rows.getOrNull(row)?.health) {
                    RobotHealth.OK -> okDot
                    RobotHealth.ATTENTION -> attentionDot
                    else -> null
                }
            append(value?.toString().orEmpty())
        }
    }

    private class RobotTableModel : AbstractTableModel() {
        var rows: List<RobotRow> = emptyList()
            set(value) {
                if (field == value) return
                val sameRows = sameRobotsInOrder(field, value)
                field = value
                // 状态、会话标题变了只更新行：整表重建会把用户的选中清掉
                if (sameRows && value.isNotEmpty()) fireTableRowsUpdated(0, value.lastIndex) else fireTableDataChanged()
            }

        private val columns =
            listOf(
                "bridge.settings.column.name",
                "bridge.settings.column.channel",
                "bridge.settings.column.status",
                "bridge.settings.column.session"
            )

        override fun getRowCount() = rows.size

        override fun getColumnCount() = columns.size

        override fun getColumnName(column: Int): String = ImuxBundle.message(columns[column])

        override fun getValueAt(
            rowIndex: Int,
            columnIndex: Int,
        ): Any {
            val row = rows[rowIndex]
            return when (columnIndex) {
                NAME_COLUMN -> row.name
                CHANNEL_COLUMN -> row.channel
                STATUS_COLUMN -> row.status
                else -> row.session ?: "—"
            }
        }
    }
}

/** 机器人是否还是同一批、同一顺序（只是状态等内容变了）。 */
internal fun sameRobotsInOrder(
    before: List<RobotRow>,
    after: List<RobotRow>,
): Boolean = before.map { it.id } == after.map { it.id }

/** 表格列顺序：机器人、渠道、状态、当前连接的会话。 */
private const val NAME_COLUMN = 0
private const val CHANNEL_COLUMN = 1
private const val STATUS_COLUMN = 2
