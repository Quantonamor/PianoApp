package de.pianotiles.core

import de.pianotiles.core.io.AbcImporter
import de.pianotiles.core.io.MeiImporter
import de.pianotiles.core.io.MuseScoreImporter
import de.pianotiles.core.io.MusicXmlExporter
import de.pianotiles.core.io.MusicXmlImporter
import de.pianotiles.core.model.Song
import java.io.File
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Compares the importers with music21 (truth files made by tools/formats/gen.py): every folder holds
 * `score.abc|mei|mscx|musicxml` + `truth.txt` ("pitch start duration" per note, repeats unfolded).
 * Runs on the small set in `src/test/resources/formats`, or on `-Pformats.dir=<dir>`.
 * Each piece must match ≥ 98 % of the notes. The MusicXML export is checked by re-importing it.
 */
class FormatCrossCheck {
    private fun key(p: Int, s: Double, d: Double) = Triple(p, (s * 48).roundToInt(), (d * 48).roundToInt())

    @Test
    fun importersMatchMusic21() {
        val dir = System.getProperty("formats.dir")?.let(::File)
            ?: File(javaClass.classLoader.getResource("formats")?.toURI() ?: return)
        val report = StringBuilder()
        var failed = 0
        for (d in dir.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }) {
            val truthFile = File(d, "truth.txt").takeIf { it.exists() } ?: continue
            val score = d.listFiles().orEmpty().firstOrNull { it.name.startsWith("score.") } ?: continue
            val result = runCatching {
                when (score.extension) {
                    "abc" -> AbcImporter.import(score.readBytes(), d.name)
                    "mei" -> MeiImporter.import(score.readBytes(), d.name)
                    "mscx", "mscz" -> MuseScoreImporter.import(score.readBytes(), d.name)
                    else -> MusicXmlImporter.import(score.readBytes(), d.name)
                }
            }
            val song: Song = result.getOrNull() ?: run {
                report.append("${d.name}: ERROR ${result.exceptionOrNull()?.message}\n"); failed++; null
            } ?: continue
            val truth = truthFile.readLines().filter { it.isNotBlank() }.map { l -> l.trim().split(' ').let { key(it[0].toInt(), it[1].toDouble(), it[2].toDouble()) } }
            val got = song.notes.map { key(it.pitch, it.start, it.duration) }
            val score1 = match(truth, got)
            val back = MusicXmlImporter.import(MusicXmlExporter.export(song).toByteArray(), "x").notes.map { key(it.pitch, it.start, it.duration) }
            val score2 = match(got, back)
            val ok = score1 >= 0.98 && score2 >= 0.98
            if (!ok) failed++
            if (System.getProperty("formats.diff") == d.name) {
                val (lost, extra) = diff(if (score1 < 0.98) truth else got, if (score1 < 0.98) got else back)
                report.append("  missing: ${lost.take(15)}\n  extra:   ${extra.take(15)}\n")
            }
            report.append("%-40s %5d notes  import %5.1f %%  export %5.1f %% %s\n".format(d.name, truth.size, score1 * 100, score2 * 100, if (ok) "" else "<-- FAIL"))
        }
        println(report)
        assertTrue(failed == 0, "format cross-check failed:\n$report")
    }

    private fun diff(a: List<Triple<Int, Int, Int>>, b: List<Triple<Int, Int, Int>>): Pair<List<Triple<Int, Int, Int>>, List<Triple<Int, Int, Int>>> {
        val rest = b.toMutableList()
        val lost = a.filter { !rest.remove(it) }
        return lost.sortedBy { it.second } to rest.sortedBy { it.second }
    }

    /** F1 of the two note multisets. */
    private fun match(a: List<Triple<Int, Int, Int>>, b: List<Triple<Int, Int, Int>>): Double {
        val counts = HashMap<Triple<Int, Int, Int>, Int>()
        a.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        var hit = 0
        b.forEach { k -> val c = counts[k] ?: 0; if (c > 0) { hit++; counts[k] = c - 1 } }
        if (a.isEmpty() && b.isEmpty()) return 1.0
        return 2.0 * hit / (a.size + b.size)
    }
}
