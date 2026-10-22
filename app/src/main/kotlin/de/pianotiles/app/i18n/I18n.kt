package de.pianotiles.app.i18n

import de.pianotiles.core.i18n.CoreText
import java.util.Locale

/**
 * Very small translation layer: the German UI texts are the keys, [ENGLISH] maps them to English.
 * German stays the default on German devices; every other device language gets English.
 * The choice can be overridden in the settings ("auto" / "de" / "en"), see [apply].
 */
object I18n {
    @Volatile
    var english: Boolean = Locale.getDefault().language != "de"

    /** Applies the language setting ("auto", "de" or "en") to the app and to the core module. */
    fun apply(language: String) {
        english = isEnglish(language)
        CoreText.english = english
    }

    /** Whether the setting [language] ("auto", "de" or "en") means an English UI. */
    fun isEnglish(language: String): Boolean = when (language) {
        "de" -> false
        "en" -> true
        else -> Locale.getDefault().language != "de"
    }

    /** Language name for the AI's explanations ("German" or "English"). */
    val aiLanguage: String get() = if (english) "English" else "German"

    internal val locale: Locale get() = if (english) Locale.ENGLISH else Locale.GERMAN
}

/** The UI text [de] in the current language (falls back to German if no translation exists). */
fun tr(de: String): String = if (I18n.english) ENGLISH[de] ?: de else de

/** Like [tr] for templates with `String.format` placeholders, e.g. `tr("Takt %s / %s", m + 1, n)`. */
fun tr(de: String, vararg args: Any?): String = String.format(I18n.locale, tr(de), *args)
