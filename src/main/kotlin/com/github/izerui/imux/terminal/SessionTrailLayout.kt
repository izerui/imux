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
 * 面板在正文中的位置：锚定右上角，右边缘贴着导航轨道左侧（[rightReserve] 是轨道连同右侧留白的宽度）。
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

/** 夹在 [min, available] 之间；可用区域比下限还小时取可用区域。 */
private fun fit(desired: Int, min: Int, available: Int): Int {
    val max = available.coerceAtLeast(0)
    return desired.coerceAtLeast(min).coerceAtMost(max)
}

/**
 * 卡片大小在所有会话间共用、重启保留。存未缩放的值，换显示器缩放后仍是同样的视觉大小。
 */
internal object TrailSizeSetting {
    const val DEFAULT_WIDTH = 360
    const val DEFAULT_HEIGHT = 420

    /** 没有时间线可显示（未生成、生成中、未落盘）时的紧凑高度。 */
    const val COMPACT_HEIGHT = 140

    private const val WIDTH_KEY = "imux.sessionTrail.width"
    private const val HEIGHT_KEY = "imux.sessionTrail.height"

    fun width(): Int = PropertiesComponent.getInstance().getInt(WIDTH_KEY, DEFAULT_WIDTH)

    fun height(): Int = PropertiesComponent.getInstance().getInt(HEIGHT_KEY, DEFAULT_HEIGHT)

    fun save(width: Int, height: Int) {
        PropertiesComponent.getInstance().setValue(WIDTH_KEY, width, DEFAULT_WIDTH)
        PropertiesComponent.getInstance().setValue(HEIGHT_KEY, height, DEFAULT_HEIGHT)
    }

    fun reset() = save(DEFAULT_WIDTH, DEFAULT_HEIGHT)
}
