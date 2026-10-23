package de.pianotiles.core.hands

import de.pianotiles.core.model.Hand
import de.pianotiles.core.model.Pitch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Renders realistic, half-transparent hands into an ARGB pixel buffer.
 *
 * The palm is one rounded, domed body (a super-ellipse, wider at the knuckles than at the wrist); each finger is a
 * chain of round cones (three phalanges getting thinner, the thumb two and set on the side of the palm). Every
 * pixel takes the highest surface over it (a height field), so the shapes merge into one 3D body that is lit by a
 * soft light from the upper left (diffuse, ambient, specular highlight); nails sit on the fingertips, faint creases
 * mark the joints, a soft shadow falls on the keys. Over the keys the hand is half transparent so the keys stay
 * readable, below them nearly opaque.
 *
 * Speed: each shape only visits its own bounding box (z-buffer), the shading pass runs once per covered pixel; at
 * 0.4 resolution a phone needs a few milliseconds per frame. Pure Kotlin: tested and previewed on the JVM.
 */
object HandRenderer {

    /** Where a hand lies, in screen pixels (y down). Index 0 = thumb … 4 = little finger. */
    class Geometry(
        val hand: Hand,
        val tips: Array<FloatArray>,      // [x, y] of each fingertip
        val knuckles: Array<FloatArray>,  // [x, y] of each finger root (thumb: on the side of the palm)
        val fingerWidth: Float,
        val palmTop: Float, val palmBottom: Float, val palmLeft: Float, val palmRight: Float,
        val down: FloatArray,             // 0..1 per finger: pressing
        /** Screen x of the player's body (middle of a full piano, between E4 and F4): the forearms come from there. */
        val bodyX: Float = Float.NaN,
        /** How far in (smallest y) each finger may rest when it does not press: white keys only below the black ones. */
        val restMinY: FloatArray = FloatArray(5),
        /** Width of a white key (the hand's size follows it). */
        val keyW: Float = fingerWidth / 0.66f,
    )

    /**
     * Places the hand of [pose] on a keyboard: [keyX] maps the key coordinate ([KeyX]) to screen x, [keyW] is the
     * width of a white key, [keysH] the height of the keys; the palms lie in the strip below (down to [height]).
     */
    fun layout(pose: HandPose, keyX: (Float) -> Float, keyW: Float, keysH: Float, height: Float): Geometry {
        val dir = if (pose.hand == Hand.RIGHT) 1f else -1f
        val fw = (keyW * 0.66f).coerceIn(9f, 40f)
        val zone = (height - keysH).coerceAtLeast(keysH * 0.3f)
        // where each fingertip touches its key (fraction of the key length, 1 = front edge): like a real hand the
        // long middle finger reaches furthest in, thumb and little finger play nearer the front
        val whiteY = floatArrayOf(0.88f, 0.76f, 0.71f, 0.74f, 0.81f)
        val blackY = floatArrayOf(0.52f, 0.42f, 0.38f, 0.40f, 0.46f)
        val tips = Array(5) { f ->
            val black = pose.pitch[f] >= 0 && Pitch.isBlackKey(pose.pitch[f])
            floatArrayOf(keyX(pose.x[f]), keysH * (if (black) blackY[f] else whiteY[f]) + keysH * 0.04f * pose.down[f])
        }
        val centre = (tips[1][0] + tips[2][0] + tips[3][0] + tips[4][0]) / 4f
        val knuckleY = floatArrayOf(0f, 0.10f, 0.05f, 0.10f, 0.18f)
        val knuckles = Array(5) { f -> floatArrayOf(centre + (tips[f][0] - centre) * 0.78f, keysH + zone * knuckleY[f]) }
        // palm below the knuckles of index … little finger
        val kx = listOf(knuckles[1][0], knuckles[4][0])
        val palmLeft = kx.min() - fw * 0.75f
        val palmRight = kx.max() + fw * 0.75f
        val palmTop = keysH + zone * 0.02f
        val palmBottom = height + zone * 0.6f
        // thumb root on the side of the palm (index side), a third down
        val sideX = if (dir > 0) palmLeft + fw * 0.35f else palmRight - fw * 0.35f
        knuckles[0] = floatArrayOf(sideX, palmTop + (palmBottom - palmTop) * 0.36f)
        return Geometry(pose.hand, tips, knuckles, fw, palmTop, palmBottom, palmLeft, palmRight, pose.down.copyOf(),
            (keyX(KeyX.of(64)) + keyX(KeyX.of(65))) / 2f,
            FloatArray(5) { f ->
                when {
                    pose.pitch[f] >= 0 && Pitch.isBlackKey(pose.pitch[f]) -> 0f
                    // a free finger above a black key stays in front of it (resting on the white key's wide part)
                    (21..108).any { p -> Pitch.isBlackKey(p) && abs(KeyX.of(p) - pose.x[f]) < 0.4f } -> keysH * 0.68f
                    else -> keysH * 0.5f
                }
            }, keyW)
    }

