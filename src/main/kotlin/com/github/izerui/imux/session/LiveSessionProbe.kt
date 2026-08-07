package com.github.izerui.imux.session

import com.github.izerui.imux.model.AgentType

/** 注入给 CLI 进程的标记，值是终端的 tabId。见 [LiveSessionProbe]。 */
const val IMUX_TAB_ENV: String = "IMUX_TAB"

/** 某个 imux 终端此刻真正在跑的会话。 */
data class LiveTab(val tabId: String, val sessionId: String)

/** 终端记账的会话 id 与实际不符。[from] 是插件以为的，[to] 是实际的。 */
data class KeyDrift(val tabId: String, val from: String, val to: String)

/**
 * 探测每个 imux 终端此刻真正在跑哪个会话。
 *
 * **为什么需要它**：会话 id 不是终端的固有属性。用户在终端里敲 `/clear`（claude）
 * 或 `/new`（codex），CLI 会换一个会话 id 并另起会话文件，而**进程自始至终没变**。
 * 本机实测：pid 11814 启动于 17:26，它当前的会话文件却创建于 17:56——同一个进程，
 * 中途换了身份。
 *
 * 插件原先只在自己发起「新建」时才做 key 迁移（靠 pending 认领），从终端内部发起的
 * 换 id 完全不在视野内。后果是终端一直记在旧 id 下：标题停更、未读清不掉、
 * 完成通知盯着一个不再增长的文件，而用户在列表里点新会话时又会以真实 id 重开一个
 * `--resume` 终端，与仍在运行的原进程抢同一个会话，CLI 报
 * 「currently running as a background agent」。
 *
 * **怎么认出「这个进程属于哪个终端」**：终端启动时注入 [IMUX_TAB_ENV]，值是我们自己
 * 发的 tabId。env 会被 shell 原样传给 CLI 进程，读回来即可对号入座。用 tabId 而不用
 * pid 作为身份，是因为命令是 `shell -l -i -c "claude"`，CLI 是 shell 的子进程，
 * 而 shell 是否 exec 掉自己因 shell 与平台而异——tabId 不受这层影响。
 *
 * **怎么知道「这个进程此刻在跑哪个会话」**：两个 CLI 的可观测面恰好相反，各取一条：
 * - claude 把 `~/.claude/sessions/<pid>.json` 当运行态文件实时维护，换 id 时原地更新，
 *   是一手权威信息；但它**不持有**会话文件句柄（open-append-close，实测 lsof 为空）
 * - codex 没有任何运行态文件（`state_5.sqlite` 无 pid 字段，rollout 的 session_meta
 *   也不记 pid），但它**长期持有** rollout 文件句柄（实测一个跑了一天多的进程仍持有），
 *   于是反过来从打开的文件名取 thread id
 *
 * 所有 IO 以函数注入，本类因此完全脱离进程表与文件系统，可直接测试。
 */
class LiveSessionProbe(
    /** 活着的 CLI 进程 pid。 */
    private val pidsOf: (AgentType) -> List<Long>,
    /** 该进程环境变量里的 [IMUX_TAB_ENV]；不是 imux 开的进程返回 null。 */
    private val tabIdOf: (Long) -> String?,
    /** claude：该 pid 此刻在跑的会话 id。 */
    private val claudeSessionOf: (Long) -> String?,
    /** codex：该 pid 正持有的 rollout 文件路径。 */
    private val rolloutsHeldBy: (Long) -> List<String>,
) {

    /**
     * 探测所有带 imux 标记的进程。
     *
     * 认不出当前会话的进程直接略过而不是猜——宁可这一轮不认领，也不能认错：
     * 认错会把终端迁到别人的会话上，比不迁移更糟。
     */
    fun probe(): List<LiveTab> =
        AgentType.entries.flatMap { type ->
            pidsOf(type).mapNotNull { pid ->
                val tabId = tabIdOf(pid) ?: return@mapNotNull null
                val sessionId = when (type) {
                    AgentType.CLAUDE -> claudeSessionOf(pid)
                    AgentType.CODEX -> currentThreadOf(rolloutsHeldBy(pid))
                } ?: return@mapNotNull null
                LiveTab(tabId, sessionId)
            }
        }

    /**
     * 进程可能同时持有新旧两个 rollout——`/new` 之后旧句柄未必立刻关闭。
     * 文件名里的时间戳是零填充的 `YYYY-MM-DDThh-mm-ss`，字典序即时间序，取最大者。
     */
    private fun currentThreadOf(rollouts: List<String>): String? =
        rollouts.filter { threadIdOfRollout(it) != null }
            .maxByOrNull { fileNameOf(it) }
            ?.let(::threadIdOfRollout)
}

