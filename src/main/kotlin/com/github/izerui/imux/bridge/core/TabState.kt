package com.github.izerui.imux.bridge.core

/** 停着等人的框是哪种：提问、权限确认、计划确认。 */
enum class PromptKind { QUESTION, PERMISSION, PLAN }

/**
 * 推给手机、等着手机作答的框：[epoch] 是当时的绑定代次，[toolUseId] 是它对应的调用，
 * 记录里出现这个调用的结果就说明已答掉。
 */
internal data class ShownPrompt(
    val epoch: Long,
    val toolUseId: String,
    val kind: PromptKind,
    val answeredRemotely: Boolean = false,
)

/** 还没有结果的工具调用：权限、计划确认停着时告诉手机的内容。 */
internal data class ToolCall(
    val name: String,
    val detail: String,
)

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

    /**
     * 还在后台跑的子代理，直到收到它结束的通知；启动调用返回结果不算结束。
     * 它们内部的工具也会弹确认框却不写在主记录里，有它们在跑时不认定停着的是主会话的哪个框。
     */
    private val runningSubagents = HashSet<String>()

    fun backgroundStarted(
        taskId: String,
        toolUseId: String?,
        description: String?,
        subagent: Boolean = false,
    ) {
        val name = toolUseId?.let { launches.remove(it) } ?: description?.takeIf { it.isNotBlank() }
        if (name != null) taskNames[taskId] = name
        running += taskId
        if (subagent) runningSubagents += taskId
    }

    fun backgroundEnded(taskId: String) {
        running -= taskId
        runningSubagents -= taskId
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
        runningSubagents.clear()
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
        forgetPrompts()
        cancelDismissal()
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
        forgetPrompts()
        awaitingReplySince = null
        pending = null
        endedByMarker = true
        settled = true
    }

    /** 记录里读到本轮结束标记：结算，交出待发的回复。 */
    fun endByMarker(): PendingReply? {
        endedByMarker = true
        settled = true
        // 这一轮已经结束：其中的框都已过去，即使还没读到它们的结果
        forgetPrompts()
        return takeReply()
    }

    /** IDE 报告第 [turn] 轮完成：属于当前轮且这一轮没有结束标记时结算，返回 true。 */
    fun completeByIde(turn: Long): Boolean {
        // 同一轮重复到达的完成信号（含中断后的）只结算一次，“没有文字回复”的提示才不会重复
        if (turn != this.turn || endedByMarker || settled) return false
        settled = true
        // 确认是当前这一轮结束了，其中的框都已过去；上一轮迟到的信号在上面就返回了，不清状态
        forgetPrompts()
        return true
    }

    /** 已结算时交出待发的回复，未结算返回 null。 */
    fun takeIfSettled(): PendingReply? = if (settled) takeReply() else null

    fun takeReply(): PendingReply? = pending.also { pending = null }

    /*
     * 停着等人的框。不读屏，判断只用两条一刀切的规则：
     * - 有没有框：运行态在等待（[waitingActive]），或记录里还有没结果的提问、计划确认（[hasPendingInteraction]）。
     *   任一成立，手机的话就不当普通消息贴进终端；
     * - 是哪个框：正在等待、只有一个没结果的调用、没有后台子代理在跑、它也不是已过了等待的旧调用，就是它（[waitingSubject]）；
     *   否则不推断，只提示到 IDE 中处理。
     */

    /** 会话记录里还没作答的提问（AskUserQuestion）及其调用 id；结果写进记录、新一轮开始、被中断或本轮结束时作废。 */
    var askedQuestions: List<AskedQuestion>? = null
        private set
    private var askedId: String? = null

    /** 手机正在答第几题（从 0 起），以及已答各题交给 Claude 的文字。 */
    var questionIndex = 0
        private set
    private val questionAnswers = mutableListOf<String>()

    fun questionAsked(
        toolUseId: String,
        questions: List<AskedQuestion>,
    ) {
        askedId = toolUseId
        askedQuestions = questions
        questionIndex = 0
        questionAnswers.clear()
    }

    fun questionClosed() {
        askedId = null
        askedQuestions = null
        questionIndex = 0
        questionAnswers.clear()
    }

    /** 手机答完当前这题：记下答案，翻到下一题。 */
    fun questionAnswered(answer: String) {
        questionAnswers += answer
        questionIndex++
    }

    /** 各题答案连同最后这题的 [last]，一题一行。 */
    fun allAnswers(last: String): String = (questionAnswers + last).joinToString("\n")

    /** 还没有结果的工具调用（不含提问），按发起顺序；结果写进记录时移除。调用失败可能等不到结果，所以限量。 */
    private val pendingTools = boundedMap<String, ToolCall>()

    fun toolCalled(
        toolUseId: String,
        call: ToolCall,
    ) {
        pendingTools[toolUseId] = call
    }

    fun toolFinished(toolUseId: String?) {
        if (toolUseId == null) return
        pendingTools.remove(toolUseId)
        passed.remove(toolUseId)
        if (toolUseId == askedId) questionClosed()
    }

    /** 运行态正停着等人：收到等待通知时置位；退出等待（转忙碌或空闲）、新一轮、本轮结束或记录重启时清除。 */
    var waitingActive = false
        private set

    /** 退出等待时还没结果的调用：那次等待已经过去（已作答、已获准在跑），之后不再当作停着的框。 */
    private val passed = HashSet<String>()

    /** 这次等待的原因（CLI 的 `waitingFor`）：权限确认只认 `permission prompt`，别的原因下没结果的调用可能只是在跑。 */
    private var waitingReason: String? = null

    fun waitingStarted(reason: String?) {
        waitingActive = true
        waitingReason = reason
    }

    /** 运行态退出等待：停着的框已过去，此刻没结果的调用都已过了这次等待。 */
    fun waitingExited() {
        waitingActive = false
        waitingReason = null
        passed += pendingTools.keys
        askedId?.let { passed += it }
    }

    /** 记录里还有没结果、也没过等待的提问或计划确认：它们一定停着等人，即使等待通知还没到。 */
    fun hasPendingInteraction(): Boolean =
        askedId?.let { it !in passed } == true ||
            pendingTools.any { (id, call) -> call.name == PermissionPrompts.EXIT_PLAN_MODE && id !in passed }

    /** 能确定此刻停着的框：正在等待，只有一个没结果的调用，没有后台子代理在跑，它也没过等待；否则 null。 */
    fun waitingSubject(): Subject? {
        if (!waitingActive || runningSubagents.isNotEmpty()) return null
        val question = askedId
        if (question != null) {
            val questions = askedQuestions ?: return null
            return Subject(question, PromptKind.QUESTION, null, questions).takeIf { pendingTools.isEmpty() && question !in passed }
        }
        val (id, call) = pendingTools.entries.singleOrNull()?.toPair() ?: return null
        if (id in passed) return null
        // 同步 Agent 尚未返回时没有后台启动事件；权限框可能属于其内部工具，主记录里的任务参数不是授权内容。
        if (call.name == "Agent") return null
        if (call.name == PermissionPrompts.EXIT_PLAN_MODE) return Subject(id, PromptKind.PLAN, call, null)
        return Subject(id, PromptKind.PERMISSION, call, null).takeIf { waitingReason == PERMISSION_PROMPT }
    }

    /** 能确定、又还没推过的停着的框：推送后记下，手机作答后、退出等待前不重复推。 */
    fun subjectToPush(epoch: Long): Subject? {
        val subject = waitingSubject() ?: return null
        if (subject.toolUseId == lastPushed) return null
        lastPushed = subject.toolUseId
        shownPrompt = ShownPrompt(epoch, subject.toolUseId, subject.kind)
        return subject
    }

    private var lastPushed: String? = null

    /** 此刻能确定的框已经推过（等待通知重复到达）。 */
    fun subjectAlreadyPushed(): Boolean = waitingSubject()?.toolUseId?.let { it == lastPushed } == true

    /** 停着的框：提问带 [questions]，权限、计划确认带 [call]。 */
    data class Subject(
        val toolUseId: String,
        val kind: PromptKind,
        val call: ToolCall?,
        val questions: List<AskedQuestion>?,
    )

    /** 推给手机、尚未确认关闭的框；数字写入输入项时仍允许再次作答。 */
    var shownPrompt: ShownPrompt? = null
        private set

    /** 手机来答：不再跟踪，交出原来的，按键失败时用 [restorePrompt] 放回。 */
    fun promptClosed(): ShownPrompt? = shownPrompt.also { shownPrompt = null }

    /** 期间没推过新框时才放回。 */
    fun restorePrompt(prompt: ShownPrompt) {
        if (shownPrompt == null) shownPrompt = prompt
    }

    /** 先标记手机作答来源，防止按键返回前到达的工具结果误报电脑作答；不关闭作答入口。 */
    fun promptAnswerStarted(): ShownPrompt? = shownPrompt?.also { shownPrompt = it.copy(answeredRemotely = true) }

    /** 只回滚仍在跟踪的同一个框，期间已关闭或被替换的框不能复活。 */
    fun promptAnswerFailed(prompt: ShownPrompt) {
        if (shownPrompt == prompt.copy(answeredRemotely = true)) shownPrompt = prompt
    }

    /** 记录里出现了 [toolUseId] 的结果：推过的框若是它就关闭并交出。 */
    fun closePromptFor(toolUseId: String?): ShownPrompt? {
        val shown = shownPrompt ?: return null
        if (toolUseId == null || shown.toolUseId != toolUseId) return null
        shownPrompt = null
        return shown.takeUnless { it.answeredRemotely }
    }

    /**
     * 新一轮开始、被中断、本轮结束或记录重启：之前的框都已过去（没写结果也一样），不再跟踪，
     * 否则手机后来的话会被当成在答那个框。
     */
    fun forgetPrompts() {
        questionClosed()
        pendingTools.clear()
        passed.clear()
        shownPrompt = null
        lastPushed = null
        waitingActive = false
        waitingReason = null
    }

    /** 手机作答时插件按 Esc 关掉的框对应的调用；等它的结果。 */
    private var dismissedToolUseId: String? = null

    /** 刚读到被关掉那次调用的结果：紧随其后的中断标记是 Esc 引起的。 */
    private var dismissedResultSeen = false

    /**
     * 插件要按 Esc 关掉 [toolUseId] 的框。Esc 后 Claude 写下这次调用的结果，通常紧跟一条中断标记，但不保证有；
     * 所以只认紧跟在这个结果后面的中断，不留一个会吞掉之后真实中断的标记。
     */
    fun expectDismissal(toolUseId: String) {
        dismissedToolUseId = toolUseId
        dismissedResultSeen = false
    }

    fun cancelDismissal() {
        dismissedToolUseId = null
        dismissedResultSeen = false
    }

    /**
     * 每个事件处理前调用，返回它是不是插件按 Esc 引起的中断。读到被关掉那次调用的结果后，只有紧跟其后的中断算；
     * 中间夹了别的事件（会话接着做了），之后的中断就是电脑上真的中断。
     */
    fun dismissalInterrupt(event: SessionEvent): Boolean {
        when (event) {
            is SessionEvent.ToolResult -> {
                if (event.toolUseId != null && event.toolUseId == dismissedToolUseId) {
                    dismissedToolUseId = null
                    dismissedResultSeen = true
                }
                return false
            }
            SessionEvent.TurnInterrupted -> return dismissedResultSeen.also { dismissedResultSeen = false }
            else -> {
                dismissedResultSeen = false
                return false
            }
        }
    }

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

        /** Claude 运行态文件里权限确认（含计划确认）的等待原因。 */
        const val PERMISSION_PROMPT = "permission prompt"
        const val BACKGROUND_CAPACITY = 200

        /** 超过 [BACKGROUND_CAPACITY] 时丢掉最早的。 */
        fun <K, V> boundedMap(): MutableMap<K, V> =
            object : LinkedHashMap<K, V>() {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > BACKGROUND_CAPACITY
            }
        val WHITESPACE = Regex("\\s+")
    }
}
