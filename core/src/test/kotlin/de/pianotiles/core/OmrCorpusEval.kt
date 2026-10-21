package de.pianotiles.core

import de.pianotiles.core.omr.GrayImage
import de.pianotiles.core.omr.OmrEngine
import de.pianotiles.core.omr.OmrOptions
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Evaluates the OMR on a corpus of public-domain scores engraved by two different programs
 * (LilyPond and Verovio, see docs/OMR.md "Testkorpus"). Each sub-folder contains truth.txt
 * ("midi start duration hand" per line) and <engraver>.pdf (or page images <engraver>-<n>.png).
 * By default the bundled corpus in src/test/resources/corpus is used; -Pomr.corpus=<dir> overrides it.
 * Fails when the average quality drops below the documented level (regression guard).
 */
class OmrCorpusEval {
    /** Renders all pages at 2000 px width, like the app does with PdfRenderer. */
    private fun renderPdf(f: File): List<java.awt.image.BufferedImage> =
        org.apache.pdfbox.Loader.loadPDF(f).use { doc ->
            val r = org.apache.pdfbox.rendering.PDFRenderer(doc)
            (0 until doc.numberOfPages).map { i ->
                val widthPt = doc.getPage(i).mediaBox.width
                r.renderImageWithDPI(i, 2000f / widthPt * 72f, org.apache.pdfbox.rendering.ImageType.GRAY)
            }
        }

    data class T(val pitch: Int, val start: Double, val hand: Char)

