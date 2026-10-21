package de.pianotiles.core.omr

import de.pianotiles.core.model.Hand
import de.pianotiles.core.model.Note
import de.pianotiles.core.model.Pitch
import de.pianotiles.core.model.Song
import de.pianotiles.core.model.SongSource
import de.pianotiles.core.model.SongUtils
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class StaffLayout { AUTO, PIANO, SINGLE_TREBLE, SINGLE_BASS }
enum class Clef { TREBLE, BASS }

data class OmrOptions(
    val layout: StaffLayout = StaffLayout.AUTO,
    /** null = detect from the key signature. */
    val keyFifths: Int? = null,
    /** null = estimate from the measure contents. */
    val beatsPerMeasure: Int? = null,
    val beatUnit: Int = 4,
    val bpm: Double = 90.0,
    val title: String = "PDF-Import",
)

/** A recognised notehead, in page pixel coordinates (for the overlay in the review screen). */
data class DetectedHead(
    val page: Int,
    val x0: Int, val y0: Int, val x1: Int, val y1: Int,
    val pitch: Int,
    val hollow: Boolean,
    val duration: Double,
    val clef: Clef,
    val accidental: Int?,
    val measure: Int,
    val onset: Double,
)

data class PageDiagnostics(
    val page: Int,
    val width: Int,
    val height: Int,
    val staves: List<Staff>,
    val barlines: List<Pair<Int, IntRange>>,
    val heads: List<DetectedHead>,
)

data class OmrResult(
    val song: Song,
    val pages: List<PageDiagnostics>,
    val warnings: List<String>,
    val keyFifths: Int,
    val beatsPerMeasure: Int,
    val beatUnit: Int,
    /** Measures whose rhythm had to be estimated from horizontal positions. */
    val estimatedMeasures: List<Int>,
)

/**
 * Optical music recognition for clean, printed piano scores.
 *
 * Pipeline per page: binarise → find staves → group into systems (grand staff) → remove staff lines →
 * fill note-head holes → morphological opening → note-head blobs → stems, beams/flags, dots,
 * accidentals, bar lines → per measure rhythm → pitches (clef + key signature + accidentals).
 * See docs/OMR.md for a detailed description and the known limitations.
 */
class OmrEngine(private val options: OmrOptions = OmrOptions()) {

    /** Optional hook to inspect intermediate images (tests/debugging). */
    var debugSink: ((name: String, image: BitImage) -> Unit)? = null
    private var staffCounter = 0

    private class Head(
        val page: Int,
        var x0: Int, var y0: Int, var x1: Int, var y1: Int,
        val cx: Double, val cy: Double,
        val hollow: Boolean,
        /** Vertical extent of the blob the head was found in (chords share one blob). */
        val blobTop: Int = y0,
        val blobBottom: Int = y1,
        val blobId: Int = -1,
    ) {
        var stemX: Int = -1
        var stemUp = false
        var stemEnd = 0
        var beams = 0
        var dotted = false
        var accidental: Int? = null
        var step = 0
        val hasStem get() = stemX >= 0
        var measure = 0
        var onset = 0.0
        var pitch = 0
        var duration = 1.0
        val baseDuration: Double
            get() {
                val d = when {
                    hollow && !hasStem -> 4.0
                    hollow -> 2.0
                    hasStem -> 1.0 / (1 shl beams.coerceIn(0, 3))
                    else -> 1.0
                }
                return if (dotted) d * 1.5 else d
            }
    }

    private class StaffData(
        val page: Int,
        val staff: Staff,
        /** Clef assumed when none is detected. */
        val clef: Clef,
        /** Hand by position in the system: upper staff = right hand, lower staff = left hand. */
        val hand: Hand,
        val heads: MutableList<Head> = ArrayList(),
        val barlines: MutableList<IntRange> = ArrayList(),
        val stemXs: MutableList<Int> = ArrayList(),
        val strokes: MutableList<Stroke> = ArrayList(),
        /** Page-coordinate boxes (x0, y0, x1, y1) of time signatures. */
        val timeSigs: MutableList<IntArray> = ArrayList(),
        val rests: MutableList<Rest> = ArrayList(),
        /** Key signature read at the start of this staff (null = could not tell). */
        var keyGuess: Int? = null,
        /** Detected clefs (x position → clef), sorted by x. */
        val clefs: MutableList<Pair<Double, Clef>> = ArrayList(),
    ) {
        fun clefAt(x: Double): Clef = clefs.lastOrNull { it.first <= x }?.second ?: clefs.firstOrNull()?.second ?: clef
    }

    private data class Rest(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val duration: Double) {
        val cx get() = (x0 + x1) / 2.0
    }

    private data class Stroke(val x: Double, val top: Int, val bottom: Int, val width: Int) {
        val length get() = bottom - top + 1
        val center get() = (top + bottom) / 2.0
    }

    private class SystemData(val staves: List<StaffData>)

    private val warnings = ArrayList<String>()
    private var firstKey: Int? = null

    fun recognize(pages: List<GrayImage>, progress: (Int, Int) -> Unit = { _, _ -> }): OmrResult {
        warnings.clear()
        firstKey = null
        val systems = ArrayList<SystemData>()
        val diagnostics = ArrayList<PageDiagnostics>()
        val pageStaves = ArrayList<List<StaffData>>()

        for ((pageIdx, gray) in pages.withIndex()) {
            progress(pageIdx, pages.size)
            val bit = BitImage.fromGray(gray)
            val staves = StaffDetector.detect(bit)
            if (staves.isEmpty()) {
                warnings.add("Seite ${pageIdx + 1}: keine Notenlinien gefunden")
                pageStaves.add(emptyList())
                continue
            }
            val grouped = groupSystems(bit, staves)
            val noStaff = removeStaffLines(bit, staves)
            currentNoStaff = noStaff
            val all = ArrayList<StaffData>()
            for (sys in grouped) {
                val sds = sys.mapIndexed { i, (st, clef) ->
                    val hand = when {
                        sys.size >= 2 -> if (i == 0) Hand.RIGHT else Hand.LEFT
                        options.layout == StaffLayout.SINGLE_BASS -> Hand.LEFT
                        else -> Hand.RIGHT
                    }
                    StaffData(pageIdx, st, clef, hand)
                }
                for (sd in sds) analyzeStaff(bit, noStaff, sd, staves)
                if (firstKey == null && sds.isNotEmpty()) firstKey = detectKey(sds.first())
                all.addAll(sds)
                systems.add(SystemData(sds))
            }
            pageStaves.add(all)
        }
        progress(pages.size, pages.size)

        val votes = systems.flatMap { it.staves }.mapNotNull { it.keyGuess }
        val voted = votes.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val keyFifths = options.keyFifths ?: voted ?: firstKey ?: 0
        val (song, estimated) = assemble(systems, keyFifths)

        for ((pageIdx, gray) in pages.withIndex()) {
            val sds = pageStaves[pageIdx]
            diagnostics.add(
                PageDiagnostics(
                    pageIdx, gray.width, gray.height, sds.map { it.staff },
                    sds.flatMap { sd -> sd.barlines.map { b -> ((b.first + b.last) / 2) to (sd.staff.top.toInt()..sd.staff.bottom.toInt()) } },
                    sds.flatMap { sd ->
                        sd.heads.map { h ->
                            DetectedHead(h.page, h.x0, h.y0, h.x1, h.y1, h.pitch, h.hollow, h.duration, sd.clefAt(h.cx), h.accidental, h.measure, h.onset)
                        }
                    },
                ),
            )
        }
        if (song.notes.isEmpty()) warnings.add("Es wurden keine Noten erkannt")
        return OmrResult(song, diagnostics, warnings.toList(), keyFifths, song.beatsPerMeasure, song.beatUnit, estimated)
    }

