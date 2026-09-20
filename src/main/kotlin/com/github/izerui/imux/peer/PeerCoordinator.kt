package com.github.izerui.imux.peer

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.session.JsonLineScanner
import com.github.izerui.imux.session.SessionListModel
import com.github.izerui.imux.settings.ImuxSettings
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.ui.AnimatedIcon
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel

class PeerCoordinator(
    private val project: Project,
    private val projectPath: String,
    private val model: SessionListModel,
    private val viewOf: (String) -> TerminalView?,
    private val coroutineScope: CoroutineScope,
) {
    private val bindings = ConcurrentHashMap<String, PeerBinding>()
    private val roundCounts = ConcurrentHashMap<String, AtomicInteger>()

    /** 持久横幅的状态：bannerDisposable 控制横幅生命周期，label 用于更新文字。 */
    private val bannerStates = ConcurrentHashMap<String, BannerState>()
    /** 副驾驶执行期间的输入拦截器，执行完后释放。 */
    private val inputGuards = ConcurrentHashMap<String, Disposable>()

    private data class BannerState(
        val disposable: Disposable,
        val label: JLabel,
        val spinnerLabel: JLabel,
    )

    fun bind(sessionId: String, targetAgentType: AgentType) {
        unbind(sessionId)
        val task = extractTask(sessionId)
        bindings[sessionId] = PeerBinding(targetAgentType, task)
        LOG.info("结对编程：绑定 $sessionId -> ${targetAgentType.displayName}")
        showBanner(sessionId, targetAgentType, waiting = false)
    }

    fun unbind(sessionId: String) {
        bindings.remove(sessionId)
        roundCounts.remove(sessionId)
        removeBanner(sessionId)
        removeInputGuard(sessionId)
        LOG.info("结对编程：解绑 $sessionId")
    }

    fun isBound(sessionId: String): Boolean = bindings.containsKey(sessionId)

    fun boundTarget(sessionId: String): AgentType? = bindings[sessionId]?.targetAgentType

    fun onTurnCompleted(sessionId: String) {
        val binding = bindings[sessionId] ?: return
        LOG.info("结对编程：主会话轮次完成 sessionId=$sessionId")

        updateBannerToWaiting(sessionId, binding.targetAgentType)
        lockInput(sessionId)

        coroutineScope.launch(Dispatchers.IO) {
            try {
                runReviewAndInject(sessionId)
            } finally {
                withContext(Dispatchers.EDT) {
                    removeInputGuard(sessionId)
                    if (bindings.containsKey(sessionId)) {
                        updateBannerToIdle(sessionId, binding.targetAgentType)
                    }
                }
            }
        }
    }

    private fun showBanner(sessionId: String, agentType: AgentType, waiting: Boolean) {
        val view = viewOf(sessionId) ?: return
        runCatching {
            val disposable = Disposer.newDisposable("peerBanner-$sessionId")
            val spinnerLabel = JLabel(AnimatedIcon.Default()).apply { isVisible = waiting }
            val textLabel = JLabel("  " + if (waiting) {
                ImuxBundle.message("action.peer.progress.reviewing", agentType.displayName)
            } else {
                ImuxBundle.message("action.peer.enabled", agentType.displayName)
            })
            val banner = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                border = JBUI.Borders.empty(6, 12)
                isOpaque = true
                background = JBUI.CurrentTheme.Banner.INFO_BACKGROUND
                add(spinnerLabel)
                add(textLabel)
            }
            view.setTopComponent(banner, disposable)
            bannerStates[sessionId] = BannerState(disposable, textLabel, spinnerLabel)
        }.onFailure {
            LOG.warn("结对编程：无法显示横幅", it)
        }
    }

    private fun updateBannerToWaiting(sessionId: String, agentType: AgentType) {
        val state = bannerStates[sessionId]
        if (state != null) {
            state.label.text = "  " + ImuxBundle.message("action.peer.progress.reviewing", agentType.displayName)
            state.spinnerLabel.isVisible = true
        } else {
            showBanner(sessionId, agentType, waiting = true)
        }
    }

    private fun updateBannerToIdle(sessionId: String, agentType: AgentType) {
        val state = bannerStates[sessionId] ?: return
        state.label.text = "  " + ImuxBundle.message("action.peer.enabled", agentType.displayName)
        state.spinnerLabel.isVisible = false
    }

    private fun removeBanner(sessionId: String) {
        bannerStates.remove(sessionId)?.let { Disposer.dispose(it.disposable) }
    }

    private fun lockInput(sessionId: String) {
        val view = viewOf(sessionId) ?: return
        runCatching {
            val guard = Disposer.newDisposable("peerInputGuard-$sessionId")
            view.addInputInterceptor(guard) { true }
            inputGuards[sessionId] = guard
        }
    }

    private fun removeInputGuard(sessionId: String) {
        inputGuards.remove(sessionId)?.let { Disposer.dispose(it) }
    }

    private fun runReviewAndInject(mainSessionId: String) {
        val binding = bindings[mainSessionId] ?: return

        val round = roundCounts
            .computeIfAbsent(mainSessionId) { AtomicInteger(0) }
            .incrementAndGet()

        val maxRounds = ImuxSettings.getInstance().state.peerMaxRounds
        if (round > maxRounds) {
            LOG.info("结对编程：已达安全上限 $maxRounds 轮，停止")
            roundCounts[mainSessionId]?.set(0)
            return
        }

        LOG.info("结对编程：第 $round 轮，采集对话历史...")

        val conversation = collectLatestConversation(mainSessionId)
        val reviewPrompt = buildReviewPrompt(binding.task, conversation)
        LOG.info("结对编程：调用 ${binding.targetAgentType.cli}...")

        val rawOutput = callCliOnce(binding.targetAgentType, reviewPrompt)
        if (rawOutput.isNullOrBlank()) {
            LOG.warn("结对编程：副驾驶无返回")
            return
        }

        val feedback = rawOutput.trim()
        val injectPrompt = feedback.take(MAX_FEEDBACK_LENGTH)
        LOG.info("结对编程：收到反馈（${injectPrompt.length} 字符），注入主会话")

        coroutineScope.launch(Dispatchers.EDT) {
            if (project.isDisposed) return@launch
            if (ImuxSettings.getInstance().state.peerAutoInject) {
                injectFeedback(mainSessionId, injectPrompt)
            } else {
                notifyForConfirmation(mainSessionId, injectPrompt)
            }
        }
    }

    private fun buildReviewPrompt(task: String, conversation: String): String {
        val customPrompt = ImuxSettings.getInstance().state.peerPromptOverride
        if (customPrompt != null) {
            return customPrompt
                .replace("\${task}", task)
                .replace("\${conversation}", conversation)
        }

        val lang = ImuxBundle.currentLanguage()
        val isChinese = lang.id == "zh_CN" || lang.id == "zh_TW"
        val taskSection = if (task.isNotBlank()) "任务：$task\n\n" else ""
        val taskSectionEn = if (task.isNotBlank()) "Task: $task\n\n" else ""
        val convSection = if (conversation.isNotBlank()) "对话记录：\n$conversation\n\n" else ""
        val convSectionEn = if (conversation.isNotBlank()) "Conversation:\n$conversation\n\n" else ""
        return if (isChinese) DEFAULT_PROMPT_ZH
            .replace("\${task}", taskSection)
            .replace("\${conversation}", convSection)
        else DEFAULT_PROMPT_EN
            .replace("\${task}", taskSectionEn)
            .replace("\${conversation}", convSectionEn)
    }

    private fun extractTask(sessionId: String): String {
        val session = model.sessionOf(sessionId) ?: return ""
        return runCatching {
            val file = session.filePath
            if (!java.nio.file.Files.isRegularFile(file)) return@runCatching ""
            java.nio.file.Files.readAllLines(file).firstNotNullOfOrNull { line ->
                val role = JsonLineScanner.topLevelStringValue(line, "role")
                    ?: JsonLineScanner.objectStringValue(line, "message", "role")
                if (role == "user") JsonLineScanner.stringValue(line, "text")?.take(500) else null
            } ?: ""
        }.getOrElse { "" }
    }

    private fun callCliOnce(agentType: AgentType, prompt: String): String? {
        var promptFile: java.io.File? = null
        return runCatching {
            promptFile = java.io.File.createTempFile("imux-peer-prompt-", ".txt").apply {
                deleteOnExit()
                writeText(prompt)
            }
            val promptPath = promptFile!!.absolutePath

            val cliCommand = when (agentType) {
                AgentType.CLAUDE -> "claude -p < '$promptPath'"
                AgentType.CODEX -> "codex exec --ephemeral < '$promptPath'"
                AgentType.PI -> "pi -p --no-session < '$promptPath'"
            }
            val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/zsh"
            LOG.info("结对编程：通过 $shell 执行 $cliCommand")

            val process = ProcessBuilder(shell, "-l", "-c", cliCommand)
                .directory(java.io.File(projectPath))
                .redirectErrorStream(false)
                .start()

            // 并发消费 stderr 防止缓冲区满死锁，内容丢弃
            val stderrDrainer = Thread {
                runCatching { process.errorStream.bufferedReader().readText() }
            }.apply { isDaemon = true; start() }

            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(CLI_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            stderrDrainer.join(1000)

            if (!finished) {
                LOG.warn("结对编程：CLI 超时（${CLI_TIMEOUT_SECONDS}s），强制终止")
                process.destroyForcibly()
                return@runCatching null
            }

            val exitCode = process.exitValue()
            if (exitCode != 0) {
                LOG.warn("结对编程：CLI 退出码 $exitCode")
            }

            LOG.info("结对编程：CLI 返回 ${output.length} 字符")
            output.trim().takeIf { it.isNotBlank() }
        }.getOrElse {
            LOG.warn("结对编程：CLI 调用失败", it)
            null
        }.also {
            promptFile?.delete()
        }
    }

    private fun injectFeedback(mainSessionId: String, prompt: String) {
        val view = viewOf(mainSessionId)
        if (view != null) {
            LOG.info("结对编程：注入反馈到主会话 $mainSessionId（${prompt.length} 字符）")
            view.createSendTextBuilder()
                .useBracketedPasteMode()
                .send(prompt)
            view.sendText("\r")
        } else {
            LOG.warn("结对编程：找不到主会话终端 $mainSessionId")
        }
    }

    private fun notifyForConfirmation(mainSessionId: String, prompt: String) {
        val notification = NotificationGroupManager
            .getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                ImuxBundle.message("action.peer.notification.title"),
                ImuxBundle.message("action.peer.notification.content", ""),
                NotificationType.INFORMATION,
            )
        notification.addAction(
            NotificationAction.createSimpleExpiring(ImuxBundle.message("action.peer.notification.inject")) {
                injectFeedback(mainSessionId, prompt)
            },
        )
        notification.notify(project)
    }

    private fun collectLatestConversation(sessionId: String): String {
        val session = model.sessionOf(sessionId) ?: return ""
        return runCatching {
            val file = session.filePath
            if (!java.nio.file.Files.isRegularFile(file)) return@runCatching ""
            val lines = java.nio.file.Files.readAllLines(file)

            val parts = mutableListOf<String>()
            var totalLength = 0
            for (line in lines) {
                val role = JsonLineScanner.topLevelStringValue(line, "role")
                    ?: JsonLineScanner.objectStringValue(line, "message", "role")
                    ?: continue
                val text = JsonLineScanner.stringValue(line, "text") ?: continue
                val entry = when (role) {
                    "user" -> "User: $text"
                    "assistant" -> "Assistant: $text"
                    else -> continue
                }
                parts += entry
                totalLength += entry.length
                if (totalLength > MAX_CONVERSATION_LENGTH) break
            }
            parts.joinToString("\n\n")
        }.getOrElse {
            LOG.warn("结对编程：读取主会话对话失败", it)
            ""
        }
    }

    companion object {
        private val LOG = logger<PeerCoordinator>()
        private const val NOTIFICATION_GROUP = "imux.turnCompleted"
        private const val MAX_CONVERSATION_LENGTH = 100000
        private const val MAX_FEEDBACK_LENGTH = 4000
        private const val CLI_TIMEOUT_SECONDS = 300L

        val DEFAULT_PROMPT_ZH = """
# 角色

你现在扮演这个 AI 编程会话的用户。
你的搭档（另一个 AI 助手）刚完成了一轮工作。

# 上下文

${'$'}{task}
${'$'}{conversation}

# 你要做什么

请自己查看项目文件和 git 变更记录来了解代码的当前状态。
然后作为用户，你接下来会说什么？

可以是：追问、纠正、推进下一步、换个方向，或者任何你觉得该说的话。
像正常使用 AI 助手一样说话。简短自然。

# 约束

- 始终围绕用户的原始任务目标推进，不要跑偏到无关的事情上
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

Check the project files and git history yourself to understand the current code state.
Then as the user, what would you type next?

It could be: a follow-up question, a correction, pushing to the next step, changing direction, or anything you'd naturally say.
Talk like a normal user, not a reviewer. Keep it brief and natural.

# Constraints

- Always stay focused on the user's original task goal. Do not drift to unrelated topics.
- Never suggest destructive operations like deleting files, resetting the repo, or force-pushing.
- Do not modify any files. You are a read-only observer.
""".trimIndent()
    }
}
