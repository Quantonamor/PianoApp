package de.pianotiles.core.hands

import de.pianotiles.core.model.Hand
import de.pianotiles.core.model.Note
import de.pianotiles.core.model.Pitch
import de.pianotiles.core.model.Song
import de.pianotiles.core.model.toSteps

/**
 * Horizontal key coordinate in white-key widths: centre of a white key = index + 0.5 (C-1 = index 0),
 * a black key sits on the border between its white neighbours. Matches the app's keyboard layout.
 */
object KeyX {
    private val whiteOfPc = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6)

    fun whiteIndex(pitch: Int): Int = Math.floorDiv(pitch, 12) * 7 + whiteOfPc[Math.floorMod(pitch, 12)]

    fun of(pitch: Int): Float = if (Pitch.isBlackKey(pitch)) whiteIndex(pitch) + 1f else whiteIndex(pitch) + 0.5f
}

/**
 * Pose of one hand at a moment: where each finger is (key coordinate, see [KeyX]), which key it is over and how far
 * it is pressed down. Index 0 = thumb … 4 = little finger.
 */
class HandPose(
    val hand: Hand,
    val x: FloatArray,
    /** Key the finger plays or last played/will play; −1 = none. */
    val pitch: IntArray,
    /** 0 = resting, 1 = key fully down. */
    val down: FloatArray,
    /** False after the hand's last note (plus a short grace time). */
    val active: Boolean,
)

/**
 * Hand positions over time, computed from the fingered notes of a song: at each chord the playing fingers sit on
 * their keys, the free fingers keep a natural spacing of about one white key; between chords the hand glides to the
 * next position during the last part of the gap.
 */
class HandMotion(song: Song) {

    private class Frame(val start: Double, val end: Double, val x: FloatArray, val pitch: IntArray, val dir: Float = 0f)

    private val frames: Map<Hand, List<Frame>> = Hand.entries.associateWith { hand ->
        frameList(song.notes.filter { it.hand == hand && it.finger in 1..5 }, hand)
    }
    private val notes: Map<Hand, List<Note>> = Hand.entries.associateWith { hand ->
        song.notes.filter { it.hand == hand && it.finger in 1..5 }.sortedBy { it.start }
    }

    fun hasHand(hand: Hand) = frames.getValue(hand).isNotEmpty()

    fun pose(hand: Hand, beat: Double): HandPose? {
        val fs = frames.getValue(hand)
        if (fs.isEmpty()) return null
        var i = fs.indexOfLast { it.start <= beat + 1e-9 }
        // before the first note the hand already rests in its starting position (also during the lead-in)
        val active = beat <= fs.last().end + 2.0
        val x: FloatArray
        val pitch: IntArray
        if (i < 0) {
            x = fs[0].x.copyOf(); pitch = fs[0].pitch.copyOf()
        } else {
            val cur = fs[i]
            val next = fs.getOrNull(i + 1)
            val prev = fs.getOrNull(i - 1)
            if (next == null) {
                x = cur.x.copyOf(); pitch = cur.pitch.copyOf()
            } else {
                // the fingers that play the next chord get there in time; the free fingers (and with them the hand)
                // follow only after it has been struck – so the thumb passes under the hand first, like a pianist's
                val gap = next.start - cur.start
                val moveFrom = maxOf(cur.start + gap * 0.5, next.start - 0.6, cur.end - 0.05).coerceAtMost(next.start)
                val t = if (beat <= moveFrom) 0f else ((beat - moveFrom) / (next.start - moveFrom).coerceAtLeast(1e-6)).toFloat().coerceIn(0f, 1f)
                val s = t * t * (3 - 2 * t)
                x = FloatArray(5) { f -> if (next.pitch[f] >= 0 || !crossing(cur, next)) cur.x[f] + (next.x[f] - cur.x[f]) * s else cur.x[f] }
                pitch = if (s > 0.5f) next.pitch.copyOf() else cur.pitch.copyOf()
            }
            // just after a crossing chord: the free fingers catch up
            if (prev != null && crossing(prev, cur)) {
                val lag = minOf(0.5, (cur.end - cur.start) * 0.6)
                val t = ((beat - cur.start) / lag).toFloat().coerceIn(0f, 1f)
                if (t < 1f) {
                    val s = t * t * (3 - 2 * t)
                    for (f in 0 until 5) if (cur.pitch[f] < 0 && (next == null || beat < next.start)) x[f] = prev.x[f] + (cur.x[f] - prev.x[f]) * s
                }
            }
        }
        val down = FloatArray(5)
        for (n in notes.getValue(hand)) {
            if (n.start > beat + 0.0001) break
            if (beat < n.end) {
                val f = n.finger - 1
                // quick press, held, slight lift at the very end
                val into = ((beat - n.start) / 0.06).toFloat().coerceIn(0f, 1f)
                val out = ((n.end - beat) / 0.08).toFloat().coerceIn(0f, 1f)
                down[f] = maxOf(down[f], minOf(into, out))
                if (into >= 0f) { x[f] = KeyX.of(n.pitch); pitch[f] = n.pitch }
            }
        }
        return HandPose(hand, x, pitch, down, active)
    }

