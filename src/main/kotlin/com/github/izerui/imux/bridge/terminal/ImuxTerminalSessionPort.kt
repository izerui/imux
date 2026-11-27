package com.github.izerui.imux.bridge.terminal

import com.github.izerui.imux.bridge.BridgeHub
import com.github.izerui.imux.bridge.TerminalBridgeService
import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.core.BindingRegistry
import com.github.izerui.imux.bridge.core.CodexReviewMenu
import com.github.izerui.imux.bridge.core.ReviewScreen
import com.github.izerui.imux.bridge.core.SessionTarget
import com.github.izerui.imux.bridge.core.StopResult
import com.github.izerui.imux.bridge.core.SubmitResult
import com.github.izerui.imux.bridge.core.TerminalSessionPort
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.terminal.TerminalHost
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.editor.VisualPosition
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.terminal.frontend.view.TerminalViewSessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.plugins.terminal.view.TerminalSendTextBuilder

/**
 * 桥接触达终端的唯一入口，只调用 TerminalHost 已公开的方法。
 *
 * 每次提交都重新按 tabId 查找终端，不缓存 view：标签可能已关闭、拖动重建或被迁移。
 * 不检查 Agent 是否在忙；排队还是插入由 CLI 决定。找不到终端就是离线，绝不启动 CLI。
 *
 * 每次调用只在 EDT 上尝试一次。未就绪（括号粘贴尚未开启）直接返回 NOT_READY，
 * 有限重试由 BridgeRouter 负责：它在每次重试前复核绑定，换绑后不会再提交到旧终端。
 */
