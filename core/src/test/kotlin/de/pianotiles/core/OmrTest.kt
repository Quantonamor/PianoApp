package de.pianotiles.core

import de.pianotiles.core.model.Hand
import de.pianotiles.core.omr.GrayImage
import de.pianotiles.core.omr.OmrEngine
import de.pianotiles.core.omr.OmrOptions
import de.pianotiles.core.omr.StaffDetector
import de.pianotiles.core.omr.BitImage
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Renders simple synthetic piano scores with java.awt and checks the recognition result.
 */
class OmrTest {

    /** A notehead event: staff steps (0 = bottom line), duration in quarters, accidental. */
    data class Ev(val steps: List<Int>, val dur: Double, val acc: Int? = null)

    private val ss = 20.0

    private fun render(
        rh: List<List<Ev>>, lh: List<List<Ev>>, keySharps: Int = 0, file: String? = null,
        systems: Int = 1,
    ): GrayImage {
        val w = 2000; val h = 900 * systems
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.WHITE; g.fillRect(0, 0, w, h)
        g.color = Color.BLACK
        val perSystem = (rh.size + systems - 1) / systems
        for (sys in 0 until systems) {
            val trebleTop = 200.0 + sys * 900
            val bassTop = trebleTop + 4 * ss + 7 * ss
            val xStart = 120.0; val xEnd = 1880.0
            for (top in listOf(trebleTop, bassTop)) {
                g.stroke = BasicStroke(2f)
                for (i in 0..4) g.draw(java.awt.geom.Line2D.Double(xStart, top + i * ss, xEnd, top + i * ss))
            }
            // system bar line + clefs
            g.stroke = BasicStroke(3f)
            g.draw(java.awt.geom.Line2D.Double(xStart, trebleTop, xStart, bassTop + 4 * ss))
            g.draw(java.awt.geom.Line2D.Double(xStart + 1.3 * ss, trebleTop - 1.5 * ss, xStart + 1.3 * ss, trebleTop + 5.5 * ss))
            g.stroke = BasicStroke((0.3 * ss).toFloat())
            g.draw(Ellipse2D.Double(xStart + 0.5 * ss, trebleTop + 2 * ss, 1.8 * ss, 1.8 * ss))
            g.fill(Ellipse2D.Double(xStart + 0.6 * ss, bassTop + 0.6 * ss, 1.0 * ss, 1.0 * ss))
            g.draw(java.awt.geom.Arc2D.Double(xStart + 0.5 * ss, bassTop, 2.2 * ss, 3.2 * ss, -60.0, 200.0, java.awt.geom.Arc2D.OPEN))
            // key signature (sharps: F5, C5 ...)
            var kx = xStart + 3.2 * ss
            val sharpSteps = listOf(8, 5, 9, 6)
            for (k in 0 until keySharps) { drawSharp(g, kx, trebleTop + 4 * ss - sharpSteps[k] * ss / 2); kx += 1.2 * ss }
            // time signature
            g.font = Font(Font.SERIF, Font.BOLD, (2.6 * ss).toInt())
            for (top in listOf(trebleTop, bassTop)) {
                g.drawString("4", (kx + 0.3 * ss).toFloat(), (top + 2 * ss - 0.1 * ss).toFloat())
                g.drawString("4", (kx + 0.3 * ss).toFloat(), (top + 4 * ss - 0.1 * ss).toFloat())
            }
            var x = kx + 3.5 * ss
            val measures = rh.indices.drop(sys * perSystem).take(perSystem)
            for (m in measures) {
                val start = x
                var xr = start; var xl = start
                xr = drawVoice(g, rh[m], xr, trebleTop)
                xl = drawVoice(g, lh[m], xl, bassTop)
                x = maxOf(xr, xl) + ss
                g.stroke = BasicStroke(2.5f)
                g.draw(java.awt.geom.Line2D.Double(x, trebleTop, x, bassTop + 4 * ss))
                x += 2 * ss
            }
        }
        g.dispose()
        if (file != null) ImageIO.write(img, "png", File(file))
        val px = IntArray(w * h)
        img.getRGB(0, 0, w, h, px, 0, w)
        return GrayImage.fromArgb(w, h, px.map { it or (0xFF shl 24) }.toIntArray())
    }

