package com.github.izerui.imux

import com.github.izerui.imux.settings.ImuxSettings
import com.github.izerui.imux.settings.PluginLanguage
import com.intellij.AbstractBundle
import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey
import java.util.ResourceBundle

@NonNls
internal const val IMUX_BUNDLE: String = "messages.ImuxBundle"

/** Resolves messages using the plugin's own language preference instead of the IDE locale. */
internal object ImuxBundle {
    private val bundles = mutableMapOf<PluginLanguage, ResourceBundle>()

    fun message(
        @PropertyKey(resourceBundle = IMUX_BUNDLE) key: String,
        vararg params: Any,
    ): @Nls String = message(currentLanguage(), key, *params)

    fun message(
        language: PluginLanguage,
        @PropertyKey(resourceBundle = IMUX_BUNDLE) key: String,
        vararg params: Any,
    ): @Nls String = AbstractBundle.message(bundle(language), key, *params)

    private fun bundle(language: PluginLanguage): ResourceBundle =
        synchronized(bundles) {
            bundles.getOrPut(language) {
                DynamicBundle.getResourceBundle(
                    ImuxBundle::class.java.classLoader,
                    IMUX_BUNDLE,
                    language.locale,
                )
            }
        }

    internal fun currentLanguage(): PluginLanguage = ImuxSettings.getInstanceOrNull()?.language ?: PluginLanguage.ENGLISH
}
