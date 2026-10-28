package com.github.izerui.imux.bridge

import com.github.izerui.imux.bridge.core.BindResult
import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.core.SessionTarget
import com.intellij.openapi.project.Project

/**
 * 绑定操作的唯一入口：连接、断开、标签关闭释放。
 *
 * 改绑定表并通知手机；持久化与界面刷新由 [BridgeHub] 随绑定表改动自动完成。
 * 确认对话框与 IDE 提示留在界面层，这里不碰界面。
 */
object BindingService {
    private val hub get() = BridgeHub.getInstance()

    /**
     * 把机器人连到这个终端。标签已被别的机器人占用时先让它断开；机器人原先连着别的会话时一并断开，
     * 两边的手机都会收到断开提示，这个机器人的手机收到新会话的连接提示。返回新绑定，失败为 null。
     */
    fun bind(
        project: Project,
        robotId: RobotId,
        target: SessionTarget,
        sessionTitle: String,
    ): Binding? {
        val occupant = hub.bindings.bindingForTab(target.tabId)?.takeIf { it.robotId != robotId }
        occupant?.let { hub.bindings.unbind(it.robotId) }
        val result = hub.bindings.bind(robotId, target) as? BindResult.Bound
        val replaced = result?.replaced?.takeIf { it.target.tabId != target.tabId }
        val released = listOfNotNull(occupant, replaced).map { it to SessionTitles.of(it) }
        val binding = result?.binding
        // 断开与连接的提示在同一个协程里按顺序发；占用者已经断开，即使这次绑定没成功它的手机也要收到断开提示
        hub.launch { hub.router.onRebound(released, binding, project.name, target.agentType.displayName, sessionTitle) }
        binding ?: return null
        TerminalBridgeService.getInstance(project).start()
        return binding
    }

    /** 用户主动断开这个机器人的会话连接。返回被断开的绑定。 */
    fun unbind(robotId: RobotId): Binding? =
        hub.bindings.unbind(robotId)?.also { binding ->
            val title = SessionTitles.of(binding)
            hub.launch { hub.router.onReleased(binding, title) }
        }

    /**
     * 终端标签关闭时释放。[binding] 已被换绑或再次绑定（代次不同）时不动，返回 false。
     * [sessionTitle] 由调用方给出：标签已关闭，取不到标签标题了。
     */
    fun release(
        binding: Binding,
        sessionTitle: String,
    ): Boolean {
        if (hub.bindings.bindingOf(binding.robotId)?.epoch != binding.epoch) return false
        hub.bindings.unbind(binding.robotId) ?: return false
        hub.launch { hub.router.onReleased(binding, sessionTitle) }
        return true
    }
}