    private class Cone(
        val ax: Float, val ay: Float, val bx: Float, val by: Float, val ra: Float, val rb: Float,
        val z: Float, val finger: Int, val segment: Int,
    ) {
        val dx = bx - ax; val dy = by - ay
        val len2 = max(1e-3f, dx * dx + dy * dy)
        val minX = min(ax - ra, bx - rb); val maxX = max(ax + ra, bx + rb)
        val minY = min(ay - ra, by - rb); val maxY = max(ay + ra, by + rb)
    }

    private fun fingers(g: Geometry): List<Cone> {
        val out = ArrayList<Cone>()
        val w = g.fingerWidth
        for (f in 0 until 5) {
            val base = g.knuckles[f]; val tip = g.tips[f]
            val fwF = w * (if (f == 0) 1.18f else if (f == 4) 0.84f else 1f)
            val dx = tip[0] - base[0]; val dy = tip[1] - base[1]
            val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
            val nx = -dy / len; val ny = dx / len
            if (f == 0) {
                // thumb: two phalanges plus the fleshy root
                val bend = len * 0.06f * (if (g.hand == Hand.RIGHT) -1f else 1f)
                val jx = base[0] + dx * 0.55f + nx * bend; val jy = base[1] + dy * 0.55f + ny * bend
                out += Cone(base[0], base[1], jx, jy, fwF * 0.62f, fwF * 0.5f, 0.35f * w, 0, 1)
                out += Cone(jx, jy, tip[0], tip[1], fwF * 0.5f, fwF * 0.43f, 0.3f * w, 0, 2)
                continue
            }
            // fingers curve over the keys: joints slightly to the side, the middle joint highest
            val bend = len * 0.03f
            val j1x = base[0] + dx * 0.44f + nx * bend; val j1y = base[1] + dy * 0.44f + ny * bend
            val j2x = base[0] + dx * 0.75f + nx * bend * 0.5f; val j2y = base[1] + dy * 0.75f + ny * bend * 0.5f
            val r0 = fwF * 0.54f; val r1 = fwF * 0.5f; val r2 = fwF * 0.46f; val r3 = fwF * 0.42f
            out += Cone(base[0], base[1] + w * 0.4f, j1x, j1y, r0, r1, 0.45f * w, f, 0)
            out += Cone(j1x, j1y, j2x, j2y, r1, r2, 0.5f * w, f, 1)
            out += Cone(j2x, j2y, tip[0], tip[1], r2, r3, 0.4f * w, f, 2)
        }
        return out
    }

    // light from the upper left, a bit towards the viewer
    private const val LX = -0.42f
    private const val LY = -0.52f
    private const val LZ = 0.74f

    // per-pixel scratch buffers (reused between frames)
    private var cap = 0
    private lateinit var hBuf: FloatArray
    private lateinit var sdBuf: FloatArray
    private lateinit var nxBuf: FloatArray
    private lateinit var nyBuf: FloatArray
    private lateinit var nzBuf: FloatArray
    private lateinit var tag: IntArray   // finger*4 + segment + 1, 0 = palm, -1 = nothing
    private lateinit var tBuf: FloatArray
    private lateinit var latBuf: FloatArray

