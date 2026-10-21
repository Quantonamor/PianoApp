package de.pianotiles.app.ui.practice

import de.pianotiles.core.model.Pitch

/**
 * Horizontal geometry of the visible keyboard range, shared by the falling-notes view and the
 * keyboard so tiles line up exactly with their keys.
 */
class KeyLayout(val lowest: Int, val highest: Int, val width: Float) {
    private val whiteKeys: List<Int> = (lowest..highest).filter { !Pitch.isBlackKey(it) }
    val whiteWidth: Float = width / whiteKeys.size
    val blackWidth: Float = whiteWidth * 0.62f
    private val whiteIndex = HashMap<Int, Int>().apply { whiteKeys.forEachIndexed { i, p -> put(p, i) } }

    fun isBlack(pitch: Int) = Pitch.isBlackKey(pitch)

    /** Left x of the key. */
    fun left(pitch: Int): Float = if (!isBlack(pitch)) (whiteIndex[pitch] ?: 0) * whiteWidth
    else (whiteIndex[pitch - 1] ?: 0) * whiteWidth + whiteWidth - blackWidth / 2

    fun keyWidth(pitch: Int) = if (isBlack(pitch)) blackWidth else whiteWidth

    fun center(pitch: Int) = left(pitch) + keyWidth(pitch) / 2

    fun contains(pitch: Int) = pitch in lowest..highest

    /** Key under a touch point; black keys win in their upper part. */
    fun keyAt(x: Float, yFraction: Float): Int? {
        if (yFraction < 0.62f) {
            for (p in lowest..highest) if (isBlack(p) && x >= left(p) && x <= left(p) + blackWidth) return p
        }
        val idx = (x / whiteWidth).toInt().coerceIn(0, whiteKeys.size - 1)
        return whiteKeys.getOrNull(idx)
    }

    val whites: List<Int> get() = whiteKeys

    companion object {
        /** Visible range: the song's range widened to whole octaves, at least two octaves. */
        fun rangeFor(low: Int, high: Int): IntRange {
            var lo = (low / 12) * 12            // down to C
            var hi = (high / 12) * 12 + 11      // up to B
            while (hi - lo + 1 < 24) { if (lo > Pitch.LOWEST_PIANO + 12) lo -= 12 else hi += 12 }
            lo = lo.coerceAtLeast(Pitch.LOWEST_PIANO)
            hi = hi.coerceAtMost(Pitch.HIGHEST_PIANO)
            return lo..hi
        }
    }
}
