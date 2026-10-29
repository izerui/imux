package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.bridge.BridgeHub
import com.github.izerui.imux.bridge.RobotManager
import com.github.izerui.imux.bridge.channel.ChannelKind
import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.robot.RobotRecord
import com.github.izerui.imux.icons.AgentIcons
import com.github.izerui.imux.settings.ImuxSettings
import com.github.izerui.imux.terminal.AgentTerminalVirtualFile
import com.github.izerui.imux.terminal.Notice
import com.github.izerui.imux.terminal.NoticeAction
import com.github.izerui.imux.terminal.NoticeSink
import com.github.izerui.imux.terminal.NoticeTone
import com.github.izerui.imux.terminal.SessionDecorationsListener
import com.github.izerui.imux.terminal.SessionTool
import com.github.izerui.imux.terminal.ToolState
import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.AnimatedIcon
import javax.swing.JComponent
import kotlin.coroutines.cancellation.CancellationException

private val LOG = logger<PhoneTool>()

data class PhoneBannerModel(
    val robotId: RobotId,
    val channel: String,
    val robotName: String,
    val problem: String?,
    /** 正在连接：不算问题，顶部给一条蓝色的“正在连接”，不能收起。 */
    val connecting: Boolean = false,
    /**
     * 问题的种类（Failed、Retrying、Kicked…），用作顶部提示的身份。不用 [problem]：它是本地化文字、还带失败原因，
     * 切换语言或重试原因一变就成了“新问题”，用户收起的提示会反复冒出来。
     */
    val problemKind: String? = null,
)

fun phoneBannerModel(
    enabled: Boolean,
    binding: Binding?,
    robot: RobotRecord?,
    status: ConnectionStatus?,
    channel: (ChannelKind) -> String,
    statusText: (ConnectionStatus?) -> String,
): PhoneBannerModel? {
    if (!enabled || binding == null || robot == null) return null
    // 与设置页状态圆点同一判断：黄点即异常；连接中单独算，它会自己好
    val connecting = status == ConnectionStatus.Connecting
    val problem = if (!connecting && needsAttention(status)) statusText(status) else null
    return PhoneBannerModel(
        robot.id,
        channel(robot.kind),
        robot.name,
        problem,
        connecting,
        problem?.let { status?.let { s -> s::class.simpleName } })
}

/** 手机按钮显示什么。开关关着也显示（未连接），点开菜单时再引导去设置里开启。 */
sealed interface PhoneSegmentState {
    data class Unbound(val bridgeEnabled: Boolean) : PhoneSegmentState

    data class Bound(val model: PhoneBannerModel) : PhoneSegmentState
}

fun phoneSegmentState(
    bridgeEnabled: Boolean,
    model: PhoneBannerModel?,
): PhoneSegmentState =
    if (bridgeEnabled && model != null) PhoneSegmentState.Bound(model) else PhoneSegmentState.Unbound(bridgeEnabled)

/** 顶部要显示的手机提示。连上时（含重连成功、换绑）发一次性提示，见 [PhoneTool]。 */
sealed interface PhoneNoticeState {
    data object None : PhoneNoticeState

    data class Connecting(val model: PhoneBannerModel) : PhoneNoticeState

    data class Problem(val model: PhoneBannerModel, val problem: String) : PhoneNoticeState

    data class Online(val model: PhoneBannerModel) : PhoneNoticeState
}

fun phoneNoticeState(state: PhoneSegmentState): PhoneNoticeState {
    val model = (state as? PhoneSegmentState.Bound)?.model ?: return PhoneNoticeState.None
    val problem = model.problem
    return when {
        model.connecting -> PhoneNoticeState.Connecting(model)
        problem != null -> PhoneNoticeState.Problem(model, problem)
        else -> PhoneNoticeState.Online(model)
    }
}

/** 从 [previous] 变到在线是否值得提示一次：刚连上、重连成功、换了机器人。打开标签页时已在线不提示。 */
fun phoneJustConnected(
    previous: PhoneNoticeState?,
    current: PhoneNoticeState,
): Boolean =
    current is PhoneNoticeState.Online &&
            previous != null &&
            (previous !is PhoneNoticeState.Online || previous.model.robotId != current.model.robotId)

