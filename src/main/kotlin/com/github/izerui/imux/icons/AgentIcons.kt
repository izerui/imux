package com.github.izerui.imux.icons

import com.github.izerui.imux.model.AgentType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * `AllIcons` 没有 OpenAI / Claude 品牌标志，无法用语义匹配的平台图标替代；
 * 资源仍交给 [IconLoader] 选择主题与 HiDPI 变体。
 */
internal object AgentIcons {
    private val claude = IconLoader.getIcon("/icons/claude.png", javaClass)
    private val codex = IconLoader.getIcon("/icons/codex.png", javaClass)

    fun forAgent(agentType: AgentType): Icon = when (agentType) {
        AgentType.CLAUDE -> claude
        AgentType.CODEX -> codex
    }
}
