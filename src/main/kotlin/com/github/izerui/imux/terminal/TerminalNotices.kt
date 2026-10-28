package com.github.izerui.imux.terminal

import java.util.EnumMap
import javax.swing.Icon

/** 顶部提示条的来源。每个来源同一时刻最多一条，同等严重程度时按这里的顺序排。 */
enum class NoticeSource { PHONE, PAIR, TRAIL, TALK }

/** 严重程度：黄 > 蓝 > 绿，叠放时严重的在上。 */
enum class NoticeTone(internal val rank: Int) { WARNING(3), INFO(2), SUCCESS(1) }

/** 提示条上的链接；[run] 收到被点的链接，需要弹菜单时可以挂在它下面。 */
class NoticeAction(
    val text: String,
    val run: (anchor: javax.swing.JComponent) -> Unit,
)

/**
 * 一条提示。[key] 标识它对应的状态：用户按 × 收起的是这个 key，
 * 状态一变（例如第二次断线）key 不同，就会重新出来。
 */
data class Notice(
    val key: String,
    val tone: NoticeTone,
    val text: String,
    val icon: Icon? = null,
    val actions: List<NoticeAction> = emptyList(),
    val closable: Boolean = true,
)

/** 某个来源往顶部发提示的入口。只在 EDT 上调用。 */
interface NoticeSink {
    /** 由状态推出来的常驻提示，状态不变就一直在；传 null 表示没有。 */
    fun persistent(notice: Notice?)

    /** 一次性的结果提示：显示满 8 秒后收起，只在看得见时倒数，鼠标停在提示条上时暂停。 */
    fun flash(notice: Notice)
}

/** 正在显示的一条。[id] 变了说明是另一条提示，界面要换一行而不是只改字。 */
data class ShownNotice(
    val source: NoticeSource,
    val id: String,
    val notice: Notice,
    val flash: Boolean,
    val remainingFraction: Float,
)

/**
 * 顶部提示条的叠放规则，与界面无关：
 * 常驻提示优先于同来源的一次性提示；被收起的常驻提示在状态变化前不再出现；
 * 一次性提示只在显示时倒数，[tick] 由界面按真实流逝时间推进。
 */
class NoticeStack(private val ttlMillis: Long = DEFAULT_TTL_MILLIS) {
    private class Flash(val id: Long, val notice: Notice, var remaining: Long)

    private val persistent = EnumMap<NoticeSource, Notice>(NoticeSource::class.java)
    private val flashes = EnumMap<NoticeSource, Flash>(NoticeSource::class.java)
    private val dismissed = EnumMap<NoticeSource, String>(NoticeSource::class.java)
    private var sequence = 0L

    fun setPersistent(
        source: NoticeSource,
        notice: Notice?,
    ) {
        val previous = persistent[source]
        if (notice == null) {
            persistent.remove(source)
            // 状态离开了：下一次同样的问题（例如重试后又是同一个错误）要重新提示
            dismissed.remove(source)
            return
        }
        persistent[source] = notice
        // 进入新状态：同来源旧的结果提示已经过时
        if (previous?.key != notice.key) flashes.remove(source)
    }

    fun flash(
        source: NoticeSource,
        notice: Notice,
    ) {
        flashes[source] = Flash(++sequence, notice, ttlMillis)
        dismissed.remove(source)
    }

    /** 用户按了 ×。 */
    fun dismiss(source: NoticeSource) {
        val shown = shown(source) ?: return
        if (shown.flash) flashes.remove(source) else dismissed[source] = shown.notice.key
    }

    fun visible(): List<ShownNotice> =
        NoticeSource.entries
            .mapNotNull(::shown)
            .sortedWith(compareByDescending<ShownNotice> { it.notice.tone.rank }.thenBy { it.source.ordinal })

    /** 正在显示的一次性提示倒数 [elapsedMillis]；有提示到期收起时返回 true。 */
    fun tick(elapsedMillis: Long): Boolean {
        var expired = false
        for (shown in visible()) {
            if (!shown.flash) continue
            val flash = flashes[shown.source] ?: continue
            flash.remaining -= elapsedMillis
            if (flash.remaining <= 0) {
                flashes.remove(shown.source)
                expired = true
            }
        }
        return expired
    }

    fun hasCountdown(): Boolean = visible().any { it.flash }

    private fun shown(source: NoticeSource): ShownNotice? {
        val notice = persistent[source]
        if (notice != null) {
            return if (dismissed[source] == notice.key) null else ShownNotice(source, notice.key, notice, flash = false, remainingFraction = 1f)
        }
        val flash = flashes[source] ?: return null
        return ShownNotice(source, "flash:${flash.id}", flash.notice, flash = true, remainingFraction = flash.remaining.toFloat() / ttlMillis)
    }

    companion object {
        const val DEFAULT_TTL_MILLIS = 8_000L
    }
}

/**
 * 浮动图标组里一个按钮此刻的样子。
 *
 * [label] 不为空表示这个功能开着或有事：按钮显示「图标＋短字」并按 [tone] 着色（结对显示伙伴名、手机显示机器人名、
 * 出问题显示黄色）。宽度不够时先收字只留着色的图标，见 [com.github.izerui.imux.terminal.stripFit]。
 * 悬停时显示 [title] 与 [description]。
 */
data class ToolState(
    val icon: Icon,
    val title: String,
    val description: String,
    val label: String? = null,
    val tone: NoticeTone? = null,
    val selected: Boolean = false,
    val enabled: Boolean = true,
)

/** 浮动图标组里的一个功能。只在 EDT 上调用；[state] 要很快，图标组刷新时会逐个询问。 */
interface SessionTool {
    fun state(): ToolState

    /** 点了按钮；[anchor] 是按钮本身，弹菜单时挂在它下面。 */
    fun perform(anchor: javax.swing.JComponent)
}
