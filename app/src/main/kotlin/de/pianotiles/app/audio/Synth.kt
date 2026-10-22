package de.pianotiles.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import de.pianotiles.core.model.Pitch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Piano sound for playback, accompaniment, the on-screen keys and the metronome.
 *
 * Real piano recordings (every third key, see `tools/samples/make_piano.py`) are loaded once; the tone of each
 * pitch is made from the nearest recording by resampling and cached, so the audio thread only mixes samples:
 * chords start together and up to [MAX_VOICES] notes sound at once. Soft notes are darker (low-pass by velocity),
 * low notes sit a little left and high notes a little right (stereo), a released key is damped smoothly, and when
 * voices run out the quietest one fades out instead of being cut. Notes can be given a start delay, so playback
 * scheduled slightly ahead sounds exactly in time instead of jittering with the screen frames. Until the
 * recordings are loaded (or if they are missing) an additive synthesizer stands in.
 * [noteOn]/[click]/[prepare]/[loadSamples] are thread-safe.
 */
class Synth(private val sampleRate: Int = 44100) {

    private class Voice(val tone: ShortArray, val amp: Float, val stopAt: Int, val pitch: Int, delay: Int, val bright: Float, val panL: Float, val panR: Float) {
        var pos = -delay // negative: still waiting for its start
        var gain = 1f
        var release = 1f // per-sample multiplier once damped
        var lp = 0f // low-pass state (velocity colour)
        val damped get() = release < 1f
    }

    private class Command(val pitch: Int, val tone: ShortArray, val amp: Float, val samples: Int, val delay: Int, val bright: Float)