    private fun frameList(notes: List<Note>, hand: Hand): List<Frame> {
        if (notes.isEmpty()) return emptyList()
        val dir = if (hand == Hand.RIGHT) 1f else -1f
        val out = ArrayList<Frame>()
        var last: FloatArray? = null
        for (step in notes.toSteps()) {
            val fixedX = FloatArray(5) { Float.NaN }
            val pitch = IntArray(5) { -1 }
            for (n in step.notes) { fixedX[n.finger - 1] = KeyX.of(n.pitch); pitch[n.finger - 1] = n.pitch }
            val x = layout(fixedX, dir, last)
            out += Frame(step.start, step.notes.maxOf { it.end }, x, pitch, dir)
            last = x
        }
        return out
    }

    /** Fills in free fingers: between two placed fingers evenly, outside with one white key per finger. */
    private fun layout(fixed: FloatArray, dir: Float, previous: FloatArray?): FloatArray {
        val placed = (0 until 5).filter { !fixed[it].isNaN() }
        val x = fixed.copyOf()
        val first = placed.first()
        val lastP = placed.last()
        for (f in 0 until 5) {
            if (!x[f].isNaN()) continue
            x[f] = when {
                f < first -> x[first] - dir * (first - f) * spacing(f, first)
                f > lastP -> x[lastP] + dir * (f - lastP) * spacing(lastP, f)
                else -> {
                    val a = placed.last { it < f }
                    val b = placed.first { it > f }
                    x[a] + (x[b] - x[a]) * (f - a) / (b - a)
                }
            }
        }
        // a thumb that was tucked under (crossing) stays where the previous position had it, if that is natural
        if (previous != null && placed.size == 1) {
            val shift = x[first] - previous[first]
            if (kotlin.math.abs(shift) < 0.01f) return previous.copyOf()
        }
        return x
    }

    /**
     * A finger crossing between two positions: the thumb passes under (or a finger over the thumb), i.e. the thumb
     * ends up on the other side of a finger that was just playing.
     */
    private fun crossing(a: Frame, b: Frame): Boolean {
        if (a.dir == 0f) return false
        val thumbBefore = a.x[0]; val thumbAfter = b.x[0]
        for (f in 1 until 5) if (a.pitch[f] >= 0) {
            val before = (a.x[f] - thumbBefore) * a.dir
            val after = (a.x[f] - thumbAfter) * a.dir
            if (before > 0 && after < 0 && b.pitch[0] >= 0) return true // thumb under to the outside
            if (b.pitch[f] >= 0 && (b.x[f] - b.x[0]) * a.dir < 0) return true // finger over the thumb
        }
        return false
    }

    /** Thumb sits a bit further away from the index finger than the other fingers from each other. */
    private fun spacing(a: Int, b: Int): Float = if (a == 0 || b == 0) 1.1f else 1f
}
