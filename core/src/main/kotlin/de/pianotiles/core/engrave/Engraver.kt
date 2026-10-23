package de.pianotiles.core.engrave

import de.pianotiles.core.io.KeyGuess
import de.pianotiles.core.io.MusicXmlExporter
import de.pianotiles.core.model.Hand
import de.pianotiles.core.model.SheetMeasure
import de.pianotiles.core.model.Song
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The engraved sheet: PDF bytes and where each measure is on which page (for highlighting and tapping). */
class EngravedSheet(val pdf: ByteArray, val measures: List<SheetMeasure>, val pages: Int)

/**
 * A small music engraver: sets a song as piano score (right hand on a treble staff, left hand on a bass staff,
 * clefs chosen by range) and writes it as PDF. It knows key and time signatures, note values with dots, ties,
 * triplets, rests, chords (seconds, accidental columns), two voices per staff, beams, flags, ledger lines and
 * fingering. Rhythm and voices come from the same quantisation as the MusicXML export.
 *
 * Coordinates: points on an A4 page, y down; `sp` = one staff space.
 */
object Engraver {
    /** Raised when the layout changes, so stored sheets are engraved again. */
    const val VERSION = 1

    /** Start of the key signature after the clef, and distance between its accidentals (staff spaces). */
    private const val KEY_X = 4.9f
    private const val KEY_STEP = 1.3f

    class Options(
        val staffSpace: Float = 6.5f,
        val showFingering: Boolean = true,
        val margin: Float = 42f,
    )

    fun engrave(song: Song, options: Options = Options()): EngravedSheet {
        val score = ScoreModel.build(song)
        return Layout(song, score, options).run()
    }

    // ------------------------------------------------------------------------------------------ layout

    private class Layout(val song: Song, val score: ScoreModel, val o: Options) {
        val sp = o.staffSpace
        val pdf = PdfCanvas()
        val glyphScale = sp / Glyphs.UNITS_PER_SPACE
        val key = score.key
        val sheetMeasures = ArrayList<SheetMeasure>()

        /** Width of every column gap: grows with the time to the next column. */
        fun gap(ticks: Int): Float = sp * (1.3f + 1.15f * (ln(ticks / 12.0 + 1.0) / ln(2.0)).toFloat())

        class MeasureLayout(val index: Int, val columns: IntArray, val offsets: FloatArray, val width: Float)

        fun measureLayout(m: Int): MeasureLayout {
            val len = score.measureTicks[m]
            val ticks = sortedSetOf<Int>()
            var accidentalTicks = HashSet<Int>()
            var dotted = HashSet<Int>()
            for (st in score.staves) for (ev in st.measures[m]) {
                ticks.add(ev.tick)
                if (ev.heads.any { it.accidental != null }) accidentalTicks.add(ev.tick)
                if (ev.value.dots > 0) dotted.add(ev.tick)
            }
            if (ticks.isEmpty()) ticks.add(0)
            val cols = ticks.toIntArray()
            val offs = FloatArray(cols.size)
            var x = sp * 1.2f
            for ((i, t) in cols.withIndex()) {
                if (t in accidentalTicks) x += sp * (if (i == 0) 0.6f else 1.0f)
                offs[i] = x
                val next = if (i + 1 < cols.size) cols[i + 1] else len
                x += max(gap(max(next - t, 3)), if (t in dotted) sp * 2.6f else 0f)
            }
            val allRests = score.staves.all { st -> st.measures[m].all { it.rest } }
            if (allRests) x = max(x, sp * 6f)
            return MeasureLayout(m, cols, offs, x + sp * 0.4f)
        }

        fun headerWidth(first: Boolean): Float {
            var w = KEY_X * sp + abs(key) * sp * KEY_STEP + (if (key != 0) sp * 0.8f else 0f)
            if (first) w += sp * 3.6f
            return w
        }

