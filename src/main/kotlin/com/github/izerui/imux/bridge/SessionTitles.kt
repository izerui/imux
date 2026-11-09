package com.github.izerui.imux.bridge

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.monitor.SessionMonitor
import com.github.izerui.imux.terminal.AgentTerminalVirtualFile
import com.github.izerui.imux.terminal.TerminalHost
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话在菜单、设置页、IDE 提示与手机提示里显示的名字，各处只从这里取：
 * 会话标题 → 标签标题 → 会话 id 前 8 位 →“未命名会话”。刚打开、还没有会话记录的 CLI 只有标签标题，
 * 不能把内部的 tabId 露给用户。
 */
/**
 * 署名的三段，各自限长后再拼：工程名、智能体名、会话标题都至少留下一部分，
 * 拼出来的整行（含图标）也在路由的署名上限（100 字）之内，不会被整行截断挤掉后面的段。
 * 工程名前加 📂，智能体名前加 [agentIcon]，会话标题前加 💬，手机上一眼分得清各段。
 */
internal fun sessionLabel(
    project: String,
    agent: String,
    title: String,
    agentIcon: String = "",
): String =
    listOf(
        Triple(project, MAX_LABEL_PROJECT_CHARS, PROJECT_ICON),
        Triple(agent, MAX_LABEL_AGENT_CHARS, agentIcon),
        Triple(title, MAX_LABEL_TITLE_CHARS, TITLE_ICON),
    ).mapNotNull { (text, max, icon) ->
        val shown = text.trim().let { if (it.length > max) it.take(max - 1) + "…" else it }
        shown.takeIf(String::isNotEmpty)?.let { if (icon.isEmpty()) it else "$icon $it" }
    }.joinToString(" · ")

/** 智能体在署名里的图标。 */
internal fun agentIcon(type: AgentType): String =
    when (type) {
        AgentType.CLAUDE -> "✴️"
        AgentType.CODEX -> "⚛️"
        AgentType.PI -> "🚀"
    }

private const val PROJECT_ICON = "📂"
private const val TITLE_ICON = "💬"
private const val MAX_LABEL_PROJECT_CHARS = 30
private const val MAX_LABEL_AGENT_CHARS = 20

/** 三个图标各占 3 个字符（含空格），标题让出这部分，整行仍不超过 100。 */
private const val MAX_LABEL_TITLE_CHARS = 35

object SessionTitles {
    /** 绑定标签最近一次的名字：标签关闭后取不到标签标题，发断开提示时用它。 */
    private val remembered = ConcurrentHashMap<String, String>()

    fun of(binding: Binding): String {
        val project = ProjectManager.getInstance().openProjects.firstOrNull { it.basePath == binding.target.projectPath && !it.isDisposed }
        val sessionId = binding.target.sessionId
        val live =
            project?.let {
                sessionId?.let { id -> SessionMonitor.getInstance(it).model.sessionOf(id)?.title }
                    ?: TerminalHost.getInstance(it).tabTitleOf(binding.target.tabId)
            }
        return live ?: remembered[binding.target.tabId] ?: sessionId?.take(8) ?: ImuxBundle.message("bridge.session.untitled")
    }

    /**
     * 手机回复末尾的署名：工程 · 智能体 · 会话标题，与连接提示的写法一致，同时连着几个会话时据此分辨。
     * 工程已关闭时取项目路径的最后一段。
     */
    fun label(binding: Binding): String {
        val project = ProjectManager.getInstance().openProjects.firstOrNull { it.basePath == binding.target.projectPath && !it.isDisposed }
        val projectName = project?.name ?: binding.target.projectPath.substringAfterLast('/').substringAfterLast('\\')
        return sessionLabel(projectName, binding.target.agentType.displayName, of(binding), agentIcon(binding.target.agentType))
    }

    /** 正在显示的终端标签。 */
    fun of(
        project: Project,
        file: AgentTerminalVirtualFile,
    ): String = file.sessionId?.let { SessionMonitor.getInstance(project).model.sessionOf(it)?.title } ?: file.tabTitle

    /** 标签还开着时记下名字。 */
    fun remember(binding: Binding) {
        remembered[binding.target.tabId] = of(binding)
    }

    fun forget(tabId: String) {
        remembered.remove(tabId)
    }
}
