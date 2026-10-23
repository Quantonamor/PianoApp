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

    /** Room reverb on or off. */
    var reverb: Boolean
        get() = mixer.reverb > 0f
        set(value) { mixer.reverb = if (value) 0.16f else 0f }

    /**
     * False: nothing goes to the audio output (notes are still scheduled). The emulator tests run with it off: on an
     * emulator without audio device the whole emulator vanished now and then while music was playing.
     */
    @Volatile var outputEnabled = true

    fun start() {
        if (running || !outputEnabled) return
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

    /** True once the piano recordings are decoded (until then a synthesizer stands in). */
    val recordingsLoaded get() = tones.hasRecordings

    /**
     * Loads the piano recordings from the app assets (call once, off the main thread): `piano/<layer>_<midi>.mp3`,
     * layer p/m/f = soft/medium/loud, decoded with the platform decoder.
     */
    fun loadSamples(assets: android.content.res.AssetManager) {
        val names = runCatching { assets.list("piano") }.getOrNull().orEmpty().filter { it.endsWith(".mp3") }
        val layers = names.groupBy { it.substringBefore('_') }.mapNotNull { (tag, files) ->
            val velocity = LAYER_VELOCITY[tag] ?: return@mapNotNull null
            val map = HashMap<Int, ShortArray>()
            for (name in files) {
                val midi = name.substringAfter('_').removeSuffix(".mp3").toIntOrNull() ?: continue
                runCatching { decodeMono(assets, "piano/$name") }
                    .onSuccess { map[midi] = PianoTones.trimOnset(it, sampleRate) }
                    .onFailure { android.util.Log.w("Synth", "cannot decode $name", it) }
            }
            PianoTones.Layer(velocity, map)
        }
        tones.setRecordings(layers, sampleRate)
        android.util.Log.i("Synth", "piano recordings: ${layers.sumOf { it.samples.size }} in ${layers.size} layers")
    }

    /** Decodes a compressed asset to mono 16-bit samples (the recordings are mono, 44.1 kHz). */
    private fun decodeMono(assets: android.content.res.AssetManager, path: String): ShortArray {
        val extractor = android.media.MediaExtractor()
        val out = java.io.ByteArrayOutputStream(1 shl 18)
        var channels = 1
        assets.openFd(path).use { fd -> extractor.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
        try {
            extractor.selectTrack(0)
            val format = extractor.getTrackFormat(0)
            channels = format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
            val codec = android.media.MediaCodec.createDecoderByType(format.getString(android.media.MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            val info = android.media.MediaCodec.BufferInfo()
            var inputDone = false
            var chunk = ByteArray(0)
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!
                    if (chunk.size < info.size) chunk = ByteArray(info.size)
                    buf.position(info.offset); buf.get(chunk, 0, info.size)
                    out.write(chunk, 0, info.size)
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (o == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    channels = codec.outputFormat.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
            codec.stop(); codec.release()
        } finally {
            extractor.release()
        }
        val bytes = out.toByteArray()
        val all = ShortArray(bytes.size / 2)
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(all)
        if (channels <= 1) return all
        return ShortArray(all.size / channels) { i -> var sum = 0; for (c in 0 until channels) sum += all[i * channels + c]; (sum / channels).toShort() }
    }

    /** Makes the tones of [pitches] ahead of time (call off the main thread, e.g. when a song is opened). */
    fun prepare(pitches: Collection<Int>) = tones.prepare(pitches)

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
        val startNanos = System.nanoTime()
        var written = 0L
        while (running) {
            mixer.mix(left, right, frames)
            for (i in 0 until frames) {
                out[2 * i] = (left[i] * 32000f).toInt().toShort()
                out[2 * i + 1] = (right[i] * 32000f).toInt().toShort()
            }
            at.write(out, 0, out.size)
            written += frames
            // an output that does not block (no audio device, e.g. an emulator) would let this thread spin at full
            // speed: stay at most 0.2 s ahead of real time
            val ahead = written.toDouble() / sampleRate - (System.nanoTime() - startNanos) / 1e9
            if (ahead > 0.2) Thread.sleep(((ahead - 0.1) * 1000).toLong())
        }
        at.stop()
        at.release()
    }

    private companion object {
        /** MIDI velocity each recorded layer corresponds to (Salamander v4 / v9 / v14). */
        val LAYER_VELOCITY = mapOf("p" to 40, "m" to 75, "f" to 110)
    }

    private val clickTone: ShortArray by lazy { renderClick(1320.0) }
    private val clickAccent: ShortArray by lazy { renderClick(1760.0) }

    private fun renderClick(freq: Double): ShortArray {
        val n = (0.03 * sampleRate).toInt()
        return ShortArray(n) { i -> val t = i.toDouble() / sampleRate; (exp(-t * 120) * sin(2 * PI * freq * t) * 30000).toInt().toShort() }
    }
}
