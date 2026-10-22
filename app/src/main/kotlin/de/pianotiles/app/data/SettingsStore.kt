package de.pianotiles.app.data

import android.content.Context
import de.pianotiles.app.i18n.I18n
import de.pianotiles.core.model.NoteNaming
import de.pianotiles.core.practice.PracticeLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AppSettings(
    val noteNaming: NoteNaming = NoteNaming.GERMAN,
    /** How many seconds of music are visible above the keyboard ("zoom"). */
    val lookaheadSeconds: Float = 3.0f,
    val micSensitivity: Float = 0.5f,
    /** Compensates for audio input latency in FLOW mode (ms). */
    val latencyMs: Int = 80,
    val showNamesOnTiles: Boolean = true,
    val showNamesOnKeys: Boolean = false,
    val countIn: Boolean = true,
    val metronome: Boolean = false,
    val accompaniment: Boolean = true,
    val octaveTolerant: Boolean = true,
    val volume: Float = 0.8f,
    val showMeasureNumbers: Boolean = true,
    /** Shaded hands over the keyboard showing which finger plays which key. */
    val showHands: Boolean = false,
    /** Finger numbers on the falling notes. */
    val showFingers: Boolean = false,
    /** UI language: "auto" (device language), "de" or "en". */
    val language: String = "auto",
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings

    private fun read(): AppSettings {
        val language = prefs.getString("language", null) ?: "auto"
        // Without an explicit choice, English UIs get English note names (… B) instead of German (… H).
        val defaultNaming = if (I18n.isEnglish(language)) NoteNaming.ENGLISH else NoteNaming.GERMAN
        // "…2" keys: since 0.3 the practice screen starts tidy (hands, fingering, key names hidden), also after an update
        return AppSettings(
            noteNaming = runCatching { NoteNaming.valueOf(prefs.getString("naming", null) ?: defaultNaming.name) }.getOrDefault(defaultNaming),
            lookaheadSeconds = prefs.getFloat("lookahead", 3f),
            micSensitivity = prefs.getFloat("micSensitivity", 0.5f),
            latencyMs = prefs.getInt("latency", 80),
            showNamesOnTiles = prefs.getBoolean("namesTiles", true),
            showNamesOnKeys = prefs.getBoolean("namesKeys2", false),
            countIn = prefs.getBoolean("countIn", true),
            metronome = prefs.getBoolean("metronome", false),
            accompaniment = prefs.getBoolean("accompaniment", true),
            octaveTolerant = prefs.getBoolean("octaveTolerant", true),
            volume = prefs.getFloat("volume", 0.8f),
            showMeasureNumbers = prefs.getBoolean("measureNumbers", true),
            showHands = prefs.getBoolean("hands2", false),
            showFingers = prefs.getBoolean("fingers2", false),
            language = language,
        )
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val s = transform(_settings.value)
        prefs.edit()
            .putString("naming", s.noteNaming.name)
            .putFloat("lookahead", s.lookaheadSeconds)
            .putFloat("micSensitivity", s.micSensitivity)
            .putInt("latency", s.latencyMs)
            .putBoolean("namesTiles", s.showNamesOnTiles)
            .putBoolean("namesKeys2", s.showNamesOnKeys)
            .putBoolean("countIn", s.countIn)
            .putBoolean("metronome", s.metronome)
            .putBoolean("accompaniment", s.accompaniment)
            .putBoolean("octaveTolerant", s.octaveTolerant)
            .putFloat("volume", s.volume)
            .putBoolean("measureNumbers", s.showMeasureNumbers)
            .putBoolean("hands2", s.showHands)
            .putBoolean("fingers2", s.showFingers)
            .putString("language", s.language)
            .apply()
        _settings.value = s
    }
}

/** Practice time per day (statistics in the library header) and the one-time welcome card. */
class PracticeLogStore(context: Context) {
    private val prefs = context.getSharedPreferences("practice_log", Context.MODE_PRIVATE)
    private val _log = MutableStateFlow(PracticeLog.decode(prefs.getString("days", null)))
    val log: StateFlow<PracticeLog> = _log
    private val _welcomeSeen = MutableStateFlow(prefs.getBoolean("welcomeSeen", false))
    val welcomeSeen: StateFlow<Boolean> = _welcomeSeen

    fun add(seconds: Long) {
        if (seconds <= 0) return
        val s = _log.value.add(today(), seconds.coerceAtMost(6 * 3600).toInt())
        prefs.edit().putString("days", s.encode()).apply()
        _log.value = s
    }

    fun dismissWelcome() {
        prefs.edit().putBoolean("welcomeSeen", true).apply()
        _welcomeSeen.value = true
    }

    companion object {
        fun today(): Long = java.time.LocalDate.now().toEpochDay()
    }
}
