package de.pianotiles.core.omr

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Prepares a photo of a printed score for the recognition ("Noten abfotografieren"):
 *
 * 0. **Paper**: finds the sheet (the bright area in the middle) and paints the table around it white.
 * 1. **Lighting**: divides by an estimate of the paper brightness (large-scale maximum, smoothed), so shadows
 *    and uneven light disappear; then stretches the contrast.
 * 2. **Skew**: finds the angle at which the staff lines are most horizontal (projection profile) and rotates.
 * 3. **Crop**: cuts away the table/background around the page (dark or empty margins).
 * 4. **Size**: scales so that the staff space is about [TARGET_SPACE] px – like a PDF page rendered for the OMR.
 *
 * Works for photos taken roughly from above; strongly curved pages or steep perspective are not corrected.
 */
object PhotoPrep {
    const val TARGET_SPACE = 18.0
    const val MAX_SIDE = 2600
    /**
     * Brightest value of real paper during the preparation; exactly 255 marks the painted background (outside
     * the sheet, rotation fill) until [clearBorderBlobs] has used it.
     */
    private const val PAPER_MAX = 254

    class Result(val image: GrayImage, val angleDegrees: Double, val staffSpace: Double?, val scale: Double)

    fun prepare(photo: GrayImage): Result {
        // pass 1 on a working copy finds the staff size; pass 2 resamples the photo once to the final size
        val first = downscale(photo, MAX_SIDE)
        val (img1, angle1) = pipeline(first)
        val space1 = staffSpace(img1) ?: return Result(img1, angle1, null, first.width.toDouble() / photo.width)
        var scale = TARGET_SPACE / space1
        val w = img1.width * scale
        if (w > 3600) scale = 3600.0 / img1.width
        if (w < 1000) scale = 1000.0 / img1.width
        if (abs(scale - 1.0) <= 0.03) return Result(img1, angle1, space1, first.width.toDouble() / photo.width)
        val target = (first.width * scale).roundToInt()
        val second = resize(photo, target, (photo.height.toDouble() * target / photo.width).roundToInt())
        val (img2, angle2) = pipeline(second)
        return Result(img2, angle2, staffSpace(img2), target.toDouble() / photo.width)
    }

    private fun pipeline(input: GrayImage): Pair<GrayImage, Double> {
        var img = whitenBackground(input)
        img = normalizeLighting(img)
        val angle = estimateSkew(img)
        if (abs(angle) > 0.05) img = rotate(img, -angle)
        return cropToContent(img) to angle
    }