    private fun ensure(n: Int) {
        if (n <= cap) return
        cap = n
        hBuf = FloatArray(n); sdBuf = FloatArray(n); nxBuf = FloatArray(n); nyBuf = FloatArray(n); nzBuf = FloatArray(n)
        tag = IntArray(n); tBuf = FloatArray(n); latBuf = FloatArray(n)
    }

    /**
     * Draws [hands] into [out] (ARGB, [width] × [height] pixels, cleared first). Screen coordinates are multiplied
     * by [scale] (e.g. 0.4 renders at 40 % resolution). Above [keysBottom] (screen y) the hand is drawn with
     * [alphaOverKeys], below it nearly opaque.
     */
    @Synchronized
    fun render(hands: List<Geometry>, out: IntArray, width: Int, height: Int, scale: Float, keysBottom: Float, alphaOverKeys: Float = 0.55f) {
        // the rigged 3D hand model when it is available, the drawn shapes as fallback
        if (hands.isNotEmpty() && Hand3D.render(hands, hands[0].keyW, out, width, height, scale, keysBottom, alphaOverKeys)) return
        java.util.Arrays.fill(out, 0)
        for (g in hands) renderHand(g, out, width, height, scale, keysBottom, alphaOverKeys)
    }

    private fun renderHand(g: Geometry, out: IntArray, width: Int, height: Int, scale: Float, keysBottom: Float, alphaKeys: Float) {
        val cs = fingers(g)
        val w = g.fingerWidth
        val px = 1f / scale
        // bounding box of the hand (+ shadow) in buffer pixels
        var minX = g.palmLeft; var maxX = g.palmRight; var minY = g.palmTop; var maxY = g.palmBottom
        for (c in cs) { minX = min(minX, c.minX); minY = min(minY, c.minY); maxX = max(maxX, c.maxX); maxY = max(maxY, c.maxY) }
        val shX = w * 0.16f; val shY = w * 0.28f
        val bx0 = ((minX - 2) * scale).toInt().coerceIn(0, width); val bx1 = ((maxX + shX + 2) * scale + 1).toInt().coerceIn(0, width)
        val by0 = ((minY - 2) * scale).toInt().coerceIn(0, height); val by1 = ((maxY + shY + 2) * scale + 1).toInt().coerceIn(0, height)
        val bw = bx1 - bx0; val bh = by1 - by0
        if (bw <= 0 || bh <= 0) return
        ensure(bw * bh)
        java.util.Arrays.fill(hBuf, 0, bw * bh, -1f)
        java.util.Arrays.fill(sdBuf, 0, bw * bh, Float.MAX_VALUE)
        java.util.Arrays.fill(tag, 0, bw * bh, -1)

        // palm: super-ellipse, wider at the top, domed
        val pcx = (g.palmLeft + g.palmRight) / 2; val pcy = (g.palmTop + g.palmBottom) / 2
        val halfH = (g.palmBottom - g.palmTop) / 2
        val halfWTop = (g.palmRight - g.palmLeft) / 2
        val dome = w * 1.25f
        fun palmQ(x: Float, y: Float): Float {
            val v = (y - pcy) / halfH                                  // -1 top … 1 bottom
            val hw = halfWTop * (1f - 0.12f * (v + 1f) / 2f)         // narrower towards the wrist
            val u = (x - pcx) / hw
            val u2 = u * u; val v2 = v * v
            return u2 * u2 + v2 * v2 * v2                            // flat sides, rounder top
        }
        val py0 = ((g.palmTop - 1) * scale).toInt().coerceIn(by0, by1); val py1 = ((g.palmBottom + 1) * scale + 1).toInt().coerceIn(by0, by1)
        val pxx0 = ((g.palmLeft - 1) * scale).toInt().coerceIn(bx0, bx1); val pxx1 = ((g.palmRight + 1) * scale + 1).toInt().coerceIn(bx0, bx1)
        val e = 1.2f // finite-difference step (screen px)
        for (yy in py0 until py1) {
            val sy = (yy + 0.5f) * px
            for (xx in pxx0 until pxx1) {
                val sx = (xx + 0.5f) * px
                val q = palmQ(sx, sy)
                val i = (yy - by0) * bw + (xx - bx0)
                val sd = (sqrt(sqrt(q)) - 1f) * min(halfWTop, halfH) * 0.9f
                if (sd < sdBuf[i]) sdBuf[i] = sd
                if (q < 1f) {
                    val h = dome * sqrt(sqrt(1f - q))
                    if (h > hBuf[i]) {
                        hBuf[i] = h; tag[i] = 0
                        val hx = dome * sqrt(sqrt(max(0f, 1f - palmQ(sx + e, sy)))) - dome * sqrt(sqrt(max(0f, 1f - palmQ(sx - e, sy))))
                        val hy = dome * sqrt(sqrt(max(0f, 1f - palmQ(sx, sy + e)))) - dome * sqrt(sqrt(max(0f, 1f - palmQ(sx, sy - e))))
                        var nx = -hx / (2 * e); var ny = -hy / (2 * e); var nz = 1f
                        val l = sqrt(nx * nx + ny * ny + nz * nz); nx /= l; ny /= l; nz /= l
                        nxBuf[i] = nx; nyBuf[i] = ny; nzBuf[i] = nz
                    }
                }
            }
        }
        // fingers
        val soft = w * 0.3f
        for (c in cs) {
            val cx0 = ((c.minX - 1) * scale).toInt().coerceIn(bx0, bx1); val cx1 = ((c.maxX + 1) * scale + 1).toInt().coerceIn(bx0, bx1)
            val cy0 = ((c.minY - 1) * scale).toInt().coerceIn(by0, by1); val cy1 = ((c.maxY + 1) * scale + 1).toInt().coerceIn(by0, by1)
            for (yy in cy0 until cy1) {
                val sy = (yy + 0.5f) * px
                for (xx in cx0 until cx1) {
                    val sx = (xx + 0.5f) * px
                    val qx = sx - c.ax; val qy = sy - c.ay
                    val t = ((qx * c.dx + qy * c.dy) / c.len2).coerceIn(0f, 1f)
                    val ex = qx - c.dx * t; val ey = qy - c.dy * t
                    val d = sqrt(ex * ex + ey * ey)
                    val r = c.ra + (c.rb - c.ra) * t
                    val i = (yy - by0) * bw + (xx - bx0)
                    val s = d - r
                    // finger roots merge smoothly with the palm, fingers keep their gaps
                    sdBuf[i] = if (c.segment <= 1 && sdBuf[i] < soft * 2) smin(sdBuf[i], s, soft) else min(sdBuf[i], s)
                    if (d < r) {
                        val hz = sqrt(r * r - d * d)
                        val h = hz + c.z
                        if (h > hBuf[i]) {
                            hBuf[i] = h; tag[i] = c.finger * 4 + c.segment + 1
                            nxBuf[i] = ex / r; nyBuf[i] = ey / r; nzBuf[i] = hz / r
                            tBuf[i] = t; latBuf[i] = d / r
                        }
                    }
                }
            }
        }
        // shading
        val hxv = LX; val hyv = LY; val hzv = LZ + 1f
        val hl = sqrt(hxv * hxv + hyv * hyv + hzv * hzv)
        val shXp = (shX * scale).toInt(); val shYp = (shY * scale).toInt()
        for (yy in by0 until by1) {
            val sy = (yy + 0.5f) * px
            val aHand = if (sy < keysBottom - 4) alphaKeys else if (sy > keysBottom + 8) 0.93f else alphaKeys + (0.93f - alphaKeys) * ((sy - keysBottom + 4) / 12f)
            for (xx in bx0 until bx1) {
                val i = (yy - by0) * bw + (xx - bx0)
                val o = yy * width + xx
                val sd = sdBuf[i]
                val cover = (0.5f - sd / (1.3f * px)).coerceIn(0f, 1f)
                // soft shadow: the hand's distance field moved down-right
                val sxs = xx - bx0 - shXp; val sys = yy - by0 - shYp
                var shadow = 0f
                if (sxs in 0 until bw && sys in 0 until bh) {
                    val sds = sdBuf[sys * bw + sxs]
                    shadow = ((1f - (sds + w * 0.2f) / (w * 0.75f)).coerceIn(0f, 1f)) * 0.3f
                }
                if (cover <= 0f || tag[i] < 0) {
                    if (shadow > 0.01f) out[o] = blend(out[o], 0f, 0f, 0f, shadow)
                    continue
                }
                val nx = nxBuf[i]; val ny = nyBuf[i]; val nz = nzBuf[i]
                val diffuse = max(0f, nx * LX + ny * LY + nz * LZ)
                val sp = max(0f, (nx * hxv + ny * hyv + nz * hzv) / hl).let { val s2 = it * it; val s4 = s2 * s2; s4 * s4 * s2 }
                val lit = 0.40f + 0.60f * diffuse
                // skin: warm light colour, redder in the shade
                var r = 135f + (247f - 135f) * lit
                var gg = 82f + (216f - 82f) * lit
                var b = 66f + (192f - 66f) * lit
                val tg = tag[i]
                if (tg > 0) {
                    val finger = (tg - 1) / 4; val seg = (tg - 1) % 4
                    val t = tBuf[i]; val lat = latBuf[i]
                    // nail towards the end of the last phalanx
                    if (seg == 2 && t > 0.48f && lat < 0.58f) {
                        val k = ((0.58f - lat) / 0.16f).coerceIn(0f, 1f) * ((t - 0.48f) / 0.1f).coerceIn(0f, 1f) * ((0.985f - t) / 0.06f).coerceIn(0f, 1f)
                        r += (253f - r) * 0.5f * k; gg += (230f - gg) * 0.5f * k; b += (222f - b) * 0.5f * k
                    }
                    // faint creases at the joints
                    if (seg in 1..2 && t < 0.07f && finger > 0) { r *= 0.9f; gg *= 0.85f; b *= 0.85f }
                    // pressing finger: warm glow at the tip
                    if (seg == 2 && g.down[finger] > 0f && t > 0.6f) {
                        val k = g.down[finger] * 0.35f * ((t - 0.6f) / 0.4f)
                        r += (255f - r) * k; gg += (205f - gg) * k * 0.6f
                    }
                }
                r += 255f * 0.32f * sp; gg += 255f * 0.32f * sp; b += 255f * 0.32f * sp
                var dst = out[o]
                if (shadow > 0.01f) dst = blend(dst, 0f, 0f, 0f, shadow * (1f - cover))
                out[o] = blend(dst, r.coerceIn(0f, 255f), gg.coerceIn(0f, 255f), b.coerceIn(0f, 255f), aHand * cover)
            }
        }
    }

    /** Polynomial smooth minimum. */
    private fun smin(a: Float, b: Float, k: Float): Float {
        val h = (0.5f + 0.5f * (b - a) / k).coerceIn(0f, 1f)
        return b + (a - b) * h - k * h * (1f - h)
    }

    /** "source over" for non-premultiplied ARGB. */
    private fun blend(dst: Int, r: Float, g: Float, b: Float, a: Float): Int {
        val da = (dst ushr 24 and 0xFF) / 255f
        val oa = a + da * (1f - a)
        if (oa <= 0f) return 0
        val dr = (dst shr 16 and 0xFF).toFloat(); val dg = (dst shr 8 and 0xFF).toFloat(); val db = (dst and 0xFF).toFloat()
        val nr = (r * a + dr * da * (1f - a)) / oa
        val ng = (g * a + dg * da * (1f - a)) / oa
        val nb = (b * a + db * da * (1f - a)) / oa
        return ((oa * 255f).toInt().coerceIn(0, 255) shl 24) or (nr.toInt().coerceIn(0, 255) shl 16) or
            (ng.toInt().coerceIn(0, 255) shl 8) or nb.toInt().coerceIn(0, 255)
    }

}
