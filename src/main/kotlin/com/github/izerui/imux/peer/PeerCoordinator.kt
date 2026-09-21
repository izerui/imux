package com.github.izerui.imux.peer

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.session.SessionListModel
import com.github.izerui.imux.session.SessionTranscriptMessage
import com.github.izerui.imux.session.scanTail
import com.github.izerui.imux.session.sessionTranscriptMessages
import com.github.izerui.imux.session.transcriptMessage
import com.github.izerui.imux.settings.ImuxSettings
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.util.EventDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.EventListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class PeerStatus(
    val targetAgentType: AgentType,
    val running: Boolean,
)

fun interface PeerStateListener : EventListener {
    fun peerStateChanged(sessionKey: String)
}

private class PeerRun {
    val cancelled = AtomicBoolean(false)
    val reviewing = AtomicBoolean(false)
    val process = AtomicReference<Process?>()

    fun attach(started: Process?) {
        process.set(started)
        if (started != null && cancelled.get()) destroyProcessTree(started)
    }

    fun cancel() {
        cancelled.set(true)
        process.getAndSet(null)?.let(::destroyProcessTree)
    }
}

class PeerCoordinator(
    private val project: Project,
    private val projectPath: String,
    private val model: SessionListModel,
    private val viewOf: (String) -> TerminalView?,
    private val coroutineScope: CoroutineScope,
    private val shell: String,
    private val runCli: (
        command: List<String>,
        cwd: Path,
        prompt: String,
        timeoutSeconds: Long,
        onProcess: (Process?) -> Unit,
    ) -> String? = ::runPeerCli,
) : Disposable {
    private val bindings = ConcurrentHashMap<String, PeerBinding>()
    private val roundCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val activeRuns = ConcurrentHashMap<String, PeerRun>()
    private val stateDispatcher = EventDispatcher.create(PeerStateListener::class.java)

    fun addStateListener(
        parentDisposable: Disposable,
        listener: PeerStateListener,
    ) {
        stateDispatcher.addListener(listener, parentDisposable)
    }

    fun status(sessionKey: String): PeerStatus? =
        bindings[sessionKey]?.let {
            PeerStatus(it.targetAgentType, activeRuns[sessionKey]?.reviewing?.get() == true)
        }

    fun bind(
        sessionKey: String,
        targetAgentType: AgentType,
    ) {
        unbind(sessionKey)
        bindings[sessionKey] = PeerBinding(targetAgentType, extractTask(sessionKey))
        LOG.info("结对编程：绑定 $sessionKey -> ${targetAgentType.displayName}")
        notifyStateChanged(sessionKey)
    }

    fun unbind(sessionKey: String) {
        bindings.remove(sessionKey)
        roundCounts.remove(sessionKey)
        cancelRun(sessionKey)
        LOG.info("结对编程：解绑 $sessionKey")
        notifyStateChanged(sessionKey)
    }

    fun boundTarget(sessionKey: String): AgentType? = bindings[sessionKey]?.targetAgentType

    fun onTurnCompleted(sessionKey: String) {
        val binding = bindings[sessionKey] ?: return
        val run = PeerRun()
        if (activeRuns.putIfAbsent(sessionKey, run) != null) {
            LOG.info("结对编程：$sessionKey 已有副驾驶运行，忽略重复完成事件")
            return
        }
        LOG.info("结对编程：主会话轮次完成 sessionKey=$sessionKey")

        coroutineScope.launch(Dispatchers.IO) {
            try {
                runReviewAndInject(sessionKey, binding, run)
            } finally {
                activeRuns.remove(sessionKey, run)
                run.cancel()
                withContext(Dispatchers.EDT) {
                    notifyStateChanged(sessionKey)
                }
            }
        }
    }

    fun cancelCurrentRun(sessionKey: String) {
        LOG.info("结对编程：手动取消 $sessionKey")
        roundCounts[sessionKey]?.set(0)
        cancelRun(sessionKey)
        notifyStateChanged(sessionKey)
    }

    /**
     * pending 绑定、/clear 与 /new 都会更换终端记账 key。旧会话的反馈不能跨到新会话，
     * 所以迁移绑定时终止在途调用，并让新会话重新提取自己的任务目标。
     */
    fun migrateSessionKey(
        from: String,
        to: String,
    ) {
        if (from == to) return
        val binding = bindings.remove(from)
        roundCounts.remove(from)
        cancelRun(from)
        if (binding != null) {
            bindings[to] = binding.copy(task = "")
            roundCounts.remove(to)
            LOG.info("结对编程：迁移绑定 $from -> $to")
        }
        notifyStateChanged(from)
        notifyStateChanged(to)
    }

    override fun dispose() {
        activeRuns.keys.toList().forEach(::cancelRun)
        bindings.clear()
        roundCounts.clear()
    }

    private suspend fun runReviewAndInject(
        mainSessionKey: String,
        initialBinding: PeerBinding,
        run: PeerRun,
    ) {
        if (projectHasChanges(projectPath) == false) {
            roundCounts[mainSessionKey]?.set(0)
            LOG.info("结对编程：项目没有 Git 变更，跳过副驾驶")
            return
        }
        run.reviewing.set(true)
        withContext(Dispatchers.EDT) {
            if (runIsCurrent(mainSessionKey, initialBinding, run)) notifyStateChanged(mainSessionKey)
        }

        val round =
            roundCounts
                .computeIfAbsent(mainSessionKey) { AtomicInteger(0) }
                .incrementAndGet()
        val maxRounds = ImuxSettings.getInstance().state.peerMaxRounds
        if (round > maxRounds) {
            LOG.info("结对编程：已达安全上限 $maxRounds 轮，停止")
            roundCounts[mainSessionKey]?.set(0)
            return
        }

        val binding =
            if (initialBinding.task.isBlank()) {
                initialBinding.copy(task = extractTask(mainSessionKey)).also {
                    bindings.replace(mainSessionKey, initialBinding, it)
                }
            } else {
                initialBinding
            }
        val conversation = collectLatestConversation(mainSessionKey)
        val prompt = buildReviewPrompt(binding.task, conversation)
        val command = peerCliCommand(shell, binding.targetAgentType, projectPath)
        LOG.info("结对编程：第 $round 轮，调用 ${binding.targetAgentType.cli}")

        val rawOutput =
            runCatching {
                runCli(command, Path.of(projectPath), prompt, CLI_TIMEOUT_SECONDS, run::attach)
            }.onFailure {
                LOG.warn("结对编程：CLI 调用失败", it)
            }.getOrNull()

        if (!runIsCurrent(mainSessionKey, binding, run)) return
        val feedback = actionablePeerFeedback(rawOutput)
        if (feedback == null) {
            roundCounts[mainSessionKey]?.set(0)
            LOG.info("结对编程：副驾驶无有效反馈，本轮结束")
            return
        }

        withContext(Dispatchers.EDT) {
            if (!runIsCurrent(mainSessionKey, binding, run) || project.isDisposed) return@withContext
            if (ImuxSettings.getInstance().state.peerAutoInject) {
                injectFeedback(mainSessionKey, feedback)
            } else {
                notifyForConfirmation(mainSessionKey, binding, feedback)
            }
        }
    }

    private fun runIsCurrent(
        sessionKey: String,
        binding: PeerBinding,
        run: PeerRun,
    ): Boolean =
        !run.cancelled.get() &&
                activeRuns[sessionKey] === run &&
                bindings[sessionKey] == binding

    private fun cancelRun(sessionKey: String) {
        activeRuns.remove(sessionKey)?.cancel()
    }

    private fun notifyStateChanged(sessionKey: String) {
        stateDispatcher.multicaster.peerStateChanged(sessionKey)
    }

    private fun buildReviewPrompt(
        task: String,
        conversation: String,
    ): String {
        val customPrompt = ImuxSettings.getInstance().state.peerPromptOverride
        if (customPrompt != null) {
            return customPrompt
                .replace("\${task}", task)
                .replace("\${conversation}", conversation)
        }

        val isChinese = ImuxBundle.currentLanguage().id in setOf("zh_CN", "zh_TW")
        val taskSection =
            if (task.isBlank()) {
                ""
            } else if (isChinese) {
                "## 任务目标\n\n$task\n\n"
            } else {
                "## Task Goal\n\n$task\n\n"
            }
        val conversationSection =
            if (conversation.isBlank()) {
                ""
            } else if (isChinese) {
                "## 对话记录\n\n$conversation\n\n"
            } else {
                "## Conversation\n\n$conversation\n\n"
            }
        return (if (isChinese) DEFAULT_PROMPT_ZH else DEFAULT_PROMPT_EN)
            .replace("\${task}", taskSection)
            .replace("\${conversation}", conversationSection)
    }

    private fun extractTask(sessionKey: String): String {
        val session = model.sessionOf(sessionKey) ?: return ""
        return peerTask(session)
    }

    private fun collectLatestConversation(sessionKey: String): String {
        val session = model.sessionOf(sessionKey) ?: return ""
        if (!Files.isRegularFile(session.filePath)) return ""
        return scanTail(
            session.filePath,
            initialTailBytes = CONVERSATION_TAIL_BYTES,
            maxTailBytes = CONVERSATION_TAIL_BYTES,
        ) { lines ->
            latestConversation(
                lines.mapNotNull {
                    transcriptMessage(it, session.agentType, MAX_MESSAGE_LENGTH)
                },
                MAX_CONVERSATION_LENGTH,
            )
        }.orEmpty()
    }

    private fun injectFeedback(
        mainSessionKey: String,
        prompt: String,
    ) {
        val view = viewOf(mainSessionKey)
        if (view == null) {
            LOG.warn("结对编程：找不到主会话终端 $mainSessionKey")
            return
        }
        LOG.info("结对编程：注入反馈到主会话 $mainSessionKey（${prompt.length} 字符）")
        view.createSendTextBuilder()
            .useBracketedPasteMode()
            .shouldExecute()
            .send(prompt)
    }

    private fun notifyForConfirmation(
        mainSessionKey: String,
        binding: PeerBinding,
        prompt: String,
    ) {
        val notification =
            NotificationGroupManager
                .getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(
                    ImuxBundle.message("action.peer.notification.title"),
                    ImuxBundle.message("action.peer.notification.content", binding.targetAgentType.displayName),
                    NotificationType.INFORMATION,
                )
        notification.addAction(
            NotificationAction.createSimpleExpiring(ImuxBundle.message("action.peer.notification.inject")) {
                if (bindings[mainSessionKey] == binding) injectFeedback(mainSessionKey, prompt)
            },
        )
        notification.notify(project)
    }

    companion object {
        private val LOG = logger<PeerCoordinator>()
        private const val NOTIFICATION_GROUP = "imux.turnCompleted"
        private const val MAX_CONVERSATION_LENGTH = 100_000
        private const val MAX_MESSAGE_LENGTH = 20_000
        private const val CONVERSATION_TAIL_BYTES = 8L * 1024 * 1024
        private const val CLI_TIMEOUT_SECONDS = 300L

        val DEFAULT_PROMPT_ZH = """
# 角色

你现在扮演这个 AI 编程会话的用户。
你的搭档（另一个 AI 助手）刚完成了一轮工作。

# 上下文

${'$'}{task}
${'$'}{conversation}

# 你要做什么

请以只读方式查看项目文件和 git 变更，判断是否还有能实质推进原始任务的内容。
如果有，直接输出你下一步会在主会话输入框里说的话。
如果任务已完成、没有遗漏或没有新的有效建议，只输出 PASS。

# 约束

- 直接输出要发送的内容或 PASS，不要输出思考过程、分析过程或前缀
- 始终围绕用户的原始任务目标推进，不要跑偏
- 绝对不要建议删除文件、重置代码仓库、强制推送等破坏性操作
- 不要修改任何文件，你是只读的观察者
""".trimIndent()

        val DEFAULT_PROMPT_EN = """
# Role

You are the user of this AI coding session.
Your partner (another AI assistant) just completed a round of work.

# Context

${'$'}{task}
${'$'}{conversation}

# What to do

Inspect the project files and git changes in read-only mode and decide whether anything can materially advance the original task.
If so, output exactly what you would type into the main session next.
If the task is complete or there is no new actionable input, output only PASS.

# Constraints

- Output only the message to send or PASS. Do not include thinking, analysis, or a prefix.
- Stay focused on the user's original task goal.
- Never suggest destructive operations such as deleting files, resetting the repository, or force-pushing.
- Do not modify any files. You are a read-only observer.
""".trimIndent()
    }
}

