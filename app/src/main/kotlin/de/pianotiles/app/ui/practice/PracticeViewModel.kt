package de.pianotiles.app.ui.practice

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import de.pianotiles.app.PianoTilesApp
import de.pianotiles.app.audio.MicInput
import de.pianotiles.app.i18n.tr
import de.pianotiles.core.audio.DetectionFrame
import de.pianotiles.core.model.Section
import de.pianotiles.core.model.Song
import de.pianotiles.core.practice.EngineEvent
import de.pianotiles.core.practice.HandSelection
import de.pianotiles.core.practice.LoopSettings
import de.pianotiles.core.practice.PracticeEngine
import de.pianotiles.core.practice.PracticeMode
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

data class PassInfo(val pass: Int, val accuracy: Int, val newSpeed: Double?, val at: Long = System.currentTimeMillis())
data class FinishInfo(val accuracy: Int, val seconds: Int, val worstMeasure: Int?)

class PracticeViewModel(app: Application, songId: String) : AndroidViewModel(app) {
    private val pt = app as PianoTilesApp
    private val synth = pt.synth
    val settings get() = pt.settings.settings.value

    var song: Song by mutableStateOf(pt.songs.get(songId) ?: error("Song $songId not found"))
        private set
    val engine = PracticeEngine(song)

    /** Incremented every frame / state change so Compose redraws. */
    var frame by mutableLongStateOf(0L)
        private set
    var isPlaying by mutableStateOf(false); private set
    var mode by mutableStateOf(PracticeMode.WAIT); private set
    var hands by mutableStateOf(HandSelection.BOTH); private set
    var speed by mutableFloatStateOf(1f); private set
    var loop by mutableStateOf(LoopSettings()); private set
    var metronome by mutableStateOf(settings.metronome); private set
    var micActive by mutableStateOf(false); private set
    var micLevel by mutableFloatStateOf(0f); private set
    var heardPitches by mutableStateOf<Set<Int>>(emptySet()); private set
    var tappedPitches by mutableStateOf<Set<Int>>(emptySet()); private set
    var wrongPitch by mutableIntStateOf(-1); private set
    var hitFlash by mutableLongStateOf(0L); private set
    var lastPass by mutableStateOf<PassInfo?>(null); private set
    var finished by mutableStateOf<FinishInfo?>(null)
    var message by mutableStateOf<String?>(null)

    private val mic = MicInput(::onMicFrame)
    /** Pitches recently produced by the synthesizer – ignored by the microphone to avoid self-triggering. */
    private val synthRecent = HashMap<Int, Long>()
    private var sessionStart = System.currentTimeMillis()

    init {
        engine.countIn = settings.countIn
        engine.accompaniment = settings.accompaniment
        engine.octaveTolerant = settings.octaveTolerant
        engine.metronome = metronome
        engine.mode = mode
        engine.seek(-engine.song.measureLength * 0.5)
        synth.volume = settings.volume
        mic.sensitivity = settings.micSensitivity.toDouble()
        synth.start()
    }

    private fun bump() { frame++ }

    // ---------------------------------------------------------------- frame loop

    fun tick(dtSeconds: Double) {
        val events = engine.update(dtSeconds.coerceAtMost(0.1))
        handle(events)
        if (isPlaying != engine.isPlaying) isPlaying = engine.isPlaying
        bump()
    }

    private fun handle(events: List<EngineEvent>) {
        for (e in events) when (e) {
            is EngineEvent.PlayNote -> {
                synth.noteOn(e.pitch, e.velocity, e.durationSeconds)
                synthRecent[e.pitch] = System.currentTimeMillis() + (e.durationSeconds * 1000).toLong() + 400
            }
            is EngineEvent.Click -> synth.click(e.accent)
            is EngineEvent.PassCompleted -> {
                lastPass = PassInfo(e.pass, e.accuracy, e.newSpeed)
                e.newSpeed?.let { speed = it.toFloat() }
            }
            is EngineEvent.LoopFinished -> message = tr("Schleife fertig: %s Durchgänge", e.passes)
            is EngineEvent.SongFinished -> onFinished(e.accuracy)
            is EngineEvent.StepHit -> hitFlash = System.currentTimeMillis()
            is EngineEvent.WrongNote -> { wrongPitch = e.pitch; hitFlash = 0 }
        }
    }

    private fun onFinished(accuracy: Int) {
        val worst = engine.mistakesPerMeasure.maxByOrNull { it.value }?.key
        val seconds = engine.practiceSeconds.roundToInt()
        if (mode != PracticeMode.LISTEN) finished = FinishInfo(accuracy, seconds, worst)
        saveStats(accuracy.takeIf { mode != PracticeMode.LISTEN && (mode == PracticeMode.WAIT || micActive) })
    }

    // ---------------------------------------------------------------- input

    private fun onMicFrame(f: DetectionFrame) {
        micLevel = (f.level * 8).toFloat().coerceIn(0f, 1f)
        val now = System.currentTimeMillis()
        synthRecent.entries.removeAll { it.value < now }
        val sounding = f.sounding.filterTo(HashSet()) { it !in synthRecent }
        val onsets = f.onsets.filterTo(HashSet()) { it !in synthRecent }
        heardPitches = sounding
        if (onsets.isNotEmpty() || sounding.isNotEmpty()) handle(engine.onNotesPlayed(onsets, sounding))
        bump()
    }

