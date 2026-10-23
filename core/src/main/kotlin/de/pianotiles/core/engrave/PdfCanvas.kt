package de.pianotiles.core.engrave

import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.Deflater

/**
 * A minimal vector PDF writer (no dependencies, runs on Android and the JVM). Coordinates are in points with the
 * origin at the top left and y pointing down; they are flipped when written. Text uses the standard Helvetica fonts
 * (WinAnsi encoding), music symbols are filled glyph outlines ([Glyphs]).
 */
class PdfCanvas(val width: Float = 595.28f, val height: Float = 841.89f) {

    private val pages = ArrayList<StringBuilder>()
    private var cur = StringBuilder()

    init { pages.add(cur) }

    val pageCount: Int get() = pages.size

    fun newPage() { cur = StringBuilder(); pages.add(cur) }

    private fun n(v: Float): String {
        val r = Math.round(v * 100f) / 100f
        return if (r == r.toInt().toFloat()) r.toInt().toString() else String.format(Locale.ROOT, "%.2f", r)
    }
    private fun py(y: Float) = height - y

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, roundCap: Boolean = false) {
        cur.append(n(width)).append(" w ").append(if (roundCap) "1" else "0").append(" J ")
            .append(n(x1)).append(' ').append(n(py(y1))).append(" m ")
            .append(n(x2)).append(' ').append(n(py(y2))).append(" l S\n")
    }

    fun fillRect(x: Float, y: Float, w: Float, h: Float) {
        cur.append(n(x)).append(' ').append(n(py(y + h))).append(' ').append(n(w)).append(' ').append(n(h)).append(" re f\n")
    }

    /** Filled polygon through the given points (x0, y0, x1, y1, …). */
    fun fillPolygon(vararg xy: Float) {
        for (i in xy.indices step 2) {
            cur.append(n(xy[i])).append(' ').append(n(py(xy[i + 1]))).append(if (i == 0) " m " else " l ")
        }
        cur.append("h f\n")
    }

    /** Filled shape bounded by two cubic curves from (x0,y0) to (x3,y3): outer controls c1/c2, inner d1/d2. */
    fun fillLens(x0: Float, y0: Float, c1x: Float, c1y: Float, c2x: Float, c2y: Float, x3: Float, y3: Float,
                 d2x: Float, d2y: Float, d1x: Float, d1y: Float) {
        cur.append(n(x0)).append(' ').append(n(py(y0))).append(" m ")
            .append(n(c1x)).append(' ').append(n(py(c1y))).append(' ').append(n(c2x)).append(' ').append(n(py(c2y))).append(' ')
            .append(n(x3)).append(' ').append(n(py(y3))).append(" c ")
            .append(n(d2x)).append(' ').append(n(py(d2y))).append(' ').append(n(d1x)).append(' ').append(n(py(d1y))).append(' ')
            .append(n(x0)).append(' ').append(n(py(y0))).append(" c h f\n")
    }

    /** Draws glyph [code] with its origin at (x, y); [sx]/[sy] = points per font unit. */
    fun glyph(code: String, x: Float, y: Float, sx: Float, sy: Float = sx) {
        val path = Glyphs.pdfPath(code) ?: return
        cur.append("q ").append(String.format(Locale.ROOT, "%.5f", sx)).append(" 0 0 ").append(String.format(Locale.ROOT, "%.5f", sy)).append(' ')
            .append(n(x)).append(' ').append(n(py(y))).append(" cm\n").append(path).append(" f Q\n")
    }

    enum class Font(val res: String, val base: String) { REGULAR("F1", "Helvetica"), BOLD("F2", "Helvetica-Bold"), ITALIC("F3", "Helvetica-Oblique") }

    /** Text with its baseline at [y]; [align] −1 left, 0 centre, 1 right. */
    fun text(s: String, x: Float, y: Float, size: Float, font: Font = Font.REGULAR, align: Int = -1) {
        val w = textWidth(s, size, font)
        val left = when (align) { 0 -> x - w / 2; 1 -> x - w; else -> x }
        cur.append("BT /").append(font.res).append(' ').append(n(size)).append(" Tf ")
            .append(n(left)).append(' ').append(n(py(y))).append(" Td (").append(escape(s)).append(") Tj ET\n")
    }

    fun textWidth(s: String, size: Float, font: Font = Font.REGULAR): Float =
        s.sumOf { c -> (if (c.code in 32..126) HELVETICA[c.code - 32] else 556).toDouble() }.toFloat() / 1000f * size *
            (if (font == Font.BOLD) 1.06f else 1f)

    private fun escape(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when {
                c == '(' || c == ')' || c == '\\' -> sb.append('\\').append(c)
                c.code in 32..126 -> sb.append(c)
                else -> {
                    val b = String(charArrayOf(c)).toByteArray(charset("windows-1252"))
                    val code = if (b.size == 1 && b[0].toInt() != '?'.code) b[0].toInt() and 0xFF else '?'.code
                    sb.append('\\').append(Integer.toOctalString(code).padStart(3, '0'))
                }
            }
        }
        return sb.toString()
    }

    /** The finished PDF file. */
    fun toBytes(title: String = ""): ByteArray {
        val out = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        fun obj(body: ByteArray) {
            offsets.add(out.size())
            out.write("${offsets.size} 0 obj\n".toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.write("\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        }
        fun obj(s: String) = obj(s.toByteArray(Charsets.ISO_8859_1))
        out.write("%PDF-1.4\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1))
        // 1 catalog, 2 pages, 3–5 fonts, 6 info, then per page: page + content
        val firstPage = 7
        obj("<< /Type /Catalog /Pages 2 0 R >>")
        val kids = pages.indices.joinToString(" ") { "${firstPage + it * 2} 0 R" }
        obj("<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>")
        for (f in Font.entries) obj("<< /Type /Font /Subtype /Type1 /BaseFont /${f.base} /Encoding /WinAnsiEncoding >>")
        obj("<< /Title (${escape(title)}) /Producer (PianoTiles) >>")
        for ((i, p) in pages.withIndex()) {
            obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${n(width)} ${n(height)}] " +
                "/Resources << /Font << /F1 3 0 R /F2 4 0 R /F3 5 0 R >> >> /Contents ${firstPage + i * 2 + 1} 0 R >>")
            val raw = ("0 g 0 G\n" + p).toByteArray(Charsets.ISO_8859_1)
            val deflater = Deflater(Deflater.BEST_COMPRESSION).apply { setInput(raw); finish() }
            val buf = ByteArrayOutputStream()
            val tmp = ByteArray(8192)
            while (!deflater.finished()) buf.write(tmp, 0, deflater.deflate(tmp))
            deflater.end()
            val data = buf.toByteArray()
            val head = "<< /Length ${data.size} /Filter /FlateDecode >>\nstream\n".toByteArray(Charsets.ISO_8859_1)
            obj(head + data + "\nendstream".toByteArray(Charsets.ISO_8859_1))
        }
        val xref = out.size()
        val sb = StringBuilder("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) sb.append(String.format(Locale.ROOT, "%010d 00000 n \n", o))
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R /Info 6 0 R >>\nstartxref\n$xref\n%%EOF\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    private companion object {
        /** Helvetica advance widths (1/1000 em) for ASCII 32…126 (Adobe AFM). */
        val HELVETICA = intArrayOf(
            278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
            556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
            1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
            667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
            333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
            556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584,
        )
    }
}

/** Bravura glyph outlines (SMuFL code points), converted from SVG path syntax to PDF path operators. */
object Glyphs {
    /** Font units per staff space (SMuFL: 1 em = 4 staff spaces = 1000 units). */
    const val UNITS_PER_SPACE = 250f

    private class Glyph(val advance: Float, val svg: String) { val pdf: String by lazy { svgToPdf(svg) } }

    private val glyphs: Map<String, Glyph> by lazy {
        val text = Glyphs::class.java.getResourceAsStream("/engrave/bravura.txt")!!.bufferedReader().readText()
        text.lines().filter { it.isNotBlank() }.associate { line ->
            val (code, adv, d) = line.split('\t')
            code to Glyph(adv.toFloat(), d)
        }
    }

    fun pdfPath(code: String): String? = glyphs[code]?.pdf

    /** Advance width in staff spaces. */
    fun advance(code: String): Float = (glyphs[code]?.advance ?: 0f) / UNITS_PER_SPACE

    /** Converts SVG path data (M/m L/l H/h V/v C/c S/s Q/q Z/z; y up) to absolute PDF operators. */
    internal fun svgToPdf(d: String): String {
        val tokens = Regex("[MmLlHhVvCcSsQqZz]|-?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?").findAll(d).map { it.value }.toList()
        val sb = StringBuilder()
        var i = 0
        var cmd = 'M'
        var x = 0f; var y = 0f
        var sx = 0f; var sy = 0f // subpath start
        var lcx = 0f; var lcy = 0f // last cubic control point (for S)
        var lastWasCubic = false
        fun num(): Float = tokens[i++].toFloat()
        fun f(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else String.format(Locale.ROOT, "%.2f", v)
        while (i < tokens.size) {
            val t = tokens[i]
            if (t[0].isLetter()) { cmd = t[0]; i++; if (cmd == 'Z' || cmd == 'z') { sb.append("h "); x = sx; y = sy; lastWasCubic = false; continue } }
            val rel = cmd.isLowerCase()
            when (cmd.uppercaseChar()) {
                'M' -> {
                    val nx = num() + if (rel) x else 0f; val ny = num() + if (rel) y else 0f
                    x = nx; y = ny; sx = x; sy = y
                    sb.append(f(x)).append(' ').append(f(y)).append(" m ")
                    cmd = if (rel) 'l' else 'L' // further pairs are line-tos
                    lastWasCubic = false
                }
                'L' -> {
                    x = num() + if (rel) x else 0f; y = num() + if (rel) y else 0f
                    sb.append(f(x)).append(' ').append(f(y)).append(" l "); lastWasCubic = false
                }
                'H' -> { x = num() + if (rel) x else 0f; sb.append(f(x)).append(' ').append(f(y)).append(" l "); lastWasCubic = false }
                'V' -> { y = num() + if (rel) y else 0f; sb.append(f(x)).append(' ').append(f(y)).append(" l "); lastWasCubic = false }
                'C' -> {
                    val bx = if (rel) x else 0f; val by = if (rel) y else 0f
                    val x1 = num() + bx; val y1 = num() + by; val x2 = num() + bx; val y2 = num() + by
                    val ex = num() + bx; val ey = num() + by
                    sb.append(f(x1)).append(' ').append(f(y1)).append(' ').append(f(x2)).append(' ').append(f(y2)).append(' ')
                        .append(f(ex)).append(' ').append(f(ey)).append(" c ")
                    lcx = x2; lcy = y2; x = ex; y = ey; lastWasCubic = true
                }
                'S' -> {
                    val bx = if (rel) x else 0f; val by = if (rel) y else 0f
                    val x1 = if (lastWasCubic) 2 * x - lcx else x; val y1 = if (lastWasCubic) 2 * y - lcy else y
                    val x2 = num() + bx; val y2 = num() + by; val ex = num() + bx; val ey = num() + by
                    sb.append(f(x1)).append(' ').append(f(y1)).append(' ').append(f(x2)).append(' ').append(f(y2)).append(' ')
                        .append(f(ex)).append(' ').append(f(ey)).append(" c ")
                    lcx = x2; lcy = y2; x = ex; y = ey; lastWasCubic = true
                }
                'Q' -> {
                    val bx = if (rel) x else 0f; val by = if (rel) y else 0f
                    val qx = num() + bx; val qy = num() + by; val ex = num() + bx; val ey = num() + by
                    val x1 = x + 2f / 3f * (qx - x); val y1 = y + 2f / 3f * (qy - y)
                    val x2 = ex + 2f / 3f * (qx - ex); val y2 = ey + 2f / 3f * (qy - ey)
                    sb.append(f(x1)).append(' ').append(f(y1)).append(' ').append(f(x2)).append(' ').append(f(y2)).append(' ')
                        .append(f(ex)).append(' ').append(f(ey)).append(" c ")
                    x = ex; y = ey; lastWasCubic = false
                }
                else -> i++
            }
        }
        return sb.toString().trim()
    }
}
