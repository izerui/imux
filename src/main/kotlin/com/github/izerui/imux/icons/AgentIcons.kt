package com.github.izerui.imux.icons

import com.github.izerui.imux.model.AgentType
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.LayeredIcon
import com.intellij.util.IconUtil
import javax.swing.Icon
import javax.swing.SwingConstants

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

    /** 标题栏的新建按钮：光有品牌标志读起来像「筛选」，右下角的加号才点明是「新建」。 */
    fun forNewSession(agentType: AgentType): Icon = LayeredIcon(2).apply {
        setIcon(forAgent(agentType), 0)
        setIcon(IconUtil.scale(AllIcons.General.Add, null, BADGE_SCALE), 1, SwingConstants.SOUTH_EAST)
    }

    /** 加号缩到一半，够看清又不遮住品牌标志的可辨识部分。 */
    private const val BADGE_SCALE = 0.5f
}
