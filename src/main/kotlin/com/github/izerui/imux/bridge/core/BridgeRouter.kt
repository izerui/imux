package com.github.izerui.imux.bridge.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * 渠道与终端之间的中介。两边互不认识，只通过绑定关系连接。
 *
 * 入站：校验本人 → 平台消息去重 → 找绑定 → 交给终端端口。不判断 Agent 忙闲，
 * 排队还是插入由 CLI 自己决定；这里只保证按到达顺序逐条提交。
 * 终端未就绪时在提交锁内有限重试，每次尝试前都复核绑定，换绑或解绑后立即停止。
 * 出站：每轮只推最终回复——最后一次工具调用之后的助手文本，在本轮结算时作为一条发出，
 * 中间的过程说明不推，避免逐条推送触发平台限频。轮次的划分与结算规则在 [TabState]；
 * 电脑端输入带标签单独转发；远程输入在记录中再次出现时不回显；等待确认立即推送。
 * Claude 的确认框（权限、提问、计划）经 PermissionRequest hook 送来（含子代理的），按标签排队、一次只推队首；
 * 手机的回复译成 [HookDecision] 回给 hook，不按键。电脑端先答时由 hook 入口告知，先答者生效。
 * 没有 hook 请求挂着时（Codex、Pi，或没能注入 hook 的 Claude 标签）停在框上只提示到 IDE 中处理，
 * 等待期间手机的话不贴进终端。
 * 同一标签的 [onSessionEvents]、[onTurnCompleted] 由调用方串行调用。
 * 只做简单转接：换绑、断开的空档里漏掉的内容不补（见 AGENTS.md 手机桥接约定）。
 */
