package de.pianotiles.core.hands

import de.pianotiles.core.model.Hand
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A rigged 3D hand mesh (WebXR "generic-hand", MIT; converted by tools/hands/convert_hand.py) and a small
 * software renderer for it.
 *
 * Posing: the hand is placed palm down with the fingers pointing away from the player (up on the screen). Every
 * finger gets a spread angle at its root and a curl over its three joints, found so that the fingertip lands
 * exactly on its target point (the key it plays); the thumb is turned and curled the same way. Vertices follow the
 * joints by linear blend skinning. The triangles are rasterised with a z-buffer and lit per pixel (interpolated
 * normals: diffuse, ambient, specular, a bit of warm translucency at the edges); a soft shadow falls on the keys.
 * Pure Kotlin, previewed on the JVM by HandRenderTest.
 */
class HandModel private constructor(
    val positions: FloatArray, val normals: FloatArray, val joints: ByteArray, val weights: FloatArray,
    val indices: ShortArray, val jointPos: FloatArray, val names: List<String>,
) {
    val vertexCount = positions.size / 3

    fun joint(name: String) = names.indexOf(name)

    companion object {
        private val cache = HashMap<Hand, HandModel?>()

        @Synchronized
        fun get(hand: Hand): HandModel? = cache.getOrPut(hand) {
            runCatching {
                val res = if (hand == Hand.RIGHT) "/hands/hand_right.bin" else "/hands/hand_left.bin"
                val bytes = HandModel::class.java.getResourceAsStream(res)!!.use { it.readBytes() }
                val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val v = b.int; val t = b.int; val j = b.int
                val pos = FloatArray(v * 3) { b.float }
                val nor = FloatArray(v * 3) { b.float }
                val jts = ByteArray(v * 4).also { b.get(it) }
                val wts = FloatArray(v * 4) { b.float }
                val idx = ShortArray(t * 3) { b.short }
                val jp = FloatArray(j * 3) { b.float }
                val names = (0 until j).map { val n = b.get().toInt() and 0xFF; val a = ByteArray(n); b.get(a); String(a) }
                HandModel(pos, nor, jts, wts, idx, jp, names)
            }.getOrNull()
        }
    }
}

/** 3×4 affine transform (rotation + translation) in model space. */
private class Affine(val m: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)) {
    fun apply(x: Float, y: Float, z: Float, out: FloatArray, o: Int) {
        out[o] = m[0] * x + m[1] * y + m[2] * z + m[3]
        out[o + 1] = m[4] * x + m[5] * y + m[6] * z + m[7]
        out[o + 2] = m[8] * x + m[9] * y + m[10] * z + m[11]
    }
    fun rotate(x: Float, y: Float, z: Float, out: FloatArray, o: Int) {
        out[o] = m[0] * x + m[1] * y + m[2] * z
        out[o + 1] = m[4] * x + m[5] * y + m[6] * z
        out[o + 2] = m[8] * x + m[9] * y + m[10] * z
    }
    /** this ∘ other (apply other first). */
    fun times(o: Affine): Affine {
        val a = m; val b = o.m
        val r = FloatArray(12)
        for (row in 0 until 3) {
            for (col in 0 until 3) r[row * 4 + col] = a[row * 4] * b[col] + a[row * 4 + 1] * b[4 + col] + a[row * 4 + 2] * b[8 + col]
            r[row * 4 + 3] = a[row * 4] * b[3] + a[row * 4 + 1] * b[7] + a[row * 4 + 2] * b[11] + a[row * 4 + 3]
        }
        return Affine(r)
    }

    companion object {
        /** Rotation by [angle] about the axis ([ax],[ay],[az], normalised) through the point ([px],[py],[pz]). */
        fun rotation(px: Float, py: Float, pz: Float, ax: Float, ay: Float, az: Float, angle: Float): Affine {
            val c = cos(angle); val s = sin(angle); val t = 1 - c
            val r = floatArrayOf(
                t * ax * ax + c, t * ax * ay - s * az, t * ax * az + s * ay, 0f,
                t * ax * ay + s * az, t * ay * ay + c, t * ay * az - s * ax, 0f,
                t * ax * az - s * ay, t * ay * az + s * ax, t * az * az + c, 0f,
            )
            // translate so the pivot stays fixed: p - R p
            r[3] = px - (r[0] * px + r[1] * py + r[2] * pz)
            r[7] = py - (r[4] * px + r[5] * py + r[6] * pz)
            r[11] = pz - (r[8] * px + r[9] * py + r[10] * pz)
            return Affine(r)
        }
    }
}

