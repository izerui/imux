package com.github.izerui.imux.icons

import com.github.izerui.imux.model.AgentType
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.RowIcon
import java.util.concurrent.ConcurrentHashMap
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
 * 忙碌是在品牌图标**之上**做修饰，而不是换掉它——换掉的话标签页一忙起来就只剩一个
 * 转圈，既认不出是 Claude 还是 Codex 的会话，也认不出它和旁边普通编辑器标签的区别。
 * 未读则在品牌图标与标题之间另加一格，见 [forTab]。
 */
internal object AgentIcons {
    private val claude = IconLoader.getIcon("/icons/claude.png", javaClass)
    private val codex = IconLoader.getIcon("/icons/codex.png", javaClass)

    // 动画帧只建一次：标签页会反复来取图标，每次新建不只是浪费，
    // 还会让呼吸每次都从第一帧重来，看着像卡住。
    private val claudeBusy by lazy { breathing(claude) }
    private val codexBusy by lazy { breathing(codex) }

    // 四种组合各缓存一份，理由同上：动画那一格必须是同一个实例。
    private val tabIcons = ConcurrentHashMap<TabIconKey, Icon>()

    private data class TabIconKey(
        val agentType: AgentType,
        val running: Boolean,
        val unread: Boolean,
    )

    fun forAgent(agentType: AgentType): Icon = when (agentType) {
        AgentType.CLAUDE -> claude
        AgentType.CODEX -> codex
    }

    /** 品牌图标明暗呼吸，表示这个会话正在跑。 */
    fun busy(agentType: AgentType): Icon = when (agentType) {
        AgentType.CLAUDE -> claudeBusy
        AgentType.CODEX -> codexBusy
    }

    /**
     * 标签页图标：品牌图标在前，未读标记跟在它和标题之间。
     *
     * 未读用的就是会话列表里那一个 [AllIcons.General.Modified]，同一个常量，
     * 样式自然一致——标题前放个字符做不到这点，字符颜色只能跟着标题走。
     *
     * 没有未读时不留空位，图标就是品牌图标本身。代价是标记出现与消失时标题会横移一格；
     * 留空位能免掉这一下，但换来每个标签常年空着半截，权衡后选了前者。
     */
    fun forTab(agentType: AgentType, running: Boolean, unread: Boolean): Icon =
        tabIcons.getOrPut(TabIconKey(agentType, running, unread)) {
            val brand = if (running) busy(agentType) else forAgent(agentType)
            if (!unread) {
                brand
            } else {
                RowIcon(2).apply {
                    setIcon(brand, 0)
                    setIcon(AllIcons.General.Modified, 1)
                }
            }
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
}