internal fun latestConversation(
    messages: List<SessionTranscriptMessage>,
    maxChars: Int,
): String {
    val selected = ArrayDeque<String>()
    var length = 0
    for (message in messages.asReversed()) {
        if (message.hiddenFromTerminal) continue
        val entry = "${message.role.replaceFirstChar(Char::uppercase)}: ${message.text}"
        val remaining = maxChars - length
        if (remaining <= 0) break
        if (entry.length > remaining && selected.isNotEmpty()) break
        val kept = if (entry.length <= remaining) entry else entry.takeLast(remaining)
        selected.addFirst(kept)
        length += kept.length
        if (kept.length < entry.length) break
    }
    return selected.joinToString("\n\n")
}

internal fun actionablePeerFeedback(output: String?): String? {
    val feedback = output?.trim().orEmpty()
    if (feedback.isEmpty() || feedback.equals("PASS", ignoreCase = true)) return null
    return feedback.take(4_000)
}

internal fun peerTask(session: com.github.izerui.imux.model.AgentSession): String =
    runCatching {
        sessionTranscriptMessages(
            session,
            maxLines = 400,
            maxMessages = 20,
            maxMessageChars = 2_000,
        ).firstOrNull { it.role == "user" && !it.hiddenFromTerminal }
            ?.text
            .orEmpty()
    }.getOrDefault("")
