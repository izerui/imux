package com.github.izerui.imux.bridge

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.bridge.core.Binding
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
 * 只在最前面留一个 💬，不给每段都加图标，在手机上读着像一句淡淡的注脚，不跟正文抢眼。
 */
internal fun sessionLabel(
    project: String,
    agent: String,
    title: String,
): String =
    listOf(
        project to MAX_LABEL_PROJECT_CHARS,
        agent to MAX_LABEL_AGENT_CHARS,
        title to MAX_LABEL_TITLE_CHARS,
    ).mapNotNull { (text, max) ->
        text.trim().let { if (it.length > max) it.take(max - 1) + "…" else it }.takeIf(String::isNotEmpty)
    }.joinToString(" · ")
        .let { if (it.isEmpty()) it else "$TITLE_ICON $it" }

/** 署名与「已连接」「已断开会话」提示打头的图标。 */
internal const val TITLE_ICON = "💬"
private const val MAX_LABEL_PROJECT_CHARS = 30
private const val MAX_LABEL_AGENT_CHARS = 20

/** 图标 3（含空格）+ 工程 30 + 智能体 20 + 两个分隔点 6，标题取 41，整行正好不超过 100。 */
private const val MAX_LABEL_TITLE_CHARS = 41

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
     * 手机回复末尾的署名：💬 工程 · 智能体 · 会话标题，同时连着几个会话时据此分辨。
     */
    fun label(binding: Binding): String = sessionLabel(projectName(binding), binding.target.agentType.displayName, of(binding))

    /** 会话所在工程的名字；工程已关闭时取项目路径的最后一段。 */
    fun projectName(binding: Binding): String {
        val project = ProjectManager.getInstance().openProjects.firstOrNull { it.basePath == binding.target.projectPath && !it.isDisposed }
        return project?.name ?: binding.target.projectPath.substringAfterLast('/').substringAfterLast('\\')
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
