package com.github.izerui.imux.icons

import com.github.izerui.imux.model.AgentType
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.BadgeIcon
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import javax.swing.Icon
import kotlin.math.cos

/** 一个呼吸周期分几帧。帧太少会看出是在跳档而不是在渐变。 */
private const val BREATH_FRAMES = 16

/** 每帧停留毫秒。16 帧 × 100ms ≈ 1.6 秒一次呼吸，比心跳略慢，不抢注意力。 */
private const val BREATH_FRAME_MS = 100

/** 呼吸最暗处的不透明度。再低就快看不见了，容易误以为标签失效。 */
private const val BREATH_MIN_ALPHA = 0.35f

/**
 * `AllIcons` 没有 OpenAI / Claude 品牌标志，无法用语义匹配的平台图标替代；
 * 资源仍交给 [IconLoader] 选择主题与 HiDPI 变体。
 *
 * 忙碌与未读都是在品牌图标**之上**做修饰，而不是换掉它——换掉的话标签页一忙起来
 * 就只剩一个转圈，既认不出是 Claude 还是 Codex 的会话，也认不出它和旁边普通编辑器
 * 标签的区别。三种状态都是 16×16，标题不会随状态左右跳。
 */
internal object AgentIcons {
    private val claude = IconLoader.getIcon("/icons/claude.png", javaClass)
    private val codex = IconLoader.getIcon("/icons/codex.png", javaClass)

    // 动画帧与角标都只建一次：标签页会反复来取图标，每次新建不只是浪费，
    // 还会让呼吸每次都从第一帧重来，看着像卡住。
    private val claudeBusy by lazy { breathing(claude) }
    private val codexBusy by lazy { breathing(codex) }
    private val claudeUnread by lazy { badged(claude) }
    private val codexUnread by lazy { badged(codex) }

    fun forAgent(agentType: AgentType): Icon = when (agentType) {
        AgentType.CLAUDE -> claude
        AgentType.CODEX -> codex
    }

    /** 品牌图标明暗呼吸，表示这个会话正在跑。 */
    fun busy(agentType: AgentType): Icon = when (agentType) {
        AgentType.CLAUDE -> claudeBusy
        AgentType.CODEX -> codexBusy
    }

    /** 品牌图标挂一个角标，表示跑完了还没看。 */
    fun unread(agentType: AgentType): Icon = when (agentType) {
        AgentType.CLAUDE -> claudeUnread
        AgentType.CODEX -> codexUnread
    }

    /**
     * 亮度按余弦往复，而不是线性来回。
     *
     * 线性的话最亮和最暗两端会有明显的折返感，像被掐了一下；余弦在两端自然减速，
     * 才是「呼吸」。首帧 alpha 为 1，所以刚变忙碌时是从满亮度开始暗下去。
     */
    private fun breathing(base: Icon): Icon = AnimatedIcon(
        BREATH_FRAME_MS,
        *Array<Icon>(BREATH_FRAMES) { frame ->
            val phase = 2 * Math.PI * frame / BREATH_FRAMES
            val brightness = ((1 + cos(phase)) / 2).toFloat()
            IconLoader.getTransparentIcon(
                base,
                BREATH_MIN_ALPHA + (1f - BREATH_MIN_ALPHA) * brightness,
            )
        },
    )

    /**
     * 用平台标准角标：它会先在底图上挖掉一圈再画实心点，
     * 所以无论标签底色深浅都能看清，比直接压一个小图标上去醒目得多。
     *
     * 直接构造 [BadgeIcon] 而不走 `BadgeIconSupplier`：后者要经 `IconManager` 服务，
     * 而单元测试里那是个原样返回的空实现，角标有没有加上根本测不出来。
     */
    private fun badged(base: Icon): Icon = IconUtil.resizeSquared(
        // 角标画在底图边界之外，加完是 18×18。缩回 16 才和另外两态同宽，
        // 否则标签页一有新消息就宽 2 像素，标题跟着抖一下。
        BadgeIcon(base, JBUI.CurrentTheme.IconBadge.INFORMATION),
        base.iconWidth,
    )
}
