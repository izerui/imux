package com.github.izerui.imux.terminal

import java.awt.Rectangle
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 会话内提问导航的几何计算：每条用户输入一根短横线，等间距、整列垂直居中。
 *
 * 纵向位置只看第几条，不看它在终端文档里的位置——和 Codex 一样，长短回复不会让横线扎堆或稀疏。
 * 全部是纯函数，绘制、命中和卡片定位共用，单测直接覆盖。
 */

/** 每根横线中心的纵坐标；放不下时压缩间距铺满 `height - padding * 2`。 */
internal fun tickCenters(
    count: Int,
    height: Int,
    padding: Int,
    gap: Int,
): List<Int> {
    if (count <= 0) return emptyList()
    if (count == 1) return listOf(height / 2)
    val usable = (height - padding * 2).coerceAtLeast(0)
    val step = minOf(gap.toDouble(), usable.toDouble() / (count - 1))
    val top = (height - step * (count - 1)) / 2
    return List(count) { (top + it * step).roundToInt() }
}

/** 常态横线长度按提问字数分三档，看起来参差，也能粗略看出哪轮问得长。 */
internal fun tickLength(
    textLength: Int,
    short: Int,
    medium: Int,
    long: Int,
): Int =
    when {
        textLength <= 15 -> short
        textLength <= 40 -> medium
        else -> long
    }

/**
 * 鼠标落在哪根横线上；不在任何横线上返回 null。
 *
 * 横线列盖在终端上层，命中区放多大就从终端手里吃掉多少鼠标事件，所以只认横线本身：
 * 横向是这根横线的当前长度加 [slack]，纵向是最近那根上下各 `min(半个间距, maxBand)`。
 * 横线之间、横线右侧的空白都不算，点击、拖选、滚轮照常落到终端。
 */
internal fun tickHitIndex(
    x: Int,
    y: Int,
    centers: List<Int>,
    lengths: List<Int>,
    left: Int,
    slack: Int,
    maxBand: Int,
): Int? {
    if (centers.isEmpty()) return null
    val nearest = centers.indices.minBy { abs(centers[it] - y) }
    val step = if (centers.size > 1) (centers.last() - centers.first()).toDouble() / (centers.size - 1) else Double.MAX_VALUE
    val band = minOf(maxBand, maxOf(1, (step / 2).toInt()))
    if (abs(centers[nearest] - y) > band) return null
    if (x < left - slack || x > left + lengths[nearest] + slack) return null
    return nearest
}

/** 悬停卡片：纵向中心对齐横线，贴近上下边界时收进可视区。 */
internal fun previewCardBounds(
    tickY: Int,
    cardWidth: Int,
    cardHeight: Int,
    x: Int,
    containerHeight: Int,
    margin: Int,
): Rectangle {
    val maxY = (containerHeight - cardHeight - margin).coerceAtLeast(margin)
    val y = (tickY - cardHeight / 2).coerceIn(margin, maxY)
    return Rectangle(x, y, cardWidth, cardHeight)
}

/**
 * 按实际字宽把文本折成最多 [maxLines] 行，超出时末行以「…」收尾。
 *
 * 逐字符折行：卡片里多是中文，没有空格可断；英文偶尔断在词中间，换来的是行数可控。
 */
internal fun clampToLines(
    text: String,
    maxWidth: Int,
    maxLines: Int,
    width: (String) -> Int,
): List<String> {
    if (text.isEmpty() || maxLines <= 0) return emptyList()
    val lines = mutableListOf<String>()
    val current = StringBuilder()
    for ((index, char) in text.withIndex()) {
        if (current.isNotEmpty() && width(current.toString() + char) > maxWidth) {
            if (lines.size == maxLines - 1) {
                lines += ellipsized(current.toString(), maxWidth, width)
                return lines
            }
            lines += current.toString()
            current.clear()
        }
        current.append(char)
        if (index == text.lastIndex) lines += current.toString()
    }
    return lines
}

private fun ellipsized(
    line: String,
    maxWidth: Int,
    width: (String) -> Int,
): String {
    var kept = line
    while (kept.isNotEmpty() && width("$kept…") > maxWidth) kept = kept.dropLast(1)
    return "$kept…"
}
