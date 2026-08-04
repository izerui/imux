package com.github.liuyuhua.imux.toolwindow

import com.github.liuyuhua.imux.model.AgentType
import com.github.liuyuhua.imux.session.ClaudeRuntimeSession
import com.github.liuyuhua.imux.session.ListEntry
import com.github.liuyuhua.imux.session.SessionListModel
import com.github.liuyuhua.imux.terminal.TerminalHost
import com.github.liuyuhua.imux.turn.TurnNotifier
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import java.time.Instant
import java.time.ZoneId
import javax.swing.tree.TreeSelectionModel

private const val PAGE_SIZE = 50

/** 未读会话的前置标记。 */
private const val UNREAD_MARK = "● "

/** 运行中会话的前置标记，与「有新结果待看」的蓝点区分开。 */
private const val RUNNING_MARK = "▶ "

/** 用 IDE 的强调蓝，深浅色主题各给一个值。 */
private val UNREAD_MARK_ATTRIBUTES =
    SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(0x3592C4, 0x548AF7))

/** 运行中用绿色，与未读的蓝色区分。 */
private val RUNNING_MARK_ATTRIBUTES =
    SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor(0x369650, 0x57965C))

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
        /** 已格式化的「多久以前」，渲染时灰色显示在标题右侧。 */
        val relativeTime: String,
    ) : NodeData {
        override fun toString(): String = title
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
    /** 轮次刚完成、用户还没回来看的会话 id。 */
    private val unread = mutableSetOf<String>()

    /**
     * 当前活着的 Claude 进程，按会话 id 索引。由外部每轮轮询后灌入。
     *
     * 这来自 CLI 自己写的运行态文件，而非我们对终端的记账——后者感知不到
     * IDE 之外启动的会话，也分不出后台 agent。
     */
    private var runtime: Map<String, ClaudeRuntimeSession> = emptyMap()

    fun updateRuntime(snapshot: Map<String, ClaudeRuntimeSession>) {
        if (snapshot.keys == runtime.keys) {
            runtime = snapshot
            return
        }
        runtime = snapshot
        reload()
    }

    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree,
                value: Any?,
                selected: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean,
            ) {
                val data = (value as? DefaultMutableTreeNode)?.userObject
                val text = data?.toString() ?: ""

                val sessionId = (data as? NodeData.Session)?.id

                when {
                    sessionId != null && sessionId in unread -> {
                        // 前置圆点比单纯加粗显眼得多，扫一眼列表就能定位
                        append(UNREAD_MARK, UNREAD_MARK_ATTRIBUTES)
                        append(text, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    }

                    sessionId != null && runtime.containsKey(sessionId) -> {
                        append(RUNNING_MARK, RUNNING_MARK_ATTRIBUTES)
                        append(text, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }

                    else -> append(text, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }

                if (data is NodeData.Session) {
                    append("  ${data.relativeTime}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }
    }

    fun markUnread(sessionId: String) {
        if (unread.add(sessionId)) reload()
    }

    fun clearUnread(sessionId: String) {
        // 用户已经看到该会话，挂着的提醒气泡也该一并撤掉
        TurnNotifier.dismiss(sessionId)
        if (unread.remove(sessionId)) reload()
    }

    /** 每个分组当前展示的条数上限，点「显示更多」后递增。 */
    private val limits = mutableMapOf(AgentType.CLAUDE to PAGE_SIZE, AgentType.CODEX to PAGE_SIZE)

    init {
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                // 单击即触发。只认第一次点击：双击时 mouseClicked 会以 clickCount=1、2
                // 各触发一次，若不过滤，「显示更多」会一次加两页。
                if (event.clickCount != 1 || !SwingUtilities.isLeftMouseButton(event)) return
                // 点在展开箭头或空白处时 getPathForLocation 返回 null，天然不触发
                handleActivate(tree.getPathForLocation(event.x, event.y) ?: return)
            }
        })

        // 键盘可达：选中后回车等同于单击
        tree.registerKeyboardAction(
            { tree.selectionPath?.let { handleActivate(it) } },
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            JComponent.WHEN_FOCUSED,
        )

        model.addListener { reload() }
    }

    fun component(): JComponent = tree

    fun reload() {
        applyNewBindings()
        val expanded = expandedGroups()
        root.removeAllChildren()
        AgentType.entries.forEach { addGroup(it) }
        treeModel.reload()
        restoreExpansion(expanded)
    }

    /**
     * 把刚发生的绑定告知终端宿主：新建会话的终端原本记在合成 key 下，
     * 拿到真实 id 后必须迁过去，否则运行中标识失效、再点会重开一个终端。
     */
    private fun applyNewBindings() {
        val bindings = model.drainNewBindings()
        if (bindings.isEmpty()) return

        val titles = AgentType.entries
            .flatMap { model.entries(it) }
            .filterIsInstance<ListEntry.Existing>()
            .associate { it.session.id to it.session.title }

        val host = TerminalHost.getInstance(project)
        bindings.forEach { (pendingKey, sessionId) ->
            host.rebindKey(pendingKey, sessionId, titles[sessionId] ?: "会话 ${sessionId.take(8)}")
            // 新建的会话直到落盘才有文件路径，绑定这一刻才能纳入监控
            model.sessionOf(sessionId)?.let {
                host.startWatchingTurn(sessionId, it.agentType, it.filePath)
            }
        }
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
                    relativeTime = RelativeTime.format(
                        entry.session.lastActiveAt,
                        Instant.now(),
                        ZoneId.systemDefault(),
                    ),
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
                // 预检：正在后台跑的会话不能 resume，CLI 会拒绝并报
                // 「currently running as a background agent」。提前拦住并说明原因，
                // 比让用户在终端里撞一脸报错好。
                val running = runtime[data.id]
                if (running != null && running.isBackground && running.isBusy) {
                    TurnNotifier.notifyBusy(project, data.title)
                    return
                }

                val host = TerminalHost.getInstance(project)
                host.openResume(data.agentType, data.id, data.title)
                model.sessionOf(data.id)?.let {
                    host.startWatchingTurn(data.id, data.agentType, it.filePath)
                }
                clearUnread(data.id)
            }

            is NodeData.PendingSession -> {
                val boundId = model.boundIdFor(data.key)
                if (boundId != null) {
                    TerminalHost.getInstance(project)
                        .openResume(data.agentType, boundId, "会话 ${boundId.take(8)}")
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
