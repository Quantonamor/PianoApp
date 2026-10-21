package de.pianotiles.core.omr

import kotlin.math.abs
import kotlin.math.roundToInt

/** One five-line staff. [lines] holds the y centre of each line from top to bottom. */
data class Staff(
    val lines: DoubleArray,
    val lineThickness: Int,
    val xStart: Int,
    val xEnd: Int,
) {
    val top: Double get() = lines[0]
    val bottom: Double get() = lines[4]
    val space: Double get() = (lines[4] - lines[0]) / 4.0
    val middle: Double get() = lines[2]
}

object StaffDetector {

    /** Most frequent vertical black run (line thickness) and white run (gap between lines). */
    fun runLengthEstimate(img: BitImage): Pair<Int, Int> {
        val black = IntArray(64)
        val white = IntArray(256)
        val step = maxOf(1, img.width / 300)
        var x = 0
        while (x < img.width) {
            var y = 0
            while (y < img.height) {
                val v = img[x, y]
                var len = 0
                while (y < img.height && img[x, y] == v) { len++; y++ }
                if (v) { if (len < black.size) black[len]++ } else if (len < white.size) white[len]++
            }
            x += step
        }
        black[0] = 0; white[0] = 0
        val lt = black.indices.maxBy { black[it] }.coerceAtLeast(1)
        // Ignore tiny white runs (noise inside symbols): require at least 2 * line thickness.
        var ws = 0; var best = -1
        for (i in (2 * lt) until white.size) if (white[i] > best) { best = white[i]; ws = i }
        return lt to ws
    }

    fun detect(img: BitImage): List<Staff> {
        val (lt, ws) = runLengthEstimate(img)
        val spacing = (lt + ws).toDouble()
        val minRun = (spacing * 3).roundToInt().coerceAtLeast(20)

        // Score per row: total length of long horizontal runs.
        val score = IntArray(img.height)
        for (y in 0 until img.height) {
            var x = 0; var total = 0
            while (x < img.width) {
                if (!img[x, y]) { x++; continue }
                var len = 0
                while (x < img.width && img[x, y]) { len++; x++ }
                if (len >= minRun) total += len
            }
            score[y] = total
        }
        val maxScore = score.maxOrNull() ?: 0
        if (maxScore == 0) return emptyList()
        val threshold = maxOf(maxScore * 0.35, img.width * 0.15)

        // Merge neighbouring candidate rows into lines.
        data class Line(val y: Double, val thickness: Int)
        val lines = ArrayList<Line>()
        var y = 0
        while (y < img.height) {
            if (score[y] < threshold) { y++; continue }
            var sumW = 0.0; var sumYW = 0.0; val start = y
            while (y < img.height && score[y] >= threshold * 0.6) { sumW += score[y]; sumYW += y * score[y].toDouble(); y++ }
            lines.add(Line(sumYW / sumW, y - start))
        }

        // Group into staves of five equally spaced lines. Long beams can look like staff lines but are
        // thicker: a thick line may be inside a staff (beam on a line) but never be its first or last line.
        val thick = lt * 2 + 2
        val staves = ArrayList<Staff>()
        fun chainFrom(start: Int): List<Int>? {
            val chain = arrayListOf(start)
            var cur = start
            var gap = spacing
            while (chain.size < 5) {
                val tol = if (chain.size == 1) spacing * 0.4 + 1 else gap * 0.2 + 1
                val next = (cur + 1 until lines.size)
                    .filter { abs(lines[it].y - lines[cur].y - gap) <= tol }
                    .minByOrNull { lines[it].thickness * 10 + abs(lines[it].y - lines[cur].y - gap) }
                    ?: return null
                if (chain.size == 1) gap = lines[next].y - lines[cur].y
                chain.add(next); cur = next
            }
            val ys = chain.map { lines[it].y }
            val gaps = (0 until 4).map { ys[it + 1] - ys[it] }
            val mean = gaps.average()
            return chain.takeIf { gaps.all { abs(it - mean) <= mean * 0.2 + 1 } }
        }
        var i = 0
        while (i < lines.size) {
            var chain = chainFrom(i)
            if (chain == null) { i++; continue }
            // A thick first line followed by a complete staff is a beam above the staff.
            if (lines[chain[0]].thickness > thick) chainFrom(chain[1])?.let { chain = it }
            val c = chain!!
            val ys = DoubleArray(5) { lines[c[it]].y }
            val (xs, xe) = horizontalExtent(img, ys, minRun)
            staves.add(Staff(ys, c.map { lines[it].thickness }.sorted()[2].coerceAtLeast(lt), xs, xe))
            i = c[4] + 1
        }
        return staves
    }

    /** Leftmost/rightmost x where all five lines have ink (allowing small gaps). */
    private fun horizontalExtent(img: BitImage, ys: DoubleArray, minRun: Int): Pair<Int, Int> {
        fun lineInk(x: Int, yc: Double): Boolean {
            val y0 = yc.roundToInt()
            return img[x, y0] || img[x, y0 - 1] || img[x, y0 + 1]
        }
        val cols = BooleanArray(img.width) { x -> ys.count { lineInk(x, it) } >= 4 }
        // find longest stretch allowing gaps of up to minRun/4
        var bestS = 0; var bestE = 0; var s = -1; var lastTrue = -1
        val maxGap = maxOf(3, minRun / 4)
        for (x in 0 until img.width) {
            if (cols[x]) {
                if (s < 0 || x - lastTrue > maxGap) s = x
                lastTrue = x
                if (x - s > bestE - bestS) { bestS = s; bestE = x }
            }
        }
        return bestS to bestE
    }
}
