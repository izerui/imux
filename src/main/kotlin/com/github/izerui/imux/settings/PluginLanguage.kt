package com.github.izerui.imux.settings

import java.util.Locale

/** Language used by imux UI, independent of the host IDE language. */
enum class PluginLanguage(
    val id: String,
    val locale: Locale,
    private val nativeName: String,
) {
    ENGLISH("en", Locale.ENGLISH, "English"),
    SIMPLIFIED_CHINESE("zh-CN", Locale.SIMPLIFIED_CHINESE, "简体中文"),
    ;

    override fun toString(): String = nativeName

    companion object {
        fun fromId(id: String?): PluginLanguage = entries.firstOrNull { it.id == id } ?: ENGLISH
    }
}
