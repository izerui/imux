package com.github.izerui.imux.settings

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.model.AgentType
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComboBox
import javax.swing.JComponent

/** Application-level imux preferences. */
class ImuxSettingsConfigurable : Configurable {
    private var panel: DialogPanel? = null
    private var languageComboBox: JComboBox<PluginLanguage>? = null
    private var singleClickCheckBox: JBCheckBox? = null
    private val agentCheckBoxes = mutableMapOf<AgentType, JBCheckBox>()

    override fun getDisplayName(): String = "Imux"

    override fun createComponent(): JComponent =
        panel {
            group(ImuxBundle.message("settings.general")) {
                row(ImuxBundle.message("settings.language")) {
                    languageComboBox = comboBox(PluginLanguage.entries).component
                }
                row {
                    singleClickCheckBox = checkBox(ImuxBundle.message("settings.open.with.single.click")).component
                }
            }
            group(ImuxBundle.message("settings.agents")) {
                AgentType.entries.forEach { agentType ->
                    row {
                        agentCheckBoxes[agentType] = checkBox(agentType.displayName).component
                    }
                }
            }
        }.also { panel = it }

    override fun isModified(): Boolean {
        val settings = ImuxSettings.getInstance()
        return languageComboBox?.selectedItem != settings.language ||
            singleClickCheckBox?.isSelected != settings.state.openWithSingleClick ||
            selectedAgentTypes() != settings.enabledAgentTypes.toSet()
    }

    override fun apply() {
        val settings = ImuxSettings.getInstance()
        val enabledAgentTypes = selectedAgentTypes()
        if (enabledAgentTypes.isEmpty()) {
            throw ConfigurationException(ImuxBundle.message("settings.agents.validation"))
        }
        (languageComboBox?.selectedItem as? PluginLanguage)?.let(settings::setLanguage)
        singleClickCheckBox?.let { settings.state.openWithSingleClick = it.isSelected }
        settings.setEnabledAgentTypes(enabledAgentTypes)
    }

    override fun reset() {
        val settings = ImuxSettings.getInstance()
        languageComboBox?.selectedItem = settings.language
        singleClickCheckBox?.isSelected = settings.state.openWithSingleClick
        agentCheckBoxes.forEach { (agentType, checkBox) ->
            checkBox.isSelected = agentType in settings.enabledAgentTypes
        }
    }

    private fun selectedAgentTypes(): Set<AgentType> = agentCheckBoxes.filterValues(JBCheckBox::isSelected).keys

    override fun disposeUIResources() {
        panel = null
        languageComboBox = null
        singleClickCheckBox = null
        agentCheckBoxes.clear()
    }
}
