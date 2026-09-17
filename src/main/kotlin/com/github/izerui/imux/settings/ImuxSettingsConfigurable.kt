package com.github.izerui.imux.settings

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.terminal.canConnectToIdeaMcp
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.MutableProperty
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JButton

/** Application-level imux preferences. */
class ImuxSettingsConfigurable : BoundConfigurable("Imux") {
    private val mcpCheckGeneration = AtomicInteger()
    private var mcpCheckButton: JButton? = null
    private var mcpCheckStatus: JBLabel? = null

    override fun createPanel(): DialogPanel {
        val settings = ImuxSettings.getInstance()
        val agentCheckBoxes = mutableMapOf<AgentType, JBCheckBox>()
        var portField: JBTextField? = null
        val status = JBLabel()
        mcpCheckStatus = status

        return panel {
            row(ImuxBundle.message("settings.interface.language")) {
                comboBox(PluginLanguage.entries).bindItem(
                    { settings.language },
                    { language -> language?.let { applyLanguageSelection(settings, it) } },
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
                row {
                    checkBox(ImuxBundle.message("settings.confirm.before.closing.session"))
                        .bindSelected(settings.state::confirmBeforeClosingSession)
                        .comment(ImuxBundle.message("settings.confirm.before.closing.session.comment"))
                }
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
            group(ImuxBundle.message("settings.group.idea.mcp")) {
                row {
                    checkBox(ImuxBundle.message("settings.idea.mcp.inject"))
                        .bindSelected(settings.state::injectIdeaMcp)
                        .comment(ImuxBundle.message("settings.idea.mcp.inject.comment"))
                }
                row(ImuxBundle.message("settings.idea.mcp.port")) {
                    portField =
                        intTextField(1..65535)
                        .bindIntText(
                            MutableProperty(
                                { settings.state.ideaMcpPort },
                                settings::setIdeaMcpPort,
                            ),
                        ).validationOnApply { field ->
                            val detected = readIdeaMcpPlatformPort()
                            val entered = field.text.toIntOrNull()
                            if (detected != null && entered != null && entered != detected) {
                                warning(ImuxBundle.message("settings.idea.mcp.port.mismatch", detected))
                            } else {
                                null
                            }
                        }
                        .comment(ImuxBundle.message("settings.idea.mcp.port.comment"))
                        .component
                    mcpCheckButton =
                        button(ImuxBundle.message("settings.idea.mcp.check")) {
                            checkIdeaMcpConnection(portField)
                        }.component
                    cell(status)
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

    override fun disposeUIResources() {
        mcpCheckGeneration.incrementAndGet()
        mcpCheckButton = null
        mcpCheckStatus = null
        super.disposeUIResources()
    }

    private fun checkIdeaMcpConnection(portField: JBTextField?) {
        val port = portField?.text?.toIntOrNull()
        if (port == null || port !in 1..65535) {
            mcpCheckStatus?.text = ImuxBundle.message("settings.idea.mcp.check.invalid")
            return
        }

        val token = mcpCheckGeneration.incrementAndGet()
        mcpCheckButton?.isEnabled = false
        mcpCheckStatus?.text = ImuxBundle.message("settings.idea.mcp.checking")
        ApplicationManager.getApplication().executeOnPooledThread {
            val connected = canConnectToIdeaMcp(port)
            ApplicationManager.getApplication().invokeLater(
                {
                    if (token != mcpCheckGeneration.get()) return@invokeLater
                    mcpCheckButton?.isEnabled = true
                    mcpCheckStatus?.text =
                        ImuxBundle.message(
                            if (connected) {
                                "settings.idea.mcp.check.connected"
                            } else {
                                "settings.idea.mcp.check.unavailable"
                            },
                        )
                },
                ModalityState.any(),
            )
        }
    }
}

/** UI DSL 会在每次 Apply 时调用 setter；未改语言时不能把自动检测结果写成显式偏好。 */
internal fun applyLanguageSelection(
    settings: ImuxSettings,
    language: PluginLanguage,
) {
    if (settings.state.languageId == null && settings.language == language) return
    settings.setLanguage(language)
}

private fun selectedAgentTypes(agentCheckBoxes: Map<AgentType, JBCheckBox>): Set<AgentType> =
    agentCheckBoxes.filterValues(JBCheckBox::isSelected).keys
