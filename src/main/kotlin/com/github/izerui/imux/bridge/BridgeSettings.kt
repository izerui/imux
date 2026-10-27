package com.github.izerui.imux.bridge

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 通讯桥接总开关。独立存储，不修改 ImuxSettings；默认关闭。
 * 设置界面在机器人管理计划中提供。
 */
@Service(Service.Level.APP)
@State(name = "ImuxBridgeSettings", storages = [Storage("imux-bridge.xml", roamingType = RoamingType.DISABLED)])
class BridgeSettings : SimplePersistentStateComponent<BridgeSettings.State>(State()) {
    class State : BaseState() {
        var enabled: Boolean by property(false)
    }

    companion object {
        fun getInstance(): BridgeSettings = ApplicationManager.getApplication().getService(BridgeSettings::class.java)
    }
}
