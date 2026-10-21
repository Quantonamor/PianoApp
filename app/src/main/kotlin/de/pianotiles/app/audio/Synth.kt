package de.pianotiles.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import de.pianotiles.core.model.Pitch
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Tiny additive synthesizer for demo playback, accompaniment and the metronome.
 * Runs its own audio thread; [noteOn]/[click] are thread-safe.
 */
class Synth(private val sampleRate: Int = 44100) {

    private class Voice(val freq: Double, val amp: Double, val lengthSamples: Int, val isClick: Boolean = false) {
        var t = 0
        val phases = DoubleArray(PARTIALS.size)
        var done = false
    }

    private val pending = ConcurrentLinkedQueue<Voice>()
    private val voices = ArrayList<Voice>()
    @Volatile var volume = 0.8f
    @Volatile private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "PianoSynth").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    fun stop() {
        running = false
        thread?.join(300)
        thread = null
    }

    fun noteOn(pitch: Int, velocity: Int = 80, durationSeconds: Double = 0.5) {
        start()
        val len = (durationSeconds.coerceIn(0.08, 6.0) * sampleRate).toInt()
        pending.add(Voice(Pitch.frequency(pitch), 0.22 * velocity / 127.0, len))
    }

    fun click(accent: Boolean) {
        start()
        pending.add(Voice(if (accent) 1760.0 else 1320.0, if (accent) 0.35 else 0.22, (0.03 * sampleRate).toInt(), isClick = true))
    }

    fun allNotesOff() { pending.clear(); synchronized(voices) { voices.forEach { it.done = true } } }

    private fun loop() {
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val frames = 256
        val at = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minBuf, frames * 2 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        track = at
        at.play()
        val out = ShortArray(frames)
        val mix = DoubleArray(frames)
        while (running) {
            while (true) { val v = pending.poll() ?: break; synchronized(voices) { voices.add(v) } }
            mix.fill(0.0)
            synchronized(voices) {
                for (v in voices) render(v, mix)
                voices.removeAll { it.done }
                if (voices.size > 24) voices.subList(0, voices.size - 24).forEach { it.done = true }
            }
            val vol = volume
            for (i in 0 until frames) {
                // soft clipping
                val x = mix[i] * vol
                val y = x / (1 + kotlin.math.abs(x))
                out[i] = (y * 32000).toInt().toShort()
            }
            at.write(out, 0, frames)
        }
        at.stop()
        at.release()
        track = null
    }

    private fun render(v: Voice, mix: DoubleArray) {
        val sr = sampleRate.toDouble()
        for (i in mix.indices) {
            if (v.done) return
            val t = v.t / sr
            if (v.isClick) {
                val env = exp(-t * 120)
                mix[i] += v.amp * env * sin(2 * PI * v.freq * t)
                if (v.t >= v.lengthSamples) v.done = true
                v.t++
                continue
            }
            val attack = if (v.t < 60) v.t / 60.0 else 1.0
            val release = if (v.t > v.lengthSamples) exp(-(v.t - v.lengthSamples) / (0.08 * sr)) else 1.0
            if (release < 0.001) { v.done = true; return }
            var s = 0.0
            for (p in PARTIALS.indices) {
                val f = v.freq * (p + 1) * (1 + 0.0003 * (p + 1) * (p + 1))
                if (f > sr / 2) break
                v.phases[p] += 2 * PI * f / sr
                if (v.phases[p] > 2 * PI) v.phases[p] -= 2 * PI
                s += PARTIALS[p] * exp(-t * (1.0 + v.freq / 900.0) * (1 + p * 0.6)) * sin(v.phases[p])
            }
            mix[i] += v.amp * attack * release * s
            v.t++
        }
    }

    private companion object {
        val PARTIALS = doubleArrayOf(1.0, 0.55, 0.3, 0.18, 0.1, 0.05)
    }
}