    private fun drawSharp(g: java.awt.Graphics2D, x: Double, cy: Double) {
        g.stroke = BasicStroke(2f)
        g.draw(java.awt.geom.Line2D.Double(x, cy - 1.3 * ss, x, cy + 1.4 * ss))
        g.draw(java.awt.geom.Line2D.Double(x + 0.45 * ss, cy - 1.4 * ss, x + 0.45 * ss, cy + 1.3 * ss))
        g.stroke = BasicStroke((0.35 * ss).toFloat())
        g.draw(java.awt.geom.Line2D.Double(x - 0.2 * ss, cy - 0.3 * ss, x + 0.65 * ss, cy - 0.55 * ss))
        g.draw(java.awt.geom.Line2D.Double(x - 0.2 * ss, cy + 0.55 * ss, x + 0.65 * ss, cy + 0.3 * ss))
    }

    private fun drawVoice(g: java.awt.Graphics2D, evs: List<Ev>, x0: Double, top: Double): Double {
        var x = x0
        val bottom = top + 4 * ss
        var pendingBeam: Pair<Double, Double>? = null
        for (ev in evs) {
            val hollow = ev.dur >= 2.0
            val whole = ev.dur >= 4.0
            val dotted = ev.dur == 3.0 || ev.dur == 1.5
            if (ev.acc != null) drawSharp(g, x - 1.3 * ss, bottom - ev.steps.first() * ss / 2)
            val avg = ev.steps.average()
            val up = avg < 4
            val hw = if (whole) 1.7 * ss else 1.3 * ss
            for (s in ev.steps) {
                val cy = bottom - s * ss / 2
                // ledger lines
                g.stroke = BasicStroke(2f)
                var ly = if (s < 0) -2 else 10
                while ((s < 0 && ly >= s) || (s > 8 && ly <= s)) {
                    val y = bottom - ly * ss / 2
                    g.draw(java.awt.geom.Line2D.Double(x - 0.4 * ss, y, x + hw + 0.4 * ss, y))
                    ly += if (s < 0) -2 else 2
                }
                val e = Ellipse2D.Double(x, cy - 0.5 * ss, hw, ss)
                val at = AffineTransform.getRotateInstance(Math.toRadians(-20.0), x + hw / 2, cy)
                val shape = at.createTransformedShape(e)
                if (hollow) {
                    g.stroke = BasicStroke((0.2 * ss).toFloat())
                    val inner = AffineTransform.getRotateInstance(Math.toRadians(-20.0), x + hw / 2, cy)
                        .createTransformedShape(Ellipse2D.Double(x + 0.1 * ss, cy - 0.4 * ss, hw - 0.2 * ss, 0.8 * ss))
                    g.draw(inner)
                } else g.fill(shape)
                if (dotted) g.fill(Ellipse2D.Double(x + hw + 0.4 * ss, cy - 0.2 * ss - (if (s % 2 == 0) 0.5 * ss else 0.0), 0.4 * ss, 0.4 * ss))
            }
            if (!whole) {
                g.stroke = BasicStroke(2f)
                val sx = if (up) x + 1.3 * ss - 1 else x + 1
                val hi = bottom - ev.steps.max() * ss / 2
                val lo = bottom - ev.steps.min() * ss / 2
                val end = if (up) hi - 3.5 * ss else lo + 3.5 * ss
                g.draw(java.awt.geom.Line2D.Double(sx, if (up) lo else hi, sx, end))
                if (ev.dur == 0.5) {
                    if (pendingBeam == null) pendingBeam = sx to end
                    else {
                        g.stroke = BasicStroke((0.5 * ss).toFloat())
                        val (bx, by) = pendingBeam!!
                        val off = if (up) 0.25 * ss else -0.25 * ss
                        g.draw(java.awt.geom.Line2D.Double(bx, by + off, sx, end + off))
                        pendingBeam = null
                    }
                }
            }
            x += 2.5 * ss + ev.dur * 3 * ss
        }
        return x
    }

