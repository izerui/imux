package com.github.izerui.imux.bridge.transcript

import com.github.izerui.imux.bridge.core.Binding
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** 会话记录文件与它的创建时间（判断迁移后是不是新会话）。 */
class SessionFile(
    val path: Path,
    val createdAt: Instant,
)

/** 带边界读取的结果，见 [TailRegistry.readUpTo]。 */
sealed interface BoundedRead {
    /** 完整读到了边界；[poll] 是这次新读到的部分。 */
    data class Reached(val poll: TailPoll) : BoundedRead

    /**
     * 没有可靠边界：取不到尾随器、信号到达时没有尾随器、读取位置已越过边界、尾随器已重建，
     * 或读的过程中文件被替换、截短、读取出错。[poll] 是这次已经读出的内容（例如“记录重启”），
     * 照常交出但不结算；没读时为 null。
     */
    data class Unreliable(val poll: TailPoll? = null) : BoundedRead
}

/**
 * IDE 信号发出那一刻某个尾随器所在文件的大小：只读到这里再结算。连同尾随器与它当时的重启代次一起记：
 * 之后文件被替换过，新文件的位置与旧边界无关（大小恰好相同也一样），边界作废。
 */
class ReadMark internal constructor(
    internal val tail: TranscriptTail,
    internal val restarts: Int,
    internal val upTo: Long?,
)

/**
 * 一个项目里各绑定标签的会话记录尾随器：何时新建、从哪里开始读、何时作废。
 *
 * - 以当前绑定为准：换绑（代次变了）或迁移（会话变了）后旧尾随器作废重建，新绑定从记录末尾开始，不倒灌历史。
 * - 迁移到新会话（/clear、/new，或 fork 出的新文件）从头读，但跳过复制进来的旧历史；回到旧会话从末尾开始。
 * - 远程控制关闭又开启过（开关代次变了）就全部作废，关闭期间的输出不推送到手机。
 *
 * 尾随器只在 [read] 的锁内建立和使用；IDE 回调线程只经 [migrated] 登记迁移时间、经 [mark] 记下读取边界。
 * 不依赖 IDE，绑定、会话文件与开关代次都由调用方给出。
 */
