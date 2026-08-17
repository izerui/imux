package com.github.izerui.imux.terminal

import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.vfs.VirtualFile

class AgentTerminalTabTitleProvider : EditorTabTitleProvider {

    override fun getEditorTabTitle(project: Project, file: VirtualFile): String? =
        (file as? AgentTerminalVirtualFile)?.let { ellipsizeTabTitle(it.tabTitle) }

    override fun getEditorTabTooltipHtml(
        project: Project,
        virtualFile: VirtualFile,
    ): HtmlChunk? =
        (virtualFile as? AgentTerminalVirtualFile)?.let {
            HtmlChunk.text(it.tabTitle)
        }
}

/** 标签页上最多显示多少个字符，超出的截掉。 */
internal const val MAX_TAB_TITLE_LENGTH = 20

/**
 * 标签页上要显示的标题：太长的截断补省略号。
 *
 * 会话标题由 CLI 生成，长短完全不受控，一句话摘要动辄二三十字，标签页会被撑得占满
 * 整条标签栏，把别的会话挤出可视区。只在这里截：窗口标题、会话列表用的仍是
 * [AgentTerminalVirtualFile.tabTitle] 里那份完整标题。
 */
internal fun ellipsizeTabTitle(title: String, max: Int = MAX_TAB_TITLE_LENGTH): String {
    if (title.codePointCount(0, title.length) <= max) return title
    // 按 code point 而不是 char 切：emoji 是代理对，从中间切开会显示成乱码方块。
    val end = title.offsetByCodePoints(0, max - 1)
    return title.substring(0, end).trimEnd() + "…"
}
