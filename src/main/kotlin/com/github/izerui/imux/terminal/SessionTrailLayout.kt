package com.github.izerui.imux.terminal

import com.intellij.ide.util.PropertiesComponent
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Rectangle

/** 轨迹面板的像素尺寸（已按 DPI 缩放）。 */
internal data class TrailMetrics(
    val margin: Int,
    val chip: Int,
    val minWidth: Int,
    val minHeight: Int,
)

internal fun trailMetrics(): TrailMetrics =
    TrailMetrics(
        margin = JBUI.scale(8),
        chip = JBUI.scale(24),
        minWidth = JBUI.scale(240),
        minHeight = JBUI.scale(140),
    )

/**
 * 面板在正文中的位置：锚定右上角，右边缘贴着提问导航横线列左侧（[rightReserve] 是横线列连同右侧留白的宽度）。
 *
 * 折叠时是一个 chip × chip 的图标；展开时按期望尺寸，夹在下限与可用区域之间。
 * 正文比下限还小时以可用区域为准，任何情况下都不出现负值。
 * [topOffset] 是上方已被占用的高度（顶部提示条与浮动图标组），面板从它下面开始。
 */
internal fun trailBounds(
    width: Int,
    height: Int,
    rightReserve: Int,
    expanded: Boolean,
    desiredWidth: Int,
    desiredHeight: Int,
    metrics: TrailMetrics,
    topOffset: Int = 0,
): Rectangle {
    val right = (width - rightReserve - metrics.margin).coerceAtLeast(0)
    val top = topOffset.coerceAtLeast(0) + metrics.margin
    if (!expanded) return Rectangle((right - metrics.chip).coerceAtLeast(0), top, metrics.chip, metrics.chip)

    val cardWidth = fit(desiredWidth, metrics.minWidth, right - metrics.margin)
    val cardHeight = fit(desiredHeight, metrics.minHeight, height - top - metrics.margin)
    return Rectangle((right - cardWidth).coerceAtLeast(0), top, cardWidth, cardHeight)
}

/**
 * 拖拽调整后的尺寸。卡片锚定右上角：向左拖（dx < 0）变宽，向下拖（dy > 0）变高。
 * [maxWidth]、[maxHeight] 是当前正文里的可用区域。
 */
internal fun resizedTrailSize(
    startWidth: Int,
    startHeight: Int,
    dx: Int,
    dy: Int,
    resizeWidth: Boolean,
    resizeHeight: Boolean,
    maxWidth: Int,
    maxHeight: Int,
    metrics: TrailMetrics,
): Dimension =
    Dimension(
        if (resizeWidth) fit(startWidth - dx, metrics.minWidth, maxWidth) else startWidth,
        if (resizeHeight) fit(startHeight + dy, metrics.minHeight, maxHeight) else startHeight,
    )

/**
 * 放不下时宽高按同一比例缩小，保持设定尺寸的形状；宽度缩到 [minWidth] 为止，之后只缩高度。放得下时原样返回，不放大。
 * 只用于还没手动拖过尺寸的报告页：拖过之后按手动的宽高显示（见 [TrailSizeSetting.isCustomized]）。
 */
internal fun fitKeepingRatio(
    desiredWidth: Int,
    desiredHeight: Int,
    availableWidth: Int,
    availableHeight: Int,
    minWidth: Int,
): Dimension {
    if (desiredWidth <= 0 || desiredHeight <= 0) return Dimension(desiredWidth, desiredHeight)
    val scale = minOf(1.0, availableWidth.toDouble() / desiredWidth, availableHeight.toDouble() / desiredHeight)
    val width = (desiredWidth * scale).toInt().coerceAtLeast(minOf(minWidth, desiredWidth))
    return Dimension(width, (desiredHeight * scale).toInt())
}

/** 夹在 [min, available] 之间；可用区域比下限还小时取可用区域。 */
private fun fit(desired: Int, min: Int, available: Int): Int {
    val max = available.coerceAtLeast(0)
    return desired.coerceAtLeast(min).coerceAtMost(max)
}

/**
 * 卡片大小在所有会话间共用、重启保留。存未缩放的值，换显示器缩放后仍是同样的视觉大小。
 * 默认是手机屏幕的比例：卡片里的报告页按手机排版显示，520 是报告页手机排版的宽度上限。
 */
internal object TrailSizeSetting {
    const val DEFAULT_WIDTH = 520
    const val DEFAULT_HEIGHT = 1127

    /** 报告页按比例缩小时的最小宽度，再窄页面就挤了。 */
    const val MIN_REPORT_WIDTH = 320

    /** 没有时间线可显示（未生成、生成中、未落盘）时的紧凑高度。 */
    const val COMPACT_HEIGHT = 140

    // 换了新键：不沿用改成报告页之前的 360×420 记录
    private const val WIDTH_KEY = "imux.sessionReport.width"
    private const val HEIGHT_KEY = "imux.sessionReport.height"

    fun width(): Int = PropertiesComponent.getInstance().getInt(WIDTH_KEY, DEFAULT_WIDTH)

    fun height(): Int = PropertiesComponent.getInstance().getInt(HEIGHT_KEY, DEFAULT_HEIGHT)

    /** 手动拖过尺寸：之后按存下的宽高显示，不再按比例自适应。恢复默认（[reset]）后回到自适应。 */
    fun isCustomized(): Boolean =
        PropertiesComponent.getInstance().let { it.isValueSet(WIDTH_KEY) || it.isValueSet(HEIGHT_KEY) }

    /** 拖过就记下，即便恰好等于默认值：带默认值存时平台会把它删掉，就成了「没拖过」，又被按比例缩回去。 */
    fun save(width: Int, height: Int) {
        PropertiesComponent.getInstance().setValue(WIDTH_KEY, width.toString())
        PropertiesComponent.getInstance().setValue(HEIGHT_KEY, height.toString())
    }

    /** 恢复默认：清掉记录，回到按比例自适应。 */
    fun reset() {
        PropertiesComponent.getInstance().unsetValue(WIDTH_KEY)
        PropertiesComponent.getInstance().unsetValue(HEIGHT_KEY)
    }
}
