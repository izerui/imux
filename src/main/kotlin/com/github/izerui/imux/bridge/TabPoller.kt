package com.github.izerui.imux.bridge

import com.github.izerui.imux.bridge.core.BridgeRouter
import com.github.izerui.imux.bridge.transcript.BoundedRead
import com.github.izerui.imux.bridge.transcript.ReadMark
import com.github.izerui.imux.bridge.transcript.TailPoll
import com.github.izerui.imux.bridge.transcript.TailRegistry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 按标签读取会话记录并交给路由器：定时轮询、IDE 的完成与等待信号都从这里进。
 *
 * 同一标签的读取、发送与收尾在同一把标签锁内按顺序进行；一个标签发送卡住（限频、网络）不拖住其它标签。
 * 不依赖 IDE，远程控制开关由调用方给出。
 */
internal class TabPoller(
    private val tails: TailRegistry,
    private val router: BridgeRouter,
    private val enabled: () -> Boolean,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** 上一次读取或发送还没结束。 */
    fun isBusy(tabId: String): Boolean = lock(tabId).isLocked

    suspend fun poll(tabId: String) =
        lock(tabId).withLock {
            // 关闭期间不读；读取位置留着，重新开启后按开关代次作废
            if (enabled()) deliver(tabId, tails.read(tabId))
        }

    /**
     * IDE 报告本轮完成。[mark] 是信号到达那一刻记下的读取边界：只读到那里，再把这一轮的最终回复发出
     * （记录里没有结束标记时的兜底）。边界不可靠（没有边界、已越过、尾随器重建）时放弃，不补发：
     * 此刻的“当前轮”可能已不是这个信号所属的那一轮，结算它会把下一轮的过程说明当结论发出去。
     */
    suspend fun completed(
        tabId: String,
        mark: ReadMark?,
    ) = lock(tabId).withLock {
        if (!enabled()) return@withLock
        when (val read = tails.readUpTo(tabId, mark)) {
            // 已经读出的内容照常交出（例如“记录重启”提示只报这一次），只是不结算
            is BoundedRead.Unreliable -> deliver(tabId, read.poll)
            is BoundedRead.Reached -> {
                deliver(tabId, read.poll)
                router.onTurnCompleted(tabId, router.currentTurn(tabId))
            }
        }
    }

    /**
     * 说的是主会话刚结束那一轮的状态（例如已达审查上限、不再审查）：先把这一轮的回复结算发出，再推状态，
     * 手机上状态永远排在它所说的那条回复之后。这类状态只在主会话刚完成时发出，此刻读到的都属于这一轮。
     */
    suspend fun afterTurn(
        tabId: String,
        text: String,
    ) = lock(tabId).withLock {
        if (!enabled()) return@withLock
        deliver(tabId, tails.read(tabId))
        router.onTurnCompleted(tabId, router.currentTurn(tabId))
        router.onStatus(tabId, text)
    }

    /** 与轮次无关的状态（审查结果、取消）：排在这个标签已在发送的内容之后。 */
    suspend fun status(
        tabId: String,
        text: String,
    ) = lock(tabId).withLock {
        if (enabled()) router.onStatus(tabId, text)
    }

    /** IDE 报告等待确认：先读出之前的内容，再推等待提示。 */
    suspend fun waiting(
        tabId: String,
        reason: String?,
    ) = lock(tabId).withLock {
        if (!enabled()) return@withLock
        deliver(tabId, tails.read(tabId))
        router.onWaiting(tabId, reason)
    }

    private suspend fun deliver(
        tabId: String,
        poll: TailPoll?,
    ) {
        poll ?: return
        if (poll.restarted) router.onTranscriptRestarted(tabId)
        if (poll.events.isNotEmpty()) router.onSessionEvents(tabId, poll.events)
    }

    private fun lock(tabId: String): Mutex = locks.computeIfAbsent(tabId) { Mutex() }
}
