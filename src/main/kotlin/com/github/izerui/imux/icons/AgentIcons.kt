package com.github.izerui.imux.icons

import com.github.izerui.imux.model.AgentType
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.RowIcon
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon

/**
 * 转一圈分几帧。
 *
 * 帧太少会看出是在跳格而不是在转，而转得越慢每帧停留越久，跳格也越明显——
 * 24 帧每帧只跨 15°，慢到 2 秒一圈也还是连续的。
 */
private const val SPIN_FRAMES = 24

/** 每帧停留毫秒。24 帧 × 85ms ≈ 2 秒一圈，比平台自带的转菊花慢一半，不抢注意力。 */
private const val SPIN_FRAME_MS = 85

/**
 * 编辑器标签通过 [javax.swing.CellRendererPane] 绘制图标，但其 owner 没有设置
 * [AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED]。默认实现因此不会安排下一帧重绘；
 * 标签创建和标题变化带来的偶发重绘会让动画短暂移动，随后便停住。
 *
 * 这个动画只用于编辑器标签，直接返回 renderer owner，让平台自带的帧调度器重绘它。
 */
internal class EditorTabAnimatedIcon(
    delay: Int,
    vararg frames: Icon,
) : AnimatedIcon(delay, *frames) {
    public override fun getRendererOwner(component: Component?): Component? = component
}

/**
 * `AllIcons` 没有 OpenAI / Claude / pi 的品牌标志，无法用语义匹配的平台图标替代；
 * 资源仍交给 [IconLoader] 选择主题与 HiDPI 变体。
 *
 * 忙碌是让品牌图标**自己**转起来，而不是换成平台的转菊花——换掉的话标签页一忙起来
 * 就只剩一个菊花，既认不出是哪个 agent 的会话，也认不出它和旁边普通编辑器标签的区别。
 * 未读则在品牌图标与标题之间另加一格，见 [forTab]。
 */
internal object AgentIcons {
    private val claude = IconLoader.getIcon("/icons/claude.png", javaClass)
    private val codex = IconLoader.getIcon("/icons/codex.png", javaClass)
    private val pi = IconLoader.getIcon("/icons/pi.png", javaClass)

    // 动画帧只建一次：标签页会反复来取图标，每次新建不只是浪费，
    // 还会让动画每次都从第一帧重来，看着像卡住。
    private val claudeBusy by lazy { spinning(claude) }
    private val codexBusy by lazy { spinning(codex) }
    private val piBusy by lazy { spinning(pi) }

    // 四种组合各缓存一份，理由同上：动画那一格必须是同一个实例。
    private val tabIcons = ConcurrentHashMap<TabIconKey, Icon>()

    private data class TabIconKey(
        val agentType: AgentType,
        val running: Boolean,
        val unread: Boolean,
    )

    fun forAgent(agentType: AgentType): Icon =
        when (agentType) {
            AgentType.CLAUDE -> claude
            AgentType.CODEX -> codex
            AgentType.PI -> pi
        }

    /** 品牌图标原地转圈，表示这个会话正在跑。 */
    fun busy(agentType: AgentType): Icon =
        when (agentType) {
            AgentType.CLAUDE -> claudeBusy
            AgentType.CODEX -> codexBusy
            AgentType.PI -> piBusy
        }

    /**
     * 标签页图标：未读标记在最左边，品牌图标跟在它和标题之间。
     *
     * 标记放最左而不是夹在品牌图标与标题中间——一排标签扫过去时，所有未读标记对齐在
     * 各自标签的同一侧，一眼就数得出哪几个有新结果；夹在中间则被品牌图标顶得参差。
     *
     * 未读用的就是会话列表里那一个 [AllIcons.General.Modified]，同一个常量，
     * 样式自然一致——标题前放个字符做不到这点，字符颜色只能跟着标题走。
     *
     * 没有未读时不留空位，图标就是品牌图标本身。代价是标记出现与消失时后面整块会横移一格；
     * 留空位能免掉这一下，但换来每个标签常年空着半截，权衡后选了前者。
     */
    fun forTab(
        agentType: AgentType,
        running: Boolean,
        unread: Boolean,
    ): Icon =
        tabIcons.getOrPut(TabIconKey(agentType, running, unread)) {
            val brand = if (running) busy(agentType) else forAgent(agentType)
            if (!unread) {
                brand
            } else {
                RowIcon(2).apply {
                    setIcon(AllIcons.General.Modified, 0)
                    setIcon(brand, 1)
                }
            }
        }

    /**
     * 匀速转一整圈，亮度与尺寸都不动。
     *
     * Claude 是星芒、Codex 是环，都是径向图案，绕中心转看着就是在转。pi 是直角阶梯，
     * 转起来会有方形特有的歪斜感，但仍是同一套动效，好过一忙起来就认不出是哪个 agent。
     * 首帧是 0°，即原图本身，所以刚变忙碌时不会跳一下。
     *
     * 图案必须落在声明区域的内切圆里，否则转到 45° 会削角——pi 的图标为此特意
     * 画得比另外两个小一圈，见 IconResourceTest「Pi 图标留出旋转所需的余量」。
     */
    private fun spinning(base: Icon): Icon =
        EditorTabAnimatedIcon(
            SPIN_FRAME_MS,
            *Array<Icon>(SPIN_FRAMES) { frame ->
                if (frame == 0) base else RotatedIcon(base, 2 * Math.PI * frame / SPIN_FRAMES)
            },
        )

    /**
     * 绕中心旋转绘制，对外仍报原来的尺寸。
     *
     * 尺寸必须保持不变：一是 [AnimatedIcon] 要求各帧同尺寸，二是尺寸一变标签里的
     * 标题就会跟着横移。图案本身在内切圆范围内，转到任何角度都不会被声明区域裁掉。
     */
    private class RotatedIcon(
        private val base: Icon,
        private val radians: Double,
    ) : Icon {
        override fun paintIcon(
            c: Component?,
            g: Graphics,
            x: Int,
            y: Int,
        ) {
            val g2 = g.create() as Graphics2D
            try {
                // 旋转会让像素落在半像素上，不开插值的话边缘是硬切的锯齿。
                g2.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR,
                )
                g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON,
                )
                g2.rotate(radians, x + base.iconWidth / 2.0, y + base.iconHeight / 2.0)
                base.paintIcon(c, g2, x, y)
            } finally {
                g2.dispose()
            }
        }

        override fun getIconWidth(): Int = base.iconWidth

        override fun getIconHeight(): Int = base.iconHeight
    }
}
