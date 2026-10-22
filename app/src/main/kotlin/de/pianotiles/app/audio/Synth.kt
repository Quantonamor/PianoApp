package de.pianotiles.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import de.pianotiles.core.audio.PianoMixer
import de.pianotiles.core.audio.PianoTones
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * Piano sound for playback, accompaniment, the on-screen keys and the metronome.
 *
 * The sound itself comes from the core ([PianoTones]: real piano recordings, resampled per key; [PianoMixer]:
 * scheduled notes, voices, room reverb, compressor – tested offline in `PianoSoundTest`). This class only loads
 * the recordings from the assets and feeds the mixed blocks to an [AudioTrack].
 * [noteOn]/[click]/[prepare]/[loadSamples] are thread-safe.
 */
class Synth(private val sampleRate: Int = 44100) {

    private val tones = PianoTones(sampleRate)
    private val mixer = PianoMixer(sampleRate, tones)
    @Volatile private var running = false
    private var thread: Thread? = null

    var volume: Float
        get() = mixer.volume
        set(value) { mixer.volume = value }

    fun start() {
        if (running) return
        running = true
        thread = Thread({
            // Never crash the app because of audio output problems (e.g. no audio device).
            try { loop() } catch (e: Exception) { android.util.Log.w("Synth", "audio output failed", e); running = false }
        }, "PianoSynth").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    fun stop() {
        running = false
        thread?.join(300)
        thread = null
    }

    /** Loads the piano recordings from the app assets (call once, off the main thread). */
    fun loadSamples(assets: android.content.res.AssetManager) {
        val map = HashMap<Int, ShortArray>()
        val names = runCatching { assets.list("piano") }.getOrNull().orEmpty()
        for (name in names) {
            val midi = name.removeSuffix(".pcm").toIntOrNull() ?: continue
            runCatching {
                val bytes = assets.open("piano/$name").use { it.readBytes() }
                val sh = ShortArray(bytes.size / 2)
                java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(sh)
                map[midi] = sh
            }
        }
        tones.setRecordings(map)
    }

    /** Makes the tones of [pitches] ahead of time (call off the main thread, e.g. when a song is opened). */
    fun prepare(pitches: Collection<Int>) { for (p in pitches) tones.tone(p) }

    /**
     * Plays [pitch]. [delaySeconds] postpones the start (scheduled playback), [durationSeconds] is how long the key
     * is held before it is damped.
     */
    fun noteOn(pitch: Int, velocity: Int = 80, durationSeconds: Double = 0.5, delaySeconds: Double = 0.0) {
        start()
        mixer.noteOn(pitch, velocity, durationSeconds, delaySeconds)
    }

    fun click(accent: Boolean) {
        start()
        mixer.click(if (accent) clickAccent else clickTone, if (accent) -2 else -1)
    }

    fun allNotesOff() = mixer.allNotesOff()

    private fun loop() {
        val frames = 256
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val at = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(max(minBuf * 2, frames * 4 * 8))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        at.play()
        val out = ShortArray(frames * 2)
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        while (running) {
            mixer.mix(left, right, frames)
            for (i in 0 until frames) {
                out[2 * i] = (left[i] * 32000f).toInt().toShort()
                out[2 * i + 1] = (right[i] * 32000f).toInt().toShort()
            }
            at.write(out, 0, out.size)
        }
        at.stop()
        at.release()
    }

    private val clickTone: ShortArray by lazy { renderClick(1320.0) }
    private val clickAccent: ShortArray by lazy { renderClick(1760.0) }

    private fun renderClick(freq: Double): ShortArray {
        val n = (0.03 * sampleRate).toInt()
        return ShortArray(n) { i -> val t = i.toDouble() / sampleRate; (exp(-t * 120) * sin(2 * PI * freq * t) * 30000).toInt().toShort() }
    }
}
