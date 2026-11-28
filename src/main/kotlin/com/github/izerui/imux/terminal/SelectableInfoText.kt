package com.github.izerui.imux.terminal

import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil

/** Read-only text retains native selection and copy shortcuts without input-field chrome. */
internal class SelectableInfoText(value: String = "") : JBTextField(value) {
    init {
        isEditable = false
        isOpaque = false
        border = null
        // The IDE text-field UI adds margins even without a border.
        putClientProperty("TextFieldWithoutMargins", true)
        font = UIUtil.getLabelFont()
        foreground = UIUtil.getLabelForeground()
        toolTipText = value
    }

    override fun setText(value: String?) {
        // Repeated status notifications must not clear an unchanged text selection.
        if (text != value) super.setText(value)
    }
}
