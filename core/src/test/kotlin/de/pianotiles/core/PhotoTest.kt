package de.pianotiles.core

import de.pianotiles.core.omr.GrayImage
import de.pianotiles.core.omr.OmrEngine
import de.pianotiles.core.omr.OmrOptions
import de.pianotiles.core.omr.PhotoPrep
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * "Noten abfotografieren": corpus pages are turned into fake photos (smaller, on a dark table, rotated,
 * shadowed, low contrast, noisy, slightly blurred). After [PhotoPrep] the recognition must be almost as good
 * as on the clean page.
 */
class PhotoTest {
    private val NOISE = (System.getProperty("photo.noise") ?: "10").toInt()
    private fun render(pdf: File, page: Int = 0): GrayImage = org.apache.pdfbox.Loader.loadPDF(pdf).use { doc ->
        val img = org.apache.pdfbox.rendering.PDFRenderer(doc)
            .renderImageWithDPI(page, 2000f / doc.getPage(page).mediaBox.width * 72f, org.apache.pdfbox.rendering.ImageType.GRAY)
        val px = IntArray(img.width * img.height)
        img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
        GrayImage.fromArgb(img.width, img.height, px)
    }

    /** Simulated phone photo of [page]. */
    private fun fakePhoto(page: GrayImage, angleDeg: Double, seed: Int): GrayImage {
        val rnd = Random(seed)
        val scale = 1.25
        val pw = (page.width * scale).roundToInt(); val ph = (page.height * scale).roundToInt()
        val margin = pw / 10
        val w = pw + 2 * margin; val h = ph + 2 * margin
        val out = GrayImage(w, h)
        val a = Math.toRadians(angleDeg); val c = cos(a); val s = sin(a)
        val cx = w / 2.0; val cy = h / 2.0
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x - cx; val dy = y - cy
            val px = (c * dx + s * dy + cx - margin) / scale
            val py = (-s * dx + c * dy + cy - margin) / scale
            val paper = px >= 0 && py >= 0 && px < page.width - 1 && py < page.height - 1
            var v = if (paper) {
                val x0 = px.toInt(); val y0 = py.toInt(); val ax = px - x0; val ay = py - y0
                val g = (page[x0, y0] * (1 - ax) + page[x0 + 1, y0] * ax) * (1 - ay) + (page[x0, y0 + 1] * (1 - ax) + page[x0 + 1, y0 + 1] * ax) * ay
                g * 0.82 + 30 // low contrast paper
            } else 55.0 // dark table
            val light = 0.55 + 0.45 * (x.toDouble() / w) * (0.6 + 0.4 * y.toDouble() / h)   // shadow from the left/top
            v = v * light + rnd.nextInt(-NOISE, NOISE + 1)
            out[x, y] = v.roundToInt()
        }
        // slight blur (3x3 box)
        val blurred = GrayImage(w, h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            var sum = 0
            for (yy in -1..1) for (xx in -1..1) sum += out[x + xx, y + yy]
            blurred[x, y] = sum / 9
        }
        return blurred
    }

    /**
     * Agreement of two recognitions: longest common subsequence of the notes ordered by (onset, pitch), as in
     * the corpus evaluation – a single rhythm error does not count as "all following notes wrong".
     */
    private fun f1(a: List<Pair<Int, Double>>, b: List<Pair<Int, Double>>): Double {
        val x = a.sortedWith(compareBy({ it.second }, { it.first })).map { it.first }
        val y = b.sortedWith(compareBy({ it.second }, { it.first })).map { it.first }
        val dp = Array(x.size + 1) { IntArray(y.size + 1) }
        for (i in x.indices.reversed()) for (j in y.indices.reversed())
            dp[i][j] = if (x[i] == y[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        return 2.0 * dp[0][0] / (x.size + y.size).coerceAtLeast(1)
    }

    @Test
    fun skewEstimateFindsTheAngle() {
        val page = render(File(javaClass.getResource("/corpus/mozart_k545/lilypond.pdf")!!.toURI()))
        for (angle in listOf(-4.0, 2.5, 6.0)) {
            val photo = fakePhoto(page, angle, 1)
            val est = PhotoPrep.estimateSkew(PhotoPrep.normalizeLighting(PhotoPrep.downscale(photo, PhotoPrep.MAX_SIDE)))
            assertTrue(abs(est - angle) < 0.3, "angle $angle estimated as $est")
        }
    }

    @Test
    fun photosAreRecognisedAlmostLikeThePdf() {
        val report = StringBuilder()
        var worst = 1.0
        for ((piece, eng) in listOf("mozart_k545" to "lilypond", "folk_essen" to "verovio", "bach_bwv269" to "lilypond")) {
            val page = render(File(javaClass.getResource("/corpus/$piece/$eng.pdf")!!.toURI()))
            val clean = OmrEngine(OmrOptions()).recognize(listOf(page)).song.notes.map { it.pitch to it.start }
            val photo = fakePhoto(page, (System.getProperty("photo.angle") ?: "").toDoubleOrNull() ?: if (piece == "folk_essen") -3.0 else 4.0, piece.length)
            val prepared = PhotoPrep.prepare(photo)
            System.getProperty("omr.debugDir")?.let { dbg ->
                val img = prepared.image
                val bi = java.awt.image.BufferedImage(img.width, img.height, java.awt.image.BufferedImage.TYPE_BYTE_GRAY)
                for (y in 0 until img.height) for (x in 0 until img.width) bi.raster.setSample(x, y, 0, img[x, y])
                javax.imageio.ImageIO.write(bi, "png", File(dbg, "photo_$piece.png"))
            }
            val got = OmrEngine(OmrOptions()).recognize(listOf(prepared.image)).song.notes.map { it.pitch to it.start }
            val q = f1(clean, got)
            if (System.getProperty("omr.debugDir") != null) {
                println("CLEAN $piece " + clean.sortedBy { it.second }.take(24).joinToString { "${it.first}@${it.second}" })
                println("PHOTO $piece " + got.sortedBy { it.second }.take(24).joinToString { "${it.first}@${it.second}" })
            }
            worst = minOf(worst, q)
            report.append("%-14s angle %.2f space %.1f scale %.2f  %d/%d notes  agreement %.1f %%\n".format(
                piece, prepared.angleDegrees, prepared.staffSpace ?: 0.0, prepared.scale, got.size, clean.size, q * 100))
        }
        println(report)
        assertTrue(worst >= 0.85, "photo recognition too far from the PDF:\n$report")
    }
}
