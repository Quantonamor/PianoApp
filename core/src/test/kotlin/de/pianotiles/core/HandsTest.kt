package de.pianotiles.core

import de.pianotiles.core.hands.AutoFingering
import de.pianotiles.core.hands.HandMotion
import de.pianotiles.core.hands.KeyX
import de.pianotiles.core.model.DemoSongs
import de.pianotiles.core.model.FingeringSource
import de.pianotiles.core.model.Hand
import de.pianotiles.core.model.Note
import de.pianotiles.core.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HandsTest {

    private fun fingers(text: String, hand: Hand): List<Int> {
        val notes = DemoSongs.seq(text, hand)
        return AutoFingering.fingerHand(notes, hand).sortedWith(compareBy({ it.start }, { it.pitch })).map { it.finger }
    }

    @Test
    fun scalesUseThumbUnder() {
        assertEquals(listOf(1, 2, 3, 1, 2, 3, 4, 5), fingers("C4/1 D4/1 E4/1 F4/1 G4/1 A4/1 B4/1 C5/1", Hand.RIGHT))
        assertEquals(listOf(5, 4, 3, 2, 1, 3, 2, 1), fingers("C5/1 B4/1 A4/1 G4/1 F4/1 E4/1 D4/1 C4/1", Hand.RIGHT))
        assertEquals(listOf(5, 4, 3, 2, 1, 3, 2, 1), fingers("C3/1 D3/1 E3/1 F3/1 G3/1 A3/1 B3/1 C4/1", Hand.LEFT))
        assertEquals(listOf(1, 2, 3, 1, 2, 3, 4, 5), fingers("C4/1 B3/1 A3/1 G3/1 F3/1 E3/1 D3/1 C3/1", Hand.LEFT))
    }

    @Test
    fun fivePositionAndChords() {
        assertEquals(listOf(1, 2, 3, 4, 5, 4, 3, 2, 1), fingers("C4/1 D4/1 E4/1 F4/1 G4/1 F4/1 E4/1 D4/1 C4/1", Hand.RIGHT))
        assertEquals(listOf(1, 3, 5), fingers("C4+E4+G4/1", Hand.RIGHT))
        assertEquals(listOf(5, 3, 1), fingers("C3+E3+G3/1", Hand.LEFT))
        assertEquals(listOf(1, 5), fingers("C4+C5/1", Hand.RIGHT))
    }

    @Test
    fun noThumbOnBlackKeysInFurElise() {
        val song = AutoFingering.apply(DemoSongs.furElise())
        assertEquals(FingeringSource.AUTO, song.fingeringSource)
        assertTrue(song.notes.all { it.finger in 1..5 })
        // right hand: never the thumb on D#/G#; left hand E-E-G# is 5-2-1 as in printed editions
        val thumbOnBlack = song.notes.count { it.hand == Hand.RIGHT && it.finger == 1 && de.pianotiles.core.model.Pitch.isBlackKey(it.pitch) }
        assertEquals(0, thumbOnBlack)
        val lh = song.notes.filter { it.hand == Hand.LEFT }.sortedBy { it.start }.map { it.finger }
        assertEquals(listOf(5, 2, 1, 5, 2, 1), lh.take(6))
        val rh = song.notes.filter { it.hand == Hand.RIGHT }.sortedBy { it.start }.take(4).map { it.finger }
        // E5 D#5 E5 D#5: alternating neighbours, never the same finger on both
        assertTrue(rh[0] != rh[1] && rh[1] != rh[2], "$rh")
    }

    @Test
    fun keepsGivenFingers() {
        val notes = DemoSongs.seq("C4/1 D4/1 E4/1 F4/1 G4/1", Hand.RIGHT).mapIndexed { i, n -> if (i == 2) n.copy(finger = 2) else n }
        val song = AutoFingering.apply(Song(id = "x", title = "x", notes = notes, fingeringSource = FingeringSource.FILE))
        assertEquals(2, song.notes[2].finger)
        assertEquals(FingeringSource.FILE, song.fingeringSource)
        assertEquals(notes.map { it.pitch to it.start }, song.notes.map { it.pitch to it.start })
    }

    @Test
    fun allDemosFingeredAndHandsFollowTheKeys() {
        for (demo in DemoSongs.all()) {
            val song = AutoFingering.apply(demo)
            assertTrue(song.notes.all { it.finger in 1..5 }, demo.title)
            val motion = HandMotion(song)
            for (n in song.notes) {
                val pose = assertNotNull(motion.pose(n.hand, n.start + n.duration / 2))
                assertEquals(KeyX.of(n.pitch), pose.x[n.finger - 1], 1e-4f, "${demo.title} $n")
                assertTrue(pose.down[n.finger - 1] > 0.9f)
                // fingers keep their order (thumb left of the little finger for the right hand) unless crossing
            }
        }
    }

    @Test
    fun keyCoordinates() {
        assertEquals(KeyX.of(60) + 0.5f, KeyX.of(61), 1e-6f) // C#4 on the border of C4 and D4
        assertEquals(KeyX.of(64) + 1f, KeyX.of(65), 1e-6f)   // E → F next white key
        assertEquals(KeyX.of(60) + 7f, KeyX.of(72), 1e-6f)
    }

    @Test
    fun ensureRecomputesAutoAndKeepsFile() {
        val auto = AutoFingering.apply(DemoSongs.odeToJoy())
        assertEquals(null, AutoFingering.ensure(auto))
        val edited = auto.copy(notes = auto.notes + Note(72, 100.0, 1.0, Hand.RIGHT))
        val again = assertNotNull(AutoFingering.ensure(edited))
        assertTrue(again.notes.all { it.finger in 1..5 })
        assertEquals(FingeringSource.AUTO, again.fingeringSource)
        val file = edited.copy(fingeringSource = FingeringSource.FILE)
        val kept = assertNotNull(AutoFingering.ensure(file))
        assertEquals(edited.notes.dropLast(1).map { it.finger }, kept.notes.dropLast(1).map { it.finger })
    }
}
