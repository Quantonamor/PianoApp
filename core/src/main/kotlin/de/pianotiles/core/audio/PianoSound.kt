package de.pianotiles.core.audio

import de.pianotiles.core.model.Pitch
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the sound of a key comes from: the nearest real piano recording (Salamander Grand Piano, every third key,
 * three velocity layers – see `tools/samples/make_piano.py`), played back at [step] source samples per output
 * sample, so the mixer shifts the pitch while playing (no copy per key). Until recordings are there (or without
 * them) an additive synthesizer stands in. Thread-safe.
 */
class PianoTones(private val sampleRate: Int) {

    /** A source to play: [samples] read at [step] per output sample, loudness factor [gain] (velocity inside the layer). */
    class Source(val samples: ShortArray, val step: Double, val gain: Float)

    /** One velocity layer: the MIDI velocity it was recorded at and its recordings (MIDI pitch → samples). */
    class Layer(val velocity: Int, val samples: Map<Int, ShortArray>)

    private val synthTones = ConcurrentHashMap<Int, ShortArray>()
    @Volatile private var layers: List<Layer> = emptyList()
    @Volatile private var recordingRate = 44100

    val hasRecordings get() = layers.isNotEmpty()

    /** Installs the recordings ([rate] = their sample rate); layers are sorted from soft to loud. */
    fun setRecordings(layers: List<Layer>, rate: Int) {
        val l = layers.filter { it.samples.isNotEmpty() }.sortedBy { it.velocity }
        if (l.isEmpty()) return
        recordingRate = rate
        this.layers = l
        synthTones.clear()
    }

    fun source(pitch: Int, velocity: Int): Source {
        val ls = layers
        if (ls.isEmpty()) return Source(synthTones.getOrPut(pitch) { toShorts(renderTone(pitch)) }, 1.0, velocity.coerceIn(1, 127) / 100f)
        // the layer recorded closest to this velocity; inside it the loudness follows the velocity gently
        val layer = ls.minBy { abs(it.velocity - velocity) }
        val base = layer.samples.keys.minBy { abs(it - pitch) }
        val gain = (velocity.coerceIn(1, 127).toFloat() / layer.velocity).pow(0.7f).coerceIn(0.45f, 1.6f)
        return Source(layer.samples.getValue(base), 2.0.pow((pitch - base) / 12.0) * recordingRate / sampleRate, gain)
    }

    /** Makes the stand-in tones ahead of time (only needed while there are no recordings). */
    fun prepare(pitches: Collection<Int>) { if (layers.isEmpty()) for (p in pitches) synthTones.getOrPut(p) { toShorts(renderTone(p)) } }

    private fun toShorts(f: FloatArray) = ShortArray(f.size) { (f[it] * 30000f).toInt().toShort() }

    /** Additive piano-like tone (stand-in), peak-normalised. Low notes ring long, high notes short. */
    private fun renderTone(pitch: Int): FloatArray {
        val sr = sampleRate.toDouble()
        val f0 = Pitch.frequency(pitch)
        val k = ((pitch - 21) / 87.0).coerceIn(0.0, 1.0)
        val n = ((3.6 - 2.6 * k) * sr).toInt()
        val buf = FloatArray(n)
        val inharm = 0.00008 + 0.0006 * k * k
        val slowTau = 3.2 - 2.5 * k
        val fastTau = 0.35 - 0.25 * k
        val partials = if (pitch < 48) 14 else if (pitch < 72) 10 else 6
        val detune = 1.0009
        for (p in 1..partials) {
            val f = f0 * p * sqrt(1 + inharm * p * p)
            if (f * detune >= sr * 0.45) break
            val amp = (1.0 / p.toDouble().pow(1.1 + 0.6 * k)) * (if (p == 1 && pitch < 40) 0.7 else 1.0)
            val tf = fastTau / (1 + 0.5 * (p - 1))
            val ts = slowTau / (1 + 0.35 * (p - 1))
            val w1 = 2 * PI * f / sr
            val w2 = w1 * detune
            val c1 = 2 * cos(w1); val c2 = 2 * cos(w2)
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
        val ramp = (0.002 * sr).toInt()
        for (i in 0 until min(ramp, n)) buf[i] *= i.toFloat() / ramp
        val fade = min(n, (0.05 * sr).toInt())
        for (i in 0 until fade) buf[n - 1 - i] *= i.toFloat() / fade
        var peak = 1e-9f
        for (x in buf) peak = max(peak, abs(x))
        for (i in buf.indices) buf[i] /= peak
        return buf
    }

    companion object {
        /** Cuts silence (and decoder delay) before the note onset, keeping a few milliseconds. */
        fun trimOnset(s: ShortArray, rate: Int): ShortArray {
            var peak = 1
            for (x in s) peak = max(peak, abs(x.toInt()))
            val start = s.indexOfFirst { abs(it.toInt()) > peak * 0.02 }.coerceAtLeast(0)
            return s.copyOfRange(max(0, start - rate / 700), s.size)
        }
    }
}

/**
 * Mixes piano notes into a stereo stream (the audio thread calls [mix] block by block).
 *
 * Notes are queued with a start delay ([noteOn], any thread). The mixer turns the delay into an absolute sample
 * time and keeps waiting notes in a schedule – they occupy no voice until they start, so a whole passage can be
 * scheduled at once without notes being dropped. When a note starts, an earlier note of the same key is damped
 * (re-strike); if more than [maxVoices] notes sound, the quietest fades out quickly.
 *
 * Output stage: a light room reverb and a smooth peak compressor (no hard clipping, so big chords stay clean).
 */
class PianoMixer(val sampleRate: Int, private val tones: PianoTones, private val maxVoices: Int = 64) {

