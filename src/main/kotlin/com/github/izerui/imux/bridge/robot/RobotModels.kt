package com.github.izerui.imux.bridge.robot

import com.github.izerui.imux.bridge.channel.ChannelKind
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.core.RobotOwner

/**
 * 一个长期保存的机器人。它与会话无关：会话来来去去，机器人反复绑定。
 *
 * 扫码拿不到本人身份的渠道（企业微信、钉钉）在认主前带一个 [pairCode]，
 * 等本人把它发给机器人；认主后只接受本人的消息。
 */
data class RobotRecord(
    val id: RobotId,
    val name: String,
    val kind: ChannelKind,
    val ownerSenderId: String? = null,
    val ownerChatId: String? = null,
    val pairCode: String? = null,
    val pairCodeExpiresAtMillis: Long = 0,
    val createdAtMillis: Long = 0,
) {
    fun owner(): RobotOwner? {
        val sender = ownerSenderId ?: return null
        val chat = ownerChatId ?: return null
        return RobotOwner(sender, chat)
    }
}

/** 持久化的绑定：IDE 重启后按原生会话 id 重新挂接到恢复的标签。字段都是持久化用的字符串。 */
data class SavedBinding(
    val robotId: String,
    val projectPath: String,
    val agentCli: String,
    val sessionId: String,
)

/** 全部机器人与绑定的不可变快照。修改都是纯函数，持久化层只负责读写。 */
data class RobotBook(
    val robots: List<RobotRecord> = emptyList(),
    val bindings: List<SavedBinding> = emptyList(),
) {
    fun robot(id: RobotId): RobotRecord? = robots.firstOrNull { it.id == id }

    fun add(record: RobotRecord): RobotBook = copy(robots = robots.filterNot { it.id == record.id } + record)

    fun rename(
        id: RobotId,
        name: String,
    ): RobotBook = update(id) { it.copy(name = name) }

    fun remove(id: RobotId): RobotBook =
        copy(robots = robots.filterNot { it.id == id }, bindings = bindings.filterNot { it.robotId == id.value })

    fun withOwner(
        id: RobotId,
        owner: RobotOwner,
    ): RobotBook =
        update(id) {
            it.copy(ownerSenderId = owner.senderId, ownerChatId = owner.chatId, pairCode = null, pairCodeExpiresAtMillis = 0)
        }

    /** [code] 为 null 表示作废当前配对码（例如连续猜错）。 */
    fun withPairCode(
        id: RobotId,
        code: String?,
        expiresAtMillis: Long,
    ): RobotBook = update(id) { it.copy(pairCode = code, pairCodeExpiresAtMillis = expiresAtMillis) }

    fun withBindings(next: List<SavedBinding>): RobotBook =
        copy(bindings = next.filter { binding -> robots.any { it.id.value == binding.robotId } })

    private fun update(
        id: RobotId,
        transform: (RobotRecord) -> RobotRecord,
    ): RobotBook = copy(robots = robots.map { if (it.id == id) transform(it) else it })
}
