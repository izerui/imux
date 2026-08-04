package com.github.izerui.imux.toolwindow

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.session.ClaudeRuntimeSession
import com.github.izerui.imux.session.ListEntry
import com.github.izerui.imux.session.SessionListModel
import com.github.izerui.imux.terminal.TerminalHost
import com.github.izerui.imux.turn.TurnNotifier
import com.intellij.openapi.project.Project
import com.intellij.ui.ClickListener
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import java.awt.event.KeyEvent
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

/**
 * 返回 y 坐标所在的整行，而不是只命中文字与图标的区域。
 *
 * [JTree.getPathForLocation] 会把节点右侧空白视为未命中，标题长短不同就会导致
 * 同一横坐标有时能点、有时不能点。这里只按行的纵向边界判断，同时排除末行下方空白。
 */
internal fun JTree.pathForRowAt(y: Int): TreePath? {
    val path = getClosestPathForLocation(0, y) ?: return null
    val bounds = getPathBounds(path) ?: return null
    return path.takeIf { y >= bounds.y && y < bounds.y + bounds.height }
}

/**
 * 要让第 [index] 条可见，分页上限至少得是多少。
 *
 * 只增不减：用户手动点过「显示更多」把上限撑大了，定位一条靠前的会话不该把它缩回去。
 */
internal fun limitCovering(index: Int, current: Int, pageSize: Int): Int =
    if (index < current) current else ((index / pageSize) + 1) * pageSize

/** 未读会话的前置标记。 */
private const val UNREAD_MARK = "● "

/** 正在执行的会话的前置标记，与「有新结果待看」的蓝点区分开。 */
private const val RUNNING_MARK = "▶ "

/** 用 IDE 的强调蓝，深浅色主题各给一个值。 */
private val UNREAD_MARK_ATTRIBUTES =
    SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(0x3592C4, 0x548AF7))