    private class Command(val pitch: Int, val tone: ShortArray, val step: Double, val amp: Float, val holdSamples: Int, val delay: Int, val bright: Float)

    private class Scheduled(val startAt: Long, val seq: Long, val c: Command)

    private class Voice(val c: Command, var wait: Int) {
        var pos = 0.0
        var gain = 1f
        var release = 1f
        var lp = 0f
        val damped get() = release < 1f
        val panL: Float
        val panR: Float
        init {
            val pan = if (c.pitch < 0) 0.5f else (0.3f + 0.4f * ((c.pitch - 21) / 87f).coerceIn(0f, 1f))
            panL = cos(pan * PI / 2).toFloat() * 1.41f
            panR = sin(pan * PI / 2).toFloat() * 1.41f
        }
    }

    private val pending = ConcurrentLinkedQueue<Command>()
    private val schedule = PriorityQueue<Scheduled>(compareBy<Scheduled> { it.startAt }.thenBy { it.seq })
    private val voices = ArrayList<Voice>()
    private var seq = 0L
    @Volatile private var silence = false
    @Volatile var volume = 0.8f
    @Volatile var reverb = 0.16f

    /** Samples mixed so far (the mixer's clock). */
    var clock = 0L
        private set
    /** For tests: notes that started sounding, and notes that were faded out early to free a voice. */
    var started = 0L
        private set
    var stolen = 0L
        private set
    /** For tests: start time (sample) of every note that sounded, in order. */
    var startLog: MutableList<Pair<Int, Long>>? = null

    private val damp = exp(-1.0 / (0.12 * sampleRate)).toFloat()    // key released: damper ~0.12 s
    private val steal = exp(-1.0 / (0.006 * sampleRate)).toFloat()   // voice freed for a new note: ~6 ms
    private val restrike = exp(-1.0 / (0.03 * sampleRate)).toFloat() // same key struck again

    /**
     * Plays [pitch] after [delaySeconds] (counted from the next block), held for [durationSeconds].
     * Loudness and brightness follow [velocity].
     */
    fun noteOn(pitch: Int, velocity: Int, durationSeconds: Double, delaySeconds: Double = 0.0) {
        val src = tones.source(pitch, velocity)
        val v = velocity.coerceIn(1, 127) / 127f
        // the recordings carry the dynamics; a gentle low-pass only adds a little colour to soft notes
        pending.add(Command(pitch, src.samples, src.step, 0.62f * src.gain, (durationSeconds.coerceIn(0.08, 30.0) * sampleRate).toInt(),
            (delaySeconds.coerceIn(0.0, 60.0) * sampleRate).toInt(), 0.55f + 0.45f * v))
    }

    fun click(tone: ShortArray, id: Int) { pending.add(Command(id, tone, 1.0, 0.3f, Int.MAX_VALUE, 0, 1f)) }

    /** Stops everything: waiting notes are dropped, sounding notes fade out quickly. */
    fun allNotesOff() { pending.clear(); silence = true }