    @Test
    fun evaluateCorpus() {
        val external = System.getProperty("omr.corpus")
        val dir = external ?: File(javaClass.getResource("/corpus")!!.toURI()).path
        val only = System.getProperty("omr.only")
        val rows = ArrayList<String>()
        var sumR = 0.0; var sumP = 0.0; var sumPitch = 0.0; var n = 0
        val errors = java.util.TreeMap<String, Int>()
        for (piece in File(dir).listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }) {
            if (only != null && !piece.name.contains(only)) continue
            val truth = File(piece, "truth.txt").readLines().filter { it.isNotBlank() }.map {
                val p = it.split(" "); T(p[0].toInt(), p[1].toDouble(), p[3][0])
            }
            for (eng in listOf("lilypond", "verovio")) {
                val pdf = File(piece, "$eng.pdf")
                val pngs = piece.listFiles()!!.filter { it.name.startsWith("$eng-") && it.name.endsWith(".png") }
                    .sortedBy { it.name.substringAfterLast('-').substringBefore('.').toInt() }
                val buffered: List<java.awt.image.BufferedImage> = when {
                    pngs.isNotEmpty() -> pngs.map { ImageIO.read(it) }
                    pdf.exists() -> renderPdf(pdf)
                    else -> continue
                }
                val imgs = buffered.map { img ->
                    val px = IntArray(img.width * img.height)
                    img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
                    GrayImage.fromArgb(img.width, img.height, px)
                }
                val t0 = System.currentTimeMillis()
                val result = try { OmrEngine(OmrOptions()).recognize(imgs) } catch (e: Exception) {
                    rows.add(String.format("%-24s %-9s CRASH %s", piece.name, eng, e)); e.printStackTrace(); continue
                }
                val ms = System.currentTimeMillis() - t0
                System.getProperty("omr.debugDir")?.let { dbg ->
                    result.pages.forEachIndexed { pi, page ->
                        val img = imgs[pi]
                        val out = java.awt.image.BufferedImage(img.width, img.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
                        for (y in 0 until img.height) for (x in 0 until img.width) { val v = img[x, y]; out.setRGB(x, y, (v shl 16) or (v shl 8) or v) }
                        val g = out.createGraphics()
                        g.font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 11)
                        g.color = java.awt.Color.BLUE
                        for ((x, r) in page.barlines) g.drawRect(x - 3, r.first, 6, r.last - r.first)
                        for (st in page.staves) { g.color = java.awt.Color(0, 160, 0); g.drawRect(st.xStart, st.top.toInt(), st.xEnd - st.xStart, (st.bottom - st.top).toInt()) }
                        for (h in page.heads) {
                            g.color = if (h.hollow) java.awt.Color.MAGENTA else java.awt.Color.RED
                            g.drawRect(h.x0, h.y0, h.x1 - h.x0, h.y1 - h.y0)
                            g.drawString("${h.pitch}", h.x0, h.y1 + 12)
                            g.drawString("${h.onset}", h.x0, h.y1 + 23)
                        }
                        g.dispose()
                        ImageIO.write(out, "png", File(dbg, "${piece.name}_${eng}_${pi + 1}.png"))
                    }
                    val tSorted = truth.sortedWith(compareBy<T>({ it.start }, { it.pitch })).take(40)
                    val gSorted = result.song.notes.sortedWith(compareBy<de.pianotiles.core.model.Note>({ it.start }, { it.pitch })).take(40)
                    println("TRUTH ${piece.name}: " + tSorted.joinToString { "${it.pitch}@${it.start}" })
                    println("GOT   ${piece.name}: " + gSorted.joinToString { "${it.pitch}@${it.start}" })
                }
                val got = result.song.notes
                // Sequence alignment (LCS) of all notes ordered by (onset, pitch). Robust against a shifted
                // start (pick-up handling) or a single missing measure; then rhythm = equal onset distances
                // between consecutive matched notes.
                val tl = truth.map { it.pitch to it.start }.distinct().sortedWith(compareBy({ it.second }, { it.first }))
                val gl = got.map { it.pitch to it.start }.distinct().sortedWith(compareBy({ it.second }, { it.first }))
                val dp = Array(tl.size + 1) { IntArray(gl.size + 1) }
                for (i in tl.indices.reversed()) for (j in gl.indices.reversed())
                    dp[i][j] = if (tl[i].first == gl[j].first) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
                val pairs = ArrayList<Pair<Int, Int>>()
                var i = 0; var j = 0
                while (i < tl.size && j < gl.size) {
                    if (tl[i].first == gl[j].first) { pairs.add(i to j); i++; j++ }
                    else if (dp[i + 1][j] >= dp[i][j + 1]) i++ else j++
                }
                var rhythmOk = 0
                for (k in 1 until pairs.size) {
                    val dt = tl[pairs[k].first].second - tl[pairs[k - 1].first].second
                    val dg = gl[pairs[k].second].second - gl[pairs[k - 1].second].second
                    if (abs(dt - dg) < 0.05) rhythmOk++
                }
                if (System.getProperty("omr.debugDir") != null) {
                    // first places where pitch order or rhythm diverge
                    var shown = 0
                    for (k in 1 until pairs.size) {
                        val (ti, gi) = pairs[k]; val (pti, pgi) = pairs[k - 1]
                        val dt = tl[ti].second - tl[pti].second; val dg = gl[gi].second - gl[pgi].second
                        if (abs(dt - dg) >= 0.05 || ti - pti != 1 || gi - pgi != 1) {
                            println("DIFF $eng truth[${tl.subList(maxOf(0, pti - 2), minOf(tl.size, ti + 3)).joinToString { "${it.first}@${it.second}" }}]  got[${gl.subList(maxOf(0, pgi - 2), minOf(gl.size, gi + 3)).joinToString { "${it.first}@${it.second}" }}]")
                            if (++shown >= 6) break
                        }
                    }
                }
                // error categories (by absolute time)
                run {
                    val matched = pairs.map { it.first }.toHashSet()
                    for ((k, t) in tl.withIndex()) {
                        if (k in matched) continue
                        val same = gl.filter { abs(it.second - t.second) < 0.05 }
                        val d = same.map { it.first - t.first }
                        val cat = when {
                            same.isEmpty() -> "fehlt"
                            d.any { abs(it) == 1 } -> "vorzeichen"
                            d.any { abs(it) == 12 } -> "oktave"
                            d.any { abs(it) in 2..4 } -> "linie"
                            else -> "anders"
                        }
                        errors[cat] = (errors[cat] ?: 0) + 1
                    }
                    val matchedG = pairs.map { it.second }.toHashSet()
                    errors["extra"] = (errors["extra"] ?: 0) + gl.indices.count { it !in matchedG && tl.none { t -> abs(t.second - gl[it].second) < 0.05 } }
                }
                val r = pairs.size.toDouble() / tl.size          // notes found in the right order
                val p = pairs.size.toDouble() / gl.size.coerceAtLeast(1)
                val pr = if (pairs.size > 1) rhythmOk.toDouble() / (pairs.size - 1) else 0.0 // rhythm
                sumR += r; sumP += p; sumPitch += pr; n++
                rows.add(String.format("%-24s %-9s pages=%d truth=%4d got=%4d  noten=%5.1f%%  präzision=%5.1f%%  rhythmus=%5.1f%%  key=%d ts=%d/%d  %4dms  %s",
                    piece.name, eng, imgs.size, truth.size, got.size, r * 100, p * 100, pr * 100, result.keyFifths, result.beatsPerMeasure, result.beatUnit, ms,
                    result.warnings.joinToString("; ").take(120)))
            }
        }
        println("==== OMR CORPUS ====")
        println("FEHLERARTEN $errors")
        rows.forEach { println(it) }
        if (external == null && only == null) {
            assertTrue(sumR / n >= 0.78, "Notenerkennung gesunken: ${sumR / n}")
            assertTrue(sumPitch / n >= 0.82, "Rhythmuserkennung gesunken: ${sumPitch / n}")
        }
        println(String.format("DURCHSCHNITT über %d: noten=%.1f%% präzision=%.1f%% rhythmus=%.1f%%", n, sumR / n * 100, sumP / n * 100, sumPitch / n * 100))
    }
}