class BridgeRouter(
    private val bindings: BindingRegistry,
    private val ownerOf: (RobotId) -> RobotOwner?,
    private val sessionPort: TerminalSessionPort,
    private val channelOf: (RobotId) -> ChannelAdapter?,
    private val texts: BridgeTexts,
    private val onDelivery: (OutboundMessage, DeliveryResult) -> Unit = { _, _ -> },
    /** 入站附件落盘；默认不支持附件。 */
    private val attachmentStore: AttachmentStore = AttachmentStore.NONE,
    /** 终端未就绪时两次尝试之间的间隔；测试传 0。 */
    private val notReadyRetryDelayMs: Long = 300,
    /** 渠道限频拒发后等多久重发；测试传 0。 */
    private val rateLimitRetryDelayMs: Long = 20_000,
    /** IDE 完成信号到了却还没读到回复时，最多等多久再判定这一轮没有文字回复。 */
    private val noReplyGraceMs: Long = 3_000,
    private val now: () -> Long = System::currentTimeMillis,
    /** 自动接回后等机器人上线的最长时间。 */
    private val reconnectWaitMs: Long = 30_000,
    /** 警告日志出口（core 不依赖 IDE 日志）；只传原因，不含路径、对端 id、正文与密钥。 */
    private val onWarning: (String) -> Unit = {},
    /** 等渠道报告真正上线；由接线层订阅连接状态，默认用于已就绪的独立端口。 */
    private val awaitOnline: suspend (RobotId) -> Unit = {},
    /**
     * 发到手机的消息末尾的署名（工程 · 智能体 · 会话标题），手机上同时连着多个会话时据此分辨；返回 null 不署名。
     * 凡能确定所属会话的消息都署名；连接状态提示（连接、恢复连接、断开、离线）正文已写明会话，不署名。
     */
    private val sessionLabelOf: (Binding) -> String? = { null },
    /**
     * 断开、离线提示正文里的会话：与连接提示一样写成「💬 工程 · 智能体 · 标题」。
     * 标题由调用方传入：断开时标签可能已关，只能用它还开着时记下的名字。
     */
    private val sessionNameOf: (Binding, String) -> String = { _, title -> title },
) {
    /** 每个机器人一把提交锁：同一机器人按到达顺序提交，不同机器人互不阻塞。只在 [lock] 内取用。 */
    private val submitLocks = HashMap<RobotId, Mutex>()
    private val lock = Any()
    private val seen = HashMap<RobotId, LinkedHashSet<String>>()

    /** 各终端标签的轮次与回显状态，只在 [lock] 内访问。 */
    private val tabs = HashMap<String, TabState>()

    /** 各标签挂起的 hook 请求，只在 [lock] 内访问；与 [tabs] 分开存放，清掉轮次状态时不会漏回还挂着的请求。 */
    private val hookQueues = HashMap<String, HookPromptQueue>()

    /** 加锁提交阶段的结果。 */
    private sealed interface SubmitPhase {
        data class Done(val result: SubmitResult) : SubmitPhase
        data object BindingGone : SubmitPhase
        data object BindingChanged : SubmitPhase

        /** hook 请求挂着时回复带了附件（或请求刚被电脑端了结），没有作答。 */
        data object PromptRejected : SubmitPhase

        /** 终端停在手机无法作答的框上，这句没有贴进终端。 */
        data object DialogOpen : SubmitPhase

        /** 手机答了 hook 送来的确认：依次发出确认与下一条推送。 */
        data class Hook(val binding: Binding, val texts: List<String>) : SubmitPhase
    }

    suspend fun onInbound(message: InboundMessage): InboundOutcome {
        val owner = ownerOf(message.robotId) ?: return InboundOutcome.NOT_PAIRED
        if (message.senderId != owner.senderId || message.chatId != owner.chatId) return InboundOutcome.UNAUTHORIZED
        if (!markSeen(message.robotId, message.externalMessageId)) return InboundOutcome.DUPLICATE

        val typed = message.text.trim()
        if (typed.isEmpty() && message.attachments.isEmpty()) {
            reply(message.robotId, owner, texts.emptyInput())
            return InboundOutcome.EMPTY
        }
        val binding = bindings.bindingOf(message.robotId)
        if (binding == null) {
            reply(message.robotId, owner, texts.unbound())
            return InboundOutcome.UNBOUND
        }
        // 确认本人、非重复、已绑定后才取回附件。下载放在提交锁之外，不阻塞其它机器人的提交；
        // 同一机器人的消息由上层按机器人逐条处理，下载期间该机器人的后续消息仍在排队
        val composed =
            if (message.attachments.isEmpty()) {
                typed
            } else {
                try {
                    withAttachments(message.robotId, owner, binding.target, typed, message.attachments)
                } catch (e: CancellationException) {
                    unmarkSeen(message.robotId, message.externalMessageId)
                    throw e
                }
            }
        val text = withQuote(message.quote, composed ?: return InboundOutcome.ATTACHMENT_FAILED)

        val phase =
            submitLock(message.robotId).withLock {
                // 加锁后复核绑定：排队期间可能已换绑或解绑
                val current = bindings.bindingOf(message.robotId)
                if (current == null) return@withLock SubmitPhase.BindingGone
                if (current.epoch != binding.epoch) return@withLock SubmitPhase.BindingChanged

                // 在锁内取定目标标签：之后所有回显登记与清除都用它，不再回读可能已换绑的绑定
                val tabId = current.target.tabId
                // hook 送来的确认优先：译成决定回给 hook，不按任何键
                val hookHead = synchronized(lock) { hookQueues[tabId]?.head() }
                if (hookHead != null) {
                    if (message.attachments.isNotEmpty()) return@withLock SubmitPhase.PromptRejected
                    return@withLock answerHook(current, hookHead, message.text)
                }
                // 运行态正停着等人，又没有挂着的 hook 请求：手机答不了这个框，不当普通消息贴进终端
                if (tab(tabId) { waitingActive }) return@withLock SubmitPhase.DialogOpen
                // 先登记再提交：CLI 可能在 submit 返回前就把这句写进记录
                tab(tabId) { expectEcho(text) }
                try {
                    val phase = submitWithRetry(message.robotId, current, text)
                    // 未提交成功，清除回显登记
                    if (phase != SubmitPhase.Done(SubmitResult.SUBMITTED_TO_TERMINAL)) tab(tabId) { forgetEcho(text) }
                    phase
                } catch (e: CancellationException) {
                    tab(tabId) { forgetEcho(text) }
                    unmarkSeen(message.robotId, message.externalMessageId)
                    throw e
                } catch (_: Exception) {
                    tab(tabId) { forgetEcho(text) }
                    SubmitPhase.Done(SubmitResult.OFFLINE)
                }
            }
        return when (phase) {
            SubmitPhase.BindingGone -> {
                reply(message.robotId, owner, texts.unbound())
                InboundOutcome.UNBOUND
            }
            SubmitPhase.BindingChanged -> {
                reply(message.robotId, owner, texts.rebound())
                InboundOutcome.REBOUND
            }
            SubmitPhase.PromptRejected -> {
                reply(message.robotId, owner, texts.promptRejected())
                InboundOutcome.PROMPT_REJECTED
            }
            SubmitPhase.DialogOpen -> {
                reply(message.robotId, owner, texts.dialogOpen())
                InboundOutcome.DIALOG_OPEN
            }
            is SubmitPhase.Hook -> {
                phase.texts.forEach { send(phase.binding, it) }
                InboundOutcome.SUBMITTED_TO_TERMINAL
            }
            is SubmitPhase.Done -> outcomeOf(message.robotId, owner, phase.result)
        }
    }

    private suspend fun outcomeOf(
        robotId: RobotId,
        owner: RobotOwner,
        result: SubmitResult,
    ): InboundOutcome =
        when (result) {
            SubmitResult.SUBMITTED_TO_TERMINAL -> InboundOutcome.SUBMITTED_TO_TERMINAL
            SubmitResult.NOT_READY -> {
                reply(robotId, owner, texts.notReady())
                InboundOutcome.NOT_READY
            }
            SubmitResult.OFFLINE -> {
                reply(robotId, owner, texts.offline())
                InboundOutcome.OFFLINE
            }
        }

    /** 远程控制关闭时调用：丢弃尚未发出的回复与轮次状态，重新开启后不补发关闭前的内容。 */
    fun discardPending() {
        val pending =
            synchronized(lock) {
                tabs.clear()
                hookQueues.values.flatMap { it.drain() }.also { hookQueues.clear() }
            }
        pending.forEach { it.respond(HookDecision.Pass) }
    }

    /**
     * 标签的 Claude 启动时注入了 hook（true）或会话结束（false）。注入时无需记录：hook 请求自带标签，
     * 有没有挂着的请求就决定了等待时推不推；会话结束时放行它挂起的请求。
     */
    fun hookAttached(
        tabId: String,
        attached: Boolean,
    ) {
        if (!attached) releaseHooks(tabId)
    }

    /** 未绑定立即 respond(Pass)；否则入队，成为队首时推送。respond 只会被调用一次。 */
    suspend fun onHookRequest(
        tabId: String,
        request: HookRequest,
        respond: (HookDecision) -> Unit,
    ) {
        // 在锁内查绑定再入队：解绑后的放行（onReleased）也在锁内清队，入队与清队不会错过
        val (binding, text) =
            synchronized(lock) {
                val binding = bindings.bindingForTab(tabId) ?: return@synchronized null
                // 没有题目的提问推不出去、手机也答不了，入队会卡住整个队列：直接交给电脑
                if (request.kind == PromptKind.QUESTION && request.questions.isNullOrEmpty()) return@synchronized null
                val queue = hookQueues.getOrPut(tabId) { HookPromptQueue() }
                queue.add(HookPromptQueue.Entry(request, respond))
                binding to queue.nextPush(binding)
            } ?: return respond(HookDecision.Pass)
        text?.let { send(binding, it) }
    }

    /** 电脑端允许后工具已跑完：了结与 [done] 匹配的最早请求，respond(Pass)。[answers] 是提问的「题目 → 答案」行。 */
    suspend fun onHookToolDone(
        tabId: String,
        done: HookToolDone,
        answers: String?,
    ) {
        settleLocally(tabId, { matchLocal(done) }, respond = true) { request ->
            when (request.kind) {
                PromptKind.PERMISSION -> texts.hookAllowedLocally()
                PromptKind.QUESTION -> texts.promptAnsweredLocally(answers)
                PromptKind.PLAN -> texts.promptAnsweredLocally(null)
            }
        }
    }

    /** hook 连接被关闭（电脑端拒绝或 Esc）：了结该请求，不再 respond。 */
    suspend fun onHookClosed(
        tabId: String,
        id: Long,
    ) {
        settleLocally(tabId, { remove(id) }, respond = false) { texts.hookDeniedLocally() }
    }

    /**
     * 电脑端先答掉了一个请求：出队，推给过这台手机的才告诉它（没推过的静默出队），再推下一个。
     * 手机已经答过、出了队的，这里找不到，什么也不做。
     */
    private suspend fun settleLocally(
        tabId: String,
        take: HookPromptQueue.() -> HookPromptQueue.Entry?,
        respond: Boolean,
        notice: (HookRequest) -> String,
    ) {
        val (entry, binding, next) =
            synchronized(lock) {
                val queue = hookQueues[tabId] ?: return
                val entry = queue.take() ?: return
                val binding = bindings.bindingForTab(tabId)
                Triple(entry, binding, binding?.let { queue.nextPush(it) })
            }
        if (respond) entry.respond(HookDecision.Pass)
        binding ?: return
        if (entry.pushedEpoch == binding.epoch) send(binding, notice(entry.request))
        next?.let { send(binding, it) }
    }

    /**
     * 本轮结束或中断时放行主会话（无 agentId）挂起的请求，静默出队：主会话的确认活不过它所在的一轮，
     * 用作电脑端了结漏判（PostToolUse 对不上、提问选 Chat about this、允许后 Esc 中断等）的兜底。
     * 后台子代理可能在本轮结束后才请求，不动。队首变了就推新的队首。
     */
    private suspend fun releaseMainSessionHooks(
        tabId: String,
        binding: Binding,
    ) {
        val (released, next) =
            synchronized(lock) {
                val queue = hookQueues[tabId] ?: return
                val released = queue.drainMainSession().ifEmpty { return }
                released to queue.nextPush(binding)
            }
        released.forEach { it.respond(HookDecision.Pass) }
        next?.let { send(binding, it) }
    }

    /** 放行标签挂起的全部请求，交给电脑处理；不发消息。 */
    private fun releaseHooks(tabId: String) {
        val pending = synchronized(lock) { hookQueues.remove(tabId)?.drain().orEmpty() }
        pending.forEach { it.respond(HookDecision.Pass) }
    }

    /**
     * 手机答 hook 队首：译成决定、出队并回给 hook，返回要发的确认与下一条推送。提问不是最后一题时只记下答案、推下一题，
     * 全部答完才一起回给 hook。须在提交锁内调用；respond 在 [lock] 外调用。
     */
    private fun answerHook(
        binding: Binding,
        entry: HookPromptQueue.Entry,
        reply: String,
    ): SubmitPhase {
        val tabId = binding.target.tabId
        val request = entry.request
        val (decision, sending) =
            synchronized(lock) {
                val queue = hookQueues[tabId]
                // 取队首与作答之间电脑端已了结：这句不再算作答，按带附件那样提示，免得误贴进终端
                if (queue == null || queue.head() !== entry) return SubmitPhase.PromptRejected
                val (decision, ack) =
                    when (request.kind) {
                        PromptKind.QUESTION -> {
                            val questions = request.questions.orEmpty()
                            val question = questions.getOrNull(entry.questionIndex) ?: return SubmitPhase.PromptRejected
                            val answer = AskedQuestions.hookAnswer(question, reply) ?: return SubmitPhase.PromptRejected
                            entry.answers += question.question to answer
                            if (entry.questionIndex < questions.size) {
                                return SubmitPhase.Hook(binding, listOfNotNull(queue.promptText(binding, entry)))
                            }
                            HookDecision.Allow(answers = entry.answers.toMap()) to texts.hookAnswersSubmitted()
                        }
                        PromptKind.PERMISSION ->
                            HookReplies.permission(reply).let { it to if (it is HookDecision.Allow) texts.hookAllowed() else texts.hookDenied() }
                        PromptKind.PLAN ->
                            HookReplies.plan(reply).let {
                                it to if (it is HookDecision.Allow) texts.hookPlanApproved(it.modes == HookReplies.PLAN_FIRST_MODES) else texts.hookPlanRevising()
                            }
                    }
                queue.remove(request.id)
                decision to listOfNotNull(ack, queue.nextPush(binding))
            }
        entry.respond(decision)
        return SubmitPhase.Hook(binding, sending)
    }

    /** 队首还没推给 [binding] 时排好推送内容并记下已推；没有要推的返回 null。在 [lock] 内调用。 */
    private fun HookPromptQueue.nextPush(binding: Binding): String? {
        val entry = head() ?: return null
        if (entry.pushedEpoch == binding.epoch) return null
        return promptText(binding, entry)?.also { entry.pushedEpoch = binding.epoch }
    }

    /**
     * 一个请求推给手机的内容：权限带工具与来源，计划带全文，提问推当前这题；后面还有排队的，末尾标出个数。
     * 在 [lock] 内调用。
     */
    private fun HookPromptQueue.promptText(
        binding: Binding,
        entry: HookPromptQueue.Entry,
    ): String? {
        val request = entry.request
        val queued = (size - 1).takeIf { it > 0 }?.let { "\n\n" + texts.hookQueued(it) }.orEmpty()
        return when (request.kind) {
            PromptKind.PERMISSION ->
                fitted(binding, PermissionPrompts.render(request.toolName, request.detail, request.agentType)) { texts.hookPermission(it) + queued }
            PromptKind.PLAN -> fitted(binding, request.detail) { texts.plan(it) + queued }
            PromptKind.QUESTION -> {
                val questions = request.questions.orEmpty()
                val question = questions.getOrNull(entry.questionIndex) ?: return null
                texts.question(AskedQuestions.render(question, texts.multiSelectMark()), entry.questionIndex + 1, questions.size) + queued
            }
        }
    }

    /** 引用的文字用 Markdown 引用块放在正文前，Agent 才知道“这里”指的是哪段。 */
    private fun withQuote(
        quote: String?,
        text: String,
    ): String {
        val quoted = quote?.trim()?.takeIf(String::isNotEmpty) ?: return text
        return quoted.lines().joinToString("\n") { "> $it".trimEnd() } + "\n\n" + text
    }

    /** 取回并落盘全部附件，拼装提交文字；附件全部失败且没有文字时返回 null。失败提示各回复一次。 */
    private suspend fun withAttachments(
        robotId: RobotId,
        owner: RobotOwner,
        target: SessionTarget,
        typed: String,
        attachments: List<InboundAttachment>,
    ): String? {
        val saved = mutableListOf<String>()
        var tooLarge = false
        var failed = false
        for (attachment in attachments) {
            val result =
                try {
                    attachmentStore.place(target, attachment)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    PlaceResult.Failed(e.javaClass.simpleName)
                }
            when (result) {
                is PlaceResult.Saved -> saved += result.relativePath
                PlaceResult.TooLarge -> tooLarge = true
                is PlaceResult.Failed -> {
                    failed = true
                    onWarning("入站附件处理失败：reason=${result.reason}")
                }
            }
        }
        if (tooLarge) reply(robotId, owner, texts.attachmentTooLarge())
        if (failed) reply(robotId, owner, texts.attachmentFailed())
        return when {
            saved.isEmpty() -> typed.ifEmpty { null }
            typed.isEmpty() -> texts.attachmentsOnly(saved)
            else -> typed + "\n\n" + saved.joinToString("\n") { texts.attachmentLine(it) }
        }
    }

    /**
     * 须在这个机器人的提交锁内调用。终端未就绪时最多尝试 [NOT_READY_ATTEMPTS] 次；
     * 每次尝试前复核绑定代次，等待期间换绑或解绑则不再提交，按绑定已变处理。
     */
    private suspend fun submitWithRetry(
        robotId: RobotId,
        binding: Binding,
        text: String,
    ): SubmitPhase {
        repeat(NOT_READY_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(notReadyRetryDelayMs)
            if (!bindings.isCurrent(robotId, binding.epoch)) return SubmitPhase.BindingChanged
            val result = sessionPort.submit(binding.target, text)
            if (result != SubmitResult.NOT_READY) return SubmitPhase.Done(result)
        }
        return SubmitPhase.Done(SubmitResult.NOT_READY)
    }

    /**
     * 计划、长命令截短到一条消息之内：拆成几条发，微信这类有条数额度的渠道会白白耗掉额度，
     * 怎么回的说明也会落到最后一条。截掉的部分到电脑上看。署名的长度一并预留。
     */
    private fun fitted(
        binding: Binding,
        body: String,
        wrap: (String) -> String,
    ): String {
        val channel = channelOf(binding.robotId) ?: return wrap(body)
        val budget = channel.maxTextBytes - wrap("").toByteArray(Charsets.UTF_8).size - SIGNATURE_RESERVE_BYTES
        if (body.toByteArray(Charsets.UTF_8).size <= budget) return wrap(body)
        val cut = StringBuilder()
        var bytes = 0
        for (codePoint in body.codePoints()) {
            val size = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > budget - TRUNCATED.toByteArray(Charsets.UTF_8).size) break
            cut.appendCodePoint(codePoint)
            bytes += size
        }
        return wrap(cut.toString().trimEnd() + TRUNCATED)
    }

    /** 读取期间换绑的，事件交给当前绑定；攒下的回复按代次作废，发出时只认产生它的那次绑定。 */
    suspend fun onSessionEvents(
        tabId: String,
        events: List<SessionEvent>,
    ) {
        val binding = bindings.bindingForTab(tabId) ?: return
        for (event in events) {
            when (event) {
                is SessionEvent.AssistantText -> tab(tabId) { appendReply(binding.epoch, event.text) }
                is SessionEvent.UserText -> {
                    // 上一轮没等到结算就开了新一轮：排着的状态先发，不能落到新一轮的输入后面
                    sendAfterReply(tabId)
                    val echo =
                        tab(tabId) {
                            startTurn()
                            consumeEcho(event.text)
                        }
                    if (!echo) send(binding, texts.localInputLabel() + "\n" + event.text)
                }
                SessionEvent.ToolStarted -> tab(tabId) { toolStarted() }
                SessionEvent.TurnStarted -> {
                    sendAfterReply(tabId)
                    tab(tabId) { startTurn() }
                }
                SessionEvent.TurnEnded -> {
                    sendSettledReply(tabId, tab(tabId) { endByMarker() })
                    sendBackgroundRunning(tabId)
                    sendAfterReply(tabId)
                    releaseMainSessionHooks(tabId, binding)
                }
                SessionEvent.TurnInterrupted -> {
                    tab(tabId) { interrupt() }
                    send(binding, texts.turnInterrupted())
                    sendBackgroundRunning(tabId)
                    sendAfterReply(tabId)
                    releaseMainSessionHooks(tabId, binding)
                }
                is SessionEvent.TurnFailed -> {
                    tab(tabId) { interrupt() }
                    val reason = event.reason.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() }.orEmpty()
                    send(binding, texts.turnFailed(reason))
                    sendBackgroundRunning(tabId)
                    sendAfterReply(tabId)
                    releaseMainSessionHooks(tabId, binding)
                }
                is SessionEvent.BackgroundLaunched -> tab(tabId) { backgroundLaunched(event.toolUseId, event.description) }
                is SessionEvent.BackgroundStarted -> tab(tabId) { backgroundStarted(event.taskId, event.toolUseId, event.description) }
                is SessionEvent.BackgroundEnded -> tab(tabId) { backgroundEnded(event.taskId) }
            }
        }
        // 结算之后才读到的回复（完成信号先到）：立即发；若这一轮正等着它，排着的提示随后发
        val late = tab(tabId) { takeIfSettled() }
        if (late != null) {
            val awaited = tab(tabId) { stopAwaitingReply() }
            sendReply(tabId, late)
            if (awaited) {
                sendBackgroundRunning(tabId)
                sendAfterReply(tabId)
            }
        }
    }

    /** IDE 报告会话退出等待（转忙碌或空闲）：停着的框已过去，手机的话照常提交。 */
    suspend fun onResumed(tabId: String) {
        tab(tabId) { waitingExited() }
    }

    /**
     * 定时轮询时调用：IDE 结算后等了 [noReplyGraceMs] 仍没读到回复，判定这一轮没有文字回复，
     * 说一声已完成，再发排着的提示。
     */
    suspend fun onQuiet(tabId: String) {
        val due =
            tab(tabId) {
                val since = awaitingReplySince ?: return@tab false
                (now() - since >= noReplyGraceMs).also { if (it) stopAwaitingReply() }
            }
        if (!due) return
        val binding = bindings.bindingForTab(tabId) ?: return
        send(binding, texts.turnWithoutReply())
        sendBackgroundRunning(tabId)
        sendAfterReply(tabId)
    }

    /** 这个标签当前读到的轮次，供 IDE 完成信号标明它属于哪一轮。 */
    fun currentTurn(tabId: String): Long = synchronized(lock) { tabs[tabId]?.turn ?: 0L }

    /**
     * IDE 报告第 [turn] 轮完成：这一轮会话记录里没有结束标记时，以它为准把最终回复作为一条发出。
     * [turn] 不是当前轮（信号晚到，下一轮已经开始）或这一轮已由结束标记收尾时，什么也不做。
     */
    suspend fun onTurnCompleted(
        tabId: String,
        turn: Long,
    ) {
        // 确认是当前这一轮结束了才清掉框的状态与拦截（见 completeByIde）：上一轮迟到的完成信号不说明此刻的框已经过去
        val completed = tab(tabId) { completeByIde(turn) }
        if (completed) {
            val reply = tab(tabId) { takeReply() }
            if (reply == null) {
                // 完成信号可能先于最终回复写进记录：先等，见 onQuiet
                tab(tabId) { awaitReply(now()) }
                return
            }
            sendReply(tabId, reply)
            sendBackgroundRunning(tabId)
        }
        sendAfterReply(tabId)
    }

    /**
     * 说这一轮状态的提示（例如结对审查开始）：这一轮的回复已经发出就立即发，否则排在回复之后。
     * 回复晚到（结束标记还没写进记录）时，这样状态不会抢在回复前面。
     */
    suspend fun onAfterReply(
        tabId: String,
        text: String,
    ) {
        if (tab(tabId) { queueAfterReply(text) }) return
        onStatus(tabId, text)
    }

    /** 立即发一条状态，不排在任何一轮的回复之后；之前排着的状态先发。 */
    suspend fun onStatusNow(
        tabId: String,
        text: String,
    ) {
        sendAfterReply(tabId)
        onStatus(tabId, text)
    }

    /**
     * 完成信号到了但没能结算（读取边界不可靠）：后台任务提示照常补，排着的状态也不能一直压着。
     */
    suspend fun flushAfterReply(tabId: String) {
        sendBackgroundRunning(tabId)
        sendAfterReply(tabId)
    }

    /** 换了会话：之前记下的后台任务全部作废。 */
    fun resetBackground(tabId: String) = tab(tabId) { clearBackground() }

    private suspend fun sendAfterReply(tabId: String) {
        for (text in tab(tabId) { takeAfterReply() }) onStatus(tabId, text)
    }

    /**
     * 一轮结算后还有后台任务在运行：回复之后补一条，远程用户才知道这个会话还没真正做完。
     * 任务列表和上一次提示的一样就不重复。任务结束时 CLI 会再开一轮回应它，那一轮的回复照常发出，所以结束不另行提示。
     */
    private suspend fun sendBackgroundRunning(tabId: String) {
        val tasks = tab(tabId) { backgroundHint() } ?: return
        val binding = bindings.bindingForTab(tabId) ?: return
        send(binding, texts.backgroundRunning(tasks))
    }

    /**
     * 结束标记结算时发出这一轮的回复。结束标记写在回复之后，此刻还没有回复就是真的没有：说一声已完成，
     * 否则手机上什么也看不到，会以为还在跑。
     */
    private suspend fun sendSettledReply(
        tabId: String,
        reply: PendingReply?,
    ) {
        if (reply != null) return sendReply(tabId, reply)
        val binding = bindings.bindingForTab(tabId) ?: return
        send(binding, texts.turnWithoutReply())
    }

    /** 发出本轮回复；换绑后旧绑定攒下的作废，不落到新目标的聊天里（切换空档漏消息可接受）。 */
    private suspend fun sendReply(
        tabId: String,
        reply: PendingReply?,
    ) {
        reply ?: return
        val binding = bindings.bindingForTab(tabId) ?: return
        if (binding.epoch != reply.epoch) return
        send(binding, reply.text.toString())
    }

    private fun submitLock(robotId: RobotId): Mutex = synchronized(lock) { submitLocks.getOrPut(robotId) { Mutex() } }

    private fun <T> tab(
        tabId: String,
        block: TabState.() -> T,
    ): T = synchronized(lock) { tabs.getOrPut(tabId) { TabState() }.block() }

    /** 推一条状态（例如结对审查开始、结束）到绑定这个标签的手机。 */
    suspend fun onStatus(
        tabId: String,
        text: String,
    ) {
        val binding = bindings.bindingForTab(tabId) ?: return
        send(binding, text)
    }

    /**
     * 运行态停下等人：有挂着的 hook 请求时确认框已由 hook 推过，不再提示；否则只提示到 IDE 中处理
     * （Codex、Pi，没能注入 hook 的 Claude 标签，以及空闲提醒这类不是确认框的等待）。
     */
    suspend fun onWaiting(
        tabId: String,
        reason: String?,
    ) {
        val binding = bindings.bindingForTab(tabId) ?: return
        tab(tabId) { waitingStarted() }
        if (synchronized(lock) { hookQueues[tabId]?.size ?: 0 } == 0) send(binding, texts.waiting(reason))
    }

    suspend fun onTranscriptRestarted(tabId: String) {
        releaseHooks(tabId)
        resetBackground(tabId)
        tab(tabId) { waitingExited() }
        val binding = bindings.bindingForTab(tabId) ?: return
        send(binding, texts.transcriptRestarted())
    }

    /**
     * 一次连接或换绑要发的提示，在同一个协程里按顺序发出：先给每个被断开的会话发断开提示，
     * 再给新会话发连接提示。前一条在限频上等待时后一条不能抢先，否则手机最后看到的是“已断开”。
     * [bound] 为 null 表示这次绑定没成功，只发断开提示。
     */
    suspend fun onRebound(
        released: List<Pair<Binding, String>>,
        bound: Binding?,
        project: String,
        agent: String,
        sessionTitle: String,
    ) {
        released.forEach { (binding, title) -> onReleased(binding, title) }
        bound?.let { onBound(it, project, agent, sessionTitle) }
    }

    /** 手动绑定与自动恢复都等渠道真正上线，再通知本人当前连接；发送前复核绑定代次。 */
    suspend fun onBound(
        binding: Binding,
        project: String,
        agent: String,
        sessionTitle: String,
    ) {
        if (!bindings.isCurrent(binding.robotId, binding.epoch)) return
        if (withTimeoutOrNull(reconnectWaitMs) { awaitOnline(binding.robotId) } == null) {
            onWarning("机器人 ${binding.robotId.value} ${reconnectWaitMs / 1000} 秒内未上线，不发连接通知")
            return
        }
        send(binding, texts.connected(project, agent, sessionTitle), label = null)
    }

    /**
     * IDE 重启或项目重新打开后，绑定自动接回了同一个会话：告诉本人连接已恢复。
     * 重启时标签可能比机器人先就绪，等机器人上线（最多 [reconnectWaitMs]）再发，否则这条会因为还没有连接而丢掉。
     */
    suspend fun onReattached(
        binding: Binding,
        project: String,
        agent: String,
        sessionTitle: String,
    ) = onBound(binding, project, agent, sessionTitle)

    /** 项目或 IDE 关闭：通知手机会话离线，但保留绑定供重新打开后接回；旧代次不得误报。 */
    suspend fun onSuspended(
        binding: Binding,
        sessionTitle: String,
    ) {
        send(binding, texts.suspended(sessionNameOf(binding, sessionTitle)), label = null)
    }

    /**
     * 绑定已被移除后调用：不再做 epoch 校验，只向原聊天发断开提示。
     * 释放时没有完成信号，缓存里只是过程说明，不发；旧代次的缓存之后按代次作废。
     */
    suspend fun onReleased(
        binding: Binding,
        sessionTitle: String,
    ) {
        val tabId = binding.target.tabId
        synchronized(lock) {
            // 换绑时新绑定先建立、旧绑定的清理后执行：标签已被接管时，它的状态属于新绑定
            if (bindings.bindingForTab(tabId) == null) tabs.remove(tabId)
        }
        // 挂起的请求推给的是这台手机：一律交还电脑，不留给之后接管的绑定
        releaseHooks(tabId)
        val owner = ownerOf(binding.robotId) ?: return
        val channel = channelOf(binding.robotId) ?: return
        deliver(channel, OutboundMessage(binding.robotId, owner.chatId, texts.released(sessionNameOf(binding, sessionTitle))))
    }

    /**
     * 每段发送前都核对：分段途中换绑，剩余内容不落到新目标的聊天里。
     * 有 [label] 时在最后一段末尾署名：切段时预留署名的长度，署名不会被单独切到下一条。
     */
    private suspend fun send(
        binding: Binding,
        text: String,
        label: String? = sessionLabelOf(binding),
    ) {
        val owner = ownerOf(binding.robotId) ?: return
        val channel = channelOf(binding.robotId) ?: return
        val footer = footer(label)
        val budget = (channel.maxTextBytes - footer.toByteArray(Charsets.UTF_8).size).coerceAtLeast(TextSegmenter.MIN_SEGMENT_BYTES)
        val segments = TextSegmenter.split(if (footer.isEmpty()) text else text.trimEnd(), budget)
        for ((index, part) in segments.withIndex()) {
            val segment = if (index == segments.lastIndex) part + footer else part
            // 每段发送前都核对：分段途中被换绑，剩余内容不能落到新目标的聊天里
            if (!bindings.isCurrent(binding.robotId, binding.epoch)) return
            deliver(channel, OutboundMessage(binding.robotId, owner.chatId, segment)) { bindings.isCurrent(binding.robotId, binding.epoch) }
        }
    }

    /** 回应手机发来的消息；此刻连着会话时同样署名，没连会话就不署名。 */
    private suspend fun reply(
        robotId: RobotId,
        owner: RobotOwner,
        text: String,
    ) {
        val channel = channelOf(robotId) ?: return
        val label = bindings.bindingOf(robotId)?.let(sessionLabelOf)
        deliver(channel, OutboundMessage(robotId, owner.chatId, labeled(text, label, channel)))
    }

    /** 不分段的短消息加上署名。 */
    private fun labeled(
        text: String,
        label: String?,
        channel: ChannelAdapter,
    ): String {
        val footer = footer(label)
        return if (footer.isEmpty()) text else text.trimEnd() + footer
    }

    /**
     * 发一条消息。被渠道限频拒发时稍后重发（最多 [RATE_LIMIT_RETRIES] 次），不直接丢掉：
     * 丢掉的若是一轮的最终回复，手机上会一直以为还在跑。重发期间同一标签后面的消息排在后面，顺序不乱；
     * [stillCurrent] 为 false（已换绑或断开）就不再重发。结果不明（网络异常）不重发，以免重复。
     * 渠道已发送部分正文时，后续尝试只带未发送的尾部，沿用本次发送的重试次数上限。
     */
    private suspend fun deliver(
        channel: ChannelAdapter,
        message: OutboundMessage,
        stillCurrent: () -> Boolean = { true },
    ) {
        var pending = message
        repeat(RATE_LIMIT_RETRIES + 1) { attempt ->
            if (attempt > 0) {
                delay(rateLimitRetryDelayMs)
                if (!stillCurrent()) return
            }
            val result =
                try {
                    channel.send(pending)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 渠道异常不能冒泡到终端或监控；结果不明，交给上层显示，不自动重发
                    DeliveryResult.Unknown(e.javaClass.name)
                }
            onDelivery(pending, result)
            when {
                result is DeliveryResult.RetryRemaining -> pending = pending.copy(text = result.text)
                result == DeliveryResult.Rejected(DeliveryResult.RATE_LIMITED) -> Unit
                else -> return
            }
        }
    }

    private fun markSeen(
        robotId: RobotId,
        externalMessageId: String,
    ): Boolean =
        synchronized(lock) {
            val ids = seen.getOrPut(robotId) { LinkedHashSet() }
            if (!ids.add(externalMessageId)) return false
            while (ids.size > SEEN_CAPACITY) ids.remove(ids.first())
            true
        }

    private fun unmarkSeen(
        robotId: RobotId,
        externalMessageId: String,
    ) = synchronized(lock) {
        seen[robotId]?.remove(externalMessageId)
    }

    /**
     * 分割线下一行写署名，整行放进行内代码，不用引用块。
     * 行内代码里不解析标题号、列表号，不用再转义；标题里带反引号时，外层反引号比里面最长的一串多一个。
     */
    private fun footer(label: String?): String {
        val line = label?.replace(WHITESPACE, " ")?.trim()?.takeIf(String::isNotEmpty) ?: return ""
        val shown = if (line.length > MAX_LABEL_CHARS) line.take(MAX_LABEL_CHARS - 1) + "…" else line
        val fence = "`".repeat((BACKTICKS.findAll(shown).maxOfOrNull { it.value.length } ?: 0) + 1)
        return "\n\n---\n$fence $shown $fence"
    }

    private companion object {
        const val TRUNCATED = "\n…"

        /** 署名最多 [MAX_LABEL_CHARS] 字，每字最多 4 字节，再加分隔线。 */
        const val SIGNATURE_RESERVE_BYTES = 100 * 4 + 32
        val WHITESPACE = Regex("\\s+")
        val BACKTICKS = Regex("`+")

        /** 署名只为分辨会话，过长时截短，不挤占正文的分段额度。 */
        const val MAX_LABEL_CHARS = 100
        const val RATE_LIMIT_RETRIES = 6
        const val SEEN_CAPACITY = 1000
        const val NOT_READY_ATTEMPTS = 3
    }
}
