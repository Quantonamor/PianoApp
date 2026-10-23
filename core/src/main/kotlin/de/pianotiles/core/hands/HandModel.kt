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
    val indices: IntArray, val jointPos: FloatArray, val names: List<String>,
) {
    val vertexCount = positions.size / 3
    /** Per vertex 0..1: how much it belongs to a fingernail (set by [markNails]). */
    var nail = FloatArray(vertexCount)
        private set

    fun joint(name: String) = names.indexOf(name)

    /** Finger (0 = thumb … 4) each joint moves, −1 for palm and wrist. */
    val jointFinger: IntArray = IntArray(names.size) { j ->
        val n = names[j]
        when {
            n.startsWith("thumb-") && n != "thumb-metacarpal" -> 0
            n.startsWith("index-finger-") && !n.endsWith("metacarpal") -> 1
            n.startsWith("middle-finger-") && !n.endsWith("metacarpal") -> 2
            n.startsWith("ring-finger-") && !n.endsWith("metacarpal") -> 3
            n.startsWith("pinky-finger-") && !n.endsWith("metacarpal") -> 4
            else -> -1
        }
    }

    /**
     * Per vertex 0..1: how close it lies to a finger joint (knuckle), where skin creases – 1 where two bones of
     * the same finger share the vertex equally.
     */
    val crease: FloatArray by lazy {
        val digit = IntArray(names.size) { j ->
            val n = names[j]
            listOf("thumb-", "index-finger-", "middle-finger-", "ring-finger-", "pinky-finger-").indexOfFirst { n.startsWith(it) }
        }
        FloatArray(vertexCount) { v ->
            var ja = -1; var wa = 0f; var jb = -1; var wb = 0f
            for (k in 0 until 4) {
                val w = weights[v * 4 + k]; val j = joints[v * 4 + k].toInt() and 0xFF
                if (w > wa) { jb = ja; wb = wa; ja = j; wa = w } else if (w > wb) { jb = j; wb = w }
            }
            if (ja < 0 || jb < 0 || ja == jb || digit[ja] < 0 || digit[ja] != digit[jb]) 0f
            else (4f * wa * wb).coerceIn(0f, 1f)
        }
    }

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
                val idx = IntArray(t * 3) { b.short.toInt() and 0xFFFF }
                val jp = FloatArray(j * 3) { b.float }
                val names = (0 until j).map { val n = b.get().toInt() and 0xFF; val a = ByteArray(n); b.get(a); String(a) }
                thickenFingers(pos, jts, wts, jp, names)
                subdivide(HandModel(pos, nor, jts, wts, idx, jp, names)).also { it.markNails(if (hand == Hand.RIGHT) 1f else -1f) }
            }.getOrNull()
        }

        /**
         * The model's fingers are slim (game hand); real fingers are about 16–20 mm wide. Every finger vertex moves
         * away from its bone axis (weighted by its skin weights), so the fingers get fuller without changing length.
         */
        private fun thickenFingers(pos: FloatArray, jts: ByteArray, wts: FloatArray, jp: FloatArray, names: List<String>) {
            // bone segment (from joint → to joint) and widening factor for every finger joint
            val seg = HashMap<Int, Triple<Int, Int, Float>>()
            fun chain(names2: List<String>, factor: Float) {
                val ids = names2.map { names.indexOf(it) }
                if (ids.any { it < 0 }) return
                for (k in 0 until ids.size - 1) seg[ids[k]] = Triple(ids[k], ids[k + 1], factor)
                seg[ids.last()] = Triple(ids[ids.size - 2], ids.last(), factor)
            }
            chain(listOf("thumb-phalanx-proximal", "thumb-phalanx-distal", "thumb-tip"), 1.12f)
            for (f in listOf("index-finger", "middle-finger", "ring-finger", "pinky-finger"))
                chain(listOf("$f-phalanx-proximal", "$f-phalanx-intermediate", "$f-phalanx-distal", "$f-tip"), 1.24f)
            val v = pos.size / 3
            for (i in 0 until v) {
                var dx = 0f; var dy = 0f; var dz = 0f
                for (k in 0 until 4) {
                    val w = wts[i * 4 + k]; if (w <= 0f) continue
                    val (a, b, factor) = seg[jts[i * 4 + k].toInt() and 0xFF] ?: continue
                    val ax = jp[a * 3]; val ay = jp[a * 3 + 1]; val az = jp[a * 3 + 2]
                    val ux = jp[b * 3] - ax; val uy = jp[b * 3 + 1] - ay; val uz = jp[b * 3 + 2] - az
                    val px = pos[i * 3] - ax; val py = pos[i * 3 + 1] - ay; val pz = pos[i * 3 + 2] - az
                    val t = ((px * ux + py * uy + pz * uz) / (ux * ux + uy * uy + uz * uz)).coerceIn(-0.2f, 1.3f)
                    // offset from the axis, scaled
                    dx += w * (px - t * ux) * (factor - 1); dy += w * (py - t * uy) * (factor - 1); dz += w * (pz - t * uz) * (factor - 1)
                }
                pos[i * 3] += dx; pos[i * 3 + 1] += dy; pos[i * 3 + 2] += dz
            }
        }

        /**
         * One step of smooth subdivision: every triangle becomes four. The new point on an edge lies on the curved
         * surface the vertex normals describe (PN triangle edge curve), so the silhouette gets round instead of
         * faceted; normals and skin weights are blended from both ends.
         */
        private fun subdivide(m: HandModel): HandModel {
            val pos = ArrayList<Float>(m.positions.size * 4); m.positions.forEach { pos.add(it) }
            val nor = ArrayList<Float>(m.normals.size * 4); m.normals.forEach { nor.add(it) }
            val jts = ArrayList<Byte>(m.joints.size * 4); m.joints.forEach { jts.add(it) }
            val wts = ArrayList<Float>(m.weights.size * 4); m.weights.forEach { wts.add(it) }
            val mid = HashMap<Long, Int>()
            fun edge(a: Int, b: Int): Int = mid.getOrPut(min(a, b).toLong() shl 32 or max(a, b).toLong()) {
                val p = m.positions; val n = m.normals
                val out = FloatArray(3)
                // cubic edge curve: b1 = (2 p0 + p1 - w01 n0) / 3, b2 = (2 p1 + p0 - w10 n1) / 3, midpoint = (p0 + 3 b1 + 3 b2 + p1) / 8
                var w01 = 0f; var w10 = 0f
                for (k in 0 until 3) { w01 += (p[b * 3 + k] - p[a * 3 + k]) * n[a * 3 + k]; w10 += (p[a * 3 + k] - p[b * 3 + k]) * n[b * 3 + k] }
                for (k in 0 until 3) {
                    val p0 = p[a * 3 + k]; val p1 = p[b * 3 + k]
                    val b1 = (2 * p0 + p1 - w01 * n[a * 3 + k]) / 3; val b2 = (2 * p1 + p0 - w10 * n[b * 3 + k]) / 3
                    out[k] = (p0 + 3 * b1 + 3 * b2 + p1) / 8
                }
                out.forEach { pos.add(it) }
                var nx = n[a * 3] + n[b * 3]; var ny = n[a * 3 + 1] + n[b * 3 + 1]; var nz = n[a * 3 + 2] + n[b * 3 + 2]
                val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f); nx /= l; ny /= l; nz /= l
                nor.add(nx); nor.add(ny); nor.add(nz)
                // skin weights: the union of both ends, the strongest four kept
                val w = HashMap<Int, Float>()
                for (v in intArrayOf(a, b)) for (k in 0 until 4) {
                    val ww = m.weights[v * 4 + k]; if (ww <= 0f) continue
                    val j = m.joints[v * 4 + k].toInt() and 0xFF
                    w[j] = (w[j] ?: 0f) + ww * 0.5f
                }
                val top = w.entries.sortedByDescending { it.value }.take(4)
                val sum = top.sumOf { it.value.toDouble() }.toFloat().coerceAtLeast(1e-6f)
                for (k in 0 until 4) {
                    val e = top.getOrNull(k)
                    jts.add((e?.key ?: 0).toByte()); wts.add((e?.value ?: 0f) / sum)
                }
                pos.size / 3 - 1
            }
            val idx = m.indices
            val out = IntArray(idx.size * 4)
            var o = 0
            for (t in 0 until idx.size / 3) {
                val a = idx[t * 3]; val b = idx[t * 3 + 1]; val c = idx[t * 3 + 2]
                val ab = edge(a, b); val bc = edge(b, c); val ca = edge(c, a)
                for (tri in arrayOf(intArrayOf(a, ab, ca), intArrayOf(ab, b, bc), intArrayOf(ca, bc, c), intArrayOf(ab, bc, ca))) {
                    out[o++] = tri[0]; out[o++] = tri[1]; out[o++] = tri[2]
                }
            }
            return HandModel(pos.toFloatArray(), nor.toFloatArray(), jts.toByteArray(), wts.toFloatArray(), out, m.jointPos, m.names)
        }
    }

    /**
     * Marks the fingernails: vertices on the back of each fingertip ([dorsalX] = direction of the back of the hand
     * along x), from about the middle of the last phalanx to the tip.
     */
    private fun markNails(dorsalX: Float) {
        val fingers = listOf("thumb", "index-finger", "middle-finger", "ring-finger", "pinky-finger")
        val n = FloatArray(vertexCount)
        for (f in fingers) {
            val d = joint("$f-phalanx-distal"); val t = joint("$f-tip")
            if (d < 0 || t < 0) continue
            val ax = jointPos[t * 3] - jointPos[d * 3]; val ay = jointPos[t * 3 + 1] - jointPos[d * 3 + 1]; val az = jointPos[t * 3 + 2] - jointPos[d * 3 + 2]
            val len2 = ax * ax + ay * ay + az * az
            // back of this finger: the hand's back (x), for the thumb turned towards its outer side (-z)
            var bx = dorsalX; var by = 0f; var bz = if (f == "thumb") -0.9f else 0f
            // make it perpendicular to the finger axis
            val dot = (bx * ax + by * ay + bz * az) / len2
            bx -= dot * ax; by -= dot * ay; bz -= dot * az
            val bl = sqrt(bx * bx + by * by + bz * bz); bx /= bl; by /= bl; bz /= bl
            for (v in 0 until vertexCount) {
                val px = positions[v * 3] - jointPos[d * 3]; val py = positions[v * 3 + 1] - jointPos[d * 3 + 1]; val pz = positions[v * 3 + 2] - jointPos[d * 3 + 2]
                val along = (px * ax + py * ay + pz * az) / len2
                if (along < 0.25f || along > 1.25f) continue
                val ox = px - along * ax; val oy = py - along * ay; val oz = pz - along * az
                val ol = sqrt(ox * ox + oy * oy + oz * oz).coerceAtLeast(1e-6f)
                val back = (ox * bx + oy * by + oz * bz) / ol // 1 = straight on the back of the finger
                val nd = normals[v * 3] * bx + normals[v * 3 + 1] * by + normals[v * 3 + 2] * bz
                val w = smooth(0.45f, 0.75f, back) * smooth(0.25f, 0.5f, along) * smooth(0.3f, 0.6f, nd)
                n[v] = max(n[v], w)
            }
        }
        nail = n
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float { val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
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
    /** [ys]: vertical squash of a foreshortened keyboard (keys drawn shorter than real ones). */
    private class View(val side: Float, val scale: Float, val ox: Float, val oy: Float, val ys: Float = 1f)

    private fun sx(v: View, x: Float, z: Float) = v.side * z * v.scale + v.ox
    private fun sy(v: View, y: Float) = y * v.scale * v.ys + v.oy

    /**
     * Joint transforms so that the fingertips land on [tips] (screen pixels, index 0 = thumb).
     * Returns the per-joint affine transforms (model space) and the view.
     */
    private fun pose(model: HandModel, hand: Hand, tips: Array<FloatArray>, keyW: Float, down: FloatArray, restMinY: FloatArray, centerX: Float, ys: Float): Pair<Array<Affine>, View> {
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
        // the forearm comes from the player's body (middle of the piano, about 55 cm in front of the keys): a hand away
        // from the middle turns inwards; the wrist takes up half of the forearm's angle
        val yaw = 0.5f * kotlin.math.atan((targetKx - centerX) / (0.55f * scale))
        val pivot = FloatArray(3) { k -> prox.map { p(it, k) }.average().toFloat() }
        val base = Affine.rotation(pivot[0], pivot[1], pivot[2], 1f, 0f, 0f, -side * yaw)
        // the hand moves towards the keys (fingers more curved) until the thumb and little finger reach as well
        var lift = 0f
        var result: Pair<Array<Affine>, View>? = null
        for (attempt in 0 until 5) {
            val knuckleY = targetTipY + midLen * scale * ys * 0.78f - lift
            val oy = knuckleY - prox.map { p(it, 1) }.average().toFloat() * scale * ys
            val view = View(side, scale, ox, oy, ys)
            var worst = 0f
            var worstMiddle = 0f
            // fingers that do not press rest further in when the hand has moved forward (instead of curling up
            // into stubs), but not beyond the finger reaching furthest in, and on white keys not between the black ones
            val inmost = tips.minOf { it[1] }
            val aim = Array(5) { f ->
                if (down[f] >= 0.5f) tips[f] else floatArrayOf(tips[f][0], minOf(tips[f][1], maxOf(inmost, restMinY[f], tips[f][1] - lift * 0.8f)))
            }
            val xf = solve(model, view, aim, side, base) { f, err, curl ->
                if (f == 0 || f == 4) worst = maxOf(worst, err)
                // a finger that reaches only (nearly) stretched out: the hand should come closer so it stays curved
                else worstMiddle = maxOf(worstMiddle, err, (0.9f - curl) * midLen * scale * ys * 0.5f)
            }
            result = xf to view
            // thumb or little finger short: move in a little (the fingers curl more); a middle finger short (black key
            // far in): the whole hand moves forward, as a pianist's does
            val step = maxOf(if (lift < midLen * scale * ys * 0.18f) worst else 0f, if (lift < midLen * scale * ys * 0.7f) worstMiddle else 0f)
            if (step < keyW * 0.12f) break
            lift += step * 0.9f
        }
        return result!!
    }

    private fun solve(model: HandModel, view: View, tips: Array<FloatArray>, side: Float, base: Affine, report: (Int, Float, Float) -> Unit): Array<Affine> {
        val jp = model.jointPos
        fun p(i: Int, k: Int) = jp[i * 3 + k]
        val nx = side; val ny = 0f; val nz = 0f
        val xf = Array(model.names.size) { base }
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
                val a0 = base.times(Affine.rotation(p(j0, 0), p(j0, 1), p(j0, 2), nx, ny, nz, spread)).times(
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
            report(f, t[1] - target[1], curl)
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
    private var aoR = 1
    private var aoRange = 1f
    private var rowMin = 0 // rows that contain hand pixels (shading skips the rest)
    private var rowMax = -1
    private var fbuf = FloatArray(0) // forearm fade: 1 = opaque, 0 = gone
    private var kbuf = FloatArray(0) // fingernail: 0..1
    private var cbuf = FloatArray(0) // skin crease at a knuckle: 0..1
    private var pbuf = FloatArray(0) // finger pressing a key: 0..1 (tinted in the hand's colour)
    private var hbuf = ByteArray(0)  // which hand drew the pixel (0 right, 1 left)

    /** Renders [hands] (tips in screen pixels) into [out]; see HandRenderer.render. */
    @Synchronized
    fun render(
        hands: List<HandRenderer.Geometry>, keyW: Float, out: IntArray, width: Int, height: Int, scale: Float,
        keysBottom: Float, alphaOverKeys: Float,
    ): Boolean {
        val n = width * height
        if (n > zcap) { zcap = n; zbuf = FloatArray(n); nbuf = FloatArray(n * 3); cover = ByteArray(n); fbuf = FloatArray(n); kbuf = FloatArray(n); cbuf = FloatArray(n); pbuf = FloatArray(n); hbuf = ByteArray(n) }
        java.util.Arrays.fill(out, 0)
        java.util.Arrays.fill(zbuf, 0, n, -Float.MAX_VALUE)
        java.util.Arrays.fill(cover, 0, n, 0)
        rowMin = height; rowMax = -1
        // real keys are about 6.4 key widths long; a keyboard drawn with shorter keys looks like one seen at an angle,
        // so the hands are foreshortened a little as well (otherwise thumb and little finger lie nearly flat)
        val squash = (0.45f + 0.55f * keysBottom / keyW / 5.5f).coerceIn(0.65f, 1f)
        for (g in hands) {
            val model = HandModel.get(g.hand) ?: return false
            val (xf, view) = pose(model, g.hand, g.tips, keyW, g.down, g.restMinY, if (g.bodyX.isFinite()) g.bodyX else width / scale / 2f, squash)
            rasterize(model, xf, view, width, height, scale, g.down, if (g.hand == Hand.RIGHT) 0 else 1)
        }
        shade(out, width, height, scale, keysBottom, alphaOverKeys, keyW)
        return true
    }

    private fun rasterize(model: HandModel, xf: Array<Affine>, view: View, width: Int, height: Int, scale: Float, down: FloatArray, handId: Byte) {
        val vc = model.vertexCount
        val sp = FloatArray(vc * 3) // screen x, y (buffer pixels), depth
        val sn = FloatArray(vc * 3) // normals in screen space
        val fade = FloatArray(vc)
        val press = FloatArray(vc)
        val t = FloatArray(3); val acc = FloatArray(3); val nacc = FloatArray(3)
        for (v in 0 until vc) {
            val x = model.positions[v * 3]; val y = model.positions[v * 3 + 1]; val z = model.positions[v * 3 + 2]
            // the model ends at the wrist (+y): fade the forearm out instead of showing the cut
            fade[v] = ((0.068f - y) / 0.036f).coerceIn(0f, 1f)
            var pr = 0f
            for (k in 0 until 4) {
                val f = model.jointFinger[model.joints[v * 4 + k].toInt() and 0xFF]
                if (f >= 0) pr += model.weights[v * 4 + k] * down[f]
            }
            press[v] = pr
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
            var qx = view.side * nacc[2]; var qy = nacc[1] / view.ys; var qz = view.side * nacc[0]
            val l = sqrt(qx * qx + qy * qy + qz * qz).coerceAtLeast(1e-6f); qx /= l; qy /= l; qz /= l
            sn[v * 3] = qx; sn[v * 3 + 1] = qy; sn[v * 3 + 2] = qz
        }
        val idx = model.indices
        val nail = model.nail
        val crease = model.crease
        for (tri in 0 until idx.size / 3) {
            val a = idx[tri * 3]; val b = idx[tri * 3 + 1]; val c = idx[tri * 3 + 2]
            val ax = sp[a * 3]; val ay = sp[a * 3 + 1]; val bx = sp[b * 3]; val by = sp[b * 3 + 1]; val cx = sp[c * 3]; val cy = sp[c * 3 + 1]
            val area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
            if (abs(area) < 1e-6f) continue
            // faces turned away from the viewer are hidden anyway (and inside the open forearm they would show)
            if (sn[a * 3 + 2] + sn[b * 3 + 2] + sn[c * 3 + 2] < -0.15f) continue
            val x0 = max(0, kotlin.math.floor(min(ax, min(bx, cx))).toInt()); val x1 = min(width - 1, kotlin.math.ceil(max(ax, max(bx, cx))).toInt())
            val y0 = max(0, kotlin.math.floor(min(ay, min(by, cy))).toInt()); val y1 = min(height - 1, kotlin.math.ceil(max(ay, max(by, cy))).toInt())
            if (x0 > x1 || y0 > y1) continue
            if (y0 < rowMin) rowMin = y0
            if (y1 > rowMax) rowMax = y1
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
                    kbuf[i] = w0 * nail[a] + w1 * nail[b] + w2 * nail[c]
                    cbuf[i] = w0 * crease[a] + w1 * crease[b] + w2 * crease[c]
                    pbuf[i] = w0 * press[a] + w1 * press[b] + w2 * press[c]
                    hbuf[i] = handId
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
        aoR = (keyW * 0.10f * scale).toInt().coerceAtLeast(1)
        aoRange = keyW * 0.35f * scale
        for (y in rowMin..min(height - 1, rowMax + shY + 1)) {
            val sy = (y + 0.5f) / scale
            // from half transparent over the keys to nearly opaque below them, over about half a key width
            val ramp = keyW * 0.55f
            val tr = ((sy - keysBottom + ramp * 0.5f) / ramp).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
            val aHand = alphaKeys + (0.95f - alphaKeys) * tr
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
                // soft "wrapped" light (skin scatters light into the shadow side) + a cool fill from below
                val ndl = nx * LX + ny * LY + nz * LZ
                val diffuse = ((ndl + 0.35f) / 1.35f).coerceIn(0f, 1f)
                val fill = max(0f, ny * 0.6f + nz * 0.4f) * 0.12f
                val h = max(0f, (nx * hxv + ny * hyv + nz * hzv) / hl)
                val nailW = kbuf[i].coerceIn(0f, 1f)
                // broad, faint sheen on skin; a sharper, brighter one on the nails
                val sheen = h.let { val h2 = it * it; val h4 = h2 * h2; h4 * h2 } * (0.10f + 0.25f * nailW) +
                    h.let { val h2 = it * it; val h4 = h2 * h2; val h8 = h4 * h4; h8 * h8 * h2 } * 0.35f * nailW
                val rim = (1f - max(0f, nz)).let { it * it * it } // grazing edges: light shines through, warm
                // contact shadow: neighbours clearly nearer to the viewer occlude this pixel (between fingers, under knuckles)
                var occ = 0f
                val z0 = zbuf[i]
                val rr = aoR
                if (x >= rr && x < width - rr && y >= rr && y < height - rr) {
                    occ += max(0f, zbuf[i - rr] - z0); occ += max(0f, zbuf[i + rr] - z0)
                    occ += max(0f, zbuf[i - rr * width] - z0); occ += max(0f, zbuf[i + rr * width] - z0)
                    occ = (occ / aoRange).coerceIn(0f, 1f)
                }
                val ao = 1f - 0.45f * occ
                val lit = (0.30f + 0.70f * diffuse) * ao + fill
                // skin: deeper and redder in shadow, light and warm in the light; nails pinker and paler
                var r = 150f + (250f - 150f) * lit + 34f * rim
                var g = 92f + (214f - 92f) * lit + 6f * rim
                var b = 78f + (190f - 78f) * lit - 4f * rim
                // knuckles: the skin creases there – a little darker and redder where the joint faces the viewer
                val ck = cbuf[i].let { it * it * it } * (1f - nailW) * max(0f, nz)
                if (ck > 0f) { r *= 1f - 0.16f * ck; g *= 1f - 0.27f * ck; b *= 1f - 0.27f * ck }
                if (nailW > 0f) {
                    r += (238f - r) * 0.55f * nailW; g += (196f - g) * 0.55f * nailW * lit; b += (190f - b) * 0.55f * nailW * lit
                }
                r += 255f * sheen; g += 248f * sheen; b += 240f * sheen
                // the finger that plays right now takes on the colour of its hand's notes
                val p = pbuf[i]
                if (p > 0.02f) {
                    val k = 0.62f * p.coerceAtMost(1f)
                    val t = 0.6f + 0.4f * lit
                    if (hbuf[i].toInt() == 0) { r += (255f * t - r) * k; g += (138f * t - g) * k; b += (61f * t - b) * k }
                    else { r += (110f * t - r) * k; g += (123f * t - g) * k; b += (255f * t - b) * k }
                }
                // anti-aliased silhouette: pixels at the border of the hand are partly transparent
                // (coverage of the 3×3 neighbourhood, weighted towards the centre)
                var cov = 1f
                if (x > 0 && y > 0 && x < width - 1 && y < height - 1) {
                    val e = cover[i - 1] + cover[i + 1] + cover[i - width] + cover[i + width]
                    val d = cover[i - width - 1] + cover[i - width + 1] + cover[i + width - 1] + cover[i + width + 1]
                    if (e + d < 8) cov = (4f + 2f * e + d) / 16f
                }
                val a = (aHand + (0.9f - aHand).coerceAtLeast(0f) * pbuf[i].coerceIn(0f, 1f)) * cov * fbuf[i].let { it * (2f - it) }
                out[i] = ((a * 255).toInt().coerceIn(0, 255) shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or
                    (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
            }
        }
    }
}
