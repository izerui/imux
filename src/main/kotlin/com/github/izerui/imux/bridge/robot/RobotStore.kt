package com.github.izerui.imux.bridge.robot

import com.github.izerui.imux.bridge.channel.ChannelKind
import com.github.izerui.imux.bridge.core.RobotId
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 机器人与绑定的应用级持久化。与开关共用 imux-bridge.xml，但是独立的组件名。
 * 凭据不在这里，见 [CredentialVault]。
 */
@Service(Service.Level.APP)
@State(name = "ImuxBridgeRobots", storages = [Storage("imux-bridge.xml", roamingType = RoamingType.DISABLED)])
class RobotStore : SerializablePersistentStateComponent<RobotStore.State>(State()) {
    data class State(
        @JvmField val robots: List<RobotXml> = emptyList(),
        @JvmField val bindings: List<BindingXml> = emptyList(),
    )

    // XMLB 通过写字段还原集合元素；业务代码只通过 RobotBook 使用这些数据。
    data class RobotXml(
        @JvmField var id: String = "",
        @JvmField var name: String = "",
        @JvmField var kind: String = "",
        @JvmField var ownerSenderId: String? = null,
        @JvmField var ownerChatId: String? = null,
        @JvmField var pairCode: String? = null,
        @JvmField var pairCodeExpiresAtMillis: Long = 0,
        @JvmField var createdAtMillis: Long = 0,
    )

    data class BindingXml(
        @JvmField var robotId: String = "",
        @JvmField var projectPath: String = "",
        @JvmField var agentCli: String = "",
        @JvmField var sessionId: String = "",
    )

    fun book(): RobotBook = toBook(state)

    fun update(transform: (RobotBook) -> RobotBook): RobotBook {
        var result = RobotBook()
        updateState { current ->
            result = transform(toBook(current))
            fromBook(result)
        }
        return result
    }

    companion object {
        fun getInstance(): RobotStore = ApplicationManager.getApplication().getService(RobotStore::class.java)
    }
}

internal fun toBook(state: RobotStore.State): RobotBook {
    val robots =
        state.robots.mapNotNull { xml ->
            val kind = ChannelKind.of(xml.kind) ?: return@mapNotNull null
            if (xml.id.isBlank()) return@mapNotNull null
            RobotRecord(
                id = RobotId(xml.id),
                name = xml.name,
                kind = kind,
                ownerSenderId = xml.ownerSenderId,
                ownerChatId = xml.ownerChatId,
                pairCode = xml.pairCode,
                pairCodeExpiresAtMillis = xml.pairCodeExpiresAtMillis,
                createdAtMillis = xml.createdAtMillis,
            )
        }
    val bindings =
        state.bindings
            .filter { it.robotId.isNotBlank() && it.sessionId.isNotBlank() && it.agentCli.isNotBlank() }
            .map { SavedBinding(it.robotId, it.projectPath, it.agentCli, it.sessionId) }
    return RobotBook(robots).withBindings(bindings)
}

internal fun fromBook(book: RobotBook): RobotStore.State =
    RobotStore.State(
        robots =
            book.robots.map {
                RobotStore.RobotXml(
                    id = it.id.value,
                    name = it.name,
                    kind = it.kind.id,
                    ownerSenderId = it.ownerSenderId,
                    ownerChatId = it.ownerChatId,
                    pairCode = it.pairCode,
                    pairCodeExpiresAtMillis = it.pairCodeExpiresAtMillis,
                    createdAtMillis = it.createdAtMillis,
                )
            },
        bindings = book.bindings.map { RobotStore.BindingXml(it.robotId, it.projectPath, it.agentCli, it.sessionId) },
    )
