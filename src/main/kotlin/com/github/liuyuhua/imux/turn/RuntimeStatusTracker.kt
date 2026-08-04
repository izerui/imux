package com.github.liuyuhua.imux.turn

import com.github.liuyuhua.imux.session.ClaudeRuntimeSession

/**
 * 从 Claude 运行态快照中识别「这一轮跑完了」。
 *
 * 判据是 `status` 的 `busy -> idle` 跃迁。这比从会话文件反推 `stop_reason` 可靠——
 * 那是逆向推断，这是 CLI 自己写下的一手状态。
 *
 * 两条刻意的规则：
 * - **首次观察到就是 idle 的会话不提醒**：它本来就闲着，不是刚跑完
 * - **会话消失不提醒**：那是进程退出，不是完成
 *
 * 有状态但无 IO、无平台依赖，可完整单测。
 */
class RuntimeStatusTracker {

    private val busyBefore = mutableSetOf<String>()

    /** 传入当前快照，返回本次由忙碌转为空闲的会话 id。 */
    fun completedSince(current: Map<String, ClaudeRuntimeSession>): List<String> {
        val completed = mutableListOf<String>()

        for ((sessionId, session) in current) {
            val wasBusy = sessionId in busyBefore
            if (session.isBusy) {
                busyBefore += sessionId
            } else {
                if (wasBusy) completed += sessionId
                busyBefore -= sessionId
            }
        }

        // 快照里消失的会话（进程退出）不算完成，仅清理记账
        busyBefore.retainAll(current.keys)

        return completed
    }
}