/**
 * 终端右上角浮动图标组里的手机按钮：在线时显示机器人名（绿），连接中转圈显示「连接中」（蓝），出问题显示「连接异常」（黄），
 * 说明、换绑、重连、断开都在点开的菜单里。未连接时点开就是可选的机器人；通讯桥接开关关着时，菜单引导去设置里开启。
 *
 * 断线这类要处理的事在终端顶部发常驻提示（带「重连」「换绑」），连上时发一次性提示。
 */
class PhoneTool(
    private val project: Project,
    private val file: AgentTerminalVirtualFile,
    parent: Disposable,
    private val notices: NoticeSink,
) : SessionTool {
    private var noticeState: PhoneNoticeState? = null

    init {
        val listener =
            SessionDecorationsListener {
                try {
                    refresh()
                } catch (e: Throwable) {
                    if (e is CancellationException || e is ControlFlowException || e is VirtualMachineError) throw e
                    LOG.warn("手机提示刷新出错，已忽略", e)
                }
            }
        ApplicationManager.getApplication().messageBus.connect(parent)
            .subscribe(SessionDecorationsListener.TOPIC, listener)
        ImuxSettings.getInstance().addLanguageListener(parent) { listener.decorationsChanged() }
        refresh()
    }

    override fun state(): ToolState {
        val title = ImuxBundle.message("bridge.tool.title")
        return when (val notice = phoneNoticeState(segment())) {
            PhoneNoticeState.None -> ToolState(AgentIcons.phone, title, ImuxBundle.message("bridge.tool.unbound"))
            is PhoneNoticeState.Connecting ->
                ToolState(
                    AnimatedIcon.Default.INSTANCE, title,
                    ImuxBundle.message("bridge.notice.connecting", robotLabel(notice.model)),
                    label = ImuxBundle.message("bridge.tool.connecting"),
                    tone = NoticeTone.INFO,
                )

            is PhoneNoticeState.Problem ->
                ToolState(
                    AgentIcons.phone, title,
                    robotLabel(notice.model) + " · " + notice.problem,
                    label = ImuxBundle.message("bridge.tool.problem"),
                    tone = NoticeTone.WARNING,
                )

            is PhoneNoticeState.Online ->
                ToolState(
                    AgentIcons.phone, title,
                    ImuxBundle.message("bridge.banner.connected", notice.model.channel, notice.model.robotName),
                    label = notice.model.robotName,
                    tone = NoticeTone.SUCCESS,
                )
        }
    }

    override fun perform(anchor: JComponent) {
        when (val segment = segment()) {
            is PhoneSegmentState.Bound -> showMenu(segment.model, anchor)
            is PhoneSegmentState.Unbound -> showConnectMenu(anchor)
        }
    }

    private fun segment(): PhoneSegmentState {
        val binding = BridgeHub.getInstance().bindings.bindingForTab(file.tabId)
        val manager = RobotManager.getInstance()
        val robot = binding?.let { manager.robot(it.robotId) }
        val model = phoneBannerModel(
            true,
            binding,
            robot,
            robot?.let { manager.statusOf(it.id) },
            ::channelLabel
        ) { statusLabel(it, paired = true) }
        return phoneSegmentState(true, model)
    }

    private fun refresh() {
        val current = phoneNoticeState(segment())
        notices.persistent(
            when (current) {
                is PhoneNoticeState.Connecting ->
                    Notice(
                        key = "phone:connecting",
                        tone = NoticeTone.INFO,
                        text = ImuxBundle.message("bridge.notice.connecting", robotLabel(current.model)),
                        icon = AnimatedIcon.Default.INSTANCE,
                        closable = false,
                    )

                is PhoneNoticeState.Problem ->
                    Notice(
                        key = "phone:problem:${current.model.robotId.value}:${current.model.problemKind}",
                        tone = NoticeTone.WARNING,
                        text = ImuxBundle.message("bridge.notice.problem", robotLabel(current.model), current.problem),
                        actions = buildList {
                            // 收发受阻（SendFailing）重连解决不了，不提供重新连接
                            if (current.model.problemKind != ConnectionStatus.SendFailing::class.simpleName) {
                                add(NoticeAction(ImuxBundle.message("bridge.settings.reconnect")) {
                                    RobotManager.getInstance().reconnect(current.model.robotId)
                                })
                            }
                            add(NoticeAction(ImuxBundle.message("bridge.banner.rebind")) { anchor ->
                                showRebindMenu(anchor)
                            })
                        },
                    )

                else -> null
            },
        )
        if (current is PhoneNoticeState.Online && phoneJustConnected(noticeState, current)) {
            notices.flash(
                Notice(
                    key = "phone:online",
                    tone = NoticeTone.SUCCESS,
                    text = ImuxBundle.message(
                        "bridge.notice.online",
                        ImuxBundle.message("bridge.banner.connected", current.model.channel, current.model.robotName)
                    ),
                ),
            )
        }
        noticeState = current
    }

    private fun robotLabel(model: PhoneBannerModel): String =
        ImuxBundle.message("bridge.banner.robot", model.channel, model.robotName)

    private fun showMenu(
        current: PhoneBannerModel,
        anchor: JComponent,
    ) {
        val group = DefaultActionGroup()
        group.add(Separator.create(ImuxBundle.message("bridge.banner.hint")))
        // 可换的机器人直接平铺在「换绑机器人」分隔标题下，点一下就换，不用再展开二级菜单
        group.add(Separator.create(ImuxBundle.message("bridge.banner.rebind")))
        addRobots(group)
        group.addSeparator()
        group.add(phoneAction(ImuxBundle.message("bridge.settings.reconnect")) {
            RobotManager.getInstance().reconnect(current.robotId)
        })
        group.add(phoneAction(ImuxBundle.message("bridge.action.disconnect")) {
            PhoneOperations.disconnect(
                project,
                current.robotId
            )
        })
        group.addSeparator()
        group.add(phoneAction(ImuxBundle.message("bridge.banner.manage")) { showRobotSettings(project) })
        popup(group, anchor)
    }

    /** 顶部异常提示上的「换绑」：直接弹出可选的机器人，选一个就换。 */
    private fun showRebindMenu(anchor: JComponent) {
        val group = DefaultActionGroup()
        group.add(Separator.create(ImuxBundle.message("bridge.banner.rebind")))
        addRobots(group)
        popup(group, anchor)
    }

    private fun addRobots(group: DefaultActionGroup) {
        phoneMenuFor(file.tabId).filterIsInstance<PhoneMenuEntry.Robot>().forEach { entry ->
            group.add(robotAction(entry) { PhoneOperations.bind(project, file, entry.robotId) })
        }
    }

    /** 未连接时直接提供可选的机器人、添加机器人与管理。 */
    private fun showConnectMenu(
        anchor: JComponent,
    ) {
        val group = DefaultActionGroup()
        group.add(Separator.create(ImuxBundle.message("bridge.banner.hint")))
        phoneMenuFor(file.tabId).forEach { entry ->
            when (entry) {
                is PhoneMenuEntry.Robot ->
                    group.add(robotAction(entry) { PhoneOperations.bind(project, file, entry.robotId) })

                PhoneMenuEntry.NoRobots -> group.add(
                    phoneAction(
                        ImuxBundle.message("bridge.action.none"),
                        enabled = false
                    )
                )

                PhoneMenuEntry.AddRobot -> {
                    group.addSeparator()
                    group.add(phoneAction(ImuxBundle.message("bridge.action.add")) { AddRobotDialog(project).show() })
                }

                is PhoneMenuEntry.Disconnect -> Unit
            }
        }
        group.add(phoneAction(ImuxBundle.message("bridge.banner.manage")) { showRobotSettings(project) })
        popup(group, anchor)
    }

    private fun popup(
        group: DefaultActionGroup,
        anchor: JComponent,
    ) {
        JBPopupFactory.getInstance()
            .createActionGroupPopup(
                null,
                group,
                DataManager.getInstance().getDataContext(anchor),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                // 不可选的也显示：当前连着的机器人、未配对的机器人要看得到
                true,
            )
            .showUnderneathOf(anchor)
    }
}
