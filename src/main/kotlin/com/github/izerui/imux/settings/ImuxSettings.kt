package com.github.izerui.imux.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.EventDispatcher
import java.util.EventListener

/**
 * 插件的全局偏好。
 *
 * 应用级而非项目级：这是操作手感，不是项目配置，换个项目还要重设一遍很别扭。
 * 关掉漫游是同样的道理——手感跟着这台机器的输入设备走，不该跟着账号跑到别的机器上。
 */
@Service(Service.Level.APP)
@State(
    name = "ImuxSettings",
    storages = [Storage("imux.xml", roamingType = RoamingType.DISABLED)],
)
class ImuxSettings : SimplePersistentStateComponent<ImuxSettings.State>(State()) {
    private val languageListeners = EventDispatcher.create(LanguageListener::class.java)

    class State : BaseState() {
        /** 单击即打开会话；false 表示需要双击。 */
        var openWithSingleClick: Boolean by property(false)

        /** 插件界面语言；使用稳定 id，避免枚举重命名破坏已有配置。 */
        var languageId: String? by string(PluginLanguage.ENGLISH.id)
    }

    val language: PluginLanguage
        get() = PluginLanguage.fromId(state.languageId)

    fun setLanguage(language: PluginLanguage) {
        if (this.language == language) return
        state.languageId = language.id
        languageListeners.multicaster.languageChanged()
    }

    fun addLanguageListener(
        parentDisposable: Disposable,
        listener: () -> Unit,
    ) {
        languageListeners.addListener(LanguageListener(listener), parentDisposable)
    }

    fun interface LanguageListener : EventListener {
        fun languageChanged()
    }

    companion object {
        fun getInstance(): ImuxSettings = service()

        fun getInstanceOrNull(): ImuxSettings? {
            val application = ApplicationManager.getApplication() ?: return null
            if (application.isDisposed) return null
            return application.getService(ImuxSettings::class.java)
        }
    }
}
