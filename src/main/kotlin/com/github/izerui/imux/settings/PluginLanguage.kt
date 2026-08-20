package com.github.izerui.imux.settings

import java.util.Locale

/**
 * Language used by imux UI, independent of the host IDE language.
 *
 * [id] 是配置文件契约，枚举改名不能影响已保存的偏好；[locale] 必须与
 * `messages/ImuxBundle_*.properties` 的后缀严格对应。
 */
enum class PluginLanguage(
    val id: String,
    val locale: Locale,
    private val nativeName: String,
) {
    /** 英文落在无后缀的基础包；平台的 ResourceBundle control 不做默认语言兜底，日语系统上也不会误取到别的译文。 */
    ENGLISH("en", Locale.ENGLISH, "English"),
    SIMPLIFIED_CHINESE("zh-CN", Locale.SIMPLIFIED_CHINESE, "简体中文"),
    TRADITIONAL_CHINESE("zh-TW", Locale.TRADITIONAL_CHINESE, "繁體中文"),
    JAPANESE("ja", Locale.JAPANESE, "日本語"),
    KOREAN("ko", Locale.KOREAN, "한국어"),
    GERMAN("de", Locale.GERMAN, "Deutsch"),
    FRENCH("fr", Locale.FRENCH, "Français"),
    SPANISH("es", Locale.forLanguageTag("es"), "Español"),
    PORTUGUESE_BRAZIL("pt-BR", Locale.forLanguageTag("pt-BR"), "Português (Brasil)"),
    RUSSIAN("ru", Locale.forLanguageTag("ru"), "Русский"),
    ;

    override fun toString(): String = nativeName

    companion object {
        fun fromId(id: String?): PluginLanguage = entries.firstOrNull { it.id == id } ?: ENGLISH

        /**
         * 首次使用时跟随 IDE / 系统语言：中文按字形（简体 / 繁体）判断，其余语言先按
         * 「语言+地区」精确匹配，再退回同语言的任意变体，最后落到英文。
         */
        fun fromLocale(locale: Locale?): PluginLanguage {
            if (locale == null) return ENGLISH
            val language = locale.language.lowercase(Locale.ROOT)
            if (language.isEmpty()) return ENGLISH
            val country = locale.country.uppercase(Locale.ROOT)
            // zh 的地区差异会改变字形，zh-HK/zh-MO 也必须走繁体，不能按「同语言任取其一」处理。
            if (language == "zh") {
                val traditional = country in TRADITIONAL_CHINESE_REGIONS || locale.script.equals("Hant", ignoreCase = true)
                return if (traditional) TRADITIONAL_CHINESE else SIMPLIFIED_CHINESE
            }
            entries
                .firstOrNull {
                    it.locale.language.equals(language, ignoreCase = true) &&
                        it.locale.country.equals(country, ignoreCase = true)
                }?.let { return it }
            return entries.firstOrNull { it.locale.language.equals(language, ignoreCase = true) } ?: ENGLISH
        }

        private val TRADITIONAL_CHINESE_REGIONS = setOf("TW", "HK", "MO")
    }
}
