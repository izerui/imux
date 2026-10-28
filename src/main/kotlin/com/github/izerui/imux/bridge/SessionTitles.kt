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
