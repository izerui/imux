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
import com.intellij.terminal.frontend.view.TerminalViewSessionState
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

    /**
     * 启动一个全新会话（不带 resume）。
     *
     * [key] 必须由调用方给出，且必须与 SessionListModel 的 pending key 一致——
     * 会话落盘后 [rebindKey] 要靠它把终端迁到真实会话 id 下。
     * 若两边各生成各的 key，迁移会静默失败，之后点击该会话就会重开一个
     * `--resume` 终端，与仍在运行的原终端抢同一个会话，CLI 会报
     * 「currently running as a background agent」。
     */
    fun openNew(agentType: AgentType, key: String, tabTitle: String) {
        open(key, newCommand(agentType), tabTitle)
    }

    /** 打开一个已有会话；若其终端已在运行则切到该标签页而不重启。 */
    fun openResume(agentType: AgentType, sessionId: String, tabTitle: String) {
        open(sessionId, resumeCommand(agentType, sessionId), tabTitle)
    }

    /**
     * 新建的会话在 CLI 落盘后才拿到真实 id，此时要把终端从 openNew 的合成 key
     * 迁到真实 id 下。不做这一步会有两个后果：运行中标识查不到；再次点击该会话
     * 会以真实 id 找不到已有终端，从而重开一个。
     *
     * 虚拟文件实例必须沿用同一个——它是标签页的身份，换实例会开出新标签页。
     */
    fun rebindKey(oldKey: String, newKey: String, newTitle: String) {
        val view = views.remove(oldKey)
        if (view == null) {
            // 不要静默返回：迁移失败意味着之后点击该会话会重开一个 --resume 终端，
            // 与仍在运行的原终端抢同一个会话。曾因两边 key 不一致而静默失败过。
            LOG.warn("换 key 失败：找不到 key=$oldKey 的终端，目标 $newKey。已有 key=${views.keys}")
            return
        }
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
        discardIfTerminated(key)
        val file = files.getOrPut(key) {
            val view = views.getOrPut(key) { createView(command, tabTitle) }
            AgentTerminalVirtualFile(tabTitle, view, key)
        }
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    /**
     * 丢弃已终止的终端，让本次点击重新起一个。
     *
     * 否则点击一个 CLI 已退出的会话，只会切到一个死掉的标签页，什么也不会发生。
     */
    private fun discardIfTerminated(key: String) {
        val view = views[key] ?: return
        if (view.sessionState.value !is TerminalViewSessionState.Terminated) return

        views.remove(key)
        files.remove(key)?.let { FileEditorManager.getInstance(project).closeFile(it) }
        view.coroutineScope.cancel()
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
