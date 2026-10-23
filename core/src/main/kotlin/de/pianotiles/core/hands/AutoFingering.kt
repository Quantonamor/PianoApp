package de.pianotiles.core.hands

import de.pianotiles.core.model.FingeringSource
import de.pianotiles.core.model.Hand
import de.pianotiles.core.model.Note
import de.pianotiles.core.model.Pitch
import de.pianotiles.core.model.Song
import de.pianotiles.core.model.toSteps
import kotlin.math.abs
import kotlin.math.max

/**
 * Automatic piano fingering (finger 1 = thumb … 5 = little finger), per hand.
 *
 * Notes starting together form a step (a chord). Every step gets all fingerings whose fingers rise with the pitch
 * (right hand) or fall with it (left hand); dynamic programming (Viterbi) then picks the cheapest path through the
 * piece. The costs follow Parncutt et al. (1997, "An ergonomic model of keyboard fingering"): comfortable and
 * practical spans for every finger pair, thumb crossings, thumb on black keys, the same finger on different keys.
 * The left hand is handled as a mirrored right hand (pitch → −pitch).
 *
 * Fingers already present on notes (from the file or set by hand) are kept and steer the rest.
 */
object AutoFingering {

    /** Song with a finger on every note; existing fingers are kept when [keepExisting]. */
    fun apply(song: Song, keepExisting: Boolean = true): Song {
        val out = ArrayList<Note>(song.notes.size)
        for (hand in Hand.entries) {
            val notes = song.notes.filter { it.hand == hand }
            out += fingerHand(if (keepExisting) notes else notes.map { it.copy(finger = 0) }, hand)
        }
        val order = song.notes.withIndex().associate { (i, n) -> key(n) to i }
        out.sortBy { order[key(it)] ?: Int.MAX_VALUE }
        val source = if (keepExisting && song.notes.any { it.finger in 1..5 } && song.fingeringSource != FingeringSource.NONE)
            song.fingeringSource else FingeringSource.AUTO
        return song.copy(notes = out, fingeringSource = source)
    }

    /**
     * Fingering for practising: null if every note already has a finger. Otherwise automatic fingerings are
     * recomputed completely (notes may have been edited); fingerings from a file, the AI or the user are kept and
     * only the gaps are filled.
     */
    fun ensure(song: Song): Song? {
        if (song.notes.all { it.finger in 1..5 }) return null
        val keep = song.fingeringSource != FingeringSource.AUTO && song.fingeringSource != FingeringSource.NONE
        return apply(song, keepExisting = keep)
    }

    private fun key(n: Note) = Triple(n.pitch, Math.round(n.start * 960), n.hand)

    /** Fingering of the notes of one hand. */
    fun fingerHand(notes: List<Note>, hand: Hand): List<Note> {
        if (notes.isEmpty()) return notes
        // identical notes (same pitch and start, e.g. two voices sharing a head) are one key press
        val unique = notes.distinctBy { it.pitch to Math.round(it.start * 960) }
        val steps = unique.toSteps()
        val mirror = if (hand == Hand.RIGHT) 1 else -1
        // candidates per step: finger per note of the step (notes sorted by mirrored pitch)
        val stepNotes = steps.map { s -> s.notes.sortedBy { it.pitch * mirror } }
        val cands = stepNotes.map { candidates(it, mirror) }
        val n = steps.size
        val cost = Array(n) { DoubleArray(cands[it].size) }
        val back = Array(n) { IntArray(cands[it].size) }
        for (c in cands[0].indices) cost[0][c] = chordCost(stepNotes[0], cands[0][c], mirror)
        for (s in 1 until n) {
            val prev = stepNotes[s - 1]
            val cur = stepNotes[s]
            val gap = cur.first().start - prev.maxOf { it.end }
            val weight = when {
                gap <= 0.3 -> 1.0
                gap <= 1.0 -> 0.6
                else -> 0.3
            }
            for (c in cands[s].indices) {
                val own = chordCost(cur, cands[s][c], mirror)
                var best = Double.MAX_VALUE
                var arg = 0
                for (p in cands[s - 1].indices) {
                    val v = cost[s - 1][p] + weight * transitionCost(prev, cands[s - 1][p], cur, cands[s][c], mirror)
                    if (v < best) { best = v; arg = p }
                }
                cost[s][c] = best + own
                back[s][c] = arg
            }
        }
        val choice = IntArray(n)
        choice[n - 1] = cost[n - 1].indices.minBy { cost[n - 1][it] }
        for (s in n - 1 downTo 1) choice[s - 1] = back[s][choice[s]]
        val fingerOf = HashMap<Pair<Int, Long>, Int>()
        for (s in 0 until n) stepNotes[s].forEachIndexed { i, note ->
            fingerOf[note.pitch to Math.round(note.start * 960)] = cands[s][choice[s]][i]
        }
        return notes.map { it.copy(finger = fingerOf[it.pitch to Math.round(it.start * 960)] ?: 0) }
    }