    /**
     * Paints everything outside the paper white. The paper is the bright connected area around the image
     * centre in a coarse, smoothed copy (threshold between paper and background brightness).
     */
    fun whitenBackground(img: GrayImage): GrayImage {
        val small = downscale(img, 400)
        val sw = small.width; val sh = small.height
        // smooth so that the notes do not split the paper
        val blur = GrayImage(sw, sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            var sum = 0; var n = 0
            for (yy in max(0, y - 3)..min(sh - 1, y + 3)) for (xx in max(0, x - 3)..min(sw - 1, x + 3)) { sum += small[xx, yy]; n++ }
            blur[x, y] = sum / n
        }
        val t = otsu(blur)
        // flood fill the bright area from the centre
        val paper = BooleanArray(sw * sh)
        val stack = ArrayDeque<Int>()
        val seeds = listOf(sw / 2 + sh / 2 * sw, sw / 3 + sh / 2 * sw, sw * 2 / 3 + sh / 2 * sw, sw / 2 + sh / 3 * sw, sw / 2 + sh * 2 / 3 * sw)
        for (sd in seeds) if (blur.data[sd].toInt() and 0xFF > t) { paper[sd] = true; stack.add(sd) }
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            val x = i % sw; val y = i / sw
            for ((nx, ny) in listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)) {
                if (nx !in 0 until sw || ny !in 0 until sh) continue
                val j = ny * sw + nx
                if (!paper[j] && (blur.data[j].toInt() and 0xFF) > t) { paper[j] = true; stack.add(j) }
            }
        }
        val count = paper.count { it }
        // no clear background (scan / photo of the paper only): leave as is
        if (count < sw * sh * 0.25 || count > sw * sh * 0.97) return img
        // fill holes inside the paper (dark notation blocks): a cell is paper if paper lies left and right of it
        val filled = paper.copyOf()
        for (y in 0 until sh) {
            val first = (0 until sw).firstOrNull { paper[y * sw + it] } ?: continue
            val last = (sw - 1 downTo 0).first { paper[y * sw + it] }
            for (x in first..last) filled[y * sw + x] = true
        }
        val out = GrayImage(img.width, img.height, img.data.copyOf())
        val fx = sw.toDouble() / img.width; val fy = sh.toDouble() / img.height
        val keep = filled
        for (y in 0 until img.height) {
            val cy = min(sh - 1, (y * fy).toInt())
            for (x in 0 until img.width) {
                if (!keep[cy * sw + min(sw - 1, (x * fx).toInt())]) out.data[y * img.width + x] = 255.toByte()
            }
        }
        return out
    }

    private fun otsu(img: GrayImage): Int {
        val hist = IntArray(256)
        for (b in img.data) hist[b.toInt() and 0xFF]++
        val total = img.data.size.toDouble()
        var sum = 0.0
        for (i in 0..255) sum += i * hist[i]
        var sumB = 0.0; var wB = 0.0; var best = 0.0; var threshold = 128
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += t * hist[t]
            val mB = sumB / wB; val mF = (sum - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; threshold = t }
        }
        return threshold
    }

    /** Box-filtered downscale so the longer side is at most [maxSide]. */
    fun downscale(img: GrayImage, maxSide: Int): GrayImage {
        val side = max(img.width, img.height)
        if (side <= maxSide) return img
        val f = maxSide.toDouble() / side
        return resize(img, (img.width * f).roundToInt(), (img.height * f).roundToInt())
    }

    /** Area-averaging (down) / bilinear (up) resize. */
    fun resize(img: GrayImage, w: Int, h: Int): GrayImage {
        val out = GrayImage(w, h)
        val sx = img.width.toDouble() / w
        val sy = img.height.toDouble() / h
        if (sx > 1.0 || sy > 1.0) {
            for (y in 0 until h) {
                val y0 = (y * sy).toInt(); val y1 = max(y0 + 1, min(img.height, ((y + 1) * sy).toInt()))
                for (x in 0 until w) {
                    val x0 = (x * sx).toInt(); val x1 = max(x0 + 1, min(img.width, ((x + 1) * sx).toInt()))
                    var sum = 0; var n = 0
                    for (yy in y0 until y1) { val row = yy * img.width; for (xx in x0 until x1) { sum += img.data[row + xx].toInt() and 0xFF; n++ } }
                    out.data[y * w + x] = (sum / n).toByte()
                }
            }
        } else {
            for (y in 0 until h) for (x in 0 until w) out.data[y * w + x] = bilinear(img, (x + 0.5) * sx - 0.5, (y + 0.5) * sy - 0.5).toByte()
        }
        return out
    }

    private fun bilinear(img: GrayImage, fx: Double, fy: Double): Int {
        val x0 = fx.toInt().coerceIn(0, img.width - 1); val y0 = fy.toInt().coerceIn(0, img.height - 1)
        val x1 = min(x0 + 1, img.width - 1); val y1 = min(y0 + 1, img.height - 1)
        val ax = (fx - x0).coerceIn(0.0, 1.0); val ay = (fy - y0).coerceIn(0.0, 1.0)
        val a = img[x0, y0] * (1 - ax) + img[x1, y0] * ax
        val b = img[x0, y1] * (1 - ax) + img[x1, y1] * ax
        return (a * (1 - ay) + b * ay).roundToInt()
    }

    /**
     * Divides by the local paper brightness. The background is the maximum over ~3 % of the page (removes the
     * ink), smoothed and interpolated back to full size.
     */
    fun normalizeLighting(img: GrayImage): GrayImage {
        val cell = max(8, max(img.width, img.height) / 120)
        val gw = (img.width + cell - 1) / cell
        val gh = (img.height + cell - 1) / cell
        // 90th percentile per cell (robust against single dark/bright pixels)
        val grid = DoubleArray(gw * gh)
        val hist = IntArray(256)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            hist.fill(0)
            var n = 0
            for (y in gy * cell until min(img.height, (gy + 1) * cell)) for (x in gx * cell until min(img.width, (gx + 1) * cell)) {
                hist[img.data[y * img.width + x].toInt() and 0xFF]++; n++
            }
            // pure white = painted background (see whitenBackground), not paper
            n -= hist[255]; hist[255] = 0
            if (n < cell) { grid[gy * gw + gx] = Double.NaN; continue }
            var acc = 0; var v = 254
            for (k in 254 downTo 0) { acc += hist[k]; if (acc >= n * 0.1) { v = k; break } }
            grid[gy * gw + gx] = v.toDouble()
        }
        fillGaps(grid, gw, gh)
        // max filter over the neighbourhood (staff systems are bigger than one cell), then smooth
        val maxed = filterGrid(grid, gw, gh, 2) { vals -> vals.max() }
        val smooth = filterGrid(maxed, gw, gh, 2) { vals -> vals.average() }
        val out = GrayImage(img.width, img.height)
        for (y in 0 until img.height) {
            val fy = (y + 0.5) / cell - 0.5
            for (x in 0 until img.width) {
                val fx = (x + 0.5) / cell - 0.5
                val raw = img.data[y * img.width + x].toInt() and 0xFF
                if (raw == 255) { out.data[y * img.width + x] = 255.toByte(); continue }
                val bg = gridAt(smooth, gw, gh, fx, fy).coerceAtLeast(30.0)
                val v = raw / bg * 255.0
                out.data[y * img.width + x] = v.coerceIn(0.0, PAPER_MAX.toDouble()).toInt().toByte()
            }
        }
        return stretch(out)
    }

    /** Replaces NaN cells by the mean of their known neighbours (repeated until all are filled). */
    private fun fillGaps(g: DoubleArray, w: Int, h: Int) {
        if (g.all { it.isNaN() }) { g.fill(255.0); return }
        while (g.any { it.isNaN() }) {
            val copy = g.copyOf()
            for (y in 0 until h) for (x in 0 until w) {
                if (!copy[y * w + x].isNaN()) continue
                var sum = 0.0; var n = 0
                for ((xx, yy) in listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)) {
                    if (xx in 0 until w && yy in 0 until h && !copy[yy * w + xx].isNaN()) { sum += copy[yy * w + xx]; n++ }
                }
                if (n > 0) g[y * w + x] = sum / n
            }
        }
    }

    private inline fun filterGrid(g: DoubleArray, w: Int, h: Int, r: Int, f: (List<Double>) -> Double): DoubleArray {
        val out = DoubleArray(g.size)
        val buf = ArrayList<Double>((2 * r + 1) * (2 * r + 1))
        for (y in 0 until h) for (x in 0 until w) {
            buf.clear()
            for (yy in max(0, y - r)..min(h - 1, y + r)) for (xx in max(0, x - r)..min(w - 1, x + r)) buf.add(g[yy * w + xx])
            out[y * w + x] = f(buf)
        }
        return out
    }

    private fun gridAt(g: DoubleArray, w: Int, h: Int, fx: Double, fy: Double): Double {
        val x0 = fx.toInt().coerceIn(0, w - 1); val y0 = fy.toInt().coerceIn(0, h - 1)
        val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
        val ax = (fx - x0).coerceIn(0.0, 1.0); val ay = (fy - y0).coerceIn(0.0, 1.0)
        val a = g[y0 * w + x0] * (1 - ax) + g[y0 * w + x1] * ax
        val b = g[y1 * w + x0] * (1 - ax) + g[y1 * w + x1] * ax
        return a * (1 - ay) + b * ay
    }

    /** Maps the 1st percentile to black and the 99th to white. */
    private fun stretch(img: GrayImage): GrayImage {
        val hist = IntArray(256)
        for (b in img.data) hist[b.toInt() and 0xFF]++
        val n = img.data.size
        var lo = 0; var acc = 0
        for (k in 0..255) { acc += hist[k]; if (acc >= n * 0.01) { lo = k; break } }
        var hi = 255; acc = 0
        for (k in 255 downTo 0) { acc += hist[k]; if (acc >= n * 0.01) { hi = k; break } }
        if (hi - lo < 40) return img
        val out = GrayImage(img.width, img.height)
        for (i in img.data.indices) {
            val raw = img.data[i].toInt() and 0xFF
            if (raw == 255) { out.data[i] = 255.toByte(); continue }
            val v = (raw - lo) * 255.0 / (hi - lo)
            out.data[i] = v.coerceIn(0.0, PAPER_MAX.toDouble()).toInt().toByte()
        }
        return out
    }

    /**
     * Skew angle in degrees (positive = lines go down to the right). Staff lines give the sharpest horizontal
     * projection profile at the right angle; searched coarse (0.5°) then fine (0.05°) within ±10°.
     */
    fun estimateSkew(img: GrayImage): Double {
        val small = downscale(img, 1000)
        val ink = BitImage.fromGray(small)
        val points = ArrayList<Long>()
        // only thin horizontal-ish ink (staff lines): pixel is ink and has ink left and right
        for (y in 0 until ink.height) for (x in 2 until ink.width - 2) {
            if (ink[x, y] && ink[x - 2, y] && ink[x + 2, y]) points.add((x.toLong() shl 32) or y.toLong())
        }
        if (points.size < 200) return 0.0
        val cx = small.width / 2.0
        fun score(deg: Double): Double {
            val t = tan(Math.toRadians(deg))
            val rows = IntArray(small.height * 2 + 4)
            for (p in points) {
                val x = (p ushr 32).toInt(); val y = (p and 0xffffffffL).toInt()
                val yy = (y - (x - cx) * t).roundToInt() + small.height / 2
                if (yy in rows.indices) rows[yy]++
            }
            var s = 0.0
            for (r in rows) s += r.toDouble() * r
            return s
        }
        var best = 0.0; var bestScore = score(0.0)
        var a = -10.0
        while (a <= 10.0) { val s = score(a); if (s > bestScore) { bestScore = s; best = a }; a += 0.5 }
        val coarse = best
        a = coarse - 0.5
        while (a <= coarse + 0.5) { val s = score(a); if (s > bestScore) { bestScore = s; best = a }; a += 0.05 }
        return best
    }

    /** Rotates by [deg] degrees around the centre (white fill). */
    fun rotate(img: GrayImage, deg: Double): GrayImage {
        val r = Math.toRadians(deg)
        val c = cos(r); val s = sin(r)
        val w = img.width; val h = img.height
        val cx = w / 2.0; val cy = h / 2.0
        val out = GrayImage(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            // inverse mapping
            val dx = x - cx; val dy = y - cy
            val sx = c * dx + s * dy + cx
            val sy = -s * dx + c * dy + cy
            out.data[y * w + x] = if (sx < 0 || sy < 0 || sx > w - 1 || sy > h - 1) 255.toByte() else bilinear(img, sx, sy).toByte()
        }
        return out
    }

    /**
     * Removes margins: dark borders (table around the paper) become white, then the image is cut to the
     * area with ink plus a margin.
     */
    fun cropToContent(input: GrayImage): GrayImage {
        val img = clearBorderBlobs(input)
        val w = img.width; val h = img.height
        // columns/rows that are mostly dark belong to the background around the page
        fun darkCol(x: Int): Boolean { var d = 0; for (y in 0 until h step 2) if (img[x, y] < 100) d++; return d > h / 2 * 0.6 }
        fun darkRow(y: Int): Boolean { var d = 0; for (x in 0 until w step 2) if (img[x, y] < 100) d++; return d > w / 2 * 0.6 }
        var left = 0; while (left < w / 3 && darkCol(left)) left++
        var right = w - 1; while (right > w * 2 / 3 && darkCol(right)) right--
        var top = 0; while (top < h / 3 && darkRow(top)) top++
        var bottom = h - 1; while (bottom > h * 2 / 3 && darkRow(bottom)) bottom--
        // a few more pixels: the paper edge is often a gradient
        val pad = max(w, h) / 200
        left = min(w - 1, left + if (left > 0) pad else 0); right = max(left + 1, right - if (right < w - 1) pad else 0)
        top = min(h - 1, top + if (top > 0) pad else 0); bottom = max(top + 1, bottom - if (bottom < h - 1) pad else 0)
        // ink bounding box inside the paper
        val ink = BitImage.fromGray(img)
        var ix0 = right; var ix1 = left; var iy0 = bottom; var iy1 = top
        for (y in top..bottom) {
            var cnt = 0
            for (x in left..right) if (ink[x, y]) { cnt++; if (x < ix0) ix0 = x; if (x > ix1) ix1 = x }
            if (cnt > 0) { if (y < iy0) iy0 = y; if (y > iy1) iy1 = y }
        }
        if (ix1 <= ix0 || iy1 <= iy0) return img
        val m = max(w, h) / 40
        val x0 = max(left, ix0 - m); val x1 = min(right, ix1 + m)
        val y0 = max(top, iy0 - m); val y1 = min(bottom, iy1 + m)
        if (x0 == 0 && y0 == 0 && x1 == w - 1 && y1 == h - 1) return img
        val out = GrayImage(x1 - x0 + 1, y1 - y0 + 1)
        for (y in y0..y1) System.arraycopy(img.data, y * w + x0, out.data, (y - y0) * out.width, out.width)
        return out
    }

    /**
     * Dark areas connected to the image border (leftovers of the table, the paper edge, fingers) become white.
     * Printed music keeps a margin to the paper edge, so it never touches the border.
     */
    fun clearBorderBlobs(img: GrayImage): GrayImage {
        val w = img.width; val h = img.height
        val dark = BitImage.fromGray(img)
        val seen = BooleanArray(w * h)
        val stack = ArrayDeque<Int>()
        // walk through ink and through the painted background (255) – paper stops the walk
        fun push(x: Int, y: Int) {
            val i = y * w + x
            if (!seen[i] && (dark.data[i] || img.data[i] == 255.toByte())) { seen[i] = true; stack.add(i) }
        }
        for (x in 0 until w) { push(x, 0); push(x, h - 1) }
        for (y in 0 until h) { push(0, y); push(w - 1, y) }
        if (stack.isEmpty()) return img
        val out = GrayImage(w, h, img.data.copyOf())
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            out.data[i] = 255.toByte()
            val x = i % w; val y = i / w
            if (x > 0) push(x - 1, y); if (x < w - 1) push(x + 1, y)
            if (y > 0) push(x, y - 1); if (y < h - 1) push(x, y + 1)
        }
        return out
    }

    /**
     * Staff space (line distance) in pixels: the most frequent "line + gap" period of vertical runs, or null
     * when no staff lines are found.
     */
    fun staffSpace(img: GrayImage): Double? {
        val ink = BitImage.fromGray(img)
        val hist = IntArray(200)
        for (x in 0 until ink.width step 3) {
            var y = 0
            var lastBlackStart = -1
            while (y < ink.height) {
                if (ink[x, y]) {
                    val s = y
                    while (y < ink.height && ink[x, y]) y++
                    val len = y - s
                    if (len <= 6 && lastBlackStart >= 0) { val d = s - lastBlackStart; if (d in 4 until hist.size) hist[d]++ }
                    lastBlackStart = if (len <= 6) s else -1
                } else y++
            }
        }
        val peak = hist.indices.maxBy { hist[it] }
        if (hist[peak] < 30) return null
        // sub-pixel: weighted mean around the peak
        var sw = 0.0; var s = 0.0
        for (d in max(4, peak - 1)..min(hist.size - 1, peak + 1)) { sw += hist[d]; s += d * hist[d].toDouble() }
        return s / sw
    }
}
