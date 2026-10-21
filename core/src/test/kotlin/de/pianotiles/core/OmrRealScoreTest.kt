package de.pianotiles.core

import de.pianotiles.core.io.MidiImporter
import de.pianotiles.core.model.Note
import de.pianotiles.core.omr.GrayImage
import de.pianotiles.core.omr.OmrEngine
import de.pianotiles.core.omr.OmrOptions
import de.pianotiles.core.omr.OmrResult
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Recognition quality on real engraved scores (LilyPond, see the .ly files in src/test/resources/omr).
 * Ground truth is the MIDI file LilyPond produced from the same source.
 */
class OmrRealScoreTest {

    data class Score(val recall: Double, val precision: Double, val pitchRecall: Double)

    private fun load(name: String): GrayImage {
        val img = ImageIO.read(javaClass.getResourceAsStream("/omr/$name")!!)
        val px = IntArray(img.width * img.height)
        img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
        return GrayImage.fromArgb(img.width, img.height, px)
    }

    private fun evaluate(name: String): Pair<Score, OmrResult> {
        val truth = MidiImporter.import(javaClass.getResourceAsStream("/omr/$name.mid")!!.readBytes(), name).notes
        val page = load("$name-1.png")
        val engine = OmrEngine(OmrOptions())
        System.getProperty("omr.debugDir")?.let { dir ->
            engine.debugSink = { n, img ->
                if (n.startsWith("staff0_") || n.startsWith("staff1_") || n.startsWith("staff2_")) {
                    val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_BYTE_GRAY)
                    for (y in 0 until img.height) for (x in 0 until img.width) out.setRGB(x, y, if (img[x, y]) 0 else 0xFFFFFF)
                    ImageIO.write(out, "png", File("$dir/${name}_$n.png"))
                }
            }
        }
        val result = engine.recognize(listOf(page))
        val got = result.song.notes
        fun matches(a: Note, b: Note) = a.pitch == b.pitch && abs(a.start - b.start) < 0.05
        val hit = truth.count { t -> got.any { matches(it, t) } }
        val correct = got.count { g -> truth.any { matches(g, it) } }
        // pitch-only: multiset of pitches
        val remaining = got.map { it.pitch }.toMutableList()
        var pitchHits = 0
        for (t in truth) if (remaining.remove(t.pitch)) pitchHits++
        val score = Score(hit.toDouble() / truth.size, correct.toDouble() / got.size.coerceAtLeast(1), pitchHits.toDouble() / truth.size)
        println("$name: truth=${truth.size} got=${got.size} recall=${"%.2f".format(score.recall)} precision=${"%.2f".format(score.precision)} " +
            "pitchRecall=${"%.2f".format(score.pitchRecall)} key=${result.keyFifths} ts=${result.beatsPerMeasure}/${result.beatUnit} warnings=${result.warnings}")
        val missing = truth.filter { t -> got.none { matches(it, t) } }.sortedBy { it.start }
        val extra = got.filter { g -> truth.none { matches(g, it) } }.sortedBy { it.start }
        println("  missing: " + missing.joinToString { "${it.pitch}@${it.start}" })
        println("  extra:   " + extra.joinToString { "${it.pitch}@${it.start}" })
        System.getProperty("omr.debugDir")?.let { dir -> overlay(page, result, "$dir/${name}_ov.png") }
        return score to result
    }

    private fun overlay(img: GrayImage, result: OmrResult, file: String) {
        val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until img.height) for (x in 0 until img.width) { val v = img[x, y]; out.setRGB(x, y, (v shl 16) or (v shl 8) or v) }
        val g = out.createGraphics()
        val page = result.pages[0]
        g.color = Color.BLUE
        for ((x, r) in page.barlines) g.drawRect(x - 3, r.first, 6, r.last - r.first)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        for (h in page.heads) {
            g.color = if (h.hollow) Color.MAGENTA else Color.RED
            g.drawRect(h.x0, h.y0, h.x1 - h.x0, h.y1 - h.y0)
            g.drawString("${h.pitch}", h.x0, h.y1 + 14)
            g.drawString("${h.onset}", h.x0, h.y1 + 26)
        }
        g.dispose()
        ImageIO.write(out, "png", File(file))
    }

    @Test
    fun odeToJoy() {
        val (s, r) = evaluate("ode")
        assertEquals(0, r.keyFifths)
        assertEquals(4, r.beatsPerMeasure)
        assertTrue(s.recall >= 0.9, "recall ${s.recall}")
    }

    @Test
    fun minuet() {
        val (s, r) = evaluate("minuet")
        assertEquals(1, r.keyFifths)
        assertEquals(3, r.beatsPerMeasure)
        assertTrue(s.recall >= 0.9, "recall ${s.recall}")
    }

    @Test
    fun etude() {
        val (s, r) = evaluate("etude")
        assertEquals(2, r.keyFifths)
        assertTrue(s.recall >= 0.85, "recall ${s.recall}")
    }
}
