package com.github.izerui.imux.turn

import com.github.izerui.imux.session.ClaudeRuntimeSession
import java.time.Duration
import java.time.Instant

/**
 * 从 Claude 运行态快照中识别「这一轮跑完了」，并记下它跑了多久。
 *
 * 判据是 `status` 的 `busy -> idle` 跃迁。这比从会话文件反推 `stop_reason` 可靠——
 * 那是逆向推断，这是 CLI 自己写下的一手状态。
 *
 * 两条刻意的规则：
 * - **首次观察到就是 idle 的会话不提醒**：它本来就闲着，不是刚跑完
 * - **会话消失不提醒**：那是进程退出，不是完成
 *
 * 有状态但无 IO、无平台依赖，可完整单测。时钟由外部注入，故耗时也可测。
 */
class RuntimeStatusTracker(private val clock: () -> Instant = { Instant.now() }) {

    /**
     * 正在忙的会话及其起点。
     *
     * 值为 null 表示「首次见到它时它就已经在忙」——插件启动前它就跑上了，
     * 起点无从得知。这种情况宁可不报耗时，也不要报一个错的数。
     */
    private val busySince = mutableMapOf<String, Instant?>()

    /** 已观察过的会话。用来区分「首次见到就在忙」与「从空闲转入忙碌」。 */
    private val seen = mutableSetOf<String>()

    /** 最近一次完成的耗时，供提醒展示。 */
    private val durations = mutableMapOf<String, Duration>()

    /** 传入当前快照，返回本次由忙碌转为空闲的会话 id。 */
    fun completedSince(current: Map<String, ClaudeRuntimeSession>): List<String> {
        val completed = mutableListOf<String>()

        for ((sessionId, session) in current) {
            val wasBusy = busySince.containsKey(sessionId)

            if (session.isBusy) {
                // 之前没见过它就直接是忙的，说明起点在观察窗口之外
                if (!wasBusy) busySince[sessionId] = if (sessionId in seen) clock() else null
            } else {
                if (wasBusy) {
                    completed += sessionId
                    val start = busySince[sessionId]
                    if (start != null) durations[sessionId] = Duration.between(start, clock())
                    else durations.remove(sessionId)
                }
                busySince.remove(sessionId)
            }

            seen += sessionId
        }

        // 快照里消失的会话（进程退出）不算完成，仅清理记账
        busySince.keys.retainAll(current.keys)
        seen.retainAll(current.keys)

        return completed
    }

    /** 该会话最近一轮跑了多久；起点未知或从未跑完过时为 null。 */
    fun lastDuration(sessionId: String): Duration? = durations[sessionId]
}
