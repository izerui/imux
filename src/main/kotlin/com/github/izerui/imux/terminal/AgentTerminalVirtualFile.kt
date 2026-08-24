package com.github.izerui.imux.terminal

import com.github.izerui.imux.model.AgentType
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.ex.FakeFileType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.testFramework.LightVirtualFile

/**
 * 承载一个终端会话的虚拟文件。结构参照 JetBrains 自己的
 * `com.intellij.terminal.frontend.editor.TerminalViewVirtualFile`。
 *
 * 平台的 `TerminalViewVirtualFile` / `TerminalViewFileEditor` /
 * `TerminalViewFileEditorProvider` 是 Kotlin `internal`，插件源码无法引用；因此 imux
 * 自己实现虚拟文件、编辑器和 provider，并由 [TerminalHost] 管理 [TerminalView] 的生命周期。
 *
 * @param sessionKey 该终端当前的记账 key。新建会话落盘前可能是 `pending-*`，绑定后会
 *   迁移为真实会话 id；用户在终端里 `/clear` 或 `/new` 后也会跟着迁移。
 * @param sessionId 可供用户复制的真实会话 id；尚未落盘且无法预分配时为 null。
 * @param tabId 终端自身的身份，一生不变。以 [com.github.izerui.imux.session.IMUX_TAB_ENV]
 *   注入给 CLI 进程，是把进程认回终端、进而发现 sessionKey 漂移的唯一依据。
 */
class AgentTerminalVirtualFile(
    name: String,
    val terminalView: TerminalView,
    var sessionKey: String,
    val agentType: AgentType,
    val tabId: String,
    var sessionId: String? = null,
) : LightVirtualFile(name, AgentTerminalFileType, "") {
    var tabTitle: String = name

    val displayName: String
        get() = "${agentType.cli}: $tabTitle"

    /**
     * 编辑器标签左下角的状态栏提示直接读取 VirtualFile.name，而不是
     * EditorTabTitleProvider，因此这里用 Agent 类型前缀标明这是哪一种会话。
     */
    override fun getName(): String = displayName

    override fun getFileType(): FileType = AgentTerminalFileType

    override fun isWritable(): Boolean = false
}

object AgentTerminalFileType : FakeFileType() {
    override fun getName(): String = "imuxTerminal"

    override fun getDescription(): String = "imux terminal session"

    override fun isMyFileType(file: VirtualFile): Boolean = file is AgentTerminalVirtualFile
}
