package com.github.izerui.imux.settings

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.terminal.canConnectToIdeaMcp
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.MutableProperty
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import java.awt.Point
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JViewport
import javax.swing.SwingUtilities

/** IDEA MCP connection and agent-guidance settings. */
class ImuxIdeaMcpConfigurable : BoundConfigurable("IDEA MCP") {
    private val checkGeneration = AtomicInteger()
    private var checkButton: JButton? = null
    private var checkStatus: JBLabel? = null

    override fun createPanel(): DialogPanel {
        val settings = ImuxSettings.getInstance()
        lateinit var injectToggle: Cell<JCheckBox>
        lateinit var guidanceToggle: Cell<JCheckBox>
        var portField: JBTextField? = null
        var guidanceArea: JBTextArea? = null
        val status = JBLabel()
        checkStatus = status

        return panel {
            row {
                comment(ImuxBundle.message("settings.idea.mcp.scope.note"))
            }
            group(ImuxBundle.message("settings.idea.mcp.connection.group")) {
                row {
                    injectToggle =
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
                            }.comment(ImuxBundle.message("settings.idea.mcp.port.comment"))
                            .component
                    checkButton =
                        button(ImuxBundle.message("settings.idea.mcp.check")) {
                            checkConnection(portField)
                        }.component
                    cell(status)
                }.enabledIf(injectToggle.selected)
            }
            group(ImuxBundle.message("settings.idea.mcp.guidance.group")) {
                row {
                    guidanceToggle =
                        checkBox(ImuxBundle.message("settings.idea.mcp.guidance.enable"))
                            .bindSelected(settings.state::ideaMcpGuidanceEnabled)
                            .comment(ImuxBundle.message("settings.idea.mcp.guidance.comment"))
                }
                row(ImuxBundle.message("settings.idea.mcp.guidance.prompt")) {
                    guidanceArea =
                        textArea()
                            .applyToComponent { rows = 12 }
                            .align(AlignX.FILL)
                            .bindText(
                                MutableProperty(
                                    { settings.ideaMcpGuidance },
                                    settings::setIdeaMcpGuidance,
                                ),
                            ).component
                }.resizableRow()
                    .enabledIf(guidanceToggle.selected)
                row {
                    button(ImuxBundle.message("settings.idea.mcp.guidance.restore")) {
                        guidanceArea?.text = DEFAULT_IDEA_MCP_GUIDANCE
                        resetGuidanceView(guidanceArea)
                    }
                }.enabledIf(guidanceToggle.selected)
            }
            onReset {
                resetGuidanceView(guidanceArea)
            }
        }
    }

    override fun disposeUIResources() {
        checkGeneration.incrementAndGet()
        checkButton = null
        checkStatus = null
        super.disposeUIResources()
    }

    private fun checkConnection(portField: JBTextField?) {
        val port = portField?.text?.toIntOrNull()
        if (port == null || port !in 1..65535) {
            checkStatus?.text = ImuxBundle.message("settings.idea.mcp.check.invalid")
            return
        }

        val token = checkGeneration.incrementAndGet()
        checkButton?.isEnabled = false
        checkStatus?.text = ImuxBundle.message("settings.idea.mcp.checking")
        ApplicationManager.getApplication().executeOnPooledThread {
            val connected = canConnectToIdeaMcp(port)
            ApplicationManager.getApplication().invokeLater(
                {
                    if (token != checkGeneration.get()) return@invokeLater
                    checkButton?.isEnabled = true
                    checkStatus?.text =
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

private fun resetGuidanceView(area: JBTextArea?) {
    if (area == null) return
    area.caretPosition = 0
    (SwingUtilities.getAncestorOfClass(JViewport::class.java, area) as? JViewport)
        ?.viewPosition = Point(0, 0)
}
