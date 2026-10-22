package de.pianotiles.core

import de.pianotiles.core.engrave.Engraver
import de.pianotiles.core.hands.AutoFingering
import de.pianotiles.core.model.DemoSongs
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngraveTest {
    private val outDir = System.getProperty("engrave.out")?.let { File(it).apply { mkdirs() } }

    @Test
    fun demosEngraveWithAllMeasures() {
        for (demo in DemoSongs.all()) {
            val song = AutoFingering.apply(demo)
            val sheet = Engraver.engrave(song)
            outDir?.let { File(it, "${demo.id}.pdf").writeBytes(sheet.pdf) }
            assertTrue(String(sheet.pdf, 0, 8, Charsets.ISO_8859_1).startsWith("%PDF-1.4"))
            assertEquals((0 until song.measureCount).toList(), sheet.measures.map { it.measure }, demo.title)
            for (m in sheet.measures) assertTrue(m.x0 < m.x1 && m.y0 < m.y1 && m.x0 >= 0f && m.x1 <= 1f && m.y1 <= 1f, "$m")
            org.apache.pdfbox.Loader.loadPDF(sheet.pdf).use { assertEquals(sheet.pages, it.numberOfPages) }
        }
    }

    /**
     * Round trip: engrave → render → our own OMR → compare with the written notes (pitch, onset, hand).
     * Sources: the demos, ABC folk tunes and MEI chorales from the format test data.
     */
    @Test
    fun omrReadsTheEngravedSheet() {
        val songs = ArrayList<de.pianotiles.core.model.Song>()
        songs += DemoSongs.all()
        val formats = File(javaClass.getResource("/formats")!!.toURI())
        val only = System.getProperty("omr.only")
        for (d in formats.listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }) {
            val abc = File(d, "score.abc"); val mei = File(d, "score.mei")
            when {
                abc.exists() -> songs += de.pianotiles.core.io.AbcImporter.import(abc.readBytes(), d.name).copy(title = d.name)
                mei.exists() -> songs += de.pianotiles.core.io.MeiImporter.import(mei.readBytes(), d.name).copy(title = d.name)
            }
        }
        var sum = 0.0
        val rows = ArrayList<String>()
        for (song in songs) {
            if (only != null && !song.title.contains(only)) continue
            val sheet = Engraver.engrave(song, Engraver.Options(showFingering = false))
            outDir?.let { File(it, "rt_${song.title.replace(Regex("[^A-Za-z0-9_]"), "_")}.pdf").writeBytes(sheet.pdf) }
            val imgs = org.apache.pdfbox.Loader.loadPDF(sheet.pdf).use { doc ->
                val r = org.apache.pdfbox.rendering.PDFRenderer(doc)
                (0 until doc.numberOfPages).map { i ->
                    val img = r.renderImageWithDPI(i, 2000f / doc.getPage(i).mediaBox.width * 72f, org.apache.pdfbox.rendering.ImageType.GRAY)
                    val px = IntArray(img.width * img.height)
                    img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
                    de.pianotiles.core.omr.GrayImage.fromArgb(img.width, img.height, px)
                }
            }
            val got = de.pianotiles.core.omr.OmrEngine(de.pianotiles.core.omr.OmrOptions()).recognize(imgs).song
            fun keys(s: de.pianotiles.core.model.Song) = s.notes.map { Triple(it.pitch, Math.round(it.start * 12), it.hand) }
                .groupingBy { it }.eachCount()
            val want = keys(song); val have = keys(got)
            val hit = want.entries.sumOf { (k, c) -> minOf(c, have[k] ?: 0) }
            val recall = hit.toDouble() / want.values.sum().coerceAtLeast(1)
            val precision = hit.toDouble() / have.values.sum().coerceAtLeast(1)
            val f1 = if (hit == 0) 0.0 else 2 * recall * precision / (recall + precision)
            sum += f1
            if (System.getProperty("engrave.out") != null && f1 < 0.5) {
                // diagnostics: best constant time shift and pitch-only match
                val shifts = (-48..48).maxBy { sh -> want.keys.count { (p, t, h) -> have.containsKey(Triple(p, t + sh, h)) } }
                val pitchOnly = song.notes.map { it.pitch }.groupingBy { it }.eachCount()
                val gotP = got.notes.map { it.pitch }.groupingBy { it }.eachCount()
                val ph = pitchOnly.entries.sumOf { (k, c) -> minOf(c, gotP[k] ?: 0) }
                println("DIAG ${song.title}: best shift ${shifts / 12.0} beats, pitch-only ${ph * 100 / song.notes.size} %, pickup ${song.pickupBeats}->${got.pickupBeats}, meter ${song.beatsPerMeasure}/${song.beatUnit}->${got.beatsPerMeasure}/${got.beatUnit}, key ${song.keyFifths}->${got.keyFifths}, n ${song.notes.size}->${got.notes.size}")
            }
            rows += String.format(java.util.Locale.ROOT, "%-34s notes %5.1f %%  precision %5.1f %%  pages %d", song.title.take(34), recall * 100, precision * 100, sheet.pages)
        }
        val avg = sum / songs.count { only == null || it.title.contains(only) }
        println(rows.joinToString("\n"))
        println(String.format(java.util.Locale.ROOT, "ENGRAVE-OMR average F1 %.1f %% over %d pieces", avg * 100, songs.size))
        assertTrue(avg >= MIN_ROUND_TRIP, "round trip F1 $avg")
    }

    // measured 70.6 % (2026-10-22); the misses are mostly pick-up measures the OMR does not recognise
    private companion object { const val MIN_ROUND_TRIP = 0.66 }
}