        fun run(): EngravedSheet {
            val layouts = (0 until score.measureCount).map { measureLayout(it) }
            val usable = pdf.width - 2 * o.margin - sp * 1.5f // room for the brace
            // greedy line breaking
            val systems = ArrayList<List<MeasureLayout>>()
            var current = ArrayList<MeasureLayout>()
            var used = headerWidth(true)
            for (ml in layouts) {
                if (current.isNotEmpty() && used + ml.width > usable) {
                    systems.add(current); current = ArrayList(); used = headerWidth(false)
                }
                current.add(ml); used += ml.width
            }
            if (current.isNotEmpty()) systems.add(current)

            var y = o.margin
            // title block
            y += 22f
            pdf.text(song.title, pdf.width / 2, y, 18f, PdfCanvas.Font.BOLD, align = 0)
            if (song.composer.isNotBlank()) { y += 16f; pdf.text(song.composer, pdf.width - o.margin, y, 10f, align = 1) }
            y += 14f
            // tempo
            pdf.glyph("E1D5", o.margin + sp * 1.5f, y + 2f, glyphScale * 0.9f)
            pdf.text("= ${song.bpm.roundToInt()}", o.margin + sp * 1.5f + sp * 1.3f, y + 2f, 9f)
            y += 6f
            var page = 0
            for ((si, sys) in systems.withIndex()) {
                val ext = extents(sys)
                val staffGap = if (score.staves.size == 2) max(sp * 6f, ext.lowBelow[0] + ext.highAbove[1] + sp * 1.5f) else 0f
                val above = max(sp * 2.5f, ext.highAbove[0]) + sp * 1f
                val staffHeights = sp * 4f * score.staves.size + staffGap
                val below = max(sp * 2.5f, ext.lowBelow[score.staves.size - 1]) + sp * 1f
                if (y + above + staffHeights + below > pdf.height - o.margin && si > 0) {
                    pdf.newPage(); page++; y = o.margin
                    pdf.text("${song.title} – ${page + 1}", pdf.width / 2, y, 8f, PdfCanvas.Font.ITALIC, align = 0)
                    y += 8f
                }
                val top = y + above
                val tops = FloatArray(score.staves.size) { i -> top + i * (sp * 4f + staffGap) }
                drawSystem(sys, si == 0, si == systems.lastIndex, tops, page, usable)
                y = top + staffHeights + below
            }
            return EngravedSheet(pdf.toBytes(song.title), sheetMeasures, pdf.pageCount)
        }

        class Extents(val highAbove: FloatArray, val lowBelow: FloatArray)

        /** How far notes reach above / below each staff in the system (for spacing). */
        fun extents(sys: List<MeasureLayout>): Extents {
            val above = FloatArray(score.staves.size)
            val below = FloatArray(score.staves.size)
            for ((si, st) in score.staves.withIndex()) for (ml in sys) for (ev in st.measures[ml.index]) {
                if (ev.rest) continue
                val fingers = if (o.showFingering) ev.heads.count { it.finger in 1..5 } * sp * 1.25f else 0f
                for (h in ev.heads) {
                    val pos = (h.diatonic - st.clef.bottomLine) / 2f // staff spaces above the bottom line
                    val stem = if (ev.value.type != "whole") sp * 3.5f else 0f
                    // stem direction is decided later: count it on both sides
                    above[si] = max(above[si], (pos - 4f) * sp + sp * 1.2f + stem + if (st.clef == Clef.TREBLE) fingers else 0f)
                    below[si] = max(below[si], -pos * sp + sp * 1.2f + stem + if (st.clef == Clef.BASS) fingers else 0f)
                }
            }
            return Extents(above, below)
        }