    // ------------------------------------------------------------------ systems

    private fun groupSystems(img: BitImage, staves: List<Staff>): List<List<Pair<Staff, Clef>>> {
        when (options.layout) {
            StaffLayout.SINGLE_TREBLE -> return staves.map { listOf(it to Clef.TREBLE) }
            StaffLayout.SINGLE_BASS -> return staves.map { listOf(it to Clef.BASS) }
            StaffLayout.PIANO -> return staves.chunked(2).map { ch ->
                if (ch.size == 2) listOf(ch[0] to Clef.TREBLE, ch[1] to Clef.BASS) else listOf(ch[0] to Clef.TREBLE)
            }
            StaffLayout.AUTO -> Unit
        }
        val chains = ArrayList<MutableList<Staff>>()
        for (st in staves) {
            val prev = chains.lastOrNull()?.last()
            if (prev != null && connected(img, prev, st)) chains.last().add(st) else chains.add(mutableListOf(st))
        }
        return chains.map { chain ->
            when {
                chain.size == 1 -> listOf(chain[0] to Clef.TREBLE)
                chain.size == 2 -> listOf(chain[0] to Clef.TREBLE, chain[1] to Clef.BASS)
                else -> {
                    warnings.add("System mit ${chain.size} Notenzeilen: nur die unteren zwei (Klavier) werden verwendet")
                    listOf(chain[chain.size - 2] to Clef.TREBLE, chain[chain.size - 1] to Clef.BASS)
                }
            }
        }
    }

    /** Two staves belong to one system when a vertical line (system bar line) joins them at the left. */
    private fun connected(img: BitImage, a: Staff, b: Staff): Boolean {
        val y0 = a.bottom.roundToInt(); val y1 = b.top.roundToInt()
        if (y1 - y0 > a.space * 14) return false
        val xs = min(a.xStart, b.xStart) - (a.space * 2.5).toInt()
        val xe = max(a.xStart, b.xStart) + (a.space * 1.0).toInt()
        for (x in xs.coerceAtLeast(0)..xe.coerceAtMost(img.width - 1)) {
            var ink = 0
            for (y in y0..y1) if (img[x, y] || img[x - 1, y] || img[x + 1, y]) ink++
            if (ink >= (y1 - y0 + 1) * 0.92) return true
        }
        return false
    }

    // ------------------------------------------------------------------ staff lines

