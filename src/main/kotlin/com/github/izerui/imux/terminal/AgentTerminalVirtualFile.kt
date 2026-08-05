package com.github.izerui.imux.terminal

import com.github.izerui.imux.model.AgentType
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.ex.FakeFileType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.testFramework.LightVirtualFile

/**
 * 承载一个终端会话的虚拟文件。结构参照 JetBrains 自己的
 * com.intellij.terminal.frontend.editor.TerminalViewVirtualFile。
 *
 * **为什么不复用平台那一套**（TerminalViewVirtualFile / TerminalViewFileEditor /
 * TerminalViewFileEditorProvider）——真正的原因只有一条：
 *
 * 它们是 Kotlin `internal`，只在平台自己的模块内可见，插件代码**根本引用不了**。
 * 注意 `javap` 会把它们显示成 `public final class`：Kotlin 的 `internal` 在字节码层面
 * 就是 public，可见性写在元数据里，只有 Kotlin 编译器认。想验证是否可用，
 * 别看 javap，直接写一行 `TerminalViewVirtualFile(view, true)` 编译一下。
 *
 * 曾经还有第二条理由——「平台的 FileEditor 的 dispose() 会 cancel view 的
 * CoroutineScope 从而杀掉进程」。那条**已经失效**：当时的产品决策是「关标签页不杀进程」，
 * 后来改成了「关标签页即结束会话」（见 TerminalHost 类注释），平台那个行为反倒成了
 * 我们想要的。别再拿它当理由，挡路的自始至终是 internal。
 *
 * 代价是本插件得自己实现三件套，并自己盯 sessionState 关标签页
 * （平台的 closeOnProcessTermination 只服务于它自己的虚拟文件，见 TerminalHost）。
 *
 * 注意：本文件不拥有 view，view 的生命周期由 TerminalHost 管理。
 */
class AgentTerminalVirtualFile(
    name: String,
    val terminalView: TerminalView,
    var sessionKey: String,
    val agentType: AgentType,
) : LightVirtualFile(name, AgentTerminalFileType, "") {

    var tabTitle: String = name

    override fun getFileType(): FileType = AgentTerminalFileType

    override fun isWritable(): Boolean = false
}

object AgentTerminalFileType : FakeFileType() {
    override fun getName(): String = "imuxTerminal"

    override fun getDescription(): String = "imux 终端会话"

    override fun isMyFileType(file: VirtualFile): Boolean = file is AgentTerminalVirtualFile
}