/** Renders posed 3D hands into an ARGB buffer. */
object Hand3D {

    private val FINGERS = listOf(
        listOf("thumb-metacarpal", "thumb-phalanx-proximal", "thumb-phalanx-distal", "thumb-tip"),
        listOf("index-finger-phalanx-proximal", "index-finger-phalanx-intermediate", "index-finger-phalanx-distal", "index-finger-tip"),
        listOf("middle-finger-phalanx-proximal", "middle-finger-phalanx-intermediate", "middle-finger-phalanx-distal", "middle-finger-tip"),
        listOf("ring-finger-phalanx-proximal", "ring-finger-phalanx-intermediate", "ring-finger-phalanx-distal", "ring-finger-tip"),
        listOf("pinky-finger-phalanx-proximal", "pinky-finger-phalanx-intermediate", "pinky-finger-phalanx-distal", "pinky-finger-tip"),
    )
    private val METACARPALS = listOf("thumb-metacarpal", "index-finger-metacarpal", "middle-finger-metacarpal", "ring-finger-metacarpal", "pinky-finger-metacarpal")

    /** Screen placement of a model: screen = (side * z, y) * scale + offset; depth (towards the viewer) = side * x. */
    private class View(val side: Float, val scale: Float, val ox: Float, val oy: Float)

    private fun sx(v: View, x: Float, z: Float) = v.side * z * v.scale + v.ox
    private fun sy(v: View, y: Float) = y * v.scale + v.oy

    /**
     * Joint transforms so that the fingertips land on [tips] (screen pixels, index 0 = thumb).
     * Returns the per-joint affine transforms (model space) and the view.
     */
    private fun pose(model: HandModel, hand: Hand, tips: Array<FloatArray>, keyW: Float, down: FloatArray): Pair<Array<Affine>, View> {
        val side = if (hand == Hand.RIGHT) 1f else -1f // right: thumb at -z is on the left; left hand is mirrored
        val jp = model.jointPos
        fun p(i: Int, k: Int) = jp[i * 3 + k]
        // a real white key is 23.5 mm wide
        val scale = keyW / 0.0235f
        // palm normal towards the viewer, finger direction (rest) is -y, lateral = finger × normal
        val nx = side; val ny = 0f; val nz = 0f
        // place the hand: knuckle row (proximal joints of index…little) under the fingertips, at a distance that
        // leaves the fingers gently curved
        val prox = (1..4).map { model.joint(FINGERS[it][0]) }
        val tipsJ = (1..4).map { model.joint(FINGERS[it][3]) }
        val modelKx = prox.map { p(it, 2) * side }.average().toFloat()
        val targetKx = (1..4).map { tips[it][0] }.average().toFloat()
        val midLen = abs(p(tipsJ[1], 1) - p(prox[1], 1))
        val targetTipY = (1..4).map { tips[it][1] }.average().toFloat()
        val ox = targetKx - modelKx * scale
        // the hand moves towards the keys (fingers more curved) until the thumb and little finger reach as well
        var lift = 0f
        var result: Pair<Array<Affine>, View>? = null
        for (attempt in 0 until 4) {
            val knuckleY = targetTipY + midLen * scale * 0.78f - lift
            val oy = knuckleY - prox.map { p(it, 1) }.average().toFloat() * scale
            val view = View(side, scale, ox, oy)
            var worst = 0f
            val xf = solve(model, view, tips, side) { f, err -> if (f == 0 || f == 4) worst = maxOf(worst, err) }
            result = xf to view
            if (worst < keyW * 0.12f || lift > midLen * scale * 0.4f) break
            lift += worst * 0.9f
        }
        return result!!
    }

