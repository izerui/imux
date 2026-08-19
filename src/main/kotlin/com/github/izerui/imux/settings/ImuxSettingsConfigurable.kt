package com.github.izerui.imux.settings

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.model.AgentType
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.MutableProperty
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

/** Application-level imux preferences. */
class ImuxSettingsConfigurable : BoundConfigurable("Imux") {
    override fun createPanel(): DialogPanel {
        val settings = ImuxSettings.getInstance()
        val agentCheckBoxes = mutableMapOf<AgentType, JBCheckBox>()

        return panel {
            row(ImuxBundle.message("settings.interface.language")) {
                comboBox(PluginLanguage.entries).bindItem(
                    { settings.language },
                    { language -> language?.let(settings::setLanguage) },
                )
                comment(ImuxBundle.message("settings.interface.language.comment"))
            }
            group(ImuxBundle.message("settings.group.sessions")) {
                buttonsGroup(ImuxBundle.message("settings.open.session")) {
                    row {
                        radioButton(ImuxBundle.message("settings.open.session.single.click"), true)
                        radioButton(ImuxBundle.message("settings.open.session.double.click"), false)
                    }
                }.bind(
                    MutableProperty(
                        { settings.state.openWithSingleClick },
                        { settings.state.openWithSingleClick = it },
                    ),
                    Boolean::class.javaObjectType,
                )
            }
            group(ImuxBundle.message("settings.group.agents")) {
                AgentType.entries.forEach { agentType ->
                    row {
                        val cell = checkBox(agentType.displayName)
                        agentCheckBoxes[agentType] = cell.component
                        if (agentType == AgentType.CLAUDE) {
                            cell.validationOnApply {
                                if (agentCheckBoxes.values.none(JBCheckBox::isSelected)) {
                                    error(ImuxBundle.message("settings.agents.validation"))
                                } else {
                                    null
                                }
                            }
                        }
                    }
                }
                row {
                    comment(ImuxBundle.message("settings.available.agents.comment"))
                }
            }
            group(ImuxBundle.message("settings.group.project.window")) {
                row {
                    checkBox(ImuxBundle.message("settings.project.new.agent.menu"))
                        .bindSelected(settings.state::showProjectNewAgentMenu)
                        .comment(ImuxBundle.message("settings.project.new.agent.menu.comment"))
                }
            }

            onReset {
                agentCheckBoxes.forEach { (agentType, checkBox) ->
                    checkBox.isSelected = agentType in settings.enabledAgentTypes
                }
            }
            onIsModified {
                selectedAgentTypes(agentCheckBoxes) != settings.enabledAgentTypes.toSet()
            }
            onApply {
                settings.setEnabledAgentTypes(selectedAgentTypes(agentCheckBoxes))
            }
        }
    }
}

private fun selectedAgentTypes(agentCheckBoxes: Map<AgentType, JBCheckBox>): Set<AgentType> =
    agentCheckBoxes.filterValues(JBCheckBox::isSelected).keys