    fun keyDown(pitch: Int) {
        synth.noteOn(pitch, 90, 0.6)
        tappedPitches = tappedPitches + pitch
        handle(engine.onNotesPlayed(setOf(pitch)))
        bump()
    }

    fun keyUp(pitch: Int) { tappedPitches = tappedPitches - pitch }

    // ---------------------------------------------------------------- controls

    fun togglePlay() {
        finished = null
        engine.togglePlay()
        isPlaying = engine.isPlaying
        if (!isPlaying) synth.allNotesOff()
        bump()
    }

    fun pause() { if (engine.isPlaying) togglePlay() }

    fun changeMode(m: PracticeMode) {
        mode = m
        engine.mode = m
        bump()
    }

    fun changeHands(h: HandSelection) { hands = h; engine.hands = h; bump() }

    fun changeSpeed(s: Float) {
        speed = (s * 20).roundToInt() / 20f
        engine.speed = speed.toDouble()
        bump()
    }

    fun toggleMetronome() { metronome = !metronome; engine.metronome = metronome; pt.settings.update { it.copy(metronome = metronome) } }

    /** Called by the UI after the permission check. */
    fun setMic(on: Boolean) {
        if (on) {
            mic.sensitivity = settings.micSensitivity.toDouble()
            micActive = mic.start()
            if (!micActive) message = tr("Mikrofon konnte nicht gestartet werden")
        } else {
            mic.stop(); micActive = false; heardPitches = emptySet(); micLevel = 0f
        }
    }

    fun restart() {
        finished = null
        engine.restart()
        bump()
    }

    fun seekMeasureDelta(delta: Int) {
        val m = (engine.currentMeasure + delta).coerceIn(0, song.measureCount - 1)
        engine.seek(song.measureStart(m))
        bump()
    }

    fun seekBeat(beat: Double) { engine.seek(beat); bump() }

    fun scrubBy(beats: Double) {
        if (engine.isPlaying) pause()
        engine.seek(engine.position + beats)
        bump()
    }

    // ---------------------------------------------------------------- loops

    fun setLoopMeasures(from: Int, to: Int, enable: Boolean = true) {
        val a = from.coerceIn(0, song.measureCount - 1)
        val b = to.coerceIn(a, song.measureCount - 1)
        updateLoop(loop.copy(enabled = enable, startBeat = song.measureStart(a), endBeat = song.measureEnd(b)))
    }

    fun loopCurrentMeasure() {
        val m = engine.currentMeasure
        setLoopMeasures(m, m)
        message = tr("Schleife: Takt %s", m + 1)
    }

    fun toggleLoop() {
        if (loop.endBeat <= loop.startBeat) {
            val m = engine.currentMeasure
            setLoopMeasures(m, (m + 1).coerceAtMost(song.measureCount - 1))
        } else updateLoop(loop.copy(enabled = !loop.enabled))
    }

    fun updateLoop(l: LoopSettings) {
        val wasPlaying = engine.isPlaying
        loop = l
        engine.loop = l
        if (l.tempoRamp && l.enabled) {
            // start the ramp below the target tempo
            if (speed >= l.rampTarget) changeSpeed((l.rampTarget - 0.3).toFloat().coerceAtLeast(0.3f))
        }
        if (!wasPlaying) bump()
        bump()
    }

    val loopStartMeasure get() = song.measureIndexAt(loop.startBeat)
    val loopEndMeasure get() = max(loopStartMeasure, song.measureIndexAt(loop.endBeat - 1e-6))

    fun saveLoopAsSection(name: String) {
        if (loop.endBeat <= loop.startBeat) return
        val s = Section(name.ifBlank { tr("Abschnitt %s", song.sections.size + 1) }, loop.startBeat, loop.endBeat)
        persist(song.copy(sections = song.sections + s))
        message = tr("Abschnitt „%s“ gespeichert", s.name)
    }

    fun deleteSection(s: Section) = persist(song.copy(sections = song.sections - s))

    fun selectSection(s: Section) {
        updateLoop(loop.copy(enabled = true, startBeat = s.startBeat, endBeat = s.endBeat))
    }

    /** Measures with the most mistakes (from this session and earlier ones). */
    fun difficultMeasures(): List<Int> {
        val all = HashMap(song.stats.mistakesPerMeasure)
        engine.mistakesPerMeasure.forEach { (m, c) -> all[m] = (all[m] ?: 0) + c }
        return all.entries.sortedByDescending { it.value }.filter { it.value > 0 }.take(3).map { it.key }
    }

    private fun persist(s: Song) {
        song = s
        pt.appScope.launch { pt.songs.save(s) }
    }

    private fun saveStats(accuracy: Int?) {
        val merged = HashMap(song.stats.mistakesPerMeasure)
        engine.mistakesPerMeasure.forEach { (m, c) -> merged[m] = (merged[m] ?: 0) + c }
        val elapsed = (System.currentTimeMillis() - sessionStart) / 1000
        sessionStart = System.currentTimeMillis()
        persist(song.copy(stats = song.stats.copy(
            bestAccuracy = max(song.stats.bestAccuracy, accuracy ?: 0),
            practiceSeconds = song.stats.practiceSeconds + elapsed,
            lastPracticedAt = System.currentTimeMillis(),
            mistakesPerMeasure = merged,
        )))
        engine.mistakesPerMeasure.clear()
    }

    override fun onCleared() {
        mic.stop()
        synth.allNotesOff()
        saveStats(null)
        super.onCleared()
    }
}