class ImuxTerminalSessionPort internal constructor(
    private val viewAt: (Project, SessionTarget) -> TerminalView? = ::viewInProject,
    private val bindings: () -> BindingRegistry = { BridgeHub.getInstance().bindings },
    private val pickerEntries: (SessionTarget, GitPicker) -> List<String> = { target, picker -> pickerEntriesForTab(target, picker) },
    private val projectAt: (String) -> Project? = ::openProjectAt,
) : TerminalSessionPort {
    override fun supportsReviewMenu(target: SessionTarget): Boolean = target.agentType == AgentType.CODEX

    override suspend fun reviewTargetOnline(target: SessionTarget): Boolean =
        withContext(Dispatchers.EDT) { isAvailable(target) }

    override suspend fun currentReviewMenu(target: SessionTarget): CodexReviewMenu? = (readReview(target) as? ReviewScreen.Open)?.menu

    override suspend fun readReview(target: SessionTarget): ReviewScreen =
        withContext(Dispatchers.EDT) {
            val binding = bindings().bindingForTab(target.tabId)
            if (binding?.target != target) return@withContext ReviewScreen.Unavailable
            val screen = reviewView(binding)?.let { reviewScreen(binding, it) } ?: return@withContext ReviewScreen.Unavailable
            CodexReviewMenu.parse(screen)?.let(ReviewScreen::Open) ?: ReviewScreen.Closed
        }

    override suspend fun answerReviewMenu(binding: Binding, expected: CodexReviewMenu, reply: String): Boolean =
        withContext(Dispatchers.EDT) {
            val view = reviewView(binding) ?: return@withContext false
            val current = reviewScreen(binding, view)?.let(CodexReviewMenu::parse) ?: return@withContext false
            answerCurrentReview(bindings(), binding, expected, current, reply) { keys ->
                view.createSendTextBuilder().trySend(keys)
            }
        }

    internal fun reviewView(binding: Binding): TerminalView? {
        val target = binding.target
        if (target.agentType != AgentType.CODEX || bindings().bindingOf(binding.robotId) != binding) return null
        val view = liveView(target) ?: return null
        val host = projectAt(target.projectPath)?.serviceIfCreated<TerminalHost>() ?: return null
        return view.takeIf { host.fileFor(it)?.agentType == AgentType.CODEX }
    }

    internal suspend fun reviewScreen(binding: Binding, view: TerminalView): String? =
        gitPickerScreens.read(binding, raw = {
            if (bindings().bindingOf(binding.robotId) == binding && liveView(binding.target) === view) rawReviewScreen(binding, view) else null
        }, load = { pickerEntries(binding.target, it) })

    internal fun forgetReviewScreen(binding: Binding) = gitPickerScreens.forget(binding)

    /** Read the live grid from its bottom even when the IDE user has scrolled into history. */
    private fun rawReviewScreen(binding: Binding, view: TerminalView): String? {
        val models = view.outputModels
        val model = models.active.value
        // The alternative buffer has no scrollback, even when its editor is detached.
        if (model === models.alternative) return model.getText(model.startOffset, model.endOffset).toString()
        val rows = view.gridSize?.rows?.takeIf { it > 0 } ?: return null
        val host = projectAt(binding.target.projectPath)?.serviceIfCreated<TerminalHost>()
        val editor = host?.currentTerminalEditor(view)
        val start = if (editor != null && !editor.isDisposed && editor.document.textLength == model.textLength) {
            val bottom = editor.offsetToVisualPosition(model.textLength).line
            val top = VisualPosition((bottom - rows + 1).coerceAtLeast(0), 0)
            model.startOffset + editor.visualPositionToOffset(top).toLong()
        } else {
            val first = model.firstLineIndex + (model.lineCount - rows).coerceAtLeast(0).toLong()
            model.getStartOfLine(first)
        }
        return model.getText(start, model.endOffset).toString()
    }

    override suspend fun submit(
        target: SessionTarget,
        text: String,
    ): SubmitResult = withContext(Dispatchers.EDT) { submitOnce(target, text) }

    /** 逐个按下 [stopKeys]，结果见 [pressStopKeys]。 */
    override suspend fun stop(
        target: SessionTarget,
        turnRunning: Boolean,
    ): StopResult {
        if (supportsReviewMenu(target)) {
            val binding = BridgeHub.getInstance().bindings.bindingForTab(target.tabId)
                ?.takeIf { it.target == target } ?: return StopResult.OFFLINE
            return stop(binding, turnRunning) {}
        }
        val keys = stopKeys(target.agentType, turnRunning)
        if (keys.isEmpty()) {
            return withContext(Dispatchers.EDT) {
                if (liveView(target) == null) StopResult.OFFLINE else StopResult.NOTHING_TO_STOP
            }
        }
        return pressStopKeys(keys, wait = { delay(it) }) { text ->
            withContext(Dispatchers.EDT) {
                val view = liveView(target) ?: return@withContext false
                sendKey(view.createSendTextBuilder(), text)
                true
            }
        }
    }

    override suspend fun stop(binding: Binding, turnRunning: Boolean, closedMenu: (CodexReviewMenu) -> Unit): StopResult {
        if (!supportsReviewMenu(binding.target)) return stop(binding.target, turnRunning)
        val sequence = CodexStopSequence(turnRunning, closedMenu)
        while (true) {
            val result = withContext(Dispatchers.EDT) {
                val view = reviewView(binding) ?: return@withContext StopResult.OFFLINE
                val screen = reviewScreen(binding, view) ?: return@withContext StopResult.NOT_READY
                sequence.advance(CodexReviewMenu.parse(screen)) { key ->
                    BridgeHub.getInstance().bindings.claimCurrent(binding.robotId, binding.epoch) {
                        view.createSendTextBuilder().trySend(key)
                    }
                }
            }
            if (result != null) return result
            delay(sequence.delayMs)
        }
    }

    /** 界面与提交消息使用同一个存活判据；保留的恢复绑定不代表终端在线。 */
    internal fun isAvailable(target: SessionTarget): Boolean = liveView(target) != null

    internal suspend fun submitOnce(
        target: SessionTarget,
        text: String,
    ): SubmitResult {
        val view = liveView(target) ?: return SubmitResult.OFFLINE
        if (target.agentType == AgentType.CODEX) {
            val binding = bindings().bindingForTab(target.tabId)
            if (binding?.target != target) return SubmitResult.OFFLINE
            if (reviewScreen(binding, view)?.let(CodexReviewMenu::parse) != null) return SubmitResult.NOT_READY
            if (bindings().bindingOf(binding.robotId) != binding || liveView(target) !== view) return SubmitResult.OFFLINE
        }
        return if (submitText(view.createSendTextBuilder(), text)) SubmitResult.SUBMITTED_TO_TERMINAL else SubmitResult.NOT_READY
    }

    private fun liveView(target: SessionTarget): TerminalView? {
        val project = projectAt(target.projectPath)?.takeUnless { it.isDisposed || !it.isOpen } ?: return null
        if (project.serviceIfCreated<TerminalBridgeService>()?.isClosing() == true) return null
        val view = viewAt(project, target) ?: return null
        return view.takeIf { it.sessionState.value is TerminalViewSessionState.Running }
    }
}

// The observer and input router have separate ports but read the same live picker.
private val gitPickerScreens = GitPickerScreens<Binding>()