    /**
     * All finger assignments for a step whose notes are sorted by mirrored pitch: strictly rising fingers.
     * More than five notes: the outer five get fingers, the inner rest none (0). Fixed fingers are respected.
     */
    private fun candidates(notes: List<Note>, mirror: Int): List<IntArray> {
        val k = notes.size
        val played = if (k <= 5) (0 until k).toList() else listOf(0, k / 4, k / 2, (3 * k) / 4, k - 1).distinct()
        val out = ArrayList<IntArray>()
        fun rec(i: Int, minFinger: Int, acc: IntArray) {
            if (i == played.size) { out += acc.copyOf(); return }
            for (f in minFinger..5 - (played.size - 1 - i)) {
                val fixed = notes[played[i]].finger
                if (fixed in 1..5 && fixed != f) continue
                acc[played[i]] = f
                rec(i + 1, f + 1, acc)
            }
            acc[played[i]] = 0
        }
        rec(0, 1, IntArray(k))
        if (out.isEmpty()) { // contradicting fixed fingers: ignore them
            fun free(i: Int, minFinger: Int, acc: IntArray) {
                if (i == played.size) { out += acc.copyOf(); return }
                for (f in minFinger..5 - (played.size - 1 - i)) { acc[played[i]] = f; free(i + 1, f + 1, acc) }
            }
            free(0, 1, IntArray(k))
        }
        return out
    }

    // Parncutt's span table for finger pairs (lower finger i < upper finger j), semitones from finger i to j
    // (right hand): MinPrac, MinComf, MinRel, MaxRel, MaxComf, MaxPrac
    private val spans: Map<Int, IntArray> = mapOf(
        12 to intArrayOf(-5, -3, 1, 5, 8, 10),
        13 to intArrayOf(-4, -2, 3, 7, 10, 12),
        14 to intArrayOf(-3, -1, 5, 9, 12, 14),
        15 to intArrayOf(-1, 1, 7, 10, 13, 15),
        23 to intArrayOf(1, 1, 1, 2, 3, 5),
        24 to intArrayOf(1, 1, 3, 4, 5, 7),
        25 to intArrayOf(2, 2, 5, 6, 8, 10),
        34 to intArrayOf(1, 1, 1, 2, 2, 4),
        35 to intArrayOf(1, 1, 3, 4, 5, 7),
        45 to intArrayOf(1, 1, 1, 2, 3, 5),
    )