    private val pending = ConcurrentLinkedQueue<Command>()
    private val tones = ConcurrentHashMap<Int, ShortArray>()
    private val voices = ArrayList<Voice>()
    /** Recorded piano notes: MIDI pitch → samples at [SAMPLE_RATE]. */
    @Volatile private var recordings: Map<Int, ShortArray> = emptyMap()
    @Volatile var volume = 0.8f
    @Volatile private var running = false
    @Volatile private var silence = false
    private var thread: Thread? = null

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
        if (map.isNotEmpty()) {
            val wanted = tones.keys.toList()
            recordings = map
            tones.clear()
            for (p in wanted) tone(p) // pitches already asked for get their recorded sound right away
        }
    }

    /** Makes the tones of [pitches] ahead of time (call off the main thread, e.g. when a song is opened). */
    fun prepare(pitches: Collection<Int>) { for (p in pitches) tone(p) }

    /**
     * Plays [pitch]. [delaySeconds] postpones the start (scheduled playback), [durationSeconds] is how long the key
     * is held before it is damped.
     */
    fun noteOn(pitch: Int, velocity: Int = 80, durationSeconds: Double = 0.5, delaySeconds: Double = 0.0) {
        start()
        val v = velocity.coerceIn(1, 127) / 127f
        pending.add(
            Command(
                pitch, tone(pitch), 0.12f + 0.30f * v * v, (durationSeconds.coerceIn(0.08, 30.0) * sampleRate).toInt(),
                (delaySeconds.coerceIn(0.0, 30.0) * sampleRate).toInt(), 0.18f + 0.82f * v,
            ),
        )
    }

    fun click(accent: Boolean) {
        start()
        pending.add(Command(if (accent) CLICK_ACCENT else CLICK, if (accent) clickAccent else clickTone, 0.45f, Int.MAX_VALUE, 0, 1f))
    }

    fun allNotesOff() { pending.clear(); silence = true }

    private fun tone(pitch: Int): ShortArray = tones.getOrPut(pitch) { fromRecording(pitch) ?: toShorts(renderTone(pitch)) }

    /** The nearest recording, resampled to [pitch] at the output rate (linear interpolation). */
    private fun fromRecording(pitch: Int): ShortArray? {
        val rec = recordings
        if (rec.isEmpty()) return null
        val base = rec.keys.minBy { kotlin.math.abs(it - pitch) }
        val src = rec.getValue(base)
        val step = 2.0.pow((pitch - base) / 12.0) * SAMPLE_RATE / sampleRate // source samples per output sample
        val n = ((src.size - 1) / step).toInt().coerceAtLeast(1)
        val out = ShortArray(n)
        var x = 0.0
        for (i in 0 until n) {
            val j = x.toInt()
            val f = x - j
            val a = src[j]; val b = src[min(j + 1, src.size - 1)]
            out[i] = (a + (b - a) * f).toInt().toShort()
            x += step
        }
        return out
    }

    private fun toShorts(f: FloatArray) = ShortArray(f.size) { (f[it] * 30000f).toInt().toShort() }

    // ------------------------------------------------------------------------------------------ audio thread

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
        val mixL = FloatArray(frames)
        val mixR = FloatArray(frames)
        val damp = exp(-1.0 / (0.12 * sampleRate)).toFloat()   // key released: damper ~0.12 s
        val steal = exp(-1.0 / (0.006 * sampleRate)).toFloat()  // voice taken for a new note: ~6 ms
        val restrike = exp(-1.0 / (0.03 * sampleRate)).toFloat() // same key struck again
        val inv = 1f / 32768f
        while (running) {
            if (silence) { silence = false; voices.removeAll { it.pos < 0 }; for (v in voices) v.release = min(v.release, steal) }
            while (true) {
                val c = pending.poll() ?: break
                // the same key struck again: the old sound stops quickly (only voices already sounding)
                for (v in voices) if (v.pitch == c.pitch && !v.damped && v.pos >= 0) v.release = restrike
                if (voices.count { it.gain > 0.01f } >= MAX_VOICES) {
                    voices.filter { it.gain > 0.01f }.minByOrNull { it.gain * it.amp * (if (it.damped) 0.2f else 1f) }?.release = steal
                }
                val pan = if (c.pitch < 0) 0.5f else (0.3f + 0.4f * ((c.pitch - 21) / 87f).coerceIn(0f, 1f))
                voices.add(Voice(c.tone, c.amp, c.samples, c.pitch, c.delay, c.bright,
                    kotlin.math.cos(pan * PI / 2).toFloat() * 1.41f, kotlin.math.sin(pan * PI / 2).toFloat() * 1.41f))
            }
            mixL.fill(0f); mixR.fill(0f)
            val it = voices.iterator()
            while (it.hasNext()) {
                val v = it.next()
                val tone = v.tone
                var pos = v.pos
                var gain = v.gain
                var lp = v.lp
                val a = v.amp * inv
                val k = v.bright
                for (i in 0 until frames) {
                    if (pos < 0) { pos++; continue }
                    if (pos >= tone.size) break
                    if (pos >= v.stopAt && v.release == 1f) v.release = damp
                    lp += (tone[pos] - lp) * k
                    val s = lp * a * gain
                    mixL[i] += s * v.panL; mixR[i] += s * v.panR
                    gain *= v.release
                    pos++
                }
                v.pos = pos; v.gain = gain; v.lp = lp
                if (pos >= tone.size || gain < 0.0005f) it.remove()
            }
            val vol = volume
            for (i in 0 until frames) {
                out[2 * i] = limit(mixL[i] * vol)
                out[2 * i + 1] = limit(mixR[i] * vol)
            }
            at.write(out, 0, out.size)
        }
        at.stop()
        at.release()
    }

    /** Gentle limiter: linear for normal levels, soft above. */
    private fun limit(x: Float): Short {
        val y = if (x > 0.7f) 0.7f + (x - 0.7f) / (1f + (x - 0.7f) * 3.3f) else if (x < -0.7f) -0.7f + (x + 0.7f) / (1f - (x + 0.7f) * 3.3f) else x
        return (y.coerceIn(-1f, 1f) * 32000f).toInt().toShort()
    }

    // ------------------------------------------------------------------------------------------ tone rendering

    /** One piano-like tone for [pitch], peak-normalised. Low notes ring long, high notes short. */
    private fun renderTone(pitch: Int): FloatArray {
        val sr = sampleRate.toDouble()
        val f0 = Pitch.frequency(pitch)
        val k = ((pitch - 21) / 87.0).coerceIn(0.0, 1.0) // 0 = lowest key, 1 = highest
        val seconds = 3.6 - 2.6 * k
        val n = (seconds * sr).toInt()
        val buf = FloatArray(n)
        val inharm = 0.00008 + 0.0006 * k * k // string stiffness
        val slowTau = 3.2 - 2.5 * k
        val fastTau = 0.35 - 0.25 * k
        val partials = if (pitch < 48) 14 else if (pitch < 72) 10 else 6
        val detune = 1.0 + 0.0009 // second string, slow beating
        for (p in 1..partials) {
            val f = f0 * p * sqrt(1 + inharm * p * p)
            if (f * detune >= sr * 0.45) break
            // hammer striking near the string end: weaker upper partials, softer in the bass
            val amp = (1.0 / p.toDouble().pow(1.1 + 0.6 * k)) * (if (p == 1 && pitch < 40) 0.7 else 1.0)
            val tf = fastTau / (1 + 0.5 * (p - 1))
            val ts = slowTau / (1 + 0.35 * (p - 1))
            // recursive oscillators (no sin() per sample)
            val w1 = 2 * PI * f / sr
            val w2 = w1 * detune
            val c1 = 2 * kotlin.math.cos(w1); val c2 = 2 * kotlin.math.cos(w2)
            var a1 = 0.0; var b1 = sin(-w1); var a2 = 0.0; var b2 = sin(-w2)
            val df = exp(-1.0 / (tf * sr)); val ds = exp(-1.0 / (ts * sr))
            var ef = 0.55; var es = 0.45
            for (i in 0 until n) {
                val s1 = c1 * a1 - b1; b1 = a1; a1 = s1
                val s2 = c2 * a2 - b2; b2 = a2; a2 = s2
                buf[i] += (amp * (ef + es) * (s1 + 0.8 * s2)).toFloat()
                ef *= df; es *= ds
                if (ef + es < 1e-4) break
            }
        }
        // hammer knock: a few milliseconds of filtered noise
        val rnd = java.util.Random(pitch.toLong())
        val knock = (0.004 * sr).toInt()
        var lp = 0.0
        for (i in 0 until min(knock, n)) {
            lp += (rnd.nextGaussian() - lp) * (0.15 + 0.5 * k)
            buf[i] += (lp * 0.25 * (1 - i.toDouble() / knock)).toFloat()
        }
        // attack ramp (2 ms) and fade at the end of the buffer
        val ramp = (0.002 * sr).toInt()
        for (i in 0 until min(ramp, n)) buf[i] *= i.toFloat() / ramp
        val fade = min(n, (0.05 * sr).toInt())
        for (i in 0 until fade) buf[n - 1 - i] *= i.toFloat() / fade
        var peak = 1e-9f
        for (x in buf) peak = max(peak, kotlin.math.abs(x))
        for (i in buf.indices) buf[i] /= peak
        return buf
    }

    private val clickTone: ShortArray by lazy { renderClick(1320.0) }
    private val clickAccent: ShortArray by lazy { renderClick(1760.0) }

    private fun renderClick(freq: Double): ShortArray {
        val n = (0.03 * sampleRate).toInt()
        return ShortArray(n) { i -> val t = i.toDouble() / sampleRate; (exp(-t * 120) * sin(2 * PI * freq * t) * 30000).toInt().toShort() }
    }

    private companion object {
        const val MAX_VOICES = 64
        /** Sample rate of the recordings in assets/piano. */
        const val SAMPLE_RATE = 32000
        const val CLICK = -1
        const val CLICK_ACCENT = -2
    }
}
