package de.pianotiles.app

import android.app.Application
import de.pianotiles.app.audio.Synth
import de.pianotiles.app.data.SettingsStore
import de.pianotiles.app.data.SongRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Simple manual dependency container. */
class PianoTilesApp : Application() {
    lateinit var songs: SongRepository
        private set
    lateinit var settings: SettingsStore
        private set
    val synth: Synth by lazy { Synth() }
    /** Outlives screens; used for saving when a screen closes. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        songs = SongRepository(this)
        settings = SettingsStore(this)
    }
}
