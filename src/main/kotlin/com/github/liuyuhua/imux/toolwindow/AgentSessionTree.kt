package com.github.liuyuhua.imux.toolwindow

import com.github.liuyuhua.imux.model.AgentType
import com.github.liuyuhua.imux.session.ListEntry
import com.github.liuyuhua.imux.session.SessionListModel
import com.github.liuyuhua.imux.terminal.TerminalHost
import com.intellij.openapi.project.Project
import com.intellij.ui.treeStructure.Tree
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

private const val PAGE_SIZE = 50

/** 树节点承载的数据。用密封接口避免在渲染与点击处理中做字符串判断。 */
private sealed interface NodeData {
    data class Group(val agentType: AgentType) : NodeData {
        override fun toString(): String = when (agentType) {
            AgentType.CLAUDE -> "Claude Code"
            AgentType.CODEX -> "Codex"
        }
    }

    data class Session(
        val agentType: AgentType,
        val id: String,
        val title: String,
        /** 该会话的终端此刻是否在本 IDE 中活着。仅反映本 IDE 启动的终端。 */
        val running: Boolean,
    ) : NodeData {
        // 运行中的会话加圆点前缀，与「已跑完、点击才 resume」的会话区分开
        override fun toString(): String = if (running) "● $title" else title
    }

    data class PendingSession(val agentType: AgentType, val key: String) : NodeData {
        override fun toString(): String = "新会话（等待首条消息）"
    }

    data class ShowMore(val agentType: AgentType) : NodeData {
        override fun toString(): String = "显示更多…"
    }
}

class AgentSessionTree(
    private val project: Project,
    private val model: SessionListModel,
) {

    private val root = DefaultMutableTreeNode("root")
    private val treeModel = DefaultTreeModel(root)
    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
    }

    /** 每个分组当前展示的条数上限，点「显示更多」后递增。 */
    private val limits = mutableMapOf(AgentType.CLAUDE to PAGE_SIZE, AgentType.CODEX to PAGE_SIZE)

    init {
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount != 2) return
                handleActivate(tree.getPathForLocation(event.x, event.y) ?: return)
            }
        })
        model.addListener { reload() }
    }

    fun component(): JComponent = tree

    fun reload() {
        val expanded = expandedGroups()
        root.removeAllChildren()
        AgentType.entries.forEach { addGroup(it) }
        treeModel.reload()
        restoreExpansion(expanded)
    }

    private fun addGroup(agentType: AgentType) {
        val groupNode = DefaultMutableTreeNode(NodeData.Group(agentType))
        root.add(groupNode)

        val entries = model.entries(agentType)
        val limit = limits.getValue(agentType)

        entries.take(limit).forEach { entry ->
            val data = when (entry) {
                is ListEntry.Existing -> NodeData.Session(
                    agentType,
                    entry.session.id,
                    entry.session.title,
                    running = TerminalHost.getInstance(project).isRunning(entry.session.id),
                )

                is ListEntry.Pending -> NodeData.PendingSession(agentType, entry.pending.key)
            }
            groupNode.add(DefaultMutableTreeNode(data))
        }

        if (entries.size > limit) {
            groupNode.add(DefaultMutableTreeNode(NodeData.ShowMore(agentType)))
        }
    }

    private fun handleActivate(path: TreePath) {
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        when (val data = node.userObject) {
            is NodeData.Session -> {
                TerminalHost.getInstance(project).openResume(data.agentType, data.id, data.title)
                // 打开终端不会触发扫描，需显式重绘以让「运行中」标识立即生效
                reload()
            }

            is NodeData.PendingSession -> {
                val boundId = model.boundIdFor(data.key)
                if (boundId != null) {
                    TerminalHost.getInstance(project)
                        .openResume(data.agentType, boundId, "会话 ${boundId.take(8)}")
                    reload()
                }
                // 未绑定时无操作：终端已在新建时打开，双击无额外语义
            }

            is NodeData.ShowMore -> {
                limits[data.agentType] = limits.getValue(data.agentType) + PAGE_SIZE
                reload()
            }

            else -> Unit
        }
    }

    private fun groupNodes(): List<DefaultMutableTreeNode> =
        root.children().toList().filterIsInstance<DefaultMutableTreeNode>()

    private fun expandedGroups(): Set<AgentType> = groupNodes()
        .filter { tree.isExpanded(TreePath(it.path)) }
        .mapNotNull { (it.userObject as? NodeData.Group)?.agentType }
        .toSet()

    private fun restoreExpansion(expanded: Set<AgentType>) {
        groupNodes()
            .filter { (it.userObject as? NodeData.Group)?.agentType in expanded }
            .forEach { tree.expandPath(TreePath(it.path)) }
    }
}
