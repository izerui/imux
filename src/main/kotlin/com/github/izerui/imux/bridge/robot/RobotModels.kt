package com.github.izerui.imux.bridge.robot

import com.github.izerui.imux.bridge.core.RobotOwner

/** 渠道种类。[id] 写入持久化，不能改名。 */
enum class ChannelKind(val id: String) {
    WECOM("wecom"),
    FEISHU("feishu"),
    DINGTALK("dingtalk"),
    WEIXIN("weixin"),
    ;

    companion object {
        fun of(id: String): ChannelKind? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 一个长期保存的机器人。它与会话无关：会话来来去去，机器人反复绑定。
 *
 * 扫码拿不到本人身份的渠道（企业微信、钉钉）在认主前带一个 [pairCode]，
 * 等本人把它发给机器人；认主后只接受本人的消息。
 */
data class RobotRecord(
    val id: String,
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

/** 持久化的绑定：IDE 重启后按原生会话 id 重新挂接到恢复的标签。 */
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
    fun robot(id: String): RobotRecord? = robots.firstOrNull { it.id == id }

    fun add(record: RobotRecord): RobotBook = copy(robots = robots.filterNot { it.id == record.id } + record)

    fun rename(
        id: String,
        name: String,
    ): RobotBook = update(id) { it.copy(name = name) }

    fun remove(id: String): RobotBook =
        copy(robots = robots.filterNot { it.id == id }, bindings = bindings.filterNot { it.robotId == id })

    fun withOwner(
        id: String,
        owner: RobotOwner,
    ): RobotBook =
        update(id) {
            it.copy(ownerSenderId = owner.senderId, ownerChatId = owner.chatId, pairCode = null, pairCodeExpiresAtMillis = 0)
        }

    fun withPairCode(
        id: String,
        code: String,
        expiresAtMillis: Long,
    ): RobotBook = update(id) { it.copy(pairCode = code, pairCodeExpiresAtMillis = expiresAtMillis) }

    fun withBindings(next: List<SavedBinding>): RobotBook =
        copy(bindings = next.filter { binding -> robots.any { it.id == binding.robotId } })

    private fun update(
        id: String,
        transform: (RobotRecord) -> RobotRecord,
    ): RobotBook = copy(robots = robots.map { if (it.id == id) transform(it) else it })
}