/**
 * 从 codex 的 rollout 文件路径取 thread id。
 *
 * 文件名形如 `rollout-2026-08-06T13-59-47-<uuid>.jsonl`，uuid 在末尾。
 * 只认这个形状，别的文件（`history.jsonl` 等）一律返回 null——codex 进程会打开
 * 一大堆文件，不筛就会把无关路径当会话 id。
 */
internal fun threadIdOfRollout(path: String): String? {
    val name = fileNameOf(path)
    if (!name.startsWith(ROLLOUT_PREFIX) || !name.endsWith(JSONL_SUFFIX)) return null
    val id = name.removeSuffix(JSONL_SUFFIX).takeLast(UUID_LENGTH)
    return id.takeIf(::looksLikeUuid)
}

/**
 * 终端记账与实际的差异。
 *
 * 两侧都取交集：探测不到的终端保持现状（CLI 可能还没起来），
 * 不属于任何已开标签页的探测结果丢弃（刚关掉的标签页进程可能还没退干净）。
 *
 * 同一个 tabId 报出多个**不同**会话时一条都不产出。claude 的后台 agent 会继承父进程
 * 的环境变量，于是带着同一个 IMUX_TAB 却报出它自己那个独立的会话 id；真去迁就可能
 * 迁到后台 agent 的会话上，且结果取决于遍历顺序。分不清就别动。
 * （上游还会用 [interactivePids] 把 bg 进程挡在外面，这里是最后一道防线。）
 */
internal fun driftOf(
    openTabs: Map<String, String>,
    live: List<LiveTab>,
): List<KeyDrift> = live.groupBy { it.tabId }
    .mapNotNull { (tabId, tabs) ->
        val sessionId = tabs.map { it.sessionId }.distinct().singleOrNull() ?: return@mapNotNull null
        val current = openTabs[tabId] ?: return@mapNotNull null
        if (current == sessionId) null else KeyDrift(tabId, from = current, to = sessionId)
    }

/**
 * 可用于探测的 claude 进程：只要交互式终端，不要后台 agent。
 *
 * 后台 agent（`kind=bg`）是 claude 主进程派生的，**会继承 IMUX_TAB**——实测 claude
 * 的子进程确实能读到 imux 注入的变量。但它有自己独立的会话 id，认领它就会把终端
 * 迁到一个用户根本没在看的会话上。
 *
 * `kind` 缺失时按交互式对待：漏掉一个真正的终端，比多认一个的代价大。
 */
internal fun interactivePids(runtime: Collection<ClaudeRuntimeSession>): List<Long> =
    runtime.filterNot { it.isBackground }.map { it.pid }

/**
 * 探测是异步的，结果落地前世界可能已经变了。只保留仍然成立的那些：
 * 标签页还在（tabId 仍存在），且它仍然记着我们探测时看到的那个旧 id。
 *
 * 少了这道复核，「探测期间用户关掉标签页又双击了旧会话」就会出事：
 * 新终端的 sessionKey 与旧的一样，但它是另一个终端，把它迁走是张冠李戴。
 */
internal fun stillApplicable(
    drifts: List<KeyDrift>,
    currentTabs: Map<String, String>,
): List<KeyDrift> = drifts.filter { currentTabs[it.tabId] == it.from }

private fun fileNameOf(path: String): String = path.substringAfterLast('/')

private fun looksLikeUuid(value: String): Boolean =
    value.length == UUID_LENGTH &&
        value.withIndex().all { (index, char) ->
            if (index in UUID_DASH_POSITIONS) char == '-' else char.isLetterOrDigit()
        }

private const val ROLLOUT_PREFIX = "rollout-"
private const val JSONL_SUFFIX = ".jsonl"
private const val UUID_LENGTH = 36
private val UUID_DASH_POSITIONS = setOf(8, 13, 18, 23)
