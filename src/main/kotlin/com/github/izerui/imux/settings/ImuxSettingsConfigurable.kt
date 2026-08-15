package com.github.izerui.imux.settings

import com.github.izerui.imux.ImuxBundle
import com.intellij.openapi.options.Configurable
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

    override fun getDisplayName(): String = "Imux"

    override fun createComponent(): JComponent =
        panel {
            row(ImuxBundle.message("settings.language")) {
                languageComboBox = comboBox(PluginLanguage.entries).component
            }
            row {
                singleClickCheckBox = checkBox(ImuxBundle.message("settings.open.with.single.click")).component
            }
        }.also { panel = it }

    override fun isModified(): Boolean {
        val settings = ImuxSettings.getInstance()
        return languageComboBox?.selectedItem != settings.language ||
            singleClickCheckBox?.isSelected != settings.state.openWithSingleClick
    }

    override fun apply() {
        val settings = ImuxSettings.getInstance()
        (languageComboBox?.selectedItem as? PluginLanguage)?.let(settings::setLanguage)
        singleClickCheckBox?.let { settings.state.openWithSingleClick = it.isSelected }
    }

    override fun reset() {
        val settings = ImuxSettings.getInstance()
        languageComboBox?.selectedItem = settings.language
        singleClickCheckBox?.isSelected = settings.state.openWithSingleClick
    }

    override fun disposeUIResources() {
        panel = null
        languageComboBox = null
        singleClickCheckBox = null
    }
}
