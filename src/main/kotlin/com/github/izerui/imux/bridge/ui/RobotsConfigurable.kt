package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.bridge.BridgeHub
import com.github.izerui.imux.bridge.BridgeLifecycle
import com.github.izerui.imux.bridge.BridgeSettings
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.robot.RobotManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import kotlinx.coroutines.launch
import javax.swing.Timer
import javax.swing.table.AbstractTableModel

/** 设置 › 工具 › Imux › 通讯机器人。机器人管理与会话管理分开：这里只管机器人。 */
class RobotsConfigurable : BoundConfigurable(ImuxBundle.message("bridge.settings.displayName")) {
    private val model = RobotTableModel()
    private var timer: Timer? = null
    private var table: JBTable? = null

    override fun createPanel(): DialogPanel {
        val settings = BridgeSettings.getInstance()
        val table =
            JBTable(model).apply {
                setShowGrid(false)
                emptyText.text = ImuxBundle.message("bridge.settings.empty")
            }.also { this.table = it }
        val decorated =
            ToolbarDecorator.createDecorator(table)
                .setAddAction {
                    AddRobotDialog(null).show()
                    reset()
                    refresh()
                }
                .setRemoveAction { remove(table) }
                .setEditAction { rename(table) }
                .addExtraAction(
                    object : com.intellij.openapi.project.DumbAwareAction(ImuxBundle.message("bridge.settings.unbind"), null, AllIcons.Actions.Cancel) {
                        override fun getActionUpdateThread() = ActionUpdateThread.EDT

                        override fun update(e: AnActionEvent) {
                            e.presentation.isEnabled = selected(table)?.session != null
                        }

                        override fun actionPerformed(e: AnActionEvent) = unbind(table)
                    },
                ).addExtraAction(
                    object : com.intellij.openapi.project.DumbAwareAction(ImuxBundle.message("bridge.settings.reconnect"), null, AllIcons.Actions.Refresh) {
                        override fun getActionUpdateThread() = ActionUpdateThread.EDT

                        override fun update(e: AnActionEvent) {
                            e.presentation.isEnabled = selected(table) != null && BridgeSettings.getInstance().state.enabled
                        }

                        override fun actionPerformed(e: AnActionEvent) = reconnect(table)
                    },
                ).addExtraAction(
                    object : com.intellij.openapi.project.DumbAwareAction(ImuxBundle.message("bridge.settings.pair"), null, AllIcons.General.User) {
                        override fun getActionUpdateThread() = ActionUpdateThread.EDT

                        override fun update(e: AnActionEvent) {
                            e.presentation.isEnabled = selected(table)?.let { RobotManager.getInstance().robot(it.id)?.owner() == null } == true
                        }

                        override fun actionPerformed(e: AnActionEvent) = pair(table)
                    },
                ).createPanel()
        refresh()
        // 设置页可能多次创建面板：先停掉旧定时器，避免泄漏
        timer?.stop()
        timer = Timer(1_000) { refresh() }.also { it.start() }
        return panel {
            row {
                checkBox(ImuxBundle.message("bridge.settings.enable"))
                    .bindSelected(settings.state::enabled)
                    .comment(ImuxBundle.message("bridge.settings.enable.comment"))
            }
            row { cell(decorated).align(Align.FILL) }.resizableRow()
            row { comment(ImuxBundle.message("bridge.settings.note")) }
        }
    }

    override fun apply() {
        val before = BridgeSettings.getInstance().state.enabled
        super.apply()
        val after = BridgeSettings.getInstance().state.enabled
        if (before != after) BridgeLifecycle.applyEnabled(after)
    }

    override fun disposeUIResources() {
        timer?.stop()
        timer = null
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
                bindingOf = { hub.bindings.bindingOf(RobotId(it)) },
                titleOf = BridgeLifecycle::sessionTitle,
                labels = BundleRowLabels,
            )
        // 增删机器人会整表重建：按 id 把选中放回去
        if (table != null && selectedId != null && selected(table)?.id != selectedId) {
            model.rows.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 }?.let { table.setRowSelectionInterval(it, it) }
        }
    }

    private fun selected(table: JBTable): RobotRow? = table.selectedRow.takeIf { it >= 0 }?.let { model.rows.getOrNull(it) }

    private fun remove(table: JBTable) {
        val row = selected(table) ?: return
        val ok = Messages.showOkCancelDialog(ImuxBundle.message("bridge.settings.remove.confirm", row.name), ImuxBundle.message("bridge.settings.displayName"), Messages.getOkButton(), Messages.getCancelButton(), Messages.getWarningIcon())
        if (ok != Messages.OK) return
        val manager = RobotManager.getInstance()
        manager.launch { manager.remove(row.id) }
    }

    private fun rename(table: JBTable) {
        val row = selected(table) ?: return
        val name = Messages.showInputDialog(ImuxBundle.message("bridge.settings.rename"), ImuxBundle.message("bridge.settings.displayName"), null, row.name, null)
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
        // 配对入口可能自动开启桥接，与添加入口一样同步开关状态
        reset()
        refresh()
    }

    private fun unbind(table: JBTable) {
        val row = selected(table) ?: return
        val hub = BridgeHub.getInstance()
        val binding = hub.bindings.unbind(RobotId(row.id)) ?: return
        RobotManager.getInstance().syncBindings()
        hub.launch { hub.router.onReleased(binding, BridgeLifecycle.sessionTitle(binding)) }
        refresh()
    }

    private class RobotTableModel : AbstractTableModel() {
        var rows: List<RobotRow> = emptyList()
            set(value) {
                if (field == value) return
                val sameRows = sameRobotsInOrder(field, value)
                field = value
                // 每秒都会刷新状态：只是内容变了就只更新行，整表重建会把用户的选中清掉
                if (sameRows && value.isNotEmpty()) fireTableRowsUpdated(0, value.lastIndex) else fireTableDataChanged()
            }

        private val columns =
            listOf("bridge.settings.column.name", "bridge.settings.column.channel", "bridge.settings.column.status", "bridge.settings.column.session")

        override fun getRowCount() = rows.size

        override fun getColumnCount() = columns.size

        override fun getColumnName(column: Int): String = ImuxBundle.message(columns[column])

        override fun getValueAt(
            rowIndex: Int,
            columnIndex: Int,
        ): Any {
            val row = rows[rowIndex]
            return when (columnIndex) {
                0 -> row.name
                1 -> row.channel
                2 -> row.status
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
