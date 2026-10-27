package com.github.izerui.imux.overview

import com.github.izerui.imux.agent.AgentProgressEvent
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** 某个会话的轨迹在界面上的状态。 */
sealed interface TrailState {
    /** 会话尚未落盘（`pending-*`），还没有可读的记录。 */
    data object Unavailable : TrailState

    /** 已落盘，但从未生成过。 */
    data object Empty : TrailState

    /** [previous] 是更新前已有的结果，更新期间照常展示，完成后整体替换；第一次生成时为 null。 */
    data class Loading(val progress: String?, val startedAt: Long, val previous: OverviewResult? = null) : TrailState

    data class Ready(val result: OverviewResult, val stale: Boolean) : TrailState

    /** 生成失败；[previous] 是失败前已有的结果，界面继续展示它。 */
    data class Failed(val message: String, val previous: OverviewResult?) : TrailState
}

/**
 * 按 sessionKey 维护轨迹状态与生成任务。
 *
 * 任务挂在服务的作用域上，而不是界面上：面板收起、切标签页、拖动标签页（编辑器会销毁重建）
 * 都不应打断几分钟的生成；同一会话若有多个编辑器实例，它们看到的是同一份状态、同一个任务。
 * 只有用户取消、标签页真正关闭（[releaseOwner]）或项目关闭才会取消。
 *
 * 每条记录同时记下发起它的标签页（`tabId`，一生不变）。`/clear`、`/new` 会让标签页换到新的
 * sessionKey，而迁移前的任务仍登记在旧 key 下；关闭时若只按当前 key 清理，旧任务就会在没有
 * 任何面板能取消它的情况下继续运行。因此关闭按标签页身份清理，迁移本身则不碰任务。
 *
 * 状态回写都经 [onEdt]，并且只在任务仍是该会话当前任务时生效，取消后晚到的结果被丢弃。
 */
internal class TrailStateStore(
    private val scope: CoroutineScope,
    private val loadCached: (String) -> CachedView?,
    private val isAvailable: (String) -> Boolean,
    private val generate: (String, (AgentProgressEvent) -> Unit) -> OverviewResult,
    private val errorText: (Throwable) -> String,
    private val onEdt: (() -> Unit) -> Unit,
    private val progressText: (AgentProgressEvent) -> String? = ::overviewProgressText,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** 生成过、正在生成或失败的会话才有记录；其余状态由缓存派生。 */
    private val states = ConcurrentHashMap<String, TrailState>()
    private val jobs = ConcurrentHashMap<String, Job>()

    /** sessionKey → 发起生成的标签页 tabId。 */
    private val owners = ConcurrentHashMap<String, String>()

    /** 生成前的记录，取消时恢复；原来没有记录的存 [NO_RECORD]。 */
    private val beforeGeneration = ConcurrentHashMap<String, Any>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun state(key: String): TrailState =
        when (val recorded = states[key]) {
            // 生成完会话又有新交互：结果本身带的过期标记，或缓存按文件大小判断已过期
            is TrailState.Ready -> recorded.copy(stale = recorded.result.stale || loadCached(key)?.stale == true)
            null -> derived(key)
            else -> recorded
        }

    private fun derived(key: String): TrailState {
        if (!isAvailable(key)) return TrailState.Unavailable
        val cached = loadCached(key) ?: return TrailState.Empty
        return TrailState.Ready(cached.result, cached.stale)
    }

    /** 展开时自动生成首份轨迹；取消后仍保留 owner，避免刷新或重建面板时自动重试。 */
    fun generateIfNeeded(key: String, owner: String) {
        if (!owners.containsKey(key) && state(key) is TrailState.Empty) generate(key, owner)
    }

    fun generate(key: String, owner: String) {
        // 不能看 isActive：后台已跑完、finish 的回写还在 EDT 排队时任务已不活动但状态仍是 Loading，
        // 这时放行会把 Loading 记成「生成前状态」，再取消就恢复成没有任务的 Loading。
        // 登记一直保留到 finish / cancel 把它移除。
        if (jobs.containsKey(key)) return
        val before = state(key)
        if (before is TrailState.Unavailable) return
        val previous = when (before) {
            is TrailState.Ready -> before.result
            is TrailState.Failed -> before.previous
            else -> null
        }
        beforeGeneration[key] = states[key] ?: NO_RECORD
        owners[key] = owner
        states[key] = TrailState.Loading(null, clock(), previous)

        val job = scope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            try {
                val result = runInterruptible {
                    generate(key) { event ->
                        val text = progressText(event) ?: return@generate
                        onEdt {
                            val current = states[key]
                            if (jobs[key] === self && current is TrailState.Loading) {
                                states[key] = current.copy(progress = text)
                                notifyChanged(key)
                            }
                        }
                    }
                }
                finish(key, self, TrailState.Ready(result, result.stale))
            } catch (_: CancellationException) {
                // 用户取消时 cancel() 已移除登记并恢复状态，这里什么都不会发生；
                // 其他来源的取消（比如 CLI 侧抛出）也要恢复，否则状态停在 Loading、登记也清不掉
                onEdt { if (jobs[key] === self) restoreBeforeGeneration(key) }
            } catch (e: Exception) {
                LOG.warn("会话轨迹生成失败 sessionKey=$key", e)
                finish(key, self, TrailState.Failed(errorText(e), previous))
            }
        }
        jobs[key] = job
        notifyChanged(key)
        job.start()
    }

    private fun finish(key: String, self: Job?, next: TrailState) {
        onEdt {
            if (jobs[key] !== self) return@onEdt
            // 先写结果再撤登记：看到任务已撤的人读到的一定是最终状态，不会停在 Loading
            states[key] = next
            beforeGeneration.remove(key)
            jobs.remove(key)
            notifyChanged(key)
        }
    }

    fun cancel(key: String) {
        val job = jobs[key] ?: return
        job.cancel()
        restoreBeforeGeneration(key)
    }

    private fun restoreBeforeGeneration(key: String) {
        // 与 finish 同序：先恢复状态再撤登记
        when (val before = beforeGeneration.remove(key)) {
            is TrailState -> states[key] = before
            else -> states.remove(key)
        }
        jobs.remove(key)
        notifyChanged(key)
    }

    /**
     * 标签页真正关闭：取消它发起的所有任务（包括 `/clear`、`/new` 迁移前的旧 key）并清掉记录，
     * 缓存仍保留在磁盘上。
     */
    fun releaseOwner(owner: String) {
        // 清掉后要通知：还有面板在监听这些会话（比如对同一会话「向右拆分」出的另一个编辑器）时，
        // 不通知它就会一直停在「加载中」
        owners.filterValues { it == owner }.keys.forEach { key ->
            forget(key)
            notifyChanged(key)
        }
    }

    private fun forget(key: String) {
        owners.remove(key)
        jobs.remove(key)?.cancel()
        beforeGeneration.remove(key)
        states.remove(key)
    }

    fun notifyChanged(key: String) {
        listeners.forEach { it(key) }
    }

    /** 返回移除函数，由调用方挂到自己的 Disposable 上。 */
    fun addListener(listener: (String) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    internal fun jobFor(key: String): Job? = jobs[key]

    private companion object {
        val NO_RECORD = Any()
        val LOG = logger<TrailStateStore>()
    }
}