        fun drawSystem(sys: List<MeasureLayout>, first: Boolean, last: Boolean, tops: FloatArray, page: Int, usable: Float) {
            val x0 = o.margin + sp * 1.5f
            val header = headerWidth(first)
            val natural = sys.sumOf { it.width.toDouble() }.toFloat()
            val avail = usable - header
            val stretch = if (last && natural < avail * 0.75f) 1f else avail / natural
            val xEnd = x0 + header + natural * stretch
            val bottom = tops.last() + sp * 4f
            // staff lines
            for (t in tops) for (l in 0 until 5) pdf.line(x0, t + l * sp, xEnd, t + l * sp, sp * 0.13f)
            // brace and system line
            pdf.line(x0, tops.first(), x0, bottom, sp * 0.16f)
            if (tops.size > 1) {
                val h = bottom - tops.first()
                pdf.glyph("E000", x0 - sp * 1.2f, bottom, glyphScale, h / 1000f)
            }
            // clefs, key, time
            for ((si, st) in score.staves.withIndex()) {
                val t = tops[si]
                if (st.clef == Clef.TREBLE) pdf.glyph("E050", x0 + sp * 0.8f, t + sp * 3f, glyphScale)
                else pdf.glyph("E062", x0 + sp * 0.8f, t + sp * 1f, glyphScale)
                var kx = x0 + sp * KEY_X
                for (d in keyPositions(key, st.clef)) {
                    pdf.glyph(if (key > 0) "E262" else "E260", kx, staffY(t, st.clef, d), glyphScale)
                    kx += sp * KEY_STEP
                }
                if (first) {
                    val tx = x0 + sp * KEY_X + abs(key) * sp * KEY_STEP + (if (key != 0) sp * 0.8f else 0f)
                    timeDigits(song.beatsPerMeasure, tx, t + sp)
                    timeDigits(song.beatUnit, tx, t + sp * 3f)
                }
            }
            // measures
            var x = x0 + header
            for ((mi, ml) in sys.withIndex()) {
                val w = ml.width * stretch
                if (mi == 0) {
                    val number = if (song.pickupBeats > 0) ml.index else ml.index + 1
                    if (number > 1) pdf.text("$number", x0, tops.first() - sp * 1.6f, 7f, PdfCanvas.Font.ITALIC)
                }
                for ((si, st) in score.staves.withIndex()) drawMeasure(st, si, ml, x, stretch, tops[si], w)
                val bx = x + w
                val finalBar = last && mi == sys.lastIndex
                if (finalBar) {
                    pdf.line(bx - sp * 0.75f, tops.first(), bx - sp * 0.75f, bottom, sp * 0.16f)
                    pdf.fillRect(bx - sp * 0.5f, tops.first(), sp * 0.5f, bottom - tops.first())
                } else pdf.line(bx, tops.first(), bx, bottom, sp * 0.16f)
                sheetMeasures += SheetMeasure(
                    ml.index, page,
                    x / pdf.width, (tops.first() - sp * 2f) / pdf.height,
                    bx / pdf.width, (bottom + sp * 2f) / pdf.height,
                )
                x = bx
            }
        }

        fun timeDigits(n: Int, x: Float, y: Float) {
            val s = n.toString()
            val widths = s.map { Glyphs.advance("E08" + it) }
            var cx = x + (sp * 2f - widths.sum() * sp) / 2
            for ((i, c) in s.withIndex()) { pdf.glyph("E08$c", cx, y, glyphScale); cx += widths[i] * sp }
        }

        fun staffY(top: Float, clef: Clef, diatonic: Int) = top + sp * 4f - (diatonic - clef.bottomLine) * sp / 2f

        fun keyPositions(key: Int, clef: Clef): List<Int> {
            // treble: sharps F5 C5 G5 D5 A4 E5 B4, flats B4 E5 A4 D5 G4 C5 F4 (diatonic C0 = 0); bass two lines lower
            val sharps = intArrayOf(38, 35, 39, 36, 33, 37, 34)
            val flats = intArrayOf(34, 37, 33, 36, 32, 35, 31)
            val shift = if (clef == Clef.TREBLE) 0 else -14
            return if (key > 0) (0 until min(key, 7)).map { sharps[it] + shift }
            else (0 until min(-key, 7)).map { flats[it] + shift }
        }

        // -------------------------------------------------------------------------------------- notes