    private fun solve(model: HandModel, view: View, tips: Array<FloatArray>, side: Float, report: (Int, Float) -> Unit): Array<Affine> {
        val jp = model.jointPos
        fun p(i: Int, k: Int) = jp[i * 3 + k]
        val nx = side; val ny = 0f; val nz = 0f
        val xf = Array(model.names.size) { Affine() }
        val tmp = FloatArray(3)
        for (f in 0 until 5) {
            val chain = FINGERS[f].map { model.joint(it) }
            if (chain.any { it < 0 }) continue
            val (j0, j1, j2, jt) = chain
            // lateral axis of this finger (curl axis): direction × palm normal
            var dx = p(jt, 0) - p(j0, 0); var dy = p(jt, 1) - p(j0, 1); var dz = p(jt, 2) - p(j0, 2)
            val dl = sqrt(dx * dx + dy * dy + dz * dz); dx /= dl; dy /= dl; dz /= dl
            var ax = dy * nz - dz * ny; var ay = dz * nx - dx * nz; var az = dx * ny - dy * nx
            val al = sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(1e-6f); ax /= al; ay /= al; az /= al
            val target = tips[f]
            fun build(spread: Float, curl: Float): Array<Affine> {
                // curl bends towards the palm (away from the viewer); distribution over the joints like a relaxed hand
                val c0 = curl * (if (f == 0) 0.5f else 0.45f); val c1 = curl * (if (f == 0) 0.5f else 0.35f); val c2 = curl * 0.25f
                val a0 = Affine.rotation(p(j0, 0), p(j0, 1), p(j0, 2), nx, ny, nz, spread).times(
                    Affine.rotation(p(j0, 0), p(j0, 1), p(j0, 2), ax, ay, az, c0))
                val a1 = a0.times(Affine.rotation(p(j1, 0), p(j1, 1), p(j1, 2), ax, ay, az, c1))
                val a2 = a1.times(Affine.rotation(p(j2, 0), p(j2, 1), p(j2, 2), ax, ay, az, c2))
                return arrayOf(a0, a1, a2)
            }
            fun tipScreen(a: Array<Affine>): FloatArray {
                a[2].apply(p(jt, 0), p(jt, 1), p(jt, 2), tmp, 0)
                return floatArrayOf(sx(view, tmp[0], tmp[2]), sy(view, tmp[1]))
            }
            // curl sign: positive curl must move the tip away from the viewer (into the keys)
            var sign = 1f
            run {
                val a = build(0f, 0.3f)
                a[2].apply(p(jt, 0), p(jt, 1), p(jt, 2), tmp, 0)
                if (tmp[0] * side > p(jt, 0) * side) sign = -1f
            }
            // Newton steps with a numeric 2×2 Jacobian (spread and curl both move the tip on the screen;
            // for the thumb they are strongly coupled); damped, clamped, least squares when out of reach
            var spread = 0f; var curl = 0.35f
            repeat(if (f == 0) 20 else 12) {
                val t0 = tipScreen(build(spread, curl * sign))
                val ts = tipScreen(build(spread + 0.02f, curl * sign))
                val tc = tipScreen(build(spread, (curl + 0.02f) * sign))
                val a11 = (ts[0] - t0[0]) / 0.02f; val a21 = (ts[1] - t0[1]) / 0.02f
                val a12 = (tc[0] - t0[0]) / 0.02f; val a22 = (tc[1] - t0[1]) / 0.02f
                val ex = target[0] - t0[0]; val ey = target[1] - t0[1]
                // (JᵀJ + λI)⁻¹ Jᵀ e
                val lambda = 0.05f * (a11 * a11 + a21 * a21 + a12 * a12 + a22 * a22) + 1e-3f
                val m11 = a11 * a11 + a21 * a21 + lambda; val m12 = a11 * a12 + a21 * a22; val m22 = a12 * a12 + a22 * a22 + lambda
                val g1 = a11 * ex + a21 * ey; val g2 = a12 * ex + a22 * ey
                val det = m11 * m22 - m12 * m12
                if (abs(det) > 1e-9f) {
                    spread += ((m22 * g1 - m12 * g2) / det).coerceIn(-0.2f, 0.2f)
                    curl += ((m11 * g2 - m12 * g1) / det).coerceIn(-0.2f, 0.2f)
                }
                val sl = if (f == 0) 1.5f else 0.7f
                spread = spread.coerceIn(-sl, sl); curl = curl.coerceIn(if (f == 0) -1.2f else 0f, if (f == 0) 1.2f else 2.0f)
            }
            val a = build(spread, curl * sign)
            val t = tipScreen(a)
            report(f, t[1] - target[1])
            xf[j0] = a[0]; xf[j1] = a[1]; xf[j2] = a[2]; xf[jt] = a[2]
            if (f == 0) xf[model.joint("thumb-metacarpal")] = a[0]
        }
        return xf
    }

    // light from the upper left, towards the viewer (screen space: x right, y down, z towards the viewer)
    private const val LX = -0.40f
    private const val LY = -0.55f
    private const val LZ = 0.73f

    private var zcap = 0
    private var zbuf = FloatArray(0)
    private var nbuf = FloatArray(0)
    private var cover = ByteArray(0)
    private var fbuf = FloatArray(0) // forearm fade: 1 = opaque, 0 = gone

