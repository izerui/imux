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
import java.util.concurrent.atomic.AtomicInteger

data class PeerStatus(
    val targetAgentType: AgentType,
    val running: Boolean,
)

fun interface PeerStateListener : EventListener {
    fun peerStateChanged(sessionKey: String)
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
    private val guards = ConcurrentHashMap<String, PeerSessionGuard>()
    private val peerInjectedSessions = ConcurrentHashMap.newKeySet<String>()
    private val stateDispatcher = EventDispatcher.create(PeerStateListener::class.java)

    fun addStateListener(
        parentDisposable: Disposable,
        listener: PeerStateListener,
    ) {
        stateDispatcher.addListener(listener, parentDisposable)
    }

    fun status(sessionKey: String): PeerStatus? =
        bindings[sessionKey]?.let {
            PeerStatus(it.targetAgentType, guards[sessionKey]?.isReviewing == true)
        }

    fun bind(
        sessionKey: String,
        targetAgentType: AgentType,
    ) {
        unbind(sessionKey)
        bindings[sessionKey] = PeerBinding(targetAgentType)
        LOG.info("结对编程：绑定 $sessionKey -> ${targetAgentType.displayName}")
        notifyStateChanged(sessionKey)
    }

    fun unbind(sessionKey: String) {
        bindings.remove(sessionKey)
        roundCounts.remove(sessionKey)
        peerInjectedSessions.remove(sessionKey)
        guards.remove(sessionKey)?.cancel()
        LOG.info("结对编程：解绑 $sessionKey")
        notifyStateChanged(sessionKey)
    }

    fun boundTarget(sessionKey: String): AgentType? = bindings[sessionKey]?.targetAgentType

    fun onTurnCompleted(sessionKey: String) {
        val binding = bindings[sessionKey] ?: return
        if (!peerInjectedSessions.remove(sessionKey)) {
            roundCounts[sessionKey]?.set(0)
        }
        val guard = guards.computeIfAbsent(sessionKey) { PeerSessionGuard() }
        if (!bindings.containsKey(sessionKey)) {
            guards.remove(sessionKey, guard)
            return
        }
        val run = guard.tryStart()
        if (run == null) {
            LOG.info("结对编程：$sessionKey 已有副驾驶运行，标记待补跑")
            return
        }
        LOG.info("结对编程：主会话轮次完成 sessionKey=$sessionKey")
        launchReview(sessionKey, binding, run, guard)
    }

    private fun launchReview(sessionKey: String, binding: PeerBinding, run: PeerRun, guard: PeerSessionGuard) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                runReviewAndInject(sessionKey, binding, run, guard)
            } finally {
                val rerun = guard.onFinished(run)
                withContext(Dispatchers.EDT) {
                    notifyStateChanged(sessionKey)
                }
                if (rerun != null) {
                    val latestBinding = bindings[sessionKey]
                    if (latestBinding != null) {
                        LOG.info("结对编程：补跑 $sessionKey")
                        launchReview(sessionKey, latestBinding, rerun, guard)
                    } else {
                        guard.cancel()
                    }
                }
            }
        }
    }

    fun cancelCurrentRun(sessionKey: String) {
        LOG.info("结对编程：手动取消 $sessionKey")
        roundCounts[sessionKey]?.set(0)
        guards[sessionKey]?.cancel()
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
        guards.remove(from)?.cancel()
        if (binding != null) {
            bindings[to] = binding.copy(task = "")
            roundCounts.remove(to)
            LOG.info("结对编程：迁移绑定 $from -> $to")
        }
        notifyStateChanged(from)
        notifyStateChanged(to)
    }

    override fun dispose() {
        guards.values.forEach { it.cancel() }
        guards.clear()
        bindings.clear()
        roundCounts.clear()
        peerInjectedSessions.clear()
    }

    private suspend fun runReviewAndInject(
        mainSessionKey: String,
        initialBinding: PeerBinding,
        run: PeerRun,
        guard: PeerSessionGuard,
    ) {
        if (!runIsCurrent(mainSessionKey, initialBinding, run, guard)) return

        run.reviewing.set(true)
        withContext(Dispatchers.EDT) {
            if (runIsCurrent(mainSessionKey, initialBinding, run, guard)) notifyStateChanged(mainSessionKey)
        }

        if (!runIsCurrent(mainSessionKey, initialBinding, run, guard)) return

        val round =
            roundCounts
                .computeIfAbsent(mainSessionKey) { AtomicInteger(0) }
                .incrementAndGet()
        val maxRounds = ImuxSettings.getInstance().state.peerMaxRounds
        if (round > maxRounds) {
            LOG.info("结对编程：已达安全上限 $maxRounds 轮，停止")
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

        if (!runIsCurrent(mainSessionKey, binding, run, guard)) return

        val result =
            runCatching {
                runCli(command, Path.of(projectPath), prompt, CLI_TIMEOUT_SECONDS, run::attach)
            }

        if (!runIsCurrent(mainSessionKey, binding, run, guard)) return

        val rawOutput = result.getOrNull()
        val error = result.exceptionOrNull()
        if (error != null) {
            LOG.warn("结对编程：CLI 调用失败", error)
            withContext(Dispatchers.EDT) {
                if (!project.isDisposed) notifyCliError(binding, error.message ?: error.javaClass.simpleName)
            }
            return
        }

        val feedback = actionablePeerFeedback(rawOutput)
        if (feedback == null) {
            LOG.info("结对编程：副驾驶无有效反馈，本轮结束")
            return
        }

        withContext(Dispatchers.EDT) {
            if (!runIsCurrent(mainSessionKey, binding, run, guard) || project.isDisposed) return@withContext
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
        guard: PeerSessionGuard,
    ): Boolean =
        !run.cancelled.get() &&
                guard.isActive(run) &&
                bindings[sessionKey] == binding

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
        peerInjectedSessions.add(mainSessionKey)
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

    private fun notifyCliError(binding: PeerBinding, detail: String) {
        NotificationGroupManager
            .getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                ImuxBundle.message("action.peer.notification.title"),
                ImuxBundle.message("action.peer.notification.error", binding.targetAgentType.displayName, detail),
                NotificationType.WARNING,
            )
            .notify(project)
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

你是这个 AI 编程会话的结对编程伙伴，扮演引导者的角色。
你和主会话中的 AI 助手组成搭档，共同协作完成用户交给你们的任务。
你站在独立第三方的视角审视主会话的工作，通过提问和引导帮助主会话把事情做得更好。

# 上下文

${'$'}{task}
${'$'}{conversation}

# 你要做什么

请以只读方式查看项目文件和 git 变更，结合主会话刚完成的工作，看看有什么想说的。
你的输出会被直接发送给主会话，作为它下一步工作的参考。
通过提问和启发来引导主会话思考，而不是直接下达指令让它执行。
如果你觉得当前工作已经没什么好说的了，只输出 PASS。

# 约束

- 直接输出要发送的内容或 PASS，不要输出思考过程、分析过程或前缀
- 始终围绕用户的原始任务目标
- 绝对不要建议删除文件、重置代码仓库、强制推送等破坏性操作
- 不要修改任何文件，你是只读的观察者
""".trimIndent()

        val DEFAULT_PROMPT_EN = """
# Role

You are a pair-programming partner for this AI coding session, acting as a guide.
You and the AI assistant in the main session form a team, collaborating to complete the task assigned by the user.
You observe from an independent third-party perspective, helping the main session do better work through questions and guidance.

# Context

${'$'}{task}
${'$'}{conversation}

# What to do

Inspect the project files and git changes in read-only mode. Based on the main session's latest work, share whatever comes to mind.
Your output will be sent directly to the main session as input for its next step.
Guide the main session to think by asking questions and offering insights, rather than issuing direct commands.
If you feel there is nothing worth saying about the current work, output only PASS.

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
