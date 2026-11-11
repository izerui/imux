package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.bridge.BindingService
import com.github.izerui.imux.bridge.BridgeHub
import com.github.izerui.imux.bridge.RobotManager
import com.github.izerui.imux.bridge.SessionTitles
import com.github.izerui.imux.bridge.terminal.bindingSessionAvailable
import com.github.izerui.imux.icons.AgentIcons
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
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
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
                // 会话列之前的各列按内容定宽（各列最多占两成），余下都给会话列；用户拖动列宽时不干预
                override fun doLayout() {
                    if (tableHeader?.resizingColumn == null && columnModel.columnCount == SESSION_COLUMN + 1) {
                        val widths = (0 until SESSION_COLUMN).map { minOf(contentWidth(it), width / 5) }
                        widths.forEachIndexed { index, w -> columnModel.getColumn(index).preferredWidth = w }
                        columnModel.getColumn(SESSION_COLUMN).preferredWidth = maxOf(width - widths.sum(), 0)
                    }
                    super.doLayout()
                }

                /** 表头与各行渲染后的最大宽度，加左右留白。 */
                private fun contentWidth(column: Int): Int {
                    val header = tableHeader?.let { h ->
                        h.defaultRenderer.getTableCellRendererComponent(this, columnModel.getColumn(column).headerValue, false, false, -1, column)
                            .preferredSize.width
                    } ?: 0
                    val cells = (0 until rowCount).maxOfOrNull { row ->
                        prepareRenderer(getCellRenderer(row, column), row, column).preferredSize.width
                    } ?: 0
                    return maxOf(header, cells) + JBUI.scale(16)
                }
            }.apply {
                setShowGrid(false)
                emptyText.text = ImuxBundle.message("bridge.settings.empty")
                columnModel.getColumn(STATUS_COLUMN).cellRenderer = StatusRenderer()
                columnModel.getColumn(AGENT_COLUMN).cellRenderer = AgentRenderer()
                columnModel.getColumn(SESSION_COLUMN).cellRenderer = SessionRenderer()
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
                        // 不用 ×：紧挨着移除的 −，容易看成删除机器人
                        AllIcons.Actions.Exit
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
                            e.presentation.isEnabled = selected(table)?.let { row ->
                                canReconnect(RobotManager.getInstance().statusOf(row.id))
                            } == true
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
                sessionAvailable = ::bindingSessionAvailable,
                titleOf = SessionTitles::of,
                projectOf = SessionTitles::projectName,
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
            // 失败、重试的原因常被列宽截断，悬停看全文
            toolTipText = value?.toString()
        }
    }

    /** 智能体列只画图标，名字放在悬停提示里；空闲时同其他列显示「—」。 */
    private inner class AgentRenderer : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(
            table: JTable,
            value: Any?,
            selected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ) {
            val agent = model.rows.getOrNull(row)?.agent
            // 渲染器各行复用，提示要每次重设
            toolTipText = agent?.displayName
            if (agent == null) {
                append("—")
                return
            }
            icon = AgentIcons.forAgent(agent)
        }
    }

    /** 会话离线时标题后跟灰字「（离线）」：绑定还在，恢复后自动接回。 */
    private inner class SessionRenderer : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(
            table: JTable,
            value: Any?,
            selected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ) {
            append(value?.toString().orEmpty())
            val data = model.rows.getOrNull(row)
            if (data?.sessionOffline == true) {
                append(ImuxBundle.message("bridge.settings.session.offline"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            // 悬停给出工程 · 智能体 · 完整标题，离线时说明怎么恢复
            toolTipText = data?.session?.let { title ->
                val full = listOfNotNull(data.project, data.agent?.displayName, title).joinToString(" · ")
                if (!data.sessionOffline) {
                    full
                } else {
                    "<html>" + StringUtil.escapeXmlEntities(full) + "<br>" +
                        StringUtil.escapeXmlEntities(ImuxBundle.message("bridge.settings.session.offline.tooltip")) + "</html>"
                }
            }
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
                "bridge.settings.column.project",
                "bridge.settings.column.agent",
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
                PROJECT_COLUMN -> row.project ?: "—"
                AGENT_COLUMN -> row.agent?.displayName ?: "—"
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

/** 表格列顺序：机器人、渠道、状态、工程、智能体、当前连接的会话。 */
private const val NAME_COLUMN = 0
private const val CHANNEL_COLUMN = 1
private const val STATUS_COLUMN = 2
private const val PROJECT_COLUMN = 3
private const val AGENT_COLUMN = 4
private const val SESSION_COLUMN = 5
