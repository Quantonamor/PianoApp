package de.pianotiles.app

import android.app.Application
import de.pianotiles.app.audio.Synth
import de.pianotiles.app.data.SecretStore
import de.pianotiles.app.data.SettingsStore
import de.pianotiles.app.data.SongRepository
import de.pianotiles.app.i18n.I18n
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Simple manual dependency container. */
class PianoTilesApp : Application() {
    lateinit var songs: SongRepository
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var secrets: SecretStore
        private set
    val synth: Synth by lazy { Synth() }
    /** Outlives screens; used for saving when a screen closes. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        songs = SongRepository(this)
        settings = SettingsStore(this)
        applyLanguage()
        secrets = SecretStore(this)
        // the piano recordings (~6 MB) are read in the background; until then the synthesizer stands in
        Thread({ runCatching { synth.loadSamples(assets) } }, "PianoSamples").start()
    }

    /** Applies the language from the settings to the UI texts ([I18n]) and the core module. */
    fun applyLanguage() = I18n.apply(settings.settings.value.language)
}
