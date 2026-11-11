package com.github.izerui.imux.bridge.core

/**
 * 一个标签挂起的 hook 请求，按到达顺序排队；只有队首推给手机。纯状态，由 [BridgeRouter] 在它的锁内访问。
 * 条目出队即了结：出队的一方负责（在锁外）调用它的 [Entry.respond]，所以每个请求只会回一次。
 */
internal class HookPromptQueue {
    class Entry(
        val request: HookRequest,
        val respond: (HookDecision) -> Unit,
    ) {
        /** 推给手机时的绑定代次；没推过为 null。换绑后旧代次推过的不再告诉新手机。 */
        var pushedEpoch: Long? = null

        /** 提问已答的题：题目原文 → 答案，按题目顺序。 */
        val answers = ArrayList<Pair<String, String>>()

        /** 提问下一道要答的题。 */
        val questionIndex: Int get() = answers.size
    }

    private val entries = ArrayList<Entry>()

    val size: Int get() = entries.size

    fun add(entry: Entry) {
        entries += entry
    }

    fun head(): Entry? = entries.firstOrNull()

    /** 按 id 出队，不在队中返回 null。 */
    fun remove(id: Long): Entry? = entries.firstOrNull { it.request.id == id }?.also { entries.remove(it) }

    /** 电脑端允许后对上的请求：[matchKey] 相同的取最早一个出队。 */
    fun matchLocal(matchKey: String): Entry? = entries.firstOrNull { it.request.matchKey == matchKey }?.also { entries.remove(it) }

    /** 主会话（无 agentId）的条目全部出队，按到达顺序返回；子代理的留在队中。 */
    fun drainMainSession(): List<Entry> = entries.filter { it.request.agentId == null }.also { entries.removeAll(it.toSet()) }

    /** 全部出队，按到达顺序返回。 */
    fun drain(): List<Entry> = entries.toList().also { entries.clear() }
}