        fun drawMeasure(st: ScoreModel.Staff, si: Int, ml: MeasureLayout, mx: Float, stretch: Float, top: Float, width: Float) {
            val events = st.measures[ml.index]
            val clef = st.clef
            val middleY = top + sp * 2f
            fun colX(tick: Int): Float {
                val i = ml.columns.indexOf(tick)
                return mx + (if (i >= 0) ml.offsets[i] else sp) * stretch
            }
            if (events.isEmpty() || events.all { it.rest && it.fullMeasure }) {
                // whole-measure rest, centred
                val gw = Glyphs.advance("E4E3") * sp
                pdf.glyph("E4E3", mx + width / 2 - gw / 2, top + sp, glyphScale)
                return
            }
            val lanes = events.maxOf { it.lane } + 1
            // beams: groups of consecutive beamable notes per lane and beat
            val beamGroups = beamGroups(events)
            val inBeam = HashSet<ScoreModel.Event>()
            for (g in beamGroups) inBeam.addAll(g)
            // stem direction per group / event
            for (g in beamGroups) {
                val up = if (lanes > 1) g[0].lane == 0 else g.sumOf { e -> e.heads.sumOf { it.diatonic - clef.middle } } <= 0
                g.forEach { it.stemUp = up }
            }
            for (ev in events) if (ev !in inBeam && !ev.rest) {
                ev.stemUp = if (lanes > 1) ev.lane == 0 else {
                    val hi = ev.heads.maxOf { it.diatonic } - clef.middle
                    val lo = clef.middle - ev.heads.minOf { it.diatonic }
                    hi <= lo
                }
            }
            val headX = HashMap<ScoreModel.Event, Float>()
            for (ev in events) {
                val x = colX(ev.tick)
                headX[ev] = x
                if (ev.rest) { drawRest(ev, x, top, lanes); continue }
                drawChord(ev, x, top, clef, ev !in inBeam)
            }
            for (g in beamGroups) drawBeams(g, top, clef, middleY)
            if (o.showFingering) for (ev in events) if (!ev.rest) drawFingering(ev, headX.getValue(ev), top, clef)
            drawTuplets(events, inBeam, top, clef)
            // ties
            for (ev in events) if (!ev.rest) for (h in ev.heads) if (h.tieStart) {
                val x = headX.getValue(ev) + (h.shift + headWidth(ev)) + sp * 0.15f
                val partner = events.firstOrNull { e -> e.tick == ev.tick + ev.value.ticks && e.heads.any { it.pitch == h.pitch && it.tieStop } }
                val x2 = if (partner != null) headX.getValue(partner) - sp * 0.15f else mx + width + sp * 1.0f
                val y = staffY(top, clef, h.diatonic) + (if (ev.stemUp) sp * 0.55f else -sp * 0.55f)
                tie(x, x2, y, down = ev.stemUp)
            }
            // a tie arriving from the previous system: short arc before the first note
            if (ml.columns.isNotEmpty()) for (ev in events) if (!ev.rest && ev.tick == ml.columns[0]) for (h in ev.heads) {
                if (h.tieStop && ev.tick == 0 && mx < o.margin + headerWidth(false) + sp * 3f) {
                    val x2 = headX.getValue(ev) - sp * 0.15f
                    val y = staffY(top, clef, h.diatonic) + (if (ev.stemUp) sp * 0.55f else -sp * 0.55f)
                    tie(x2 - sp * 2f, x2, y, down = ev.stemUp)
                }
            }
        }

        fun headGlyph(ev: ScoreModel.Event) = when (ev.value.type) { "whole" -> "E0A2"; "half" -> "E0A3"; else -> "E0A4" }
        fun headWidth(ev: ScoreModel.Event) = Glyphs.advance(headGlyph(ev)) * sp

        fun drawChord(ev: ScoreModel.Event, x: Float, top: Float, clef: Clef, flag: Boolean) {
            val hw = headWidth(ev)
            val glyph = headGlyph(ev)
            val heads = ev.heads.sortedBy { it.diatonic }
            // seconds: the upper (stem up) / lower (stem down) head moves to the other side of the stem
            heads.forEach { it.shift = 0f }
            if (ev.stemUp) {
                for (i in 1 until heads.size) if (heads[i].diatonic - heads[i - 1].diatonic == 1 && heads[i - 1].shift == 0f) heads[i].shift = hw
            } else {
                for (i in heads.size - 2 downTo 0) if (heads[i + 1].diatonic - heads[i].diatonic == 1 && heads[i + 1].shift == 0f) heads[i].shift = -hw
            }
            // ledger lines
            val topLine = clef.bottomLine + 8
            for (h in heads) {
                val lx0 = x + h.shift - sp * 0.4f
                val lx1 = x + h.shift + hw + sp * 0.4f
                var d = topLine + 2
                while (d <= h.diatonic) { val ly = staffY(top, clef, d); pdf.line(lx0, ly, lx1, ly, sp * 0.16f); d += 2 }
                d = clef.bottomLine - 2
                while (d >= h.diatonic) { val ly = staffY(top, clef, d); pdf.line(lx0, ly, lx1, ly, sp * 0.16f); d -= 2 }
            }
            for (h in heads) pdf.glyph(glyph, x + h.shift, staffY(top, clef, h.diatonic), glyphScale)
            // accidentals in columns from the top down
            val placed = ArrayList<Pair<Int, Int>>() // diatonic, column
            val minShift = heads.minOf { it.shift }
            for (h in heads.sortedByDescending { it.diatonic }) {
                val acc = h.accidental ?: continue
                var col = 0
                while (placed.any { it.second == col && abs(it.first - h.diatonic) < 6 }) col++
                placed += h.diatonic to col
                val code = when (acc) { 1 -> "E262"; -1 -> "E260"; else -> "E261" }
                val ax = x + minShift - sp * 0.25f - (col + 1) * sp * 1.05f
                pdf.glyph(code, ax, staffY(top, clef, h.diatonic), glyphScale)
            }
            // dots
            if (ev.value.dots > 0) {
                val dx = x + heads.maxOf { it.shift } + hw + sp * 0.45f
                for (h in heads) {
                    val onLine = (h.diatonic - clef.bottomLine) % 2 == 0
                    val dy = staffY(top, clef, h.diatonic) - if (onLine) sp * 0.5f else 0f
                    pdf.glyph("E1E7", dx, dy, glyphScale)
                }
            }
            // stem and flag
            if (ev.value.type != "whole") {
                val lowY = staffY(top, clef, heads.first().diatonic)
                val highY = staffY(top, clef, heads.last().diatonic)
                val middleY = top + sp * 2f
                val stemX = if (ev.stemUp) x + hw - sp * 0.06f else x + sp * 0.06f
                ev.stemX = stemX
                if (ev.stemUp) {
                    ev.stemEnd = min(highY - sp * 3.5f, middleY)
                    ev.stemBase = lowY - sp * 0.15f
                } else {
                    ev.stemEnd = max(lowY + sp * 3.5f, middleY)
                    ev.stemBase = highY + sp * 0.15f
                }
                if (flag) {
                    val levels = ev.value.beams
                    if (levels > 0) {
                        val extra = (levels - 1) * sp * 0.6f
                        ev.stemEnd = if (ev.stemUp) ev.stemEnd - extra else ev.stemEnd + extra
                        val code = when (levels) { 1 -> if (ev.stemUp) "E240" else "E241"; 2 -> if (ev.stemUp) "E242" else "E243"; else -> if (ev.stemUp) "E244" else "E245" }
                        pdf.glyph(code, stemX - sp * 0.06f, ev.stemEnd, glyphScale)
                    }
                    pdf.line(stemX, ev.stemBase, stemX, ev.stemEnd, sp * 0.12f)
                }
            }
        }