    /** Mixes the next [frames] samples into [left]/[right] (values about −1..1). */
    fun mix(left: FloatArray, right: FloatArray, frames: Int) {
        if (silence) { silence = false; schedule.clear(); for (v in voices) v.release = min(v.release, steal) }
        while (true) {
            val c = pending.poll() ?: break
            schedule.add(Scheduled(clock + c.delay, seq++, c))
        }
        // notes starting in this block become voices; their offset inside the block is kept exactly
        while (true) {
            val s = schedule.peek() ?: break
            if (s.startAt >= clock + frames) break
            schedule.poll()
            start(s.c, (s.startAt - clock).toInt().coerceAtLeast(0))
        }
        for (i in 0 until frames) { left[i] = 0f; right[i] = 0f }
        val inv = 1f / 32768f
        val it = voices.iterator()
        while (it.hasNext()) {
            val v = it.next()
            val tone = v.c.tone
            val last = tone.size - 1
            val step = v.c.step
            var pos = v.pos
            var wait = v.wait
            var gain = v.gain
            var lp = v.lp
            val a = v.c.amp * inv
            val k = v.c.bright
            val hold = v.c.holdSamples * step
            for (i in 0 until frames) {
                if (wait > 0) { wait--; continue }
                if (pos >= last) break
                if (pos >= hold && v.release == 1f) v.release = damp
                // pitch shift while playing: linear interpolation between two recorded samples
                val j = pos.toInt()
                val f = (pos - j).toFloat()
                val x = tone[j] + (tone[j + 1] - tone[j]) * f
                lp += (x - lp) * k
                val s = lp * a * gain
                left[i] += s * v.panL; right[i] += s * v.panR
                gain *= v.release
                pos += step
            }
            v.pos = pos; v.wait = wait; v.gain = gain; v.lp = lp
            if (pos >= last || gain < 0.0005f) it.remove()
        }
        output(left, right, frames)
        clock += frames
    }

    private fun start(c: Command, offset: Int) {
        // same key struck again: the old sound stops quickly
        for (v in voices) if (v.c.pitch == c.pitch && !v.damped) v.release = restrike
        val sounding = voices.count { !it.damped || it.gain > 0.05f }
        if (sounding >= maxVoices) {
            voices.filter { it.release != steal }.minByOrNull { it.gain * it.c.amp * (if (it.damped) 0.2f else 1f) }?.let { it.release = steal; stolen++ }
        }
        voices.add(Voice(c, offset))
        started++
        startLog?.add(c.pitch to clock + offset)
    }

    // ------------------------------------------------------------------------------------------ output stage

    // Freeverb-style room: 4 parallel combs + 2 allpasses per channel (right channel slightly detuned)
    private val combL = intArrayOf(1116, 1188, 1277, 1356).map { FloatArray(it * sampleRate / 44100) }
    private val combR = intArrayOf(1139, 1211, 1300, 1379).map { FloatArray(it * sampleRate / 44100) }
    private val combPos = IntArray(8)
    private val combLp = FloatArray(8)
    private val apL = intArrayOf(556, 441).map { FloatArray(it * sampleRate / 44100) }
    private val apR = intArrayOf(579, 464).map { FloatArray(it * sampleRate / 44100) }
    private val apPos = IntArray(4)
    private var env = 0f
    private val attack = 1f - exp(-1.0 / (0.002 * sampleRate)).toFloat()
    private val releaseC = 1f - exp(-1.0 / (0.25 * sampleRate)).toFloat()

    private fun output(left: FloatArray, right: FloatArray, frames: Int) {
        val wet = reverb
        val vol = volume
        for (i in 0 until frames) {
            var l = left[i]; var r = right[i]
            if (wet > 0f) {
                val inp = (l + r) * 0.5f
                var wl = 0f; var wr = 0f
                for (c in 0 until 4) {
                    wl += comb(combL[c], c, inp)
                    wr += comb(combR[c], 4 + c, inp)
                }
                wl = allpass(apL[0], 0, allpass(apL[1], 1, wl * 0.25f))
                wr = allpass(apR[0], 2, allpass(apR[1], 3, wr * 0.25f))
                l += wl * wet; r += wr * wet
            }
            l *= vol; r *= vol
            // smooth compressor: follows the peak quickly, recovers slowly; transparent below 0.6
            val peak = max(abs(l), abs(r))
            env += (peak - env) * (if (peak > env) attack else releaseC)
            val g = if (env > 0.6f) (0.6f + (env - 0.6f) * 0.25f) / env else 1f // ratio 4:1 above 0.6
            left[i] = softClip(l * g); right[i] = softClip(r * g)
        }
    }

    private fun comb(buf: FloatArray, idx: Int, inp: Float): Float {
        val p = combPos[idx]
        val out = buf[p]
        combLp[idx] = out * 0.6f + combLp[idx] * 0.4f // damping: highs die faster
        buf[p] = inp + combLp[idx] * 0.78f
        combPos[idx] = if (p + 1 == buf.size) 0 else p + 1
        return out
    }

    private fun allpass(buf: FloatArray, idx: Int, inp: Float): Float {
        val p = apPos[idx]
        val b = buf[p]
        buf[p] = inp + b * 0.5f
        apPos[idx] = if (p + 1 == buf.size) 0 else p + 1
        return b - inp
    }

    /** Last safety: linear up to 0.9, then smoothly towards 1. */
    private fun softClip(x: Float): Float {
        val a = abs(x)
        if (a <= 0.9f) return x
        val y = 0.9f + 0.1f * (1f - exp(-(a - 0.9f) * 10f))
        return if (x < 0) -y else y
    }
}