/** 执行中用绿色，与未读的蓝色区分。 */
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
        /** CLI 是否正在执行这一轮 */
        val running: Boolean,
        /** 是否有新结果待看 */
        val unread: Boolean,
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
     * 只服务于 resume 前的忙碌预检——那里要看 kind 与 status 两个字段。
     * 渲染不看这份快照，看已经合成好的 [runningIds]：codex 没有运行态文件，
     * 它的执行中状态来自会话文件，两者必须先合并再渲染。
     */
    private var runtime: Map<String, ClaudeRuntimeSession> = emptyMap()

    /** 此刻正在执行的会话 id，见 [com.github.izerui.imux.turn.RunningSessions]。 */
    private var runningIds: Set<String> = emptySet()

    fun updateStatus(snapshot: Map<String, ClaudeRuntimeSession>, running: Set<String>) {
        runtime = snapshot
        runningIds = running
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

                val session = data as? NodeData.Session

                // 正在跑优先于未读：此刻的处境比「上一轮跑完了」更要紧
                when {
                    session?.running == true -> {
                        append(RUNNING_MARK, RUNNING_MARK_ATTRIBUTES)
                        append(text, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }

                    session?.unread == true -> {
                        // 前置圆点比单纯加粗显眼得多，扫一眼列表就能定位
                        append(UNREAD_MARK, UNREAD_MARK_ATTRIBUTES)
                        append(text, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
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
        // 用平台的 ClickListener 而非裸的 mouseClicked：
        // mouseClicked 要求按下与抬起严格同点，手指稍有位移就不触发，
        // 表现就是「点了没反应，要点好几次」。ClickListener 容忍这点抖动。
        object : ClickListener() {
            override fun onClick(event: MouseEvent, clickCount: Int): Boolean {
                if (clickCount != 1 || !SwingUtilities.isLeftMouseButton(event)) return false
                val path = tree.pathForRowAt(event.y) ?: return false
                handleActivate(path)
                return true
            }
        }.installOn(tree)

        // 键盘可达：选中后回车等同于单击
        tree.registerKeyboardAction(
            { tree.selectionPath?.let { handleActivate(it) } },
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            JComponent.WHEN_FOCUSED,
        )

        model.addListener { reload() }
    }

    fun component(): JComponent = tree

    /**
     * 上一次真正渲染出来的内容签名。
     *
     * 树每重建一次，按下与抬起之间若插入了重建，点击就会落空——用户感觉「点不动」。
     * 而扫描每 3 秒一轮、会话文件被写就变，重建其实非常频繁。
     * 因此只在渲染结果确有变化时才重建。
     */
    private var renderedContent: Map<AgentType, List<NodeData>>? = null

    fun reload() {
        applyNewBindings()

        // 用数据类的结构相等比对，标题、时间、标记任一变化都算变化，
        // 拼字符串容易漏字段
        val content = AgentType.entries.associateWith { nodesFor(it) }
        if (content == renderedContent) return
        renderedContent = content

        val expanded = expandedGroups()
        val selected = selectedSessionId()

        root.removeAllChildren()
        AgentType.entries.forEach { type -> addGroup(type, content.getValue(type)) }
        treeModel.reload()

        restoreExpansion(expanded)
        restoreSelection(selected)
    }

    private fun selectedSessionId(): String? =
        ((tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject
            as? NodeData.Session)?.id

    private fun restoreSelection(sessionId: String?) {
        if (sessionId == null) return
        findNode(sessionId)?.let { tree.selectionPath = TreePath(it.path) }
    }

    /**
     * 在列表中选中 [key] 对应的会话，必要时展开分组、加载更多。
     *
     * 供「切到某个终端标签页」时反向定位，与点列表打开标签页构成双向联动。
     * 刻意不抢焦点、不打开工具窗口：切标签页多半是为了回终端里敲字，
     * 光标被列表偷走就成了打扰；工具窗口没开时静默把选中态备好即可。
     *
     * [key] 可能是会话真实 id，也可能是新建会话尚未落盘时的合成 key——
     * 标签页就是以后者登记的，两种都要能定位。
     */
    fun revealSession(key: String) {
        val located = locate(key) ?: return
        val (agentType, index) = located

        val raised = limitCovering(index, limits.getValue(agentType), PAGE_SIZE)
        if (raised != limits.getValue(agentType)) {
            limits[agentType] = raised
            reload()
        }

        val node = findNode(key) ?: return
        val path = TreePath(node.path)
        // 分组折叠时 setSelectionPath 不会自己展开，选中会看不见
        node.parent?.let { tree.expandPath(TreePath((it as DefaultMutableTreeNode).path)) }
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }

    /** 找出 [key] 属于哪个分组、在该分组条目里排第几。找不到返回 null。 */
    private fun locate(key: String): Pair<AgentType, Int>? {
        AgentType.entries.forEach { agentType ->
            val index = model.entries(agentType).indexOfFirst { entry ->
                when (entry) {
                    is ListEntry.Existing -> entry.session.id == key
                    is ListEntry.Pending -> entry.pending.key == key
                }
            }
            if (index >= 0) return agentType to index
        }
        return null
    }

    private fun findNode(key: String): DefaultMutableTreeNode? = groupNodes()
        .flatMap { it.children().toList().filterIsInstance<DefaultMutableTreeNode>() }
        .firstOrNull { node ->
            when (val data = node.userObject) {
                is NodeData.Session -> data.id == key
                is NodeData.PendingSession -> data.key == key
                else -> false
            }
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

    private fun addGroup(agentType: AgentType, nodes: List<NodeData>) {
        val groupNode = DefaultMutableTreeNode(NodeData.Group(agentType))
        root.add(groupNode)
        nodes.forEach { groupNode.add(DefaultMutableTreeNode(it)) }
    }

    /** 算出该分组要渲染的节点。与签名计算共用，避免两处逻辑走样。 */
    private fun nodesFor(agentType: AgentType): List<NodeData> {
        val entries = model.entries(agentType)
        val limit = limits.getValue(agentType)
        val openTabs = TerminalHost.getInstance(project).openTabKeys()

        val nodes = entries.take(limit).map { entry ->
            when (entry) {
                is ListEntry.Existing -> NodeData.Session(
                    agentType,
                    entry.session.id,
                    entry.session.title,
                    relativeTime = RelativeTime.format(
                        entry.session.lastActiveAt,
                        Instant.now(),
                        ZoneId.systemDefault(),
                    ),
                    // 与标签页取交集：只关心自己正在用的会话。IDE 之外启动的
                    // claude 进程在运行态文件里可见，但不该出现在列表标记上。
                    // 交集在此实时求，而非由外部预先算好——否则关标签页后标记
                    // 要等下一轮才消失。
                    running = entry.session.id in runningIds && entry.session.id in openTabs,
                    unread = entry.session.id in unread,
                )

                is ListEntry.Pending -> NodeData.PendingSession(agentType, entry.pending.key)
            }
        }

        return if (entries.size > limit) nodes + NodeData.ShowMore(agentType) else nodes
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