        /** Finger numbers above (treble) or below (bass) the chord, clear of stems and beams. */
        fun drawFingering(ev: ScoreModel.Event, x: Float, top: Float, clef: Clef) {
            val hw = headWidth(ev)
            val heads = ev.heads.sortedBy { it.diatonic }
            run {
                val fingers = heads.filter { it.finger in 1..5 }
                if (fingers.isNotEmpty()) {
                    val above = clef == Clef.TREBLE
                    val list = if (above) fingers.sortedByDescending { it.diatonic } else fingers.sortedBy { it.diatonic }
                    var fy = if (above) min(staffY(top, clef, heads.last().diatonic) - sp * 1.3f, top - sp * 0.8f)
                    else max(staffY(top, clef, heads.first().diatonic) + sp * 2.1f, top + sp * 5.6f)
                    if (above && ev.stemUp && ev.value.type != "whole") fy = min(fy, ev.stemEnd - sp * 0.8f)
                    if (!above && !ev.stemUp && ev.value.type != "whole") fy = max(fy, ev.stemEnd + sp * 1.6f)
                    for (h in list) {
                        pdf.text("${h.finger}", x + hw / 2, fy, sp * 1.15f, PdfCanvas.Font.BOLD, align = 0)
                        fy += if (above) -sp * 1.2f else sp * 1.2f
                    }
                }
            }
        }

        fun drawRest(ev: ScoreModel.Event, x: Float, top: Float, lanes: Int) {
            val lift = if (lanes > 1) -sp * 2f else 0f
            val code = when (ev.value.type) {
                "whole" -> "E4E3"; "half" -> "E4E4"; "quarter" -> "E4E5"; "eighth" -> "E4E6"; "16th" -> "E4E7"; else -> "E4E8"
            }
            val y = when (code) { "E4E3" -> top + sp; else -> top + sp * 2f } + lift
            pdf.glyph(code, x, y, glyphScale)
            if (ev.value.dots > 0) pdf.glyph("E1E7", x + Glyphs.advance(code) * sp + sp * 0.4f, top + sp * 1.5f + lift, glyphScale)
        }

        fun beamGroups(events: List<ScoreModel.Event>): List<List<ScoreModel.Event>> {
            val compound = song.beatUnit == 8 && song.beatsPerMeasure % 3 == 0
            val beat = if (compound) 72 else 48
            val out = ArrayList<List<ScoreModel.Event>>()
            for (lane in events.map { it.lane }.distinct()) {
                var cur = ArrayList<ScoreModel.Event>()
                fun flush() { if (cur.size >= 2) out.add(cur); cur = ArrayList() }
                for (ev in events.filter { it.lane == lane }.sortedBy { it.tick }) {
                    val beamable = !ev.rest && ev.value.beams > 0
                    if (!beamable) { flush(); continue }
                    if (cur.isNotEmpty()) {
                        val prev = cur.last()
                        val sameBeat = prev.tick / beat == ev.tick / beat
                        val adjacent = prev.tick + prev.value.ticks == ev.tick
                        if (!sameBeat || !adjacent) flush()
                    }
                    cur.add(ev)
                }
                flush()
            }
            return out
        }