    /** Renders [hands] (tips in screen pixels) into [out]; see HandRenderer.render. */
    @Synchronized
    fun render(
        hands: List<HandRenderer.Geometry>, keyW: Float, out: IntArray, width: Int, height: Int, scale: Float,
        keysBottom: Float, alphaOverKeys: Float,
    ): Boolean {
        val n = width * height
        if (n > zcap) { zcap = n; zbuf = FloatArray(n); nbuf = FloatArray(n * 3); cover = ByteArray(n); fbuf = FloatArray(n) }
        java.util.Arrays.fill(out, 0)
        java.util.Arrays.fill(zbuf, 0, n, -Float.MAX_VALUE)
        java.util.Arrays.fill(cover, 0, n, 0)
        for (g in hands) {
            val model = HandModel.get(g.hand) ?: return false
            val (xf, view) = pose(model, g.hand, g.tips, keyW, g.down)
            rasterize(model, xf, view, width, height, scale)
        }
        shade(out, width, height, scale, keysBottom, alphaOverKeys, keyW)
        return true
    }

    private fun rasterize(model: HandModel, xf: Array<Affine>, view: View, width: Int, height: Int, scale: Float) {
        val vc = model.vertexCount
        val sp = FloatArray(vc * 3) // screen x, y (buffer pixels), depth
        val sn = FloatArray(vc * 3) // normals in screen space
        val fade = FloatArray(vc)
        val t = FloatArray(3); val acc = FloatArray(3); val nacc = FloatArray(3)
        for (v in 0 until vc) {
            val x = model.positions[v * 3]; val y = model.positions[v * 3 + 1]; val z = model.positions[v * 3 + 2]
            // the model ends at the wrist (+y): fade the forearm out instead of showing the cut
            fade[v] = ((0.074f - y) / 0.038f).coerceIn(0f, 1f)
            val nx = model.normals[v * 3]; val ny = model.normals[v * 3 + 1]; val nz = model.normals[v * 3 + 2]
            acc[0] = 0f; acc[1] = 0f; acc[2] = 0f; nacc[0] = 0f; nacc[1] = 0f; nacc[2] = 0f
            for (k in 0 until 4) {
                val w = model.weights[v * 4 + k]
                if (w <= 0f) continue
                val j = model.joints[v * 4 + k].toInt() and 0xFF
                val a = xf[j]
                a.apply(x, y, z, t, 0); acc[0] += w * t[0]; acc[1] += w * t[1]; acc[2] += w * t[2]
                a.rotate(nx, ny, nz, t, 0); nacc[0] += w * t[0]; nacc[1] += w * t[1]; nacc[2] += w * t[2]
            }
            sp[v * 3] = sx(view, acc[0], acc[2]) * scale
            sp[v * 3 + 1] = sy(view, acc[1]) * scale
            sp[v * 3 + 2] = view.side * acc[0] * view.scale * scale
            // screen-space normal: x = side*z, y = y, towards viewer = side*x
            var qx = view.side * nacc[2]; var qy = nacc[1]; var qz = view.side * nacc[0]
            val l = sqrt(qx * qx + qy * qy + qz * qz).coerceAtLeast(1e-6f); qx /= l; qy /= l; qz /= l
            sn[v * 3] = qx; sn[v * 3 + 1] = qy; sn[v * 3 + 2] = qz
        }
        val idx = model.indices
        for (tri in 0 until idx.size / 3) {
            val a = idx[tri * 3].toInt() and 0xFFFF; val b = idx[tri * 3 + 1].toInt() and 0xFFFF; val c = idx[tri * 3 + 2].toInt() and 0xFFFF
            val ax = sp[a * 3]; val ay = sp[a * 3 + 1]; val bx = sp[b * 3]; val by = sp[b * 3 + 1]; val cx = sp[c * 3]; val cy = sp[c * 3 + 1]
            val area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
            if (abs(area) < 1e-6f) continue
            val x0 = max(0, kotlin.math.floor(min(ax, min(bx, cx))).toInt()); val x1 = min(width - 1, kotlin.math.ceil(max(ax, max(bx, cx))).toInt())
            val y0 = max(0, kotlin.math.floor(min(ay, min(by, cy))).toInt()); val y1 = min(height - 1, kotlin.math.ceil(max(ay, max(by, cy))).toInt())
            if (x0 > x1 || y0 > y1) continue
            val inv = 1f / area
            for (py in y0..y1) {
                val fy = py + 0.5f
                for (px in x0..x1) {
                    val fx = px + 0.5f
                    val w0 = ((bx - fx) * (cy - fy) - (by - fy) * (cx - fx)) * inv
                    val w1 = ((cx - fx) * (ay - fy) - (cy - fy) * (ax - fx)) * inv
                    val w2 = 1f - w0 - w1
                    if (w0 < 0f || w1 < 0f || w2 < 0f) continue
                    val d = w0 * sp[a * 3 + 2] + w1 * sp[b * 3 + 2] + w2 * sp[c * 3 + 2]
                    val i = py * width + px
                    if (d <= zbuf[i]) continue
                    zbuf[i] = d
                    cover[i] = 1
                    fbuf[i] = w0 * fade[a] + w1 * fade[b] + w2 * fade[c]
                    nbuf[i * 3] = w0 * sn[a * 3] + w1 * sn[b * 3] + w2 * sn[c * 3]
                    nbuf[i * 3 + 1] = w0 * sn[a * 3 + 1] + w1 * sn[b * 3 + 1] + w2 * sn[c * 3 + 1]
                    nbuf[i * 3 + 2] = w0 * sn[a * 3 + 2] + w1 * sn[b * 3 + 2] + w2 * sn[c * 3 + 2]
                }
            }
        }
    }