    private fun overlay(img: GrayImage, result: de.pianotiles.core.omr.OmrResult, file: String?) {
        if (file == null) return
        val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until img.height) for (x in 0 until img.width) { val v = img[x, y]; out.setRGB(x, y, (v shl 16) or (v shl 8) or v) }
        val g = out.createGraphics()
        val page = result.pages[0]
        g.color = Color.BLUE
        for ((x, r) in page.barlines) g.drawRect(x - 3, r.first, 6, r.last - r.first)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 14)
        for (h in page.heads) {
            g.color = if (h.hollow) Color.MAGENTA else Color.RED
            g.drawRect(h.x0, h.y0, h.x1 - h.x0, h.y1 - h.y0)
            g.drawString("${h.pitch}/${h.duration}@${h.onset}", h.x0, h.y1 + 28)
        }
        g.dispose()
        ImageIO.write(out, "png", File(file))
    }

    // treble steps: C4=-2, D4=-1, E4=0, F4=1, G4=2, A4=3, B4=4, C5=5, D5=6, E5=7, F5=8, G5=9, A5=10
    // bass steps:   G2=0, A2=1, B2=2, C3=3, D3=4, E3=5, F3=6, G3=7, A3=8, B3=9, C4=10

    @Test
    fun staffDetection() {
        val img = render(listOf(listOf(Ev(listOf(5), 4.0))), listOf(listOf(Ev(listOf(3), 4.0))))
        val staves = StaffDetector.detect(BitImage.fromGray(img))
        assertEquals(2, staves.size)
        assertEquals(20.0, staves[0].space, 1.0)
    }

    @Test
    fun recognisesSimplePianoScore() {
        val rh = listOf(
            listOf(Ev(listOf(5), 1.0), Ev(listOf(7), 1.0), Ev(listOf(9), 2.0)),
            listOf(Ev(listOf(8), 0.5, acc = 1), Ev(listOf(9), 0.5), Ev(listOf(10), 1.0), Ev(listOf(5), 2.0)),
            listOf(Ev(listOf(-2, 0, 2), 4.0)),
            listOf(Ev(listOf(4), 3.0), Ev(listOf(5), 1.0)),
        )
        val lh = listOf(
            listOf(Ev(listOf(3), 4.0)),
            listOf(Ev(listOf(0), 2.0), Ev(listOf(7), 2.0)),
            listOf(Ev(listOf(3, 7), 4.0)),
            listOf(Ev(listOf(0), 1.0), Ev(listOf(4), 1.0), Ev(listOf(7), 2.0)),
        )
        val outDir = System.getProperty("omr.debugDir")
        val img = render(rh, lh, file = outDir?.let { "$it/omr_simple.png" })
        val result = OmrEngine(OmrOptions()).recognize(listOf(img))
        overlay(img, result, outDir?.let { "$it/omr_simple_ov.png" })
        val song = result.song
        val notes = song.notes.sortedWith(compareBy({ it.start }, { it.pitch }))
        val desc = notes.joinToString { "${it.pitch}@${it.start}/${"%.2f".format(it.duration)}${it.hand.name[0]}" }
        println("warnings=${result.warnings} key=${result.keyFifths} ts=${result.beatsPerMeasure}/${result.beatUnit}\n$desc")
        assertEquals(4, song.beatsPerMeasure)
        assertEquals(0, result.keyFifths)

        val expected = listOf(
            // measure 1
            Triple(72, 0.0, Hand.RIGHT), Triple(76, 1.0, Hand.RIGHT), Triple(79, 2.0, Hand.RIGHT), Triple(48, 0.0, Hand.LEFT),
            // measure 2
            Triple(78, 4.0, Hand.RIGHT), Triple(79, 4.5, Hand.RIGHT), Triple(81, 5.0, Hand.RIGHT), Triple(72, 6.0, Hand.RIGHT),
            Triple(43, 4.0, Hand.LEFT), Triple(55, 6.0, Hand.LEFT),
            // measure 3
            Triple(60, 8.0, Hand.RIGHT), Triple(64, 8.0, Hand.RIGHT), Triple(67, 8.0, Hand.RIGHT), Triple(48, 8.0, Hand.LEFT), Triple(55, 8.0, Hand.LEFT),
            // measure 4
            Triple(71, 12.0, Hand.RIGHT), Triple(72, 15.0, Hand.RIGHT), Triple(43, 12.0, Hand.LEFT), Triple(50, 13.0, Hand.LEFT), Triple(55, 14.0, Hand.LEFT),
        )
        for ((p, t, hand) in expected) {
            assertTrue(notes.any { it.pitch == p && kotlin.math.abs(it.start - t) < 0.01 && it.hand == hand }, "missing $p@$t $hand in\n$desc")
        }
        assertEquals(expected.size, notes.size, desc)
        // durations
        assertEquals(2.0, notes.first { it.pitch == 79 && it.start == 2.0 }.duration / 0.95, 0.01)
        assertEquals(3.0, notes.first { it.pitch == 71 }.duration / 0.95, 0.01)
        assertEquals(0.5, notes.first { it.pitch == 78 }.duration / 0.95, 0.01)
    }

    @Test
    fun keySignatureAndMultipleSystems() {
        val rh = listOf(
            listOf(Ev(listOf(8), 1.0), Ev(listOf(7), 1.0), Ev(listOf(6), 1.0), Ev(listOf(5), 1.0)),
            listOf(Ev(listOf(1), 2.0), Ev(listOf(2), 2.0)),
            listOf(Ev(listOf(8), 4.0)),
            listOf(Ev(listOf(9), 4.0)),
        )
        val lh = listOf(
            listOf(Ev(listOf(7), 4.0)),
            listOf(Ev(listOf(6), 4.0)),
            listOf(Ev(listOf(0), 4.0)),
            listOf(Ev(listOf(0), 4.0)),
        )
        val outDir = System.getProperty("omr.debugDir")
        val img = render(rh, lh, keySharps = 1, systems = 2, file = outDir?.let { "$it/omr_key.png" })
        val result = OmrEngine(OmrOptions()).recognize(listOf(img))
        overlay(img, result, outDir?.let { "$it/omr_key_ov.png" })
        val notes = result.song.notes.sortedWith(compareBy({ it.start }, { it.pitch }))
        val desc = notes.joinToString { "${it.pitch}@${it.start}" }
        println("key=${result.keyFifths} warnings=${result.warnings}\n$desc")
        assertEquals(1, result.keyFifths)
        // F5 becomes F#5 (78), F4 becomes F#4 (66), F3 in the bass becomes F#3 (54)
        assertTrue(notes.any { it.pitch == 78 && it.start == 0.0 }, desc)
        assertTrue(notes.any { it.pitch == 66 && it.start == 4.0 }, desc)
        assertTrue(notes.any { it.pitch == 54 && it.start == 4.0 }, desc)
        assertTrue(notes.any { it.pitch == 78 && it.start == 8.0 }, desc)
        assertTrue(notes.any { it.pitch == 79 && it.start == 12.0 }, desc)
        assertTrue(notes.any { it.pitch == 43 && it.start == 12.0 }, desc)
        assertEquals(12, notes.size, desc)
        assertTrue(result.warnings.isEmpty(), result.warnings.toString())
    }
}
