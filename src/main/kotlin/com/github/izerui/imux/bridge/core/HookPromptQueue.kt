package com.github.izerui.imux.bridge.core

/**
 * 一个标签挂起的 hook 请求，按到达顺序排队；只有队首推给手机。纯状态，由 [BridgeRouter] 在它的锁内访问。
 * 条目出队即了结：出队的一方负责（在锁外）调用它的 [Entry.respond]，所以每个请求只会回一次。
 *
 * 另记手机已允许、还在等 PostToolUse 的请求（最多 [MAX_ANSWERED_REMOTELY] 条）：它们跑完后的 PostToolUse
 * 先在这里抵消，免得对上队中同代理、同工具、同参数的另一个请求，把它误判为电脑端已允许。
 */
internal class HookPromptQueue {
    class Entry(
        val request: HookRequest,
        /** 回给 hook；返回是否真的写出（连接已被关闭时为 false）。 */
        val respond: (HookDecision) -> Boolean,
    ) {
        /** 已送达手机时的绑定代次；没送达过为 null。只有送达给当前代次的，手机的回复才算作答。 */
        var pushedEpoch: Long? = null

        /**
         * 正在进行的推送（发送中、限频重试中），送达、失败或被取消后由发起者清除；防止同一队首被并发重复推送。
         * 清除时按对象比对，只清自己发起的那次。
         */
        var pushing: Push? = null

        /** 手机可能已经看到（已送达或还在路上）：电脑端了结、作废时据此决定要不要告诉手机。 */
        fun mayHaveReached(epoch: Long): Boolean = pushedEpoch == epoch || pushing?.epoch == epoch

        /** 提问已答的题：题目原文 → 答案，按题目顺序。 */
        val answers = ArrayList<Pair<String, String>>()

        /** 提问下一道要答的题。 */
        val questionIndex: Int get() = answers.size
    }

    /** 一次把 [entry] 推给绑定代次 [epoch] 的推送，正文 [text]。 */
    class Push(val entry: Entry, val epoch: Long, val text: String)

    private val entries = ArrayList<Entry>()

    /** 手机已允许、等 PostToolUse 的请求，按作答顺序。 */
    private val answeredRemotely = ArrayDeque<HookRequest>()

    val size: Int get() = entries.size

    fun add(entry: Entry) {
        entries += entry
    }

    fun head(): Entry? = entries.firstOrNull()

    /** 按 id 出队，不在队中返回 null。 */
    fun remove(id: Long): Entry? = entries.firstOrNull { it.request.id == id }?.also { entries.remove(it) }

    /** 电脑端允许后对上的请求：与 [done] 匹配（[HookRequest.matches]）的取最早一个出队。 */
    fun matchLocal(done: HookToolDone): Entry? = entries.firstOrNull { it.request.matches(done) }?.also { entries.remove(it) }

    /** 与 [request] 同一代理、同一工具的旧条目全部出队，按到达顺序返回。 */
    fun supersededBy(request: HookRequest): List<Entry> =
        entries.filter { it.request.agentId == request.agentId && it.request.toolName == request.toolName }
            .also { entries.removeAll(it.toSet()) }

    /** 记下手机已允许的 [request]，超出上限丢最早的。 */
    fun answeredRemotely(request: HookRequest) {
        answeredRemotely.addLast(request)
        while (answeredRemotely.size > MAX_ANSWERED_REMOTELY) answeredRemotely.removeFirst()
    }

    /** 撤销 [answeredRemotely] 记下的 [request]（回写失败，不会有 PostToolUse）。 */
    fun forgetAnsweredRemotely(request: HookRequest) {
        answeredRemotely.remove(request)
    }

    /** [done] 是手机允许过的某个请求跑完了：抵消最早匹配的一条记录并返回 true。 */
    fun consumeAnsweredRemotely(done: HookToolDone): Boolean {
        val index = answeredRemotely.indexOfFirst { it.matches(done) }
        if (index < 0) return false
        answeredRemotely.removeAt(index)
        return true
    }

    /**
     * 本轮结束：主会话（无 agentId）与前台子代理（agentId 不在仍在运行的后台任务 [background] 里）的条目全部出队，
     * 按到达顺序返回，它们的手机已允许记录一并清掉；后台子代理的留着。前台子代理活不过主会话的这一轮。
     */
    fun drainTurn(background: Set<String>): List<Entry> {
        fun endsWithTurn(agentId: String?) = agentId == null || agentId !in background
        answeredRemotely.removeAll { endsWithTurn(it.agentId) }
        return entries.filter { endsWithTurn(it.request.agentId) }.also { entries.removeAll(it.toSet()) }
    }

    /** 子代理 [agentId] 结束：它的条目全部出队，按到达顺序返回，它的手机已允许记录一并清掉。 */
    fun drainAgent(agentId: String): List<Entry> {
        answeredRemotely.removeAll { it.agentId == agentId }
        return entries.filter { it.request.agentId == agentId }.also { entries.removeAll(it.toSet()) }
    }

    /** 全部出队，按到达顺序返回。 */
    fun drain(): List<Entry> = entries.toList().also { entries.clear() }

    private companion object {
        const val MAX_ANSWERED_REMOTELY = 32
    }
}