internal fun answerCurrentReview(
    bindings: BindingRegistry,
    binding: Binding,
    expected: CodexReviewMenu,
    current: CodexReviewMenu,
    reply: String,
    enqueue: (String) -> Boolean,
): Boolean {
    if (!current.sameChoices(expected)) return false
    val keys = current.keysFor(reply)?.takeIf { it.isNotEmpty() } ?: return false
    val input = keys.joinToString("")
    // One asynchronous terminal input event. A rebind cannot interleave with registration.
    return bindings.claimCurrent(binding.robotId, binding.epoch) {
        bindings.bindingOf(binding.robotId) == binding && enqueue(input)
    }
}

internal fun bindingSessionAvailable(binding: Binding): Boolean =
    ImuxTerminalSessionPort().isAvailable(binding.target)

/**
 * 与副驾驶已抓包验证的形态一致：require 在括号粘贴未开启时一个字节都不写并返回 false；
 * 正文与回车同一次发送，回车落在括号外才会被 CLI 当作提交。
 */
internal fun submitText(
    builder: TerminalSendTextBuilder,
    text: String,
): Boolean = builder.requireBracketedPasteMode().shouldExecute().trySend(text)

/** 停止时按的一个键：[text] 原样写进终端，发它之前先等 [delayMs]。 */
internal data class StopKey(
    val text: String,
    val delayMs: Long = 0,
)

/**
 * 手机发停止时要按的键，回到等待输入、不退出 CLI。[turnRunning] 时先按 Esc 中断这一轮：三个 CLI 都以单独一个 ESC 字节中断，
 * 不用 Ctrl+C（Claude Code、Codex 空闲时连按两次会退出）。再停后台任务：
 * - Claude Code：Ctrl+X Ctrl+K 停掉全部后台子代理，3 秒内按两次才生效（顺带关掉 artifact 自动回复）；后台 shell 不管，它没有一键停止的键。
 * - Codex：`/stop` 停掉全部后台终端。逐字输入、隔一会儿再回车，与人手敲一样：连着回车会被 Codex 当成粘贴里的换行。
 * - Pi：没有后台任务，没在跑一轮时什么也不按。
 * Esc 之后隔一会儿再按下一个键：紧跟其后的键会和 Esc 拼成 Alt 组合键，也让 CLI 先把这一轮停稳。
 */
internal fun stopKeys(
    agent: AgentType,
    turnRunning: Boolean,
): List<StopKey> {
    val background =
        when (agent) {
            AgentType.CLAUDE -> listOf(StopKey(KILL_AGENTS), StopKey(KILL_AGENTS, KEY_GAP_MS))
            AgentType.CODEX -> listOf(StopKey("/stop"), StopKey("\r", KEY_GAP_MS))
            AgentType.PI -> emptyList()
        }
    if (!turnRunning) return background
    return listOf(StopKey(ESCAPE)) + background.mapIndexed { index, key -> if (index == 0) key.copy(delayMs = AFTER_ESCAPE_MS) else key }
}

/**
 * 逐个按下停止的键，每个键按前先等 [StopKey.delayMs]。全部按出去才算 [StopResult.SENT]；
 * 中途有一个没按出去（终端没了）就停下、按 [StopResult.OFFLINE]：比如 `/stop` 打进去了、回车前终端消失，指令其实没提交，
 * 不能告诉手机「已发送」。终端都不在了，本来就按未连接处理。
 */
internal suspend fun pressStopKeys(
    keys: List<StopKey>,
    wait: suspend (Long) -> Unit,
    press: suspend (String) -> Boolean,
): StopResult {
    for (key in keys) {
        if (key.delayMs > 0) wait(key.delayMs)
        if (!press(key.text)) return StopResult.OFFLINE
    }
    return StopResult.SENT
}

/** 原样写进终端，不用括号粘贴（会被当成贴进来的文字），也不带回车。 */
internal fun sendKey(
    builder: TerminalSendTextBuilder,
    text: String,
) = builder.send(text)

private const val ESCAPE = "\u001b"

/** Ctrl+X Ctrl+K。 */
private const val KILL_AGENTS = "\u0018\u000b"

private const val KEY_GAP_MS = 300L

private const val AFTER_ESCAPE_MS = 500L

private fun openProjectAt(projectPath: String): Project? =
    ProjectManager.getInstance().openProjects.firstOrNull { it.basePath == projectPath }

private fun viewInProject(project: Project, target: SessionTarget): TerminalView? {
    // 查询状态不创建终端服务，也不触发恢复会话。
    val host = project.serviceIfCreated<TerminalHost>() ?: return null
    val sessionKey = host.openTabsByTabId()[target.tabId] ?: return null
    return host.terminalViewOf(sessionKey)
}