    private fun removeStaffLines(img: BitImage, staves: List<Staff>): BitImage {
        val out = img.copy()
        for (st in staves) {
            val maxRun = (st.lineThickness * 1.6 + 2).roundToInt()
            for (yc in st.lines) {
                val y = yc.roundToInt()
                for (x in (st.xStart - 2).coerceAtLeast(0)..(st.xEnd + 2).coerceAtMost(img.width - 1)) {
                    for (dy in -(st.lineThickness / 2 + 1)..(st.lineThickness / 2 + 1)) {
                        val run = img.verticalRun(x, y + dy) ?: continue
                        if (run.last - run.first + 1 <= maxRun) for (yy in run) out[x, yy] = false
                        break
                    }
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------ per staff analysis

    private fun analyzeStaff(orig: BitImage, noStaff: BitImage, sd: StaffData, allStaves: List<Staff>) {
        val st = sd.staff
        val ss = st.space
        val idx = allStaves.indexOf(st)
        val prev = allStaves.getOrNull(idx - 1)
        val next = allStaves.getOrNull(idx + 1)
        val top = max(st.top - ss * 5, prev?.let { (it.bottom + st.top) / 2 } ?: 0.0).toInt()
        val bottom = min(st.bottom + ss * 5, next?.let { (st.bottom + it.top) / 2 } ?: orig.height.toDouble()).toInt()
        val left = (st.xStart - ss).toInt().coerceAtLeast(0)
        val right = (st.xEnd + ss).toInt().coerceAtMost(orig.width)

        // 1) Fill small enclosed holes (hollow note heads). Holes are searched in the original image
        //    (heads in a space, whose outline touches the staff lines) and in the line-free image
        //    (heads on a line, where the line cuts the hole in two).
        val origCrop = orig.crop(left, top, right, bottom)
        val filled = noStaff.crop(left, top, right, bottom)
        val holeCenters = ArrayList<Pair<Double, Double>>()
        val holeAreas = ArrayList<Int>()
        fun isHole(b: Blob, img: BitImage, maxHeight: Double): Boolean {
            if (b.minX == 0 || b.minY == 0 || b.maxX == img.width - 1 || b.maxY == img.height - 1) return false
            val h = b.height / ss; val w = b.width / ss; val a = b.area / (ss * ss)
            if (h > maxHeight || w > 1.45 || a > 0.8 || a < 0.02) return false
            // gaps between (double) beams are long and flat
            if (h < 0.28 || w / h > 3.0) return false
            if (h <= 0.78) return true
            // Taller holes (a head in a space, bounded by the staff lines) must be oval, not a
            // rectangular cell between stems and lines: top and bottom rows are clearly narrower.
            val rowWidth = IntArray(b.height)
            for (p in b.pixels) rowWidth[p / img.width - b.minY]++
            val maxW = rowWidth.max()
            val ends = (rowWidth[0] + rowWidth[1].coerceAtLeast(rowWidth[0]) + rowWidth[b.height - 1] + rowWidth[b.height - 2].coerceAtLeast(rowWidth[b.height - 1])) / 4.0
            return ends <= maxW * 0.75 && w <= 1.2
        }
        for (b in Components.find(origCrop, ink = false, keepPixels = true)) {
            if (!isHole(b, origCrop, 0.97)) continue
            for (p in b.pixels) filled.data[p] = true
            // Restore staff-line pixels crossing the hole so the head stays one solid blob.
            val pad = st.lineThickness + 1
            for (y in (b.minY - pad).coerceAtLeast(0)..(b.maxY + pad).coerceAtMost(filled.height - 1))
                for (x in b.minX..b.maxX) if (origCrop[x, y]) filled[x, y] = true
            holeCenters.add(b.cx + left to b.cy + top)
            holeAreas.add(b.area)
        }
        for (b in Components.find(filled, ink = false, keepPixels = true)) {
            if (!isHole(b, filled, 0.78)) continue
            for (p in b.pixels) filled.data[p] = true
            holeCenters.add(b.cx + left to b.cy + top)
            holeAreas.add(b.area)
        }

        // 2) Opening removes stems, lines, thin symbols; note heads survive.
        val r = max(1, (ss * 0.22).roundToInt())
        val opened = filled.open(r)
        val staffNo = staffCounter++
        debugSink?.let { sink ->
            val n = staffNo
            sink("staff${n}_orig", origCrop); sink("staff${n}_filled", filled); sink("staff${n}_opened", opened)
        }
        val headerEnd = st.xStart + ss * 3.4

        val blobs = Components.find(opened, keepPixels = true)
        // Time signature: one digit on the upper half and one on the lower half of the staff, or the
        // common-time "C" centred on the staff. Found on the line-free image where glyphs are whole.
        val noStaffCrop = noStaff.crop(left, top, right, bottom)
        val glyphs = Components.find(noStaffCrop).filter { it.height / ss in 1.4..2.6 && it.width / ss in 0.8..2.2 }
        /** The common-time "C" is open on the right side at mid height. */
        fun openRight(b: Blob): Boolean {
            var ink = 0; var total = 0
            for (y in (b.cy - ss * 0.2).roundToInt()..(b.cy + ss * 0.2).roundToInt())
                for (x in (b.minX + b.width * 0.6).roundToInt()..b.maxX) { total++; if (noStaffCrop[x, y]) ink++ }
            return total > 0 && ink < total * 0.1
        }
        fun upperDigit(b: Blob) = abs(b.minY + top - st.top) < ss * 0.6 && abs(b.maxY + top - st.middle) < ss * 0.7
        fun lowerDigit(b: Blob) = abs(b.maxY + top - st.bottom) < ss * 0.6 && abs(b.minY + top - st.middle) < ss * 0.7
        val timeSigBoxes = ArrayList<IntArray>()
        for (a in glyphs) if (upperDigit(a)) for (c in glyphs) if (lowerDigit(c) && abs(a.cx - c.cx) < ss * 0.8) {
            timeSigBoxes.add(intArrayOf(min(a.minX, c.minX), a.minY, max(a.maxX, c.maxX), c.maxY))
        }
        if (timeSigBoxes.isEmpty()) glyphs.firstOrNull {
            abs(it.cy + top - st.middle) < ss * 0.5 && it.cx + left < st.xStart + ss * 12 && openRight(it)
        }?.let { timeSigBoxes.add(intArrayOf(it.minX, it.minY, it.maxX, it.maxY)) }
        timeSigBoxes.forEach { sd.timeSigs.add(intArrayOf(it[0] + left, it[1] + top, it[2] + left, it[3] + top)) }
        fun inTimeSig(b: Blob) = timeSigBoxes.any { bx -> b.cx >= bx[0] - 2 && b.cx <= bx[2] + 2 && b.cy >= bx[1] - 2 && b.cy <= bx[3] + 2 }
        for ((blobId, b) in blobs.withIndex()) {
            if (inTimeSig(b)) continue
            val w = b.width / ss; val h = b.height / ss
            if (h < 0.8 || w < 0.85 || b.fill < 0.45) continue
            if (b.cx + left < headerEnd) continue
            // Time signature: two digits covering exactly the staff height.
            val blobTopAbs = b.minY + top; val blobBottomAbs = b.maxY + top
            if (h in 3.3..4.7 && abs(blobTopAbs - st.top) < ss * 0.6 && abs(blobBottomAbs - st.bottom) < ss * 0.6) continue
            val cands = ArrayList<DoubleArray>() // x0,y0,x1,y1,cx,cy (crop coordinates)
            when {
                h <= 1.45 && w <= 1.9 -> cands.add(doubleArrayOf(b.minX.toDouble(), b.minY.toDouble(), b.maxX.toDouble(), b.maxY.toDouble(), b.cx, b.cy))
                w in 1.9..3.2 && h <= 2.3 -> {
                    val mid = (b.minX + b.maxX) / 2
                    for (half in 0..1) {
                        var n = 0; var sx = 0.0; var sy = 0.0; var y0 = Int.MAX_VALUE; var y1 = 0
                        for (p in b.pixels) {
                            val x = p % opened.width; val y = p / opened.width
                            if ((half == 0) == (x <= mid)) { n++; sx += x; sy += y; y0 = min(y0, y); y1 = max(y1, y) }
                        }
                        if (n > 0 && (y1 - y0 + 1) / ss >= 0.5) {
                            val x0 = if (half == 0) b.minX else mid + 1
                            val x1 = if (half == 0) mid else b.maxX
                            cands.add(doubleArrayOf(x0.toDouble(), y0.toDouble(), x1.toDouble(), y1.toDouble(), sx / n, sy / n))
                        }
                    }
                }
                h in 1.45..5.5 && w <= 1.9 -> {
                    // Stacked thirds: heads are exactly one staff space apart.
                    val n = ((h - 1.0) / 1.0).roundToInt().coerceIn(1, 6) + 1
                    val first = b.minY + (b.height - (n - 1) * ss) / 2
                    for (i in 0 until n) {
                        val cy = first + i * ss
                        cands.add(doubleArrayOf(b.minX.toDouble(), cy - ss / 2, b.maxX.toDouble(), cy + ss / 2, b.cx, cy))
                    }
                }
            }
            // Each hole belongs to the nearest candidate head only.
            val hollowIdx = HashSet<Int>()
            var ghost = false
            for ((hi, hc) in holeCenters.withIndex()) {
                val (hx, hy) = hc
                val lx = hx - left; val ly = hy - top
                if (lx < b.minX - 1 || lx > b.maxX + 1 || ly < b.minY - 1 || ly > b.maxY + 1) continue
                // A real head has a ring of ink around a hole of at most ~half its area. Larger "holes"
                // are cells enclosed by beams, stems and staff lines.
                if (cands.size == 1 && holeAreas[hi] > b.area * 0.55) ghost = true
                // the hole must sit in the middle of the head (digits/clefs have off-centre holes)
                cands.indices.filter { abs(cands[it][5] - ly) <= ss * 0.35 && abs((cands[it][0] + cands[it][2]) / 2 - lx) <= ss * 0.45 }
                    .minByOrNull { (cands[it][4] - lx).let { d -> d * d } + (cands[it][5] - ly).let { d -> d * d } }?.let { hollowIdx.add(it) }
            }
            if (ghost) continue
            for ((ci, c) in cands.withIndex()) {
                val x0 = c[0] + left; val y0 = c[1] + top; val x1 = c[2] + left; val y1 = c[3] + top
                val hollow = ci in hollowIdx
                sd.heads.add(Head(sd.page, x0.toInt(), y0.toInt(), x1.toInt(), y1.toInt(), c[4] + left, c[5] + top, hollow,
                    b.minY + top, b.maxY + top, blobId))
            }
        }

        // Small filled heads (tempo marks like "♩ = 96", cue notes) are clearly smaller than regular ones.
        val filledWidths = sd.heads.groupBy { it.blobId }.filter { it.value.size == 1 && !it.value[0].hollow }
            .values.map { (it[0].x1 - it[0].x0 + 1).toDouble() }.sorted()
        if (filledWidths.size >= 3) {
            val median = filledWidths[filledWidths.size / 2]
            sd.heads.removeAll { !it.hollow && it.x1 - it.x0 + 1 < median * 0.88 }
        }

        // 3) Stems, beams/flags.
        // First pass: stems may end anywhere (also inside other candidates) – only used to find ghosts.
        for (hd in sd.heads) findStem(noStaff, hd, ss, sd.heads, allowEndInHead = true)
        // Ghosts: a hollow head without stem must be a (wide) whole note; a filled head without stem is
        // only plausible when it has full size. Others are cells enclosed by beams, stems and lines.
        val ghosts = sd.heads.filter {
            val w = (it.x1 - it.x0 + 1) / ss; val h = (it.y1 - it.y0 + 1) / ss
            !it.hasStem && (if (it.hollow) w < 1.3 else (h < 0.8 || w < 1.0))
        }.toSet()
        sd.heads.removeAll(ghosts)
        // Second pass with the cleaned list: a stem must not end inside another head of the same chord.
        for (hd in sd.heads) { hd.stemX = -1; hd.beams = 0; findStem(noStaff, hd, ss, sd.heads, allowEndInHead = false) }
        sd.heads.removeAll { !it.hollow && !it.hasStem && (it.x1 - it.x0) / ss < 1.0 } // isolated blobs, likely noise
        // Stacked heads without a stem (whole-note chords) need a hole in every head; otherwise the blob is
        // something else (time signature digits, clef parts, ...).
        val badBlobs = sd.heads.groupBy { it.blobId }.filter { (_, hs) -> hs.size > 1 && hs.none { it.hasStem } && hs.any { !it.hollow } }.keys
        sd.heads.removeAll { it.blobId in badBlobs }
        sd.heads.filter { it.hasStem }.forEach { sd.stemXs.add(it.stemX) }

        // 4) Small dots → augmentation dots.
        val noCrop = noStaff.crop(left, top, right, bottom)
        val symbols = Components.find(noCrop)
        if (options.layout == StaffLayout.AUTO || options.layout == StaffLayout.PIANO) {
            detectClefs(noStaff, sd)
            detectClefChanges(symbols, sd, left, top, headerEnd)
        }
        sd.keyGuess = readKeySignature(noCrop, symbols, sd, left, top)
        detectRests(noCrop, symbols, sd, left, top, headerEnd)
        val dots = symbols.filter {
            val w = it.width / ss; val h = it.height / ss
            w in 0.2..0.7 && h in 0.2..0.7 && it.fill > 0.5
        }.map { (it.cx + left) to (it.cy + top) }
        for (hd in sd.heads) {
            hd.dotted = dots.any { (dx, dy) -> dx in (hd.x1 + ss * 0.1)..(hd.x1 + ss * 1.4) && abs(dy - hd.cy) <= ss * 0.85 && !(hd.stemUp && abs(dx - hd.stemX) < ss * 0.3) }
        }

        // 5) Thin vertical strokes (for accidentals and the key signature) and bar lines.
        findStrokesAndBarlines(noStaff, sd, left, right)

        // 6) Staff position of each head.
        for (hd in sd.heads) hd.step = ((st.bottom - hd.cy) / (ss / 2)).roundToInt()

        // Musical sanity checks:
        //  - hollow heads (half notes) never have beams; such blobs are beam/stem junction artefacts
        //  - heads outside the staff need ledger lines (removes tempo marks, fingering, text)
        sd.heads.removeAll { it.hollow && it.hasStem && it.beams > 0 }
        sd.heads.removeAll { !hasLedgerLines(orig, it, st) }

        if (System.getProperty("omr.trace") != null) for (h in sd.heads.sortedBy { it.cx })
            println("HEAD staff=$staffNo x=${h.x0}-${h.x1} y=${h.y0}-${h.y1} step=${h.step} hollow=${h.hollow} stem=${h.stemX}/${if (h.stemUp) "up" else "down"} end=${h.stemEnd} beams=${h.beams} dot=${h.dotted} blob=${h.blobId}")

        // 7) Accidentals in front of note heads.
        for (hd in sd.heads) hd.accidental = detectAccidental(hd, sd, ss)
    }

    /**
     * Clef at the start of the staff: a treble clef reaches clearly above and below the staff,
     * a bass clef stays in the upper three quarters of the staff.
     */
    private fun detectClefs(img: BitImage, sd: StaffData) {
        val st = sd.staff
        val ss = st.space
        var above = 0.0; var below = 0.0; var ink = 0; var lowerInk = 0
        for (x in (st.xStart + ss * 0.3).roundToInt()..(st.xStart + ss * 2.8).roundToInt()) {
            for (y in (st.top - ss * 3).roundToInt()..(st.bottom + ss * 3).roundToInt()) {
                if (!img[x, y]) continue
                ink++
                above = max(above, (st.top - y) / ss)
                below = max(below, (y - st.bottom) / ss)
                if (y > st.bottom - ss * 0.9) lowerInk++
            }
        }
        if (ink < ss * ss * 0.5) return
        val clef = when {
            above >= 0.7 && below >= 0.7 -> Clef.TREBLE
            below < 0.4 && above < 0.6 && lowerInk < ink * 0.12 -> Clef.BASS
            else -> return
        }
        sd.clefs.add(st.xStart.toDouble() to clef)
    }

    /**
     * Clef changes inside the staff. Bass clef: a compact glyph in the upper part of the staff with two
     * dots to its right. Treble clef: a tall glyph reaching above and below the staff. Note heads that were
     * found on such a glyph are removed.
     */
    private fun detectClefChanges(blobs: List<Blob>, sd: StaffData, left: Int, top: Int, headerEnd: Double) {
        val st = sd.staff
        val ss = st.space
        val found = ArrayList<Pair<Blob, Clef>>()
        for (b in blobs) {
            val x0 = b.minX + left; val x1 = b.maxX + left; val y0 = b.minY + top; val y1 = b.maxY + top
            if (x1 < headerEnd) continue
            val w = b.width / ss; val h = b.height / ss
            if (sd.stemXs.any { it in x0..x1 } && h > 3.0) continue
            if (h in 1.6..3.4 && w in 1.1..2.8 && abs(y0 - st.top) < ss * 0.8 && y1 < st.bottom - ss * 0.3 && b.fill in 0.15..0.6) {
                val dots = blobs.count { d ->
                    val dw = d.width / ss; val dh = d.height / ss
                    dw in 0.15..0.6 && dh in 0.15..0.6 && d.minX + left in x1..(x1 + (ss * 1.3).toInt()) &&
                        (abs(d.cy + top - (st.lines[0] + st.lines[1]) / 2) < ss * 0.4 || abs(d.cy + top - (st.lines[1] + st.lines[2]) / 2) < ss * 0.4)
                }
                if (dots >= 2) found.add(b to Clef.BASS)
            } else if (h >= 4.3 && h <= 8.5 && w in 1.0..3.2 && y0 < st.top - ss * 0.4 && y1 > st.bottom + ss * 0.4 && b.fill in 0.1..0.5) {
                found.add(b to Clef.TREBLE)
            }
        }
        for ((b, clef) in found) {
            val x0 = b.minX + left; val x1 = b.maxX + left
            sd.clefs.add((x0 - ss * 0.2) to clef)
            sd.heads.removeAll { it.cx >= x0 - 2 && it.cx <= x1 + ss * 0.8 && it.cy >= b.minY + top - 2 && it.cy <= b.maxY + top + 2 }
        }
        sd.clefs.sortBy { it.first }
        if (sd.clefs.isNotEmpty() && sd.clefs[0].first > st.xStart + 1) sd.clefs.add(0, st.xStart.toDouble() to sd.clef)
    }

    /** Flat: only the thin stem in the upper part, a wide bowl in the lower part. */
    private fun flatShape(img: BitImage, b: Blob, ss: Double): Boolean {
        fun rowWidth(y: Int): Int { var l = -1; var r = -1; for (x in b.minX..b.maxX) if (img[x, y]) { if (l < 0) l = x; r = x }; return if (l < 0) 0 else r - l + 1 }
        val upper = (b.minY..(b.minY + b.height * 0.35).toInt()).maxOf { rowWidth(it) }
        val lower = ((b.maxY - b.height * 0.35).toInt()..b.maxY).maxOf { rowWidth(it) }
        return upper <= ss * 0.32 && lower >= ss * 0.45
    }

    /** Number of separate vertical strokes (runs ≥ [minLen]) inside a blob, with their x positions. */
    private fun verticalStrokes(img: BitImage, b: Blob, minLen: Double): List<Int> {
        val cols = ArrayList<Int>()
        for (x in b.minX..b.maxX) {
            var run = 0; var best = 0
            for (y in b.minY..b.maxY) { if (img[x, y]) { run++; best = max(best, run) } else run = 0 }
            if (best >= minLen) cols.add(x)
        }
        val strokes = ArrayList<Int>()
        var i = 0
        while (i < cols.size) {
            var j = i
            while (j + 1 < cols.size && cols[j + 1] - cols[j] <= 1) j++
            strokes.add((cols[i] + cols[j]) / 2)
            i = j + 1
        }
        return strokes
    }

    /**
     * Key signature: the accidental-shaped symbols between the clef and the time signature / first note.
     * Sharp = two vertical strokes, flat = one stroke on the left with a bowl. Returns +n / -n or null.
     */
    private fun readKeySignature(img: BitImage, blobs: List<Blob>, sd: StaffData, left: Int, top: Int): Int? {
        val st = sd.staff
        val ss = st.space
        val inStaff = blobs.filter { b ->
            val cy = b.cy + top
            cy > st.top - ss * 2 && cy < st.bottom + ss * 2 && b.minX + left > st.xStart - 2
        }
        // clef = the tallest glyph at the start of the staff
        val clef = inStaff.filter { it.minX + left < st.xStart + ss * 2.5 && it.height > ss * 2.2 }.maxByOrNull { it.height } ?: return null
        val from = clef.maxX + left + 1
        val firstNote = sd.heads.minOfOrNull { it.x0 } ?: Int.MAX_VALUE
        val firstBar = sd.barlines.minOfOrNull { it.first } ?: Int.MAX_VALUE
        val timeSig = sd.timeSigs.minOfOrNull { it[0] } ?: Int.MAX_VALUE
        val to = minOf(firstNote - (ss * 0.3).toInt(), firstBar, timeSig, (st.xStart + ss * 14).toInt())
        var sharps = 0; var flats = 0; var other = 0
        for (b in inStaff.sortedBy { it.minX }) {
            val x0 = b.minX + left; val x1 = b.maxX + left
            if (x0 < from || x1 > to) continue
            val w = b.width / ss; val h = b.height / ss
            if (h < 1.3 || h > 3.6 || w > 1.5) {
                if (System.getProperty("omr.trace") != null) println("KEYSKIP x=$x0 w=${"%.2f".format(w)} h=${"%.2f".format(h)}")
                if (h >= 1.3) other++; continue
            }
            val strokes = verticalStrokes(img, b, ss * 1.2)
            if (System.getProperty("omr.trace") != null) println("KEYSYM x=$x0 w=${"%.2f".format(w)} h=${"%.2f".format(h)} strokes=${strokes.map { it - b.minX }} width=${b.width} fill=${"%.2f".format(b.fill)}")
            // A flat whose bowl was cut off by the staff-line removal: thin stroke + small blob right next to it.
            val bowlNext = w < 0.45 && strokes.size == 1 && inStaff.any { o ->
                o !== b && o.minX in b.maxX..(b.maxX + (ss * 0.35).toInt()) && o.height / ss in 0.5..1.3 &&
                    o.width / ss in 0.25..0.8 && o.maxY >= b.maxY - ss * 0.5 && o.maxY <= b.maxY + ss * 0.3
            }
            when {
                bowlNext -> flats++
                strokes.size >= 2 && w in 0.5..1.4 -> sharps++
                strokes.size == 1 && (strokes[0] - b.minX) < b.width * 0.4 && w in 0.45..1.2 && flatShape(img, b, ss) -> flats++
                else -> other++
            }
        }
        if (sharps > 0 && flats > 0) return if (sharps >= flats) sharps.coerceAtMost(7) else -flats.coerceAtMost(7)
        return when {
            sharps > 0 -> sharps.coerceAtMost(7)
            flats > 0 -> -flats.coerceAtMost(7)
            other == 0 -> 0
            else -> null
        }
    }

    /** Longest vertical ink run inside the blob's bounding box. */
    private fun maxVerticalRun(img: BitImage, b: Blob): Int {
        var best = 0
        for (x in b.minX..b.maxX) {
            var run = 0
            for (y in b.minY..b.maxY) { if (img[x, y]) { run++; best = max(best, run) } else run = 0 }
        }
        return best
    }

    /**
     * Rests (needed so that the beats of a measure add up):
     * quarter rest = tall zig-zag without long straight strokes, eighth rest = shorter hook,
     * half rest = small filled block sitting on the middle line. Whole rests do not matter for timing.
     */
    private fun detectRests(img: BitImage, blobs: List<Blob>, sd: StaffData, left: Int, top: Int, headerEnd: Double) {
        val st = sd.staff
        val ss = st.space
        for (b in blobs) {
            val w = b.width / ss; val h = b.height / ss
            val cx = b.cx + left; val cy = b.cy + top
            if (cx < headerEnd) continue
            if (sd.heads.any { cx >= it.x0 - ss * 0.2 && cx <= it.x1 + ss * 0.2 && abs(cy - it.cy) < ss * 2 }) continue
            val vRun = maxVerticalRun(img, b) / ss
            val duration = when {
                h in 2.1..3.4 && w in 0.55..1.4 && b.fill in 0.2..0.65 && vRun < 1.5 && abs(cy - st.middle) < ss * 0.9 -> 1.0
                h in 1.3..2.1 && w in 0.6..1.4 && b.fill in 0.15..0.6 && vRun < 1.3 && cy > st.top && cy < st.bottom -> 0.5
                h in 0.3..0.7 && w in 0.8..1.6 && b.fill > 0.8 && abs((b.maxY + top) - st.middle) < ss * 0.35 -> 2.0
                else -> continue
            }
            sd.rests.add(Rest(b.minX + left, b.minY + top, b.maxX + left, b.maxY + top, duration))
        }
    }

    private fun hasLedgerLines(img: BitImage, hd: Head, st: Staff): Boolean {
        val s = hd.step
        if (s in -1..9) return true
        val ss = st.space
        val ledgerStep = if (s > 9) 10 else -2
        val y = (st.bottom - ledgerStep * ss / 2).roundToInt()
        val headW = hd.x1 - hd.x0 + 1
        val cx = (hd.x0 + hd.x1) / 2
        for (dy in -(st.lineThickness + 1)..(st.lineThickness + 1)) {
            val yy = y + dy
            var best = 0
            var x = (hd.x0 - ss * 0.6).roundToInt()
            while (x <= hd.x1 + ss * 0.6) {
                if (!img[x, yy]) { x++; continue }
                var l = x; while (img[l - 1, yy]) l--
                var r = x; while (img[r + 1, yy]) r++
                if (l <= cx + headW / 2 && r >= cx - headW / 2) best = max(best, r - l + 1)
                x = r + 1
            }
            if (best >= headW * 1.15 && best <= ss * 4) return true
        }
        return false
    }

    private fun findStem(img: BitImage, hd: Head, ss: Double, heads: List<Head>, allowEndInHead: Boolean) {
        fun insideOtherHead(x: Int, y: Int) = heads.any { it !== hd && x >= it.x0 - ss * 0.3 && x <= it.x1 + ss * 0.3 && y >= it.y0 - ss * 0.3 && y <= it.y1 + ss * 0.3 }
        val minLen = ss * 2.2
        var best = 0.0
        fun probe(xFrom: Double, xTo: Double, up: Boolean) {
            var x = xFrom.roundToInt()
            while (x <= xTo.roundToInt()) {
                var y = (hd.cy - ss * 0.4).roundToInt()
                while (y <= (hd.cy + ss * 0.4).roundToInt()) {
                    val run = img.verticalRun(x, y)
                    if (run != null) {
                        val len = if (up) hd.cy - run.first else run.last - hd.cy
                        val beyond = if (up) hd.blobTop - run.first else run.last - hd.blobBottom
                        val edge = if (up) hd.blobTop.toDouble() else hd.blobBottom.toDouble()
                        val dir = if (up) -1 else 1
                        // Sample close to the head: short beamed stems end in thick (double) beams.
                        val thin = beyond >= ss * 1.5 && doubleArrayOf(0.15, 0.3, 0.45).count { f -> isThin(img, x, edge + dir * beyond * f, ss) } >= 2
                        // A line ending in another head of the same chord is not the stem's free end.
                        val endY = if (up) run.first else run.last
                        if (len >= minLen && thin && len > best && (allowEndInHead || !insideOtherHead(x, endY))) {
                            best = len; hd.stemX = x; hd.stemUp = up; hd.stemEnd = if (up) run.first else run.last
                        }
                        y = run.last + 1
                    } else y++
                }
                x++
            }
        }
        probe(hd.x1 - ss * 0.35, hd.x1 + ss * 0.25, up = true)
        probe(hd.x0 - ss * 0.25, hd.x0 + ss * 0.35, up = false)
        if (hd.hasStem) hd.beams = countBeams(img, hd, ss, heads)
    }

    private fun isThin(img: BitImage, x: Int, y: Double, ss: Double): Boolean {
        val yy = y.roundToInt()
        var l = x; while (img[l - 1, yy]) l--
        var r = x; while (img[r + 1, yy]) r++
        return (r - l + 1) <= ss * 0.3
    }

    private fun countBeams(img: BitImage, hd: Head, ss: Double, heads: List<Head>): Int {
        val stemLen = abs(hd.stemEnd - hd.cy)
        // Beams/flags are stacked from the stem end with a spacing of ~0.75 staff spaces. Looking only
        // at the first ~1.8 spaces avoids counting the lower curl of a flag as a second beam.
        val scan = min(ss * 1.8, stemLen - ss * 1.1)
        if (scan <= 0) return 0
        var best = 0
        for (side in intArrayOf(-1, 1)) {
            var sideCount = 0
            for (off in doubleArrayOf(0.45, 0.7)) {
                val x = (hd.stemX + side * ss * off).roundToInt()
                var count = 0
                var i = -(ss * 0.25).roundToInt()
                var run = 0
                while (i <= scan) {
                    val y = if (hd.stemUp) hd.stemEnd + i else hd.stemEnd - i
                    val onHead = heads.any { x >= it.x0 && x <= it.x1 && y >= it.y0 && y <= it.y1 }
                    if (img[x, y] && !onHead) run++ else {
                        if (run >= ss * 0.25 && run <= ss * 1.0) count++
                        run = 0
                    }
                    i++
                }
                if (run >= ss * 0.25 && run <= ss * 1.0) count++
                sideCount = max(sideCount, count)
            }
            best = max(best, sideCount)
        }
        return best.coerceAtMost(3)
    }

    private fun findStrokesAndBarlines(img: BitImage, sd: StaffData, left: Int, right: Int) {
        val st = sd.staff
        val ss = st.space
        val yTop = (st.top - ss * 2.5).roundToInt()
        val yBot = (st.bottom + ss * 2.5).roundToInt()
        val barCols = ArrayList<Int>()
        // stroke columns
        data class Col(val x: Int, val top: Int, val bottom: Int)
        val cols = ArrayList<Col>()
        for (x in left until right) {
            var y = yTop
            var bestTop = 0; var bestBot = -1
            while (y <= yBot) {
                if (img[x, y]) {
                    val run = img.verticalRun(x, y)!!
                    if (run.last - run.first > bestBot - bestTop) { bestTop = run.first; bestBot = run.last }
                    y = run.last + 1
                } else y++
            }
            val len = bestBot - bestTop + 1
            if (len >= ss * 1.3) cols.add(Col(x, bestTop, bestBot))
            // bar line: covers the whole staff
            if (bestTop <= st.top + st.lineThickness && bestBot >= st.bottom - st.lineThickness && len >= ss * 3.8) barCols.add(x)
        }
        // group stroke columns
        var i = 0
        while (i < cols.size) {
            var j = i
            while (j + 1 < cols.size && cols[j + 1].x == cols[j].x + 1) j++
            val group = cols.subList(i, j + 1)
            val width = group.size
            if (width <= ss * 0.38) {
                sd.strokes.add(Stroke(group.map { it.x }.average(), group.minOf { it.top }, group.maxOf { it.bottom }, width))
            }
            i = j + 1
        }
        // group bar columns
        val groups = ArrayList<IntRange>()
        for (x in barCols) {
            if (groups.isNotEmpty() && x - groups.last().last <= 1) groups[groups.size - 1] = groups.last().first..x
            else groups.add(x..x)
        }
        val heads = sd.heads
        for (g in groups) {
            if (g.last - g.first + 1 > ss * 0.7) continue
            val cx = (g.first + g.last) / 2.0
            if (cx < st.xStart + ss * 0.8) continue
            if (sd.stemXs.any { abs(it - cx) <= ss * 0.4 }) continue
            if (heads.any { cx >= it.x0 - ss * 0.15 && cx <= it.x1 + ss * 0.15 && it.cy > st.top - ss && it.cy < st.bottom + ss }) continue
            // merge double bar lines
            if (sd.barlines.isNotEmpty() && g.first - sd.barlines.last().last <= ss * 1.2) {
                sd.barlines[sd.barlines.size - 1] = sd.barlines.last().first..g.last
            } else sd.barlines.add(g)
        }
    }

    private fun detectAccidental(hd: Head, sd: StaffData, ss: Double): Int? {
        val xMin = hd.x0 - ss * 1.9
        val xMax = hd.x0 - ss * 0.05
        val strokes = sd.strokes.filter { s ->
            s.x in xMin..xMax && s.length <= ss * 3.6 &&
                sd.stemXs.none { abs(it - s.x) <= ss * 0.3 } &&
                sd.barlines.none { s.x >= it.first - ss * 0.3 && s.x <= it.last + ss * 0.3 } &&
                s.top < hd.cy + ss * 0.6 && s.bottom > hd.cy - ss * 0.6
        }.sortedByDescending { it.x }
        if (strokes.isEmpty()) return null
        val a = strokes[0]
        val b = strokes.getOrNull(1)?.takeIf { a.x - it.x in (ss * 0.15)..(ss * 0.8) }
        if (b != null) {
            val center = (a.center + b.center) / 2
            if (abs(center - hd.cy) > ss * 0.7) return null
            val sameTop = abs(a.top - b.top) < ss * 0.45
            val sameBottom = abs(a.bottom - b.bottom) < ss * 0.45
            // sharp: both strokes slightly offset but similar; natural: left goes up, right goes down
            return if (sameTop && sameBottom) 1 else if (b.top < a.top && b.bottom < a.bottom) 0 else 1
        }
        // single stroke: flat (stem above the bowl that sits at the note's height)
        if (a.top < hd.cy - ss * 0.8 && a.bottom in (hd.cy - ss * 0.2).toInt()..(hd.cy + ss * 0.9).toInt() && looksLikeFlat(a, ss)) return -1
        return null
    }

    private var currentNoStaff: BitImage? = null

    /** A flat is a stroke with a bowl on its right at the bottom and nothing on its left. */
    private fun looksLikeFlat(s: Stroke, ss: Double): Boolean {
        val img = currentNoStaff ?: return false
        val x = s.x.roundToInt()
        var right = 0; var rightTotal = 0
        for (y in (s.bottom - ss * 0.7).roundToInt()..s.bottom) {
            for (dx in (ss * 0.25).roundToInt()..(ss * 0.75).roundToInt()) { rightTotal++; if (img[x + dx, y]) right++ }
        }
        var leftInk = 0; var leftTotal = 0
        for (y in s.top..s.bottom) for (dx in (ss * 0.2).roundToInt()..(ss * 0.6).roundToInt()) { leftTotal++; if (img[x - dx, y]) leftInk++ }
        return right > rightTotal * 0.12 && leftInk < leftTotal * 0.04
    }

    private fun detectKey(sd: StaffData): Int {
        val st = sd.staff
        val ss = st.space
        val firstHead = sd.heads.minOfOrNull { it.x0 }?.toDouble() ?: (st.xStart + ss * 12)
        // The clef has a very long stroke; the key signature starts after it.
        val clefStroke = sd.strokes.filter { it.x < st.xStart + ss * 4 && it.length > ss * 3.6 }.maxOfOrNull { it.x }
        val from = max(st.xStart + ss * 2.0, (clefStroke ?: 0.0) + ss * 0.6)
        val timeSigStart = sd.timeSigs.minOfOrNull { it[0] }?.toDouble() ?: Double.MAX_VALUE
        val strokes = sd.strokes.filter {
            it.x in from..(min(firstHead, timeSigStart) - ss * 0.3) && it.length.toDouble() in (ss * 1.3)..(ss * 3.6) &&
                it.center in (st.top - ss * 0.5)..(st.bottom + ss * 0.5)
        }
            .sortedBy { it.x }
        if (System.getProperty("omr.trace") != null) println("KEY from=$from first=$firstHead ts=${sd.timeSigs.map { it.toList() }} strokes=${strokes.map { "x=${"%.0f".format(it.x)} ${it.top}-${it.bottom} w=${it.width}" }} xStart=${st.xStart} ss=$ss")
        if (strokes.isEmpty()) return 0
        var pairs = 0; var singles = 0
        var i = 0
        while (i < strokes.size) {
            val n = strokes.getOrNull(i + 1)
            if (n != null && n.x - strokes[i].x <= ss * 0.8) { pairs++; i += 2 } else { if (looksLikeFlat(strokes[i], ss)) singles++; i++ }
        }
        return if (pairs >= singles) pairs.coerceAtMost(7) else -singles.coerceAtMost(7)
    }

    // ------------------------------------------------------------------ rhythm & assembly

    private class Column(val x: Double, val heads: List<Head>, val restDuration: Double = 0.0) {
        val advance: Double get() = if (heads.isEmpty()) restDuration else heads.minOf { it.baseDuration }
    }

    private class MeasureSlot(val system: Int, val left: Double, val right: Double, val perStaff: List<List<Column>>, val staves: List<StaffData>)

    private fun columnsOf(heads: List<Head>, rests: List<Rest>, ss: Double): List<Column> {
        val restCols = rests.map { Column(it.cx, emptyList(), it.duration) }
        if (heads.isEmpty()) return restCols.sortedBy { it.x }
        // chords: same stem or (no stem) nearly same x
        val sorted = heads.sortedBy { if (it.hasStem) it.stemX.toDouble() else it.cx }
        val chords = ArrayList<MutableList<Head>>()
        for (h in sorted) {
            val key = if (h.hasStem) h.stemX.toDouble() else h.cx
            val last = chords.lastOrNull()
            val lastKey = last?.let { l -> l.first().let { if (it.hasStem) it.stemX.toDouble() else it.cx } }
            if (last != null && lastKey != null && abs(key - lastKey) <= ss * 0.45 && last.first().hasStem == h.hasStem) last.add(h) else chords.add(mutableListOf(h))
        }
        // columns: chords whose heads overlap horizontally (multiple voices / displaced seconds)
        val cols = ArrayList<MutableList<Head>>()
        var lastX = Double.NEGATIVE_INFINITY
        for (c in chords) {
            val x = c.minOf { it.cx }
            if (cols.isNotEmpty() && x - lastX <= ss * 0.9) cols.last().addAll(c) else cols.add(c.toMutableList())
            lastX = cols.last().minOf { it.cx }
        }
        val headCols = cols.map { Column(it.minOf { h -> h.cx }, it) }
        // Rests coinciding with notes belong to another voice and do not advance time.
        val extra = restCols.filter { r -> headCols.none { abs(it.x - r.x) < ss * 1.0 } }
        return (headCols + extra).sortedBy { it.x }
    }

    private fun assemble(systems: List<SystemData>, keyFifths: Int): Pair<Song, List<Int>> {
        val slots = ArrayList<MeasureSlot>()
        for ((si, sys) in systems.withIndex()) {
            val ss = sys.staves.first().staff.space
            val xStart = sys.staves.minOf { it.staff.xStart }.toDouble()
            val xEnd = sys.staves.maxOf { it.staff.xEnd }.toDouble()
            // bar lines that appear in every staff of the system (or in the only one)
            val candidates = sys.staves.flatMap { sd -> sd.barlines.map { (it.first + it.last) / 2.0 } }.sorted()
            val bars = ArrayList<Double>()
            var k = 0
            while (k < candidates.size) {
                var j = k
                while (j + 1 < candidates.size && candidates[j + 1] - candidates[k] <= ss * 0.6) j++
                val support = sys.staves.count { sd -> sd.barlines.any { b -> abs((b.first + b.last) / 2.0 - candidates[k]) <= ss * 0.6 } }
                if (support >= min(2, sys.staves.size)) bars.add(candidates.subList(k, j + 1).average())
                k = j + 1
            }
            val edges = ArrayList<Double>()
            edges.add(xStart)
            for (b in bars) if (b - edges.last() > ss * 1.5) edges.add(b)
            if (xEnd - edges.last() > ss * 2.0) edges.add(xEnd)
            for (m in 0 until edges.size - 1) {
                val l = edges[m]; val r = edges[m + 1]
                val per = sys.staves.map { sd -> columnsOf(sd.heads.filter { it.cx > l && it.cx < r }, sd.rests.filter { it.cx > l && it.cx < r }, ss) }
                // header-only area at the start / empty staff after the final bar line
                if (per.all { cols -> cols.all { it.heads.isEmpty() } } && (m == 0 || m == edges.size - 2)) continue
                slots.add(MeasureSlot(si, l, r, per, sys.staves))
            }
        }

        // Estimate the measure length (in quarter beats).
        val measureLen: Double
        val num: Int
        val den: Int
        if (options.beatsPerMeasure != null) {
            num = options.beatsPerMeasure; den = options.beatUnit
            measureLen = num * 4.0 / den
        } else {
            val sums = slots.drop(1).dropLast(1).ifEmpty { slots }
                .mapNotNull { s -> s.perStaff.filter { it.isNotEmpty() }.maxOfOrNull { cols -> cols.sumOf { it.advance } } }
                .map { (it * 2).roundToInt() / 2.0 }
            val mode = sums.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 4.0
            when (mode) {
                1.5 -> { num = 3; den = 8 }
                2.0 -> { num = 2; den = 4 }
                3.0 -> { num = 3; den = 4 }
                6.0 -> { num = 6; den = 4 }
                else -> { num = 4; den = 4 }
            }
            measureLen = num * 4.0 / den
        }

        val notes = ArrayList<Note>()
        val estimated = ArrayList<Int>()
        var measureStart = 0.0
        var pickup = 0.0
        for ((mi, slot) in slots.withIndex()) {
            val ss = slot.staves.first().staff.space
            val sums = slot.perStaff.map { cols -> cols.sumOf { it.advance } }
            val good = slot.perStaff.indices.filter { slot.perStaff[it].isNotEmpty() && abs(sums[it] - measureLen) < 0.05 }
            var length = measureLen
            val onsets: List<List<Double>> // per staff per column
            if (good.isNotEmpty()) {
                val ref = good.maxBy { slot.perStaff[it].size }
                val anchorsX = ArrayList<Double>(); val anchorsT = ArrayList<Double>()
                var t = 0.0
                for (c in slot.perStaff[ref]) { anchorsX.add(c.x); anchorsT.add(t); t += c.advance }
                anchorsX.add(slot.right); anchorsT.add(measureLen)
                onsets = slot.perStaff.mapIndexed { si, cols ->
                    if (si in good) sequential(cols) else cols.map { quantize(interpolate(it.x, anchorsX, anchorsT), 0.25) }
                }
            } else {
                val nonEmpty = slot.perStaff.indices.filter { slot.perStaff[it].isNotEmpty() }
                val consistent = nonEmpty.isNotEmpty() && nonEmpty.all { abs(sums[it] - sums[nonEmpty[0]]) < 0.05 }
                if (mi == 0 && consistent && sums[nonEmpty[0]] < measureLen - 0.05) {
                    pickup = sums[nonEmpty[0]]
                    length = pickup
                    onsets = slot.perStaff.map { sequential(it) }
                } else {
                    estimated.add(mi + 1)
                    val firstX = slot.perStaff.flatten().minOfOrNull { it.x } ?: slot.left
                    onsets = slot.perStaff.map { cols ->
                        cols.map { c -> quantize((c.x - firstX) / max(1.0, slot.right - ss * 0.8 - firstX) * measureLen, 0.25).coerceAtMost(measureLen - 0.25) }
                    }
                }
            }
            for ((si, cols) in slot.perStaff.withIndex()) {
                val sd = slot.staves[si]
                val hand = sd.hand
                val accidentals = HashMap<Int, Int>()
                for ((ci, col) in cols.withIndex()) {
                    val onset = onsets[si][ci]
                    val nextOnset = onsets[si].getOrNull(ci + 1) ?: length
                    for (h in col.heads.sortedBy { it.cx }) {
                        h.accidental?.let { accidentals[h.step] = it }
                        val base = if (sd.clefAt(h.cx) == Clef.TREBLE) 30 else 18 // diatonic index of the bottom line (E4 / G2)
                        val diatonic = base + h.step
                        val alter = accidentals[h.step]
                        h.pitch = Pitch.fromDiatonic(diatonic, keyFifths, alter).coerceIn(Pitch.LOWEST_PIANO, Pitch.HIGHEST_PIANO)
                        val dur = if (si in good || mi == 0 && pickup > 0) h.baseDuration else min(h.baseDuration, max(0.25, nextOnset - onset))
                        h.duration = dur
                        h.onset = measureStart + onset
                        h.measure = mi
                        notes.add(Note(h.pitch, measureStart + onset, dur * 0.95, hand))
                    }
                }
            }
            measureStart += length
        }
        if (estimated.isNotEmpty()) warnings.add("Rhythmus geschätzt in Takt ${estimated.joinToString(", ")} – bitte prüfen")

        val song = Song(
            id = SongUtils.newId(),
            title = options.title,
            bpm = options.bpm,
            beatsPerMeasure = num,
            beatUnit = den,
            notes = SongUtils.dedupe(notes),
            source = SongSource.PDF,
            createdAt = System.currentTimeMillis(),
            pickupBeats = pickup,
        )
        return song to estimated
    }

    private fun sequential(cols: List<Column>): List<Double> {
        var t = 0.0
        return cols.map { c -> t.also { t += c.advance } }
    }

    private fun interpolate(x: Double, xs: List<Double>, ts: List<Double>): Double {
        if (x <= xs.first()) return ts.first()
        for (i in 1 until xs.size) {
            if (x <= xs[i]) {
                val f = (x - xs[i - 1]) / max(1e-6, xs[i] - xs[i - 1])
                return ts[i - 1] + f * (ts[i] - ts[i - 1])
            }
        }
        return ts.last()
    }

    private fun quantize(v: Double, grid: Double) = (v / grid).roundToInt() * grid
}
