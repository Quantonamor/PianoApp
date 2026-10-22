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
 * Piano-like synthesizer for playback, accompaniment, the on-screen keys and the metronome.
 *
 * Every pitch is rendered once into a sample buffer (inharmonic partials with their own two-stage decay, a pair of
 * slightly detuned strings, a short hammer knock); playing a note then only copies samples. That keeps the audio
 * thread cheap, so chords start together and up to [MAX_VOICES] notes sound at once without drop-outs. A note that
 * ends is damped smoothly; when voices run out the quietest one fades out instead of being cut.
 * [noteOn]/[click]/[prepare] are thread-safe.
 */
class Synth(private val sampleRate: Int = 44100) {

    private class Voice(val tone: FloatArray, val amp: Float, val stopAt: Int, val pitch: Int) {
        var pos = 0
        var gain = 1f
        var release = 1f // per-sample multiplier once damped
        val damped get() = release < 1f
    }

    private class Command(val pitch: Int, val tone: FloatArray, val amp: Float, val samples: Int)

    private val pending = ConcurrentLinkedQueue<Command>()
    private val tones = ConcurrentHashMap<Int, FloatArray>()
    private val voices = ArrayList<Voice>()
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

    /** Renders the tones of [pitches] ahead of time (call off the main thread, e.g. when a song is opened). */
    fun prepare(pitches: Collection<Int>) { for (p in pitches) tone(p) }

    fun noteOn(pitch: Int, velocity: Int = 80, durationSeconds: Double = 0.5) {
        start()
        val v = velocity.coerceIn(1, 127) / 127f
        pending.add(Command(pitch, tone(pitch), 0.08f + 0.22f * v * v, (durationSeconds.coerceIn(0.08, 12.0) * sampleRate).toInt()))
    }

    fun click(accent: Boolean) {
        start()
        pending.add(Command(if (accent) CLICK_ACCENT else CLICK, if (accent) clickAccent else clickTone, 0.5f, Int.MAX_VALUE))
    }

    fun allNotesOff() { pending.clear(); silence = true }

    private fun tone(pitch: Int): FloatArray = tones.getOrPut(pitch) { renderTone(pitch) }

    // ------------------------------------------------------------------------------------------ audio thread

    private fun loop() {
        val frames = 256
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val at = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(max(minBuf * 2, frames * 2 * 8))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        at.play()
        val out = ShortArray(frames)
        val mix = FloatArray(frames)
        val damp = exp(-1.0 / (0.16 * sampleRate)).toFloat()   // key released: ~0.16 s
        val steal = exp(-1.0 / (0.006 * sampleRate)).toFloat()  // voice taken for a new note: ~6 ms
        val restrike = exp(-1.0 / (0.03 * sampleRate)).toFloat() // same key struck again
        while (running) {
            if (silence) { silence = false; for (v in voices) v.release = min(v.release, steal) }
            while (true) {
                val c = pending.poll() ?: break
                // the same key struck again: the old sound stops quickly
                for (v in voices) if (v.pitch == c.pitch && !v.damped) v.release = restrike
                if (voices.count { it.gain > 0.01f } >= MAX_VOICES) {
                    voices.filter { it.gain > 0.01f }.minByOrNull { it.gain * it.amp * (if (it.damped) 0.2f else 1f) }?.release = steal
                }
                voices.add(Voice(c.tone, c.amp, c.samples, c.pitch))
            }
            mix.fill(0f)
            val it = voices.iterator()
            while (it.hasNext()) {
                val v = it.next()
                val tone = v.tone
                var pos = v.pos
                var gain = v.gain
                val a = v.amp
                for (i in 0 until frames) {
                    if (pos >= tone.size) break
                    if (pos >= v.stopAt && v.release == 1f) v.release = damp
                    mix[i] += tone[pos] * a * gain
                    gain *= v.release
                    pos++
                }
                v.pos = pos; v.gain = gain
                if (pos >= tone.size || gain < 0.0005f) it.remove()
            }
            val vol = volume
            for (i in 0 until frames) {
                // gentle limiter: linear for normal levels, soft above
                val x = mix[i] * vol
                val y = if (x > 0.7f) 0.7f + (x - 0.7f) / (1f + (x - 0.7f) * 3.3f) else if (x < -0.7f) -0.7f + (x + 0.7f) / (1f - (x + 0.7f) * 3.3f) else x
                out[i] = (y.coerceIn(-1f, 1f) * 32000f).toInt().toShort()
            }
            at.write(out, 0, frames)
        }
        at.stop()
        at.release()
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

    private val clickTone: FloatArray by lazy { renderClick(1320.0) }
    private val clickAccent: FloatArray by lazy { renderClick(1760.0) }

    private fun renderClick(freq: Double): FloatArray {
        val n = (0.03 * sampleRate).toInt()
        return FloatArray(n) { i -> val t = i.toDouble() / sampleRate; (exp(-t * 120) * sin(2 * PI * freq * t)).toFloat() }
    }

    private companion object {
        const val MAX_VOICES = 48
        const val CLICK = -1
        const val CLICK_ACCENT = -2
    }
}
