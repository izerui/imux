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

    /** 后台调用的说明，按工具调用 id；等结果给出任务 id 时取走。 */
    private val launches = HashMap<String, String>()

    /** 见过的后台任务的说明，按任务 id；续跑时沿用。 */
    private val taskNames = HashMap<String, String>()

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

    /** 还在运行的后台任务的说明；没有说明的记为 null，由调用方给个统称。 */
    fun runningBackground(): List<String?> = running.map { taskNames[it] }

    /** 有人说话或注入消息开启了新一轮（也可能是轮次进行中插话）：没结算的过程说明丢弃。 */
    fun startTurn() {
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

    /** 记录里读到本轮结束标记：结算，交出待发的回复。 */
    fun endByMarker(): PendingReply? {
        endedByMarker = true
        settled = true
        return takeReply()
    }

    /** IDE 报告第 [turn] 轮完成：属于当前轮且这一轮没有结束标记时结算，返回 true。 */
    fun completeByIde(turn: Long): Boolean {
        if (turn != this.turn || endedByMarker) return false
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
        val WHITESPACE = Regex("\\s+")
    }
}
