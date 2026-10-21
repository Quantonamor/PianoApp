package de.pianotiles.core.omr

/** 8-bit grayscale image, 0 = black, 255 = white. Platform independent (filled from an Android Bitmap). */
class GrayImage(val width: Int, val height: Int, val data: ByteArray = ByteArray(width * height)) {
    init { require(data.size == width * height) }
    operator fun get(x: Int, y: Int): Int = data[y * width + x].toInt() and 0xFF
    operator fun set(x: Int, y: Int, v: Int) { data[y * width + x] = v.coerceIn(0, 255).toByte() }

    companion object {
        /** From ARGB pixels (as returned by Bitmap.getPixels). Transparent pixels count as white. */
        fun fromArgb(width: Int, height: Int, argb: IntArray): GrayImage {
            val img = GrayImage(width, height)
            for (i in argb.indices) {
                val c = argb[i]
                val a = (c ushr 24) and 0xFF
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val lum = (r * 299 + g * 587 + b * 114) / 1000
                img.data[i] = (255 - (255 - lum) * a / 255).toByte()
            }
            return img
        }
    }
}

/** Binary image: true = ink. */
class BitImage(val width: Int, val height: Int, val data: BooleanArray = BooleanArray(width * height)) {
    operator fun get(x: Int, y: Int): Boolean =
        x in 0 until width && y in 0 until height && data[y * width + x]

    operator fun set(x: Int, y: Int, v: Boolean) {
        if (x in 0 until width && y in 0 until height) data[y * width + x] = v
    }

    fun copy() = BitImage(width, height, data.copyOf())

    fun crop(x0: Int, y0: Int, x1: Int, y1: Int): BitImage {
        val cx0 = x0.coerceIn(0, width); val cy0 = y0.coerceIn(0, height)
        val cx1 = x1.coerceIn(cx0, width); val cy1 = y1.coerceIn(cy0, height)
        val out = BitImage(cx1 - cx0, cy1 - cy0)
        for (y in cy0 until cy1) System.arraycopy(data, y * width + cx0, out.data, (y - cy0) * out.width, cx1 - cx0)
        return out
    }

    /** Length of the vertical ink run through (x, y), or 0 if (x, y) is white. */
    fun verticalRun(x: Int, y: Int): IntRange? {
        if (!this[x, y]) return null
        var top = y; while (this[x, top - 1]) top--
        var bottom = y; while (this[x, bottom + 1]) bottom++
        return top..bottom
    }

    /** Morphological erosion with a (2r+1)-square, done separably with sliding counts. */
    fun erode(r: Int): BitImage = morph(r, erode = true)
    fun dilate(r: Int): BitImage = morph(r, erode = false)
    fun open(r: Int): BitImage = erode(r).dilate(r)

    private fun morph(r: Int, erode: Boolean): BitImage {
        if (r <= 0) return copy()
        val k = 2 * r + 1
        val tmp = BooleanArray(width * height)
        val prefix = IntArray(maxOf(width, height) + 1)
        // Horizontal pass (pixels outside the image count as background).
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) prefix[x + 1] = prefix[x] + if (data[row + x]) 1 else 0
            for (x in 0 until width) {
                val c = prefix[minOf(width, x + r + 1)] - prefix[maxOf(0, x - r)]
                tmp[row + x] = if (erode) c == k else c > 0
            }
        }
        val out = BooleanArray(width * height)
        // Vertical pass.
        for (x in 0 until width) {
            for (y in 0 until height) prefix[y + 1] = prefix[y] + if (tmp[y * width + x]) 1 else 0
            for (y in 0 until height) {
                val c = prefix[minOf(height, y + r + 1)] - prefix[maxOf(0, y - r)]
                out[y * width + x] = if (erode) c == k else c > 0
            }
        }
        return BitImage(width, height, out)
    }

    companion object {
        /** Otsu threshold binarisation. */
        fun fromGray(img: GrayImage): BitImage {
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
                val mB = sumB / wB
                val mF = (sum - sumB) / wF
                val between = wB * wF * (mB - mF) * (mB - mF)
                if (between > best) { best = between; threshold = t }
            }
            // Clean digital scores are almost bimodal; keep a sensible range.
            threshold = threshold.coerceIn(60, 200)
            val out = BitImage(img.width, img.height)
            for (i in img.data.indices) out.data[i] = (img.data[i].toInt() and 0xFF) <= threshold
            return out
        }
    }
}

/** A connected component (blob) of ink pixels. */
class Blob(
    var minX: Int, var minY: Int, var maxX: Int, var maxY: Int,
    var area: Int = 0, var sumX: Long = 0, var sumY: Long = 0,
) {
    val width get() = maxX - minX + 1
    val height get() = maxY - minY + 1
    val cx get() = sumX.toDouble() / area
    val cy get() = sumY.toDouble() / area
    val fill get() = area.toDouble() / (width * height)
    /** Pixel coordinates packed as y * imageWidth + x (filled only when requested). */
    var pixels: IntArray = IntArray(0)
}

object Components {
    /**
     * 8-connected labelling of ink pixels ([ink] = true) or background pixels ([ink] = false, 4-connected).
     * Pixel lists are kept when [keepPixels] is true.
     */
    fun find(img: BitImage, ink: Boolean = true, keepPixels: Boolean = false): List<Blob> {
        val w = img.width; val h = img.height
        val visited = BooleanArray(w * h)
        val queue = IntArray(w * h)
        val blobs = ArrayList<Blob>()
        for (start in 0 until w * h) {
            if (visited[start] || img.data[start] != ink) continue
            var head = 0; var tail = 0
            queue[tail++] = start; visited[start] = true
            val b = Blob(w, h, -1, -1)
            while (head < tail) {
                val p = queue[head++]
                val x = p % w; val y = p / w
                b.area++; b.sumX += x; b.sumY += y
                if (x < b.minX) b.minX = x; if (x > b.maxX) b.maxX = x
                if (y < b.minY) b.minY = y; if (y > b.maxY) b.maxY = y
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    if (!ink && dx != 0 && dy != 0) continue
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val q = ny * w + nx
                    if (!visited[q] && img.data[q] == ink) { visited[q] = true; queue[tail++] = q }
                }
            }
            if (keepPixels) b.pixels = queue.copyOfRange(0, tail)
            blobs.add(b)
        }
        return blobs
    }
}