    /**
     * Cost of fingers [fa] < [fb] being [d] semitones apart (in mirrored pitch). One after the other ([sequential])
     * the hand can turn a little, so the limits are wider and exceeding them costs less than in a chord.
     */
    private fun spanCost(fa: Int, fb: Int, d: Int, relaxWeight: Double, sequential: Boolean = false): Double {
        val t = spans.getValue(fa * 10 + fb)
        val widen = if (sequential) 2 else 0
        val stretch = if (sequential) 1.0 else 2.0
        val impractical = if (sequential) 2.0 else 10.0
        var c = 0.0
        if (d < t[1]) c += stretch * (t[1] - d)
        if (d > t[4] + widen) c += stretch * (d - t[4] - widen)
        if (d < t[0]) c += impractical * (t[0] - d)
        if (d > t[5] + widen) c += impractical * (d - t[5] - widen)
        val rel = when {
            d < t[2] -> (t[2] - d).toDouble()
            d > t[3] -> (d - t[3]).toDouble()
            else -> 0.0
        }
        c += relaxWeight * rel * if (fa == 1) 1.0 else 2.0
        return c
    }

    /** How much a same-finger repetition on another key costs, by the time between the two onsets (beats). */
    private fun sameFingerWeight(ioi: Double): Double = when {
        ioi <= 0.5 -> 1.0
        ioi <= 1.0 -> 0.6
        ioi <= 2.0 -> 0.3
        else -> 0.12
    }

    private val crossCost = doubleArrayOf(0.0, 0.0, 4.0, 1.0, 1.5, 8.0)

    private fun keyCost(pitch: Int, finger: Int): Double {
        if (!Pitch.isBlackKey(pitch)) return 0.0
        return when (finger) { 1 -> 1.5; 5 -> 0.8; else -> 0.0 }
    }

    private fun chordCost(notes: List<Note>, fingers: IntArray, mirror: Int): Double {
        var c = 0.0
        val used = notes.indices.filter { fingers[it] > 0 }
        for (i in used) c += keyCost(notes[i].pitch, fingers[i]) + if (fingers[i] == 4) 0.3 else 0.0
        for (a in used.indices) for (b in a + 1 until used.size) {
            val i = used[a]; val j = used[b]
            c += spanCost(fingers[i], fingers[j], (notes[j].pitch - notes[i].pitch) * mirror, 0.5)
        }
        // wide chords (a fifth and more) are taken with thumb and little finger on the outer notes
        if (used.size >= 2) {
            val lo = used.first(); val hi = used.last()
            if ((notes[hi].pitch - notes[lo].pitch) * mirror >= 7 && (fingers[lo] != 1 || fingers[hi] != 5)) c += 0.8
        }
        return c
    }

    private fun transitionCost(prev: List<Note>, pf: IntArray, cur: List<Note>, cf: IntArray, mirror: Int): Double {
        var c = 0.0
        var pairs = 0
        for (i in prev.indices) {
            val fa = pf[i]
            if (fa == 0) continue
            for (j in cur.indices) {
                val fb = cf[j]
                if (fb == 0) continue
                pairs++
                val pa = prev[i].pitch * mirror
                val pb = cur[j].pitch * mirror
                if (fa == fb) {
                    // the same finger on another key: hard in quick succession, normal when there is time to move
                    // the hand (block chords, long notes)
                    if (pa != pb) c += (5.0 + abs(pb - pa)) * sameFingerWeight(cur.first().start - prev.first().start)
                    continue
                }
                val d = if (fa < fb) pb - pa else pa - pb
                c += spanCost(minOf(fa, fb), max(fa, fb), d, 0.3, sequential = true)
                if (minOf(fa, fb) == 1 && d < 0) {
                    // thumb passes under / finger crosses over the thumb: easy with 3 and 4, awkward with 2 and 5
                    c += crossCost[max(fa, fb)]
                    val thumbPitch = if (fa == 1) prev[i].pitch else cur[j].pitch
                    val otherPitch = if (fa == 1) cur[j].pitch else prev[i].pitch
                    if (Pitch.isBlackKey(thumbPitch) && !Pitch.isBlackKey(otherPitch)) c += 2.0
                }
                // the same key with another finger right away is fine, but costs a little
                if (pa == pb) c += 1.0
            }
        }
        // averaged over the previous notes, so chords do not weigh more than single notes
        return if (pairs == 0) 0.0 else c / max(1, pf.count { it > 0 })
    }
}
