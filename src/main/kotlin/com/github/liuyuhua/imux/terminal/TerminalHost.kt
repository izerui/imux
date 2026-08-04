package com.github.liuyuhua.imux.terminal

import com.github.liuyuhua.imux.model.AgentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.view.TerminalView
import kotlinx.coroutines.cancel

/**
 * 拥有所有活着的终端 view，按 key 索引。
 *
 * 所有权规则：view 归本服务所有。关闭 editor tab 不取消它的 CoroutineScope，
 * 因此关标签页不会中断会话；再次打开时把同一个 view 挂回新的 tab。
 */
@Service(Service.Level.PROJECT)
class TerminalHost(private val project: Project) : Disposable {

    private val views = mutableMapOf<String, TerminalView>()

    /**
     * 每个 key 对应的虚拟文件实例必须缓存并复用。
     * FileEditorManager 以 VirtualFile 实例为标签页身份：传同一个实例是「切到该标签页」，
     * 每次新建实例则会不断开出重复标签页。
     */
    private val files = mutableMapOf<String, AgentTerminalVirtualFile>()

    /** 启动一个全新会话（不带 resume），返回内部 key。 */
    fun openNew(agentType: AgentType, tabTitle: String): String {
        val key = "new-${agentType.name}-${System.nanoTime()}"
        open(key, newCommand(agentType), tabTitle)
        return key
    }

    /** 打开一个已有会话；若其终端已在运行则切到该标签页而不重启。 */
    fun openResume(agentType: AgentType, sessionId: String, tabTitle: String) {
        open(sessionId, resumeCommand(agentType, sessionId), tabTitle)
    }

    /**
     * 会话终端的三态。
     *
     * 之所以要区分后两者：关闭标签页刻意不销毁终端（见类注释的所有权规则），
     * 进程仍在后台运行。若只用「开/关」两态表示，这些进程就从界面上消失了，
     * 用户会不知不觉积累一堆隐形的 CLI。
     */
    enum class RunState {
        /** 标签页开着 */
        TAB_OPEN,

        /** 标签页已关，但进程仍在后台运行 */
        BACKGROUND,

        /** 未运行 */
        NONE,
    }

    fun stateOf(key: String): RunState {
        if (!views.containsKey(key)) return RunState.NONE
        val file = files[key] ?: return RunState.BACKGROUND
        return if (FileEditorManager.getInstance(project).isFileOpen(file)) {
            RunState.TAB_OPEN
        } else {
            RunState.BACKGROUND
        }
    }

    /**
     * 新建的会话在 CLI 落盘后才拿到真实 id，此时要把终端从 openNew 的合成 key
     * 迁到真实 id 下。不做这一步会有两个后果：运行中标识查不到；再次点击该会话
     * 会以真实 id 找不到已有终端，从而重开一个。
     *
     * 虚拟文件实例必须沿用同一个——它是标签页的身份，换实例会开出新标签页。
     */
    fun rebindKey(oldKey: String, newKey: String, newTitle: String) {
        val view = views.remove(oldKey) ?: return
        views[newKey] = view

        files.remove(oldKey)?.let { file ->
            files[newKey] = file
            renameTab(file, newTitle)
        }
    }

    private fun renameTab(file: AgentTerminalVirtualFile, newTitle: String) {
        if (file.name == newTitle) return
        runCatching {
            WriteAction.run<Exception> { file.rename(this, newTitle) }
        }.onFailure { LOG.warn("重命名标签页失败：${file.name} -> $newTitle", it) }
    }

    private fun open(key: String, command: List<String>, tabTitle: String) {
        val file = files.getOrPut(key) {
            val view = views.getOrPut(key) { createView(command, tabTitle) }
            AgentTerminalVirtualFile(tabTitle, view, key)
        }
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    private fun createView(command: List<String>, tabTitle: String): TerminalView =
        TerminalToolWindowTabsManager.getInstance(project)
            .createTabBuilder()
            .workingDirectory(projectPath())
            .shellCommand(command)
            .tabName(tabTitle)
            .shouldAddToToolWindow(false)     // 由本服务安置，不进工具窗口
            .closeOnProcessTermination(false) // 进程结束后保留 UI 供回看
            .createTab()
            .view

    private fun projectPath(): String =
        project.basePath ?: System.getProperty("user.home")

    private fun newCommand(agentType: AgentType): List<String> = when (agentType) {
        AgentType.CLAUDE -> listOf("claude")
        AgentType.CODEX -> listOf("codex")
    }

    private fun resumeCommand(agentType: AgentType, sessionId: String): List<String> =
        when (agentType) {
            AgentType.CLAUDE -> listOf("claude", "--resume", sessionId)
            AgentType.CODEX -> listOf("codex", "resume", sessionId)
        }

    /**
     * 项目关闭时终结所有会话。这是唯一该杀进程的地方。
     *
     * 待实机验证：若 tabs manager 会兜底释放 scope，此处的显式 cancel 应改为无操作以免重复取消。
     */
    override fun dispose() {
        views.values.forEach { it.coroutineScope.cancel() }
        views.clear()
        files.clear()
    }

    companion object {
        private val LOG = logger<TerminalHost>()

        fun getInstance(project: Project): TerminalHost = project.getService(TerminalHost::class.java)
    }
}
