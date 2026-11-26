package com.github.izerui.imux.settings

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.terminal.canConnectToIdeaMcp
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.MutableProperty
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JButton
import javax.swing.JCheckBox

/** IDEA MCP connection and agent-guidance settings. */
class ImuxIdeaMcpConfigurable : BoundConfigurable("IDEA MCP") {
    private val checkGeneration = AtomicInteger()
    private var checkButton: JButton? = null
    private var checkStatus: JBLabel? = null

    override fun createPanel(): DialogPanel {
        val settings = ImuxSettings.getInstance()
        lateinit var injectToggle: Cell<JCheckBox>
        var portField: JBTextField? = null
        val status = JBLabel()
        checkStatus = status

        return panel {
            row {
                comment(ImuxBundle.message("settings.idea.mcp.scope.note"), maxLineLength = MAX_LINE_LENGTH_WORD_WRAP)
            }
            group(ImuxBundle.message("settings.idea.mcp.connection.group")) {
                row {
                    injectToggle =
                        checkBox(ImuxBundle.message("settings.idea.mcp.inject"))
                            .bindSelected(settings.state::injectIdeaMcp)
                            .comment(ImuxBundle.message("settings.idea.mcp.inject.comment"), maxLineLength = MAX_LINE_LENGTH_WORD_WRAP)
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
                            }.comment(ImuxBundle.message("settings.idea.mcp.port.comment"), maxLineLength = MAX_LINE_LENGTH_WORD_WRAP)
                            .component
                    checkButton =
                        button(ImuxBundle.message("settings.idea.mcp.check")) {
                            checkConnection(portField)
                        }.component
                    cell(status)
                }.enabledIf(injectToggle.selected)
            }
            // 使用引导没有单独开关：哪组工具开着，就自动带上哪部分引导（见 agentGuidance）。
            group(ImuxBundle.message("settings.code.tools.group")) {
                lateinit var codeToolsToggle: Cell<JCheckBox>
                row {
                    codeToolsToggle =
                        checkBox(ImuxBundle.message("settings.code.tools.inject"))
                            .bindSelected(settings.state::injectCodeTools)
                            .comment(ImuxBundle.message("settings.code.tools.inject.comment"), maxLineLength = MAX_LINE_LENGTH_WORD_WRAP)
                }
                // 结束前自动检查用查代码工具的凭证找到项目，再经 IDEA MCP 检查：查代码工具关着时无从谈起。
                indent {
                    row {
                        checkBox(ImuxBundle.message("settings.code.tools.edit.check"))
                            .bindSelected(settings.state::autoCheckAfterEdit)
                            .comment(ImuxBundle.message("settings.code.tools.edit.check.comment"), maxLineLength = MAX_LINE_LENGTH_WORD_WRAP)
                    }.enabledIf(codeToolsToggle.selected)
                }
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
