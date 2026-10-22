package de.pianotiles.core

import de.pianotiles.core.audio.PianoMixer
import de.pianotiles.core.audio.PianoTones
import de.pianotiles.core.model.DemoSongs
import de.pianotiles.core.model.Song
import de.pianotiles.core.practice.EngineEvent
import de.pianotiles.core.practice.PracticeEngine
import de.pianotiles.core.practice.PracticeMode
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plays every demo song the way the app does (practice screen "Anhören" with 60 frames per second and
 * scheduled-ahead notes; editor: a whole passage scheduled at once) and checks that every note sounds exactly
 * once, at the right time, without overdriving the output. `-Paudio.out=<dir>` writes the results as WAV files.
 */
class PianoSoundTest {
    private val rate = 44100
    private val block = 256

    private fun tones() = PianoTones(rate).apply {
        val dir = File("../app/src/main/assets/piano")
        val map = dir.listFiles().orEmpty().mapNotNull { f ->
            val midi = f.name.removeSuffix(".pcm").toIntOrNull() ?: return@mapNotNull null
            val b = f.readBytes()
            val s = ShortArray(b.size / 2)
            java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s)
            midi to s
        }.toMap()
        setRecordings(map)
    }

    private class Result(val starts: List<Pair<Int, Long>>, val peak: Float, val loud: Double, val stolen: Long, val audio: FloatArray)

    /** The practice screen in "Anhören": engine.update per screen frame (with jitter), sound scheduled 0.12 s ahead. */
    private fun listen(song: Song, tones: PianoTones): Result {
        val mixer = PianoMixer(rate, tones).apply { startLog = ArrayList() }
        val engine = PracticeEngine(song).apply { countIn = false; mode = PracticeMode.LISTEN; audioLookaheadSeconds = 0.12; seek(0.0); play() }
        val seconds = song.notes.maxOf { it.end } * 60.0 / song.bpm + 2.0
        val l = FloatArray(block); val r = FloatArray(block)
        val out = FloatArray((seconds * rate).toInt() + block)
        var peak = 0f; var loud = 0
        var frameTime = 0.0; var lastFrame = 0.0
        val rnd = java.util.Random(1)
        var w = 0
        while (mixer.clock < seconds * rate) {
            // UI frames that happened before this audio block
            val now = mixer.clock.toDouble() / rate
            while (frameTime <= now) {
                for (e in engine.update(frameTime - lastFrame)) if (e is EngineEvent.PlayNote) mixer.noteOn(e.pitch, e.velocity, e.durationSeconds, e.delaySeconds - (now - frameTime).coerceAtMost(0.0))
                lastFrame = frameTime
                frameTime += 1.0 / 60 + (rnd.nextDouble() - 0.5) * 0.006
            }
            mixer.mix(l, r, block)
            for (i in 0 until block) {
                val m = max(abs(l[i]), abs(r[i])); peak = max(peak, m); if (m > 0.6f) loud++
                if (w < out.size) out[w++] = (l[i] + r[i]) * 0.5f
            }
        }
        return Result(mixer.startLog!!, peak, loud.toDouble() / mixer.clock, mixer.stolen, out)
    }

    @Test
    fun everyNoteSoundsOnceAndInTime() {
        val tones = tones()
        assertTrue(tones.hasRecordings, "piano recordings not found")
        val outDir = System.getProperty("audio.out")?.let { File(it).apply { mkdirs() } }
        for (song in DemoSongs.all()) {
            val res = listen(song, tones)
            val bps = song.bpm / 60.0
            val expected = song.notes.map { it.pitch to it.start / bps }.sortedWith(compareBy({ it.second }, { it.first }))
            val got = res.starts.map { it.first to it.second.toDouble() / rate }.sortedWith(compareBy({ it.second }, { it.first }))
            assertEquals(expected.size, got.size, "${song.title}: notes played")
            // a constant latency is inaudible; what matters is how much single notes deviate from it
            val offsets = expected.zip(got).map { (e, g) -> g.second - e.second }.sorted()
            val latency = offsets[offsets.size / 2]
            var worst = 0.0
            for ((e, g) in expected.zip(got)) {
                assertEquals(e.first, g.first, "${song.title}: pitch at ${e.second}")
                worst = max(worst, abs(g.second - e.second - latency))
            }
            println("%-34s notes=%3d jitter max %.1f ms  peak %.2f  >0.6: %.2f%%  stolen %d".format(song.title, got.size, worst * 1000, res.peak, res.loud * 100, res.stolen))
            assertTrue(worst < 0.012, "${song.title}: timing off by ${worst * 1000} ms")
            assertTrue(res.peak < 1f, "${song.title}: clipping")
            assertEquals(0L, res.stolen, "${song.title}: notes cut to free voices")
            outDir?.let { writeWav(File(it, song.id + ".wav"), res.audio) }
        }
    }

    /** The editor schedules a whole passage at once: notes still to come must not take voices away. */
    @Test
    fun wholePassageScheduledAtOnce() {
        val tones = tones()
        val song = DemoSongs.all().maxBy { it.notes.size }
        val mixer = PianoMixer(rate, tones).apply { startLog = ArrayList() }
        val bps = song.bpm / 60.0
        for (n in song.notes) mixer.noteOn(n.pitch, n.velocity, n.duration / bps, n.start / bps)
        val l = FloatArray(block); val r = FloatArray(block)
        val seconds = song.notes.maxOf { it.end } / bps + 2
        while (mixer.clock < seconds * rate) mixer.mix(l, r, block)
        assertEquals(song.notes.size.toLong(), mixer.started, "all notes start")
        for ((p, at) in mixer.startLog!!) {
            assertTrue(song.notes.any { it.pitch == p && abs(it.start / bps - at.toDouble() / rate) < 1.0 / rate * 2 }, "note $p at ${at.toDouble() / rate}s is exact")
        }
    }

    private fun writeWav(f: File, s: FloatArray) {
        val n = s.size
        val b = java.nio.ByteBuffer.allocate(44 + n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(n * 2)
        for (x in s) b.putShort((x.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        f.writeBytes(b.array())
    }
}
