package com.github.izerui.imux.settings

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.model.AgentType
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.github.izerui.imux.peer.PeerCoordinator
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.selected
import javax.swing.text.JTextComponent
import com.intellij.ui.dsl.builder.MutableProperty
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer

/** Application-level imux preferences. */
class ImuxSettingsConfigurable : BoundConfigurable("Imux") {
    override fun disposeUIResources() {
        ImuxSettings.getInstanceOrNull()?.clearPreviewLanguage()
        super.disposeUIResources()
    }

    override fun createPanel(): DialogPanel {
        val settings = ImuxSettings.getInstance()
        val agentCheckBoxes = mutableMapOf<AgentType, JBCheckBox>()
        var selectedLanguage = settings.language
        lateinit var promptArea: JBTextArea
        lateinit var replyArea: JBTextArea
        lateinit var replyToggle: Cell<JBCheckBox>

        return panel {
            row(ImuxBundle.message("settings.interface.language")) {
                comboBox(PluginLanguage.entries).bindItem(
                    { settings.language },
                    { language -> language?.let { applyLanguageSelection(settings, it) } },
                ).applyToComponent {
                    addActionListener {
                        val newLang = selectedItem as? PluginLanguage ?: return@addActionListener
                        promptArea.text = promptAfterLanguageSwitch(promptArea.text, newLang)
                        scrollToTop(promptArea)
                        replyArea.text = plainReplyGuidanceAfterLanguageSwitch(replyArea.text, newLang)
                        scrollToTop(replyArea)
                        selectedLanguage = newLang
                        settings.setPreviewLanguage(newLang)
                    }
                }
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
                        .comment(ImuxBundle.message("settings.confirm.before.closing.session.comment"), maxLineLength = MAX_LINE_LENGTH_WORD_WRAP)
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
            group(ImuxBundle.message("settings.group.reply.style")) {
                row {
                    replyToggle =
                        checkBox(ImuxBundle.message("settings.reply.plain.enable"))
                            .bindSelected(settings.state::plainReplyEnabled)
                            .comment(ImuxBundle.message("settings.reply.plain.comment"), maxLineLength = MAX_LINE_LENGTH_WORD_WRAP)
                }
                row(ImuxBundle.message("settings.reply.plain.prompt")) {
                    // 默认文案是一整句：不自动换行时文本框按这一句的长度撑宽，把设置页往右撑出去。
                    replyArea = textArea()
                        .applyToComponent { rows = 4; lineWrap = true; wrapStyleWord = true }
                        .align(AlignX.FILL)
                        .bindText(
                            MutableProperty(
                                { settings.state.plainReplyGuidanceOverride ?: defaultPlainReplyGuidanceForLanguage(selectedLanguage) },
                                settings::setPlainReplyGuidance,
                            ),
                        ).component
                }.resizableRow()
                    .enabledIf(replyToggle.selected)
                row {
                    button(ImuxBundle.message("settings.reply.plain.restore")) {
                        replyArea.text = defaultPlainReplyGuidanceForLanguage(selectedLanguage)
                        scrollToTop(replyArea)
                    }
                }.enabledIf(replyToggle.selected)
            }
            group(ImuxBundle.message("settings.group.peer.programming")) {
                row(ImuxBundle.message("settings.peer.max.rounds")) {
                    intTextField(1..50)
                        .bindIntText(settings.state::peerMaxRounds)
                        .comment(ImuxBundle.message("settings.peer.max.rounds.comment"), maxLineLength = MAX_LINE_LENGTH_WORD_WRAP)
                }
                row(ImuxBundle.message("settings.peer.default.partner")) {
                    comboBox(listOf<AgentType?>(null) + AgentType.entries, textListCellRenderer(ImuxBundle.message("settings.peer.default.partner.auto")) { it.displayName })
                        .bindItem(
                            { AgentType.entries.firstOrNull { it.cli == settings.state.peerDefaultPartner } },
                            { settings.state.peerDefaultPartner = it?.cli },
                        )
                        .comment(ImuxBundle.message("settings.peer.default.partner.comment"), maxLineLength = MAX_LINE_LENGTH_WORD_WRAP)
                }
                row(ImuxBundle.message("settings.peer.prompt")) {
                    promptArea = textArea()
                        .applyToComponent { rows = 8; lineWrap = true; wrapStyleWord = true }
                        .align(AlignX.FILL)
                        .bindText(
                            MutableProperty(
                                { settings.state.peerPromptOverride ?: defaultPeerPromptForLanguage(selectedLanguage) },
                                { settings.state.peerPromptOverride = it.takeUnless(::isDefaultPeerPrompt) },
                            ),
                        ).component
                }.resizableRow()
                row {
                    button(ImuxBundle.message("settings.peer.prompt.restore")) {
                        promptArea.text = defaultPeerPromptForLanguage(selectedLanguage)
                        scrollToTop(promptArea)
                    }
                }
            }
            group(ImuxBundle.message("settings.group.project.window")) {
                row {
                    checkBox(ImuxBundle.message("settings.project.new.agent.menu"))
                        .bindSelected(settings.state::showProjectNewAgentMenu)
                        .comment(ImuxBundle.message("settings.project.new.agent.menu.comment"), maxLineLength = MAX_LINE_LENGTH_WORD_WRAP)
                }
            }

            onReset {
                agentCheckBoxes.forEach { (agentType, checkBox) ->
                    checkBox.isSelected = agentType in settings.enabledAgentTypes
                }
                settings.clearPreviewLanguage()
                scrollToTop(promptArea)
                scrollToTop(replyArea)
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

/** 根据语言返回对应的默认提示词。 */
internal fun defaultPeerPromptForLanguage(language: PluginLanguage): String =
    if (language in setOf(PluginLanguage.SIMPLIFIED_CHINESE, PluginLanguage.TRADITIONAL_CHINESE)) {
        PeerCoordinator.DEFAULT_PROMPT_ZH
    } else {
        PeerCoordinator.DEFAULT_PROMPT_EN
    }

/**
 * 语言切换时决定提示词文本框是否需要跟随更新。
 * 返回新的文本框内容：如果当前内容是某个语言的默认值则切换，否则保持不变。
 */
internal fun promptAfterLanguageSwitch(currentText: String, newLanguage: PluginLanguage): String =
    if (currentText == PeerCoordinator.DEFAULT_PROMPT_ZH || currentText == PeerCoordinator.DEFAULT_PROMPT_EN) {
        defaultPeerPromptForLanguage(newLanguage)
    } else {
        currentText
    }

/** 判断提示词是否为任一语言的默认值（不需要持久化）。 */
internal fun isDefaultPeerPrompt(text: String): Boolean =
    text == PeerCoordinator.DEFAULT_PROMPT_ZH || text == PeerCoordinator.DEFAULT_PROMPT_EN

internal fun scrollToTop(component: JTextComponent) {
    component.caretPosition = 0
    component.scrollRectToVisible(java.awt.Rectangle(0, 0, 1, 1))
}
