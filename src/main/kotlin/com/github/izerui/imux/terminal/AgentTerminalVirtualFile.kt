package com.github.izerui.imux.terminal

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.ex.FakeFileType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.testFramework.LightVirtualFile

/**
 * 承载一个终端会话的虚拟文件。结构参照 JetBrains 自己的
 * com.intellij.terminal.frontend.editor.TerminalViewVirtualFile。
 *
 * 刻意不复用 TerminalViewVirtualFile：它会被 TerminalViewFileEditorProvider 接管，
 * 而那个 FileEditor 的 dispose() 会 cancel view 的 CoroutineScope 从而杀掉进程。
 * 用自己的类型可确保由本插件的 provider 处理。
 *
 * 注意：本文件不拥有 view，view 的生命周期由 TerminalHost 管理。
 */
class AgentTerminalVirtualFile(
    name: String,
    val terminalView: TerminalView,
    var sessionKey: String,
) : LightVirtualFile(name, AgentTerminalFileType, "") {

    override fun getFileType(): FileType = AgentTerminalFileType

    override fun isWritable(): Boolean = false
}

object AgentTerminalFileType : FakeFileType() {
    override fun getName(): String = "imuxTerminal"

    override fun getDescription(): String = "imux 终端会话"

    override fun isMyFileType(file: VirtualFile): Boolean = file is AgentTerminalVirtualFile
}
