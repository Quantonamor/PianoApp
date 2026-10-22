package de.pianotiles.core.i18n

/**
 * Minimal language switch for the few user-visible texts produced by the core module
 * (recognition warnings, "Bitte prüfen" reasons, AI progress messages, import errors).
 * German is the default; the app sets [english] at startup according to the user's setting.
 */
object CoreText {
    @Volatile
    var english: Boolean = false

    /** Returns [en] when the UI language is English, otherwise [de]. */
    fun t(de: String, en: String): String = if (english) en else de

    /** Language name used when asking the AI for reasons/summaries ("German" or "English"). */
    val aiLanguage: String get() = if (english) "English" else "German"
}