        fun drawBeams(g: List<ScoreModel.Event>, top: Float, clef: Clef, middleY: Float) {
            val up = g[0].stemUp
            val xs = g.map { it.stemX }
            val ends = g.map { it.stemEnd }
            val dx = (xs.last() - xs.first()).coerceAtLeast(1f)
            var slope = ((ends.last() - ends.first()) / dx).coerceIn(-sp / dx * 1.0f, sp / dx * 1.0f)
            if (g.size > 2) {
                // inner notes beyond the line between first and last: flatten
                val firstY = ends.first()
                if (g.indices.any { i -> val ly = firstY + slope * (xs[i] - xs.first()); if (up) ends[i] < ly - sp * 0.5f else ends[i] > ly + sp * 0.5f }) slope = 0f
            }
            // shift the line so that every stem is long enough
            var offset = 0f
            for (i in g.indices) {
                val ly = ends.first() + slope * (xs[i] - xs.first())
                offset = if (up) min(offset, ends[i] - ly) else max(offset, ends[i] - ly)
            }
            val maxLevels = g.maxOf { it.value.beams }
            if (maxLevels > 1) offset += if (up) -(maxLevels - 1) * sp * 0.4f else (maxLevels - 1) * sp * 0.4f
            fun lineY(x: Float) = ends.first() + offset + slope * (x - xs.first())
            val thick = sp * 0.5f
            val stemW = sp * 0.12f
            for ((i, ev) in g.withIndex()) {
                ev.stemEnd = lineY(xs[i])
                pdf.line(ev.stemX, ev.stemBase, ev.stemX, ev.stemEnd, stemW)
            }
            fun beam(x1: Float, x2: Float, level: Int) {
                val shift = level * sp * 0.75f * (if (up) 1f else -1f)
                val y1 = lineY(x1) + shift
                val y2 = lineY(x2) + shift
                val t = if (up) thick else -thick
                pdf.fillPolygon(x1, y1, x2, y2, x2, y2 + t, x1, y1 + t)
            }
            val half = stemW / 2
            beam(xs.first() - half, xs.last() + half, 0)
            for (level in 1 until maxLevels) {
                var i = 0
                while (i < g.size) {
                    if (g[i].value.beams <= level) { i++; continue }
                    var j = i
                    while (j + 1 < g.size && g[j + 1].value.beams > level) j++
                    if (j > i) beam(xs[i] - half, xs[j] + half, level)
                    else {
                        val stub = sp * 1.1f
                        if (i == g.lastIndex) beam(xs[i] - stub, xs[i] + half, level) else beam(xs[i] - half, xs[i] + stub, level)
                    }
                    i = j + 1
                }
            }
            if (g.any { it.value.triplet }) {
                val mx = (xs.first() + xs.last()) / 2
                val y = lineY(mx) + if (up) -sp * 1.4f else sp * 2.2f
                pdf.glyph("E883", mx - Glyphs.advance("E883") * sp / 2, y, glyphScale)
            }
        }

        /** "3" over unbeamed triplets (e.g. quarter triplets). */
        fun drawTuplets(events: List<ScoreModel.Event>, inBeam: Set<ScoreModel.Event>, top: Float, clef: Clef) {
            for (lane in events.map { it.lane }.distinct()) {
                val run = ArrayList<ScoreModel.Event>()
                fun flush() {
                    if (run.isNotEmpty()) {
                        val xs = run.map { it.stemX.takeIf { x -> x != 0f } ?: 0f }.filter { it != 0f }
                        if (xs.isNotEmpty()) {
                            val mx = (xs.min() + xs.max()) / 2
                            pdf.glyph("E883", mx - Glyphs.advance("E883") * sp / 2, top - sp * 1.5f, glyphScale)
                        }
                    }
                    run.clear()
                }
                for (ev in events.filter { it.lane == lane }.sortedBy { it.tick }) {
                    if (ev.value.triplet && ev !in inBeam) {
                        run.add(ev)
                        if (run.sumOf { it.value.ticks } >= run[0].value.ticks * 3) flush()
                    } else flush()
                }
                flush()
            }
        }