    private fun shade(out: IntArray, width: Int, height: Int, scale: Float, keysBottom: Float, alphaKeys: Float, keyW: Float) {
        val hxv = LX; val hyv = LY; val hzv = LZ + 1f
        val hl = sqrt(hxv * hxv + hyv * hyv + hzv * hzv)
        val shX = (keyW * 0.12f * scale).toInt().coerceAtLeast(1); val shY = (keyW * 0.22f * scale).toInt().coerceAtLeast(1)
        for (y in 0 until height) {
            val sy = (y + 0.5f) / scale
            val aHand = if (sy < keysBottom - 4) alphaKeys else if (sy > keysBottom + 8) 0.95f else alphaKeys + (0.95f - alphaKeys) * ((sy - keysBottom + 4) / 12f)
            for (x in 0 until width) {
                val i = y * width + x
                if (cover[i].toInt() == 0) {
                    // soft shadow on the keys: covered pixels up-left of here
                    val xs = x - shX; val ys = y - shY
                    if (xs >= 0 && ys >= 0) {
                        var c = 0f
                        val s0 = ys * width + xs
                        if (cover[s0].toInt() != 0) c += 2 * fbuf[s0]
                        if (xs > 0 && cover[s0 - 1].toInt() != 0) c += fbuf[s0 - 1]
                        if (ys > 0 && cover[s0 - width].toInt() != 0) c += fbuf[s0 - width]
                        if (c > 0.05f) out[i] = (((0.08f * c) * 255).toInt() shl 24)
                    }
                    continue
                }
                var nx = nbuf[i * 3]; var ny = nbuf[i * 3 + 1]; var nz = nbuf[i * 3 + 2]
                val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f); nx /= l; ny /= l; nz /= l
                val diffuse = max(0f, nx * LX + ny * LY + nz * LZ)
                val spec = max(0f, (nx * hxv + ny * hyv + nz * hzv) / hl).let { val s2 = it * it; val s4 = s2 * s2; s4 * s4 * s2 }
                val rim = (1f - max(0f, nz)).let { it * it } // edges seen at a grazing angle: warm, light shines through
                val lit = 0.38f + 0.62f * diffuse
                var r = 128f + (246f - 128f) * lit + 40f * rim
                var g = 76f + (212f - 76f) * lit + 8f * rim
                var b = 62f + (188f - 62f) * lit
                r += 255f * 0.22f * spec; g += 255f * 0.22f * spec; b += 255f * 0.22f * spec
                // anti-aliased silhouette: pixels at the border of the hand are partly transparent
                var edge = 0
                if (x > 0 && cover[i - 1].toInt() == 0) edge++
                if (x < width - 1 && cover[i + 1].toInt() == 0) edge++
                if (y > 0 && cover[i - width].toInt() == 0) edge++
                if (y < height - 1 && cover[i + width].toInt() == 0) edge++
                val a = aHand * (1f - 0.18f * edge) * fbuf[i].let { it * (2f - it) }
                out[i] = ((a * 255).toInt().coerceIn(0, 255) shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or
                    (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
            }
        }
    }
}
