package com.github.liuyuhua.imux.terminal

import com.github.liuyuhua.imux.model.AgentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
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

    private var diagnosticsInstalled = false

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

    fun isRunning(key: String): Boolean = views.containsKey(key)

    private fun open(key: String, command: List<String>, tabTitle: String) {
        installDiagnostics()
        val file = files.getOrPut(key) {
            val view = views.getOrPut(key) { createView(command, tabTitle) }
            AgentTerminalVirtualFile(tabTitle, view, key)
        }

        val view = file.terminalView
        LOG.warn(
            "[imux诊断] 准备打开 key=$key" +
                " component=${runCatching { view.component?.javaClass?.simpleName }.getOrElse { "抛错:$it" }}" +
                " preferredFocusable=${runCatching { view.preferredFocusableComponent?.javaClass?.simpleName }.getOrElse { "抛错:$it" }}" +
                " sessionState=${runCatching { view.sessionState.value.toString() }.getOrElse { "抛错:$it" }}",
        )

        FileEditorManager.getInstance(project).openFile(file, true)

        LOG.warn("[imux诊断] openFile 返回，当前该文件的编辑器数=" +
            FileEditorManager.getInstance(project).getEditors(file).size)
    }

    /**
     * 临时诊断：定位「标签页打开后立刻关闭」的元凶。
     * fileClosed 处带上 Throwable 以拿到**关闭方的调用栈**——这是唯一能直接指认元凶的证据。
     * 问题定位后应整体移除。
     */
    private fun installDiagnostics() {
        if (diagnosticsInstalled) return
        diagnosticsInstalled = true
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    if (file is AgentTerminalVirtualFile) LOG.warn("[imux诊断] fileOpened ${file.name}")
                }

                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    if (file is AgentTerminalVirtualFile) {
                        LOG.warn("[imux诊断] fileClosed ${file.name}", Throwable("关闭方调用栈"))
                    }
                }
            },
        )
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