        fun tie(x1: Float, x2: Float, y: Float, down: Boolean) {
            if (x2 - x1 < sp * 0.5f) return
            val dir = if (down) 1f else -1f
            val depth = min(sp * 0.9f, sp * 0.35f + (x2 - x1) * 0.06f)
            val c1x = x1 + (x2 - x1) * 0.25f
            val c2x = x1 + (x2 - x1) * 0.75f
            pdf.fillLens(
                x1, y, c1x, y + dir * depth, c2x, y + dir * depth, x2, y,
                c2x, y + dir * (depth - sp * 0.18f), c1x, y + dir * (depth - sp * 0.18f),
            )
        }
    }

    // ------------------------------------------------------------------------------------------ model

    enum class Clef(val bottomLine: Int, val middle: Int) { TREBLE(30, 34), BASS(18, 22) }

    /** Measures × staves × events, quantised like the MusicXML export. */
    class ScoreModel(val key: Int, val measureCount: Int, val measureTicks: IntArray, val staves: List<Staff>) {

        class Head(val pitch: Int, val diatonic: Int, val accidental: Int?, val tieStart: Boolean, val tieStop: Boolean, val finger: Int) {
            var shift = 0f
        }

        class Event(val tick: Int, val value: Value, val heads: List<Head>, val rest: Boolean, val lane: Int, val fullMeasure: Boolean = false) {
            var stemUp = true
            var stemX = 0f
            var stemEnd = 0f
            var stemBase = 0f
        }

        class Value(val ticks: Int, val type: String, val dots: Int, val triplet: Boolean) {
            /** Number of beams / flags. */
            val beams: Int get() = when (type) { "eighth" -> 1; "16th" -> 2; "32nd" -> 3; "64th" -> 4; else -> 0 }
        }

        class Staff(val hand: Hand, val clef: Clef, val measures: List<List<Event>>)

