package com.github.izerui.imux.settings

import com.github.izerui.imux.model.AgentType
import com.intellij.DynamicBundle
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
import java.util.Locale

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
    private val enabledAgentsListeners = EventDispatcher.create(EnabledAgentsListener::class.java)

    class State : BaseState() {
        /** 单击即打开会话；false 表示需要双击。 */
        var openWithSingleClick: Boolean by property(false)

        /** 关闭仍在运行的会话标签前显示确认。 */
        var confirmBeforeClosingSession: Boolean by property(true)

        /**
         * 插件界面语言；使用稳定 id，避免枚举重命名破坏已有配置。
         *
         * 默认 null 表示「尚未显式选择」，此时跟随 IDE 语言，装完插件不必先去设置里改语言。
         */
        var languageId: String? by string(null)

        /** 在 Project 工具窗口的“新建”菜单中显示 AI 智能体入口。 */
        var showProjectNewAgentMenu: Boolean by property(true)

        /** Agent 开关使用显式字段持久化；枚举名不是配置文件契约。 */
        var claudeEnabled: Boolean by property(true)
        var codexEnabled: Boolean by property(true)
        var piEnabled: Boolean by property(true)
    }

    val language: PluginLanguage
        get() = state.languageId?.let(PluginLanguage::fromId) ?: detectedLanguage()

    val enabledAgentTypes: List<AgentType>
        get() =
            AgentType.entries.filter { agentType ->
                when (agentType) {
                    AgentType.CLAUDE -> state.claudeEnabled
                    AgentType.CODEX -> state.codexEnabled
                    AgentType.PI -> state.piEnabled
                }
            }

    fun setLanguage(language: PluginLanguage) {
        val changed = this.language != language
        // 即使与自动推断结果一致也要落盘：显式选择后不应再跟随 IDE 语言变化。
        state.languageId = language.id
        if (changed) languageListeners.multicaster.languageChanged()
    }

    fun setEnabledAgentTypes(agentTypes: Set<AgentType>) {
        require(agentTypes.isNotEmpty()) { "At least one agent must be enabled" }
        if (enabledAgentTypes.toSet() == agentTypes) return
        state.claudeEnabled = AgentType.CLAUDE in agentTypes
        state.codexEnabled = AgentType.CODEX in agentTypes
        state.piEnabled = AgentType.PI in agentTypes
        enabledAgentsListeners.multicaster.enabledAgentsChanged()
    }

    fun addLanguageListener(
        parentDisposable: Disposable,
        listener: () -> Unit,
    ) {
        languageListeners.addListener(LanguageListener(listener), parentDisposable)
    }

    fun addEnabledAgentsListener(
        parentDisposable: Disposable,
        listener: () -> Unit,
    ) {
        enabledAgentsListeners.addListener(EnabledAgentsListener(listener), parentDisposable)
    }

    /**
     * IDE 语言优先于 JVM 默认语言：用户在 IDE 里装了语言包时 [Locale.getDefault] 仍是系统语言。
     * [DynamicBundle.getLocale] 依赖应用服务，单元测试等无应用环境下退回系统语言。
     */
    private fun detectedLanguage(): PluginLanguage {
        val locale = runCatching { DynamicBundle.getLocale() }.getOrNull() ?: Locale.getDefault()
        return PluginLanguage.fromLocale(locale)
    }

    fun interface LanguageListener : EventListener {
        fun languageChanged()
    }

    fun interface EnabledAgentsListener : EventListener {
        fun enabledAgentsChanged()
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
