package com.github.izerui.imux.bridge.core

/** 本轮攒下的最终回复，连同产生它时的绑定代次；换绑后作废。 */
internal class PendingReply(
    val epoch: Long,
) {
    val text = StringBuilder()
}

/**
 * 一个终端标签在路由器里的状态：第几轮、本轮攒下的最终回复、是否已结算，以及等着在记录里出现的远程输入。
 *
 * 一轮的结算优先看会话记录里的结束标记；这一轮没有标记时，以 IDE 的完成信号兜底，信号带着它所属的轮次，
 * 下一轮已经开始的迟到信号作废。工具调用之前的文字只是过程说明，遇到工具调用或新一轮开始就丢弃。
 *
 * 不加锁：由 [BridgeRouter] 在自己的锁内调用。
 */
internal class TabState {
    /** 读到的第几轮，每开始一轮加一。 */
    var turn = 0L
        private set

    private var pending: PendingReply? = null

    /** 本轮已结算：之后再读到的回复是迟到的最终输出，立即发出。 */
    private var settled = false

    /** 本轮已由记录里的结束标记结算，IDE 的完成信号不再重复结算。 */
    private var endedByMarker = false

    private val remoteEchoes = ArrayDeque<String>()

    /** 后台调用的说明，按工具调用 id；等结果给出任务 id 时取走。调用失败就等不到结果，所以限量。 */
    private val launches = boundedMap<String, String>()

    /** 见过的后台任务的说明，按任务 id；续跑时沿用。只增不减，限量。 */
    private val taskNames = boundedMap<String, String>()

    /** 还在运行的后台任务，按开始顺序。不随轮次清空：后台任务本来就跨轮运行。 */
    private val running = LinkedHashSet<String>()

    fun backgroundLaunched(
        toolUseId: String,
        description: String?,
    ) {
        if (!description.isNullOrBlank()) launches[toolUseId] = description
    }

    fun backgroundStarted(
        taskId: String,
        toolUseId: String?,
        description: String?,
    ) {
        val name = toolUseId?.let { launches.remove(it) } ?: description?.takeIf { it.isNotBlank() }
        if (name != null) taskNames[taskId] = name
        running += taskId
    }

    fun backgroundEnded(taskId: String) {
        running -= taskId
    }

    /** 上一次提示时还在运行的任务；列表没变就不再提示。 */
    private var lastHinted: Set<String> = emptySet()

    /**
     * 这一轮结算后要不要提示还有后台任务在运行：有任务、且和上一次提示的不完全一样时，返回它们的说明
     * （没有说明的记为 null，由调用方给个统称）；否则返回 null。
     */
    fun backgroundHint(): List<String?>? {
        val current = running.toSet()
        if (current.isEmpty() || current == lastHinted) {
            lastHinted = current
            return null
        }
        lastHinted = current
        return running.map { taskNames[it] }
    }

    /**
     * 换了会话或会话记录被替换：之前记下的后台任务对不上了，全部丢掉。
     * 宁可漏报一次，也不能让一个再也等不到结束通知的任务每轮都被报“还在运行”。
     */
    fun clearBackground() {
        launches.clear()
        taskNames.clear()
        running.clear()
        lastHinted = emptySet()
    }

    /** 说的是这一轮的状态（例如结对审查开始），要等这一轮的回复发出后再发。 */
    private val afterReply = ArrayList<String>()

    /** 本轮已结算就返回 false，调用方立即发；否则排队，等结算时随回复之后发出。 */
    fun queueAfterReply(text: String): Boolean {
        if (settled && awaitingReplySince == null) return false
        afterReply += text
        return true
    }

    fun takeAfterReply(): List<String> = afterReply.toList().also { afterReply.clear() }

    /**
     * IDE 已结算这一轮，但回复还没读到（完成信号可能先于最终回复写进记录）：从 [awaitReply] 给的时刻起等它。
     * 等待期间这一轮的状态提示继续排队，回复到了或确认没有回复时再一起发。
     */
    var awaitingReplySince: Long? = null
        private set

    fun awaitReply(since: Long) {
        awaitingReplySince = since
    }

    fun stopAwaitingReply(): Boolean = (awaitingReplySince != null).also { awaitingReplySince = null }

    /** 有人说话或注入消息开启了新一轮（也可能是轮次进行中插话）：没结算的过程说明丢弃。 */
    fun startTurn() {
        awaitingReplySince = null
        turn++
        pending = null
        settled = false
        endedByMarker = false
    }

    /** 工具调用之前的文字只是过程说明。 */
    fun toolStarted() {
        pending = null
    }

    fun appendReply(
        epoch: Long,
        text: String,
    ) {
        // 换绑后旧绑定攒下的作废，重新开始
        val current = pending?.takeIf { it.epoch == epoch } ?: PendingReply(epoch).also { pending = it }
        if (current.text.isNotEmpty()) current.text.append("\n\n")
        current.text.append(text)
    }

    /** 这一轮被中断：中断前的过程说明作废，按已收尾处理，之后的完成信号不再结算。 */
    fun interrupt() {
        awaitingReplySince = null
        pending = null
        endedByMarker = true
        settled = true
    }

    /** 记录里读到本轮结束标记：结算，交出待发的回复。 */
    fun endByMarker(): PendingReply? {
        endedByMarker = true
        settled = true
        return takeReply()
    }

    /** IDE 报告第 [turn] 轮完成：属于当前轮且这一轮没有结束标记时结算，返回 true。 */
    fun completeByIde(turn: Long): Boolean {
        // 同一轮重复到达的完成信号（含中断后的）只结算一次，“没有文字回复”的提示才不会重复
        if (turn != this.turn || endedByMarker || settled) return false
        settled = true
        return true
    }

    /** 已结算时交出待发的回复，未结算返回 null。 */
    fun takeIfSettled(): PendingReply? = if (settled) takeReply() else null

    fun takeReply(): PendingReply? = pending.also { pending = null }

    /** 手机发来的输入提交到终端后，会在记录里再次出现，届时不回显到手机。 */
    fun expectEcho(text: String) {
        remoteEchoes.addLast(normalize(text))
        while (remoteEchoes.size > ECHO_CAPACITY) remoteEchoes.removeFirst()
    }

    fun forgetEcho(text: String) {
        remoteEchoes.remove(normalize(text))
    }

    /** 这条用户输入是不是刚从手机提交的；是就消耗掉登记。 */
    fun consumeEcho(text: String): Boolean = remoteEchoes.remove(normalize(text))

    /** CLI 写入记录时可能调整空白；只按空白归一后的全文匹配，匹配不上就当电脑输入。 */
    private fun normalize(text: String): String = text.trim().replace(WHITESPACE, " ")

    private companion object {
        const val ECHO_CAPACITY = 20
        const val BACKGROUND_CAPACITY = 200

        /** 超过 [BACKGROUND_CAPACITY] 时丢掉最早的。 */
        fun <K, V> boundedMap(): MutableMap<K, V> =
            object : LinkedHashMap<K, V>() {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > BACKGROUND_CAPACITY
            }
        val WHITESPACE = Regex("\\s+")
    }
}