        companion object {
            private const val D = MusicXmlExporter.DIVISIONS

            fun build(song: Song): ScoreModel {
                val key = song.keyFifths ?: KeyGuess.fifths(song.notes)
                val count = song.measureCount
                val bounds = (0..count).map { (song.measureStart(it) * D).roundToInt() }
                val measureTicks = IntArray(count) { bounds[it + 1] - bounds[it] }
                val hands = Hand.entries.filter { h -> song.notes.any { it.hand == h } }.ifEmpty { listOf(Hand.RIGHT) }
                    .sortedBy { if (it == Hand.RIGHT) 0 else 1 }
                val staves = hands.map { hand ->
                    val notes = notatedDurations(song.notes.filter { it.hand == hand })
                    val median = notes.map { it.pitch }.sorted().let { if (it.isEmpty()) 64 else it[it.size / 2] }
                    val clef = when {
                        hands.size == 2 -> if (hand == Hand.RIGHT) (if (median < 55) Clef.BASS else Clef.TREBLE) else (if (median >= 64) Clef.TREBLE else Clef.BASS)
                        else -> if (median >= 57) Clef.TREBLE else Clef.BASS
                    }
                    // pieces per measure (notes split at bar lines)
                    class Piece(val pitch: Int, val start: Int, val dur: Int, val finger: Int, val tieStop: Boolean, val tieStart: Boolean)
                    val pieces = Array(count) { ArrayList<Piece>() }
                    for (n in notes) {
                        var s = (n.start * D).roundToInt()
                        val e = max((n.end * D).roundToInt(), s + 3)
                        var first = true
                        while (s < e) {
                            var m = 0
                            while (m + 1 < count && bounds[m + 1] <= s) m++
                            val end = if (m == count - 1) e else min(e, bounds[m + 1])
                            pieces[m].add(Piece(n.pitch, s - bounds[m], end - s, if (first) n.finger else 0, !first, end < e))
                            first = false
                            s = end
                        }
                    }
                    val measures = (0 until count).map { m ->
                        val len = measureTicks[m]
                        val ps = pieces[m].distinctBy { Triple(it.pitch, it.start, it.dur) }
                        if (ps.isEmpty()) return@map listOf(Event(0, Value(len, "whole", 0, false), emptyList(), rest = true, lane = 0, fullMeasure = true))
                        val chords = ps.groupBy { it.start to it.dur }.values.sortedWith(compareBy({ it[0].start }, { -it[0].dur }))
                        val lanes = ArrayList<MutableList<List<Piece>>>()
                        for (c in chords) {
                            val lane = lanes.firstOrNull { l -> l.last()[0].let { it.start + it.dur } <= c[0].start }
                            if (lane != null) lane.add(c) else lanes.add(arrayListOf(c))
                        }
                        val events = ArrayList<Event>()
                        for ((li, lane) in lanes.withIndex()) {
                            var cursor = 0
                            fun rests(from: Int, to: Int) {
                                if (li > 0) return
                                var t = from
                                for (v in MusicXmlExporter.split(to - from)) { events += Event(t, v.toValue(), emptyList(), true, li); t += v.ticks }
                            }
                            for (chord in lane) {
                                val c0 = chord[0]
                                if (c0.start > cursor) rests(cursor, c0.start)
                                val parts = MusicXmlExporter.split(c0.dur)
                                var t = c0.start
                                for ((pi, part) in parts.withIndex()) {
                                    val heads = chord.sortedBy { it.pitch }.map { p ->
                                        Head(p.pitch, diatonic(p.pitch, key), null, pi < parts.lastIndex || p.tieStart, pi > 0 || p.tieStop, if (pi == 0) p.finger else 0)
                                    }
                                    events += Event(t, part.toValue(), heads, false, li)
                                    t += part.ticks
                                }
                                cursor = c0.start + c0.dur
                            }
                            if (cursor < len) rests(cursor, len)
                        }
                        // accidentals in time order
                        val acc = Accidentals(key)
                        val ordered = events.sortedWith(compareBy({ it.tick }, { it.lane }))
                        val withAcc = ordered.map { ev ->
                            if (ev.rest) ev else Event(ev.tick, ev.value, ev.heads.map { h ->
                                Head(h.pitch, h.diatonic, if (h.tieStop) acc.markSilently(h.pitch) else acc.mark(h.pitch), h.tieStart, h.tieStop, h.finger)
                            }, false, ev.lane)
                        }
                        withAcc
                    }
                    Staff(hand, clef, measures)
                }
                return ScoreModel(key, count, measureTicks, staves)
            }

            /**
             * Played durations → written durations: a note that ends a little before the next onset of the same hand
             * (staccato-ish playing, MIDI, OMR) is written up to that onset; other ends snap to the 16th or
             * triplet-8th grid. Without this a 0.95-beat note would be printed as dotted values plus tiny rests.
             */
            fun notatedDurations(notes: List<de.pianotiles.core.model.Note>): List<de.pianotiles.core.model.Note> {
                val onsets = notes.map { it.start }.distinct().sorted()
                return notes.map { n ->
                    val tol = max(0.26 * n.duration, 0.13)
                    val next = onsets.firstOrNull { it > n.start + 1e-6 }
                    val beat = kotlin.math.ceil(n.end - 1e-9)
                    val end = when {
                        next != null && n.end <= next + 1e-9 && next - n.end <= tol -> next
                        (next == null || beat <= next + 1e-9) && beat - n.end <= tol -> beat
                        else -> {
                            val g16 = Math.round(n.end * 4) / 4.0
                            val g12 = Math.round(n.end * 3) / 3.0
                            if (abs(g16 - n.end) <= abs(g12 - n.end)) g16 else g12
                        }
                    }
                    if (end > n.start + 1e-6) n.copy(duration = end - n.start) else n
                }
            }

            private fun MusicXmlExporter.NoteValue.toValue() = Value(ticks, type, dots, triplet)

            private val LETTER = mapOf("C" to 0, "D" to 1, "E" to 2, "F" to 3, "G" to 4, "A" to 5, "B" to 6)

            fun diatonic(pitch: Int, key: Int): Int {
                val (step, _, octave) = MusicXmlExporter.spell(pitch, key)
                return octave * 7 + LETTER.getValue(step)
            }
        }

        /** Accidentals within a measure: only those that differ from key signature or an earlier note. */
        private class Accidentals(private val key: Int) {
            private val current = HashMap<String, Int>()
            private val sharpOrder = "FCGDAEB"
            private fun keyAlter(step: String): Int = when {
                key > 0 && sharpOrder.indexOf(step) < key -> 1
                key < 0 && sharpOrder.reversed().indexOf(step) < -key -> -1
                else -> 0
            }
            fun mark(pitch: Int): Int? {
                val (step, alter, octave) = MusicXmlExporter.spell(pitch, key)
                val id = "$step$octave"
                val before = current[id] ?: keyAlter(step)
                current[id] = alter
                return if (before == alter) null else alter
            }
            fun markSilently(pitch: Int): Int? { mark(pitch); return null }
        }
    }
}
