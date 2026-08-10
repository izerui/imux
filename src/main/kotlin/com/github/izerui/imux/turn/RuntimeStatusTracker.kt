package com.github.izerui.imux.turn

import com.github.izerui.imux.session.ClaudeRuntimeSession
import java.time.Duration
import java.time.Instant

/** 一个停下来等用户操作的会话，及其等待原因（CLI 的 `waitingFor`）。 */
data class WaitingSession(val sessionId: String, val reason: String?)

/**
 * 一次快照比对的产出：跑完的与停下等人的。
 *
 * 两类必须由同一次比对同时给出——它们出自同一个状态机，拆成两个方法调用
 * 就得比对两次快照，第二次读到的已经是推进过的状态。
 */
data class TurnOutcome(
    val completed: List<String>,
    val waiting: List<WaitingSession>,
)

/**
 * 从 Claude 运行态快照中识别「这一轮跑完了」与「停下等用户操作」，并记下跑了多久。
 *
 * 判据是 `status` 的跃迁。这比从会话文件反推 `stop_reason` 可靠——
 * 那是逆向推断，这是 CLI 自己写下的一手状态。
 *
 * 四条刻意的规则：
 * - **首次观察到就是 idle 的会话不提醒**：它本来就闲着，不是刚跑完
 * - **首次观察到就是 waiting 的会话不提醒**：它早就卡在那了，不是刚发生的跃迁
 * - **会话消失不提醒**：那是进程退出，不是完成
 * - **等待期间保留计时起点**：见 [busySince]
 *
 * 有状态但无 IO、无平台依赖，可完整单测。时钟由外部注入，故耗时也可测。
 */
class RuntimeStatusTracker(private val clock: () -> Instant = { Instant.now() }) {

    /**
     * 正在忙的会话及其起点。
     *
     * 值为 null 表示「首次见到它时它就已经在忙」——插件启动前它就跑上了，
     * 起点无从得知。这种情况宁可不报耗时，也不要报一个错的数。
     *
     * **等待用户操作期间这里不清空。** 判「完成」的依据是忙碌转非忙碌，而
     * `waiting` 已不算忙碌（见 [ClaudeRuntimeSession.isBusy]）。若它顺手清掉起点，
     * 一轮 `busy -> waiting -> busy -> idle` 报出的耗时就只剩最后一段。
     * 保留起点等于把两种语义拆开：标记语义认为 `waiting` 是停止，
     * 计时语义认为这一轮仍在继续。
     */
    private val busySince = mutableMapOf<String, Instant?>()

    /** 已观察过的会话。用来区分「首次见到就在忙」与「从空闲转入忙碌」。 */
    private val seen = mutableSetOf<String>()

    /** 上一拍就在等待的会话。用来只在「进入等待」那一拍报，而不是每拍都报。 */
    private val waiting = mutableSetOf<String>()

    /** 最近一次完成的耗时，供提醒展示。 */
    private val durations = mutableMapOf<String, Duration>()

    /** 传入当前快照，返回本次跑完的与本次停下等人的会话。 */
    fun completedSince(current: Map<String, ClaudeRuntimeSession>): TurnOutcome {
        val completed = mutableListOf<String>()
        val entered = mutableListOf<WaitingSession>()

        for ((sessionId, session) in current) {
            val wasBusy = busySince.containsKey(sessionId)
            val wasWaiting = sessionId in waiting

            when {
                session.isBusy -> {
                    // 之前没见过它就直接是忙的，说明起点在观察窗口之外
                    if (!wasBusy) busySince[sessionId] = if (sessionId in seen) clock() else null
                    waiting -= sessionId
                }

                session.isWaiting -> {
                    // 首次见到就在等待的不报：它早就卡在那了，不是刚发生的跃迁。
                    // 仍记入 waiting，好让它转忙碌后再次等待时能被认出是真跃迁。
                    if (!wasWaiting && sessionId in seen) {
                        entered += WaitingSession(sessionId, session.waitingFor)
                    }
                    waiting += sessionId
                    // busySince 刻意不动：等待期间要保住计时起点
                }

                else -> {
                    if (wasBusy) {
                        completed += sessionId
                        val start = busySince[sessionId]
                        if (start != null) durations[sessionId] = Duration.between(start, clock())
                        else durations.remove(sessionId)
                    }
                    busySince.remove(sessionId)
                    waiting -= sessionId
                }
            }

            seen += sessionId
        }

        // 快照里消失的会话（进程退出）不算完成，也不算等待，仅清理记账
        busySince.keys.retainAll(current.keys)
        seen.retainAll(current.keys)
        waiting.retainAll(current.keys)

        return TurnOutcome(completed, entered)
    }

    /** 该会话最近一轮跑了多久；起点未知或从未跑完过时为 null。 */
    fun lastDuration(sessionId: String): Duration? = durations[sessionId]
}