class TailRegistry(
    private val bindingForTab: (String) -> Binding?,
    private val sessionFile: (sessionId: String) -> SessionFile?,
    private val switchGeneration: () -> Long,
    private val now: () -> Instant = Instant::now,
) {
    private val lock = Mutex()
    // 增删只在锁内；[mark] 要在 IDE 信号到达的那一刻读，所以用并发表
    private val tails = ConcurrentHashMap<String, TailEntry>()
    private val migrations = ConcurrentHashMap<String, Migration>()
    private var lastGeneration = switchGeneration()

    /** 会话迁移后由 IDE 回调登记；只对登记时的那次绑定有效。 */
    fun migrated(
        tabId: String,
        epoch: Long,
    ) {
        migrations[tabId] = Migration(epoch, now())
    }

    /** 开关代次变了就丢弃全部尾随器与迁移记录；轮询在关闭期间也要调用，才不会漏掉“关了又开”。 */
    suspend fun observeSwitch() = lock.withLock { observeSwitchLocked() }

    /** 记下这个标签此刻的读取边界，交给 [read]。可在任意线程调用，不等锁。 */
    fun mark(tabId: String): ReadMark? = tails[tabId]?.tail?.let { ReadMark(it, it.restarts, it.size()) }

    /** 读出这个标签新写入的事件。还没有会话文件、或期间绑定变了时返回 null。 */
    suspend fun read(tabId: String): TailPoll? = lock.withLock { currentTail(tabId)?.poll() }

    /**
     * 只读到 [mark] 记下的位置为止，供 IDE 完成信号结算它所属的那一轮。拿不到可靠边界时返回
     * [BoundedRead.Unreliable]，不读：此刻取不到尾随器（标签已不再绑定、会话文件还没被发现）、
     * 信号到达时还没有尾随器或读不到文件大小（连接刚建立的空档）、
     * 读取位置已越过边界（普通轮询抢先读了下一轮）、尾随器期间被重建。这些情况下“当前轮”可能已不是
     * 信号所属的那一轮，宁可放弃这次兜底，也不把下一轮的过程说明当结论发出去。记下边界之后文件被替换过
     * （普通轮询已经发现并从新末尾开始）同样作废。读的过程中文件被替换、截短，
     * 或读到一半出错没读到边界，同样返回 [BoundedRead.Unreliable]，并带上已经读出的内容。
     */
    suspend fun readUpTo(
        tabId: String,
        mark: ReadMark?,
    ): BoundedRead =
        lock.withLock {
            val tail = currentTail(tabId) ?: return@withLock BoundedRead.Unreliable()
            val upTo = mark?.upTo
            if (upTo == null || mark.tail !== tail || mark.restarts != tail.restarts || tail.readPosition > upTo) {
                return@withLock BoundedRead.Unreliable()
            }
            val poll = tail.poll(upTo)
            // 读的过程中文件被替换或截短、或读到一半出错没读到边界：这一轮读没读全不确定，不结算，读到的照常交出
            if (poll.restarted || tail.readPosition < upTo) BoundedRead.Unreliable(poll) else BoundedRead.Reached(poll)
        }

    /** 在锁内：按当前绑定取（需要时新建）尾随器。 */
    private fun currentTail(tabId: String): TranscriptTail? {
        observeSwitchLocked()
        // 不再绑定的标签（标签关闭、挂接到新标签）留下的尾随器顺带清掉
        tails.keys.retainAll { bindingForTab(it) != null }
        val binding = bindingForTab(tabId) ?: return null
        return tails[tabId]?.takeIf { it.matches(binding) }?.tail ?: newTail(binding)
    }

    private fun observeSwitchLocked() {
        val generation = switchGeneration()
        if (generation == lastGeneration) return
        lastGeneration = generation
        tails.clear()
        migrations.clear()
    }

    /** 会话文件出现后才建立尾随器。 */
    private fun newTail(binding: Binding): TranscriptTail? {
        val sessionId = binding.target.sessionId ?: return null
        val file = sessionFile(sessionId) ?: return null
        val tabId = binding.target.tabId
        // 只认本次绑定期间记下的迁移；换绑、断开后残留的旧值不能让新绑定从头读
        val migration = migrations[tabId]?.takeIf { it.epoch == binding.epoch }
        val skipBefore = migration?.at?.takeIf { readFromStart(file.createdAt, it) }?.let { copiedHistoryBefore(file.createdAt, it) }
        val tail = TranscriptTail(file.path, TranscriptDecoders.forAgent(binding.target.agentType), skipBefore = skipBefore)
        if (skipBefore == null) tail.startAtEnd()
        // 只移除本次用到的迁移时间；期间又发生的迁移会写入新值，不能被吞掉
        if (migration != null) migrations.remove(tabId, migration)
        tails[tabId] = TailEntry(binding.epoch, sessionId, tail)
        return tail
    }

    /** 会话迁移的时间，连同当时的绑定代次。 */
    private data class Migration(
        val epoch: Long,
        val at: Instant,
    )

    /** 尾随器连同建立它时的绑定代次与会话；与当前绑定同一代次、同一会话才能沿用。 */
    private class TailEntry(
        val epoch: Long,
        val sessionId: String,
        val tail: TranscriptTail,
    ) {
        fun matches(binding: Binding): Boolean = binding.epoch == epoch && binding.target.sessionId == sessionId
    }
}

/**
 * 迁移后是否从头读取会话记录。会话创建时间在迁移前 [NEW_SESSION_WINDOW_SECONDS] 秒以内视为新会话
 * （/clear、/new），从头转发；更早的会话（resume 回旧会话）从末尾开始，避免倾倒全部历史。
 */
internal fun readFromStart(
    createdAt: Instant,
    migratedAt: Instant,
): Boolean = !createdAt.isBefore(migratedAt.minusSeconds(NEW_SESSION_WINDOW_SECONDS))

/**
 * 从头读时，早于这个时间的行是复制来的历史。fork 或交给 daemon 生成的新文件会带着原会话的全部历史
 * （保留原时间戳），这些行都写在文件创建之前；新内容写在创建之后。取创建时间与迁移时间的较早者再留
 * [COPIED_HISTORY_MARGIN_SECONDS] 秒余量：不支持创建时间的文件系统会退回修改时间，此时以迁移时间为准，
 * 最多漏掉迁移前的首轮。
 */
internal fun copiedHistoryBefore(
    createdAt: Instant,
    migratedAt: Instant,
): Instant = minOf(createdAt, migratedAt).minusSeconds(COPIED_HISTORY_MARGIN_SECONDS)

private const val NEW_SESSION_WINDOW_SECONDS = 60L
private const val COPIED_HISTORY_MARGIN_SECONDS = 2L
