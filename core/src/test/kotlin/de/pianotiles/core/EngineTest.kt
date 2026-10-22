package de.pianotiles.core

import de.pianotiles.core.model.DemoSongs
import de.pianotiles.core.model.Hand
import de.pianotiles.core.model.Note
import de.pianotiles.core.model.Song
import de.pianotiles.core.practice.EngineEvent
import de.pianotiles.core.practice.HandSelection
import de.pianotiles.core.practice.LoopSettings
import de.pianotiles.core.practice.NoteState
import de.pianotiles.core.practice.PracticeEngine
import de.pianotiles.core.practice.PracticeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineTest {
    private val song = Song(
        id = "t", title = "t", bpm = 60.0,
        notes = listOf(
            Note(60, 0.0, 1.0), Note(62, 1.0, 1.0), Note(64, 2.0, 1.0), Note(65, 3.0, 1.0),
            Note(48, 0.0, 4.0, Hand.LEFT),
        ),
    )

    private fun engine(mode: PracticeMode) = PracticeEngine(song).apply {
        countIn = false; this.mode = mode; seek(0.0)
    }

    @Test
    fun waitModeHoldsUntilNotePlayed() {
        val e = engine(PracticeMode.WAIT)
        e.hands = HandSelection.RIGHT
        e.seek(0.0)
        e.play()
        e.update(0.5)
        assertEquals(0.0, e.position, 1e-9)
        assertTrue(e.isWaiting)
        assertEquals(setOf(60), e.expectedPitches)
        val ev = e.onNotesPlayed(setOf(60))
        assertTrue(ev.any { it is EngineEvent.StepHit })
        e.update(0.5)
        assertEquals(0.5, e.position, 1e-9)
        e.update(1.0)
        assertEquals(1.0, e.position, 1e-9) // waits for D
        // a wrong note is counted but does not advance
        e.onNotesPlayed(setOf(61))
        e.update(1.0)
        assertEquals(1.0, e.position, 1e-9)
        assertTrue(e.accuracy() < 100)
    }

    @Test
    fun accompanimentPlaysOtherHand() {
        val e = engine(PracticeMode.WAIT)
        e.hands = HandSelection.RIGHT
        e.seek(0.0)
        e.play()
        val ev = e.update(0.01)
        assertTrue(ev.any { it is EngineEvent.PlayNote && it.pitch == 48 })
        assertFalse(ev.any { it is EngineEvent.PlayNote && it.pitch == 60 })
    }

    @Test
    fun flowModeScoresMisses() {
        val e = engine(PracticeMode.FLOW)
        e.hands = HandSelection.RIGHT
        e.seek(0.0)
        e.play()
        e.onNotesPlayed(setOf(60))
        repeat(50) { e.update(0.1) }
        assertFalse(e.isPlaying)
        assertEquals(NoteState.HIT, e.noteStates[0])
        assertEquals(NoteState.MISSED, e.noteStates[1])
        assertEquals(25, e.accuracy())
    }

    @Test
    fun loopRepeatsAndRampsTempo() {
        val e = engine(PracticeMode.FLOW)
        e.scoreInFlow = false
        e.speed = 0.5
        e.loop = LoopSettings(enabled = true, startBeat = 0.0, endBeat = 2.0, repetitions = 3, tempoRamp = true, rampStep = 0.1, rampTarget = 0.7)
        e.play()
        val events = ArrayList<EngineEvent>()
        repeat(800) { events += e.update(0.05) }
        val passes = events.filterIsInstance<EngineEvent.PassCompleted>()
        assertEquals(3, passes.size)
        assertEquals(0.7, e.speed, 1e-9)
        assertTrue(events.any { it is EngineEvent.LoopFinished })
        assertFalse(e.isPlaying)
    }

    @Test
    fun listenModePlaysEverything() {
        val e = engine(PracticeMode.LISTEN)
        e.play()
        val notes = ArrayList<Int>()
        repeat(100) { e.update(0.1).filterIsInstance<EngineEvent.PlayNote>().forEach { notes += it.pitch } }
        assertEquals(listOf(48, 60, 62, 64, 65), notes.sorted())
    }

    @Test
    fun metronomeAndCountIn() {
        val e = PracticeEngine(song).apply { mode = PracticeMode.FLOW; scoreInFlow = false; metronome = true; countIn = true; seek(0.0) }
        e.play()
        val clicks = ArrayList<EngineEvent.Click>()
        repeat(80) { clicks += e.update(0.1).filterIsInstance<EngineEvent.Click>() }
        // 4 count-in clicks + 4 beats in the song (0,1,2,3) (+ possibly the final beat at 4)
        assertTrue(clicks.size in 8..9, "clicks=${clicks.size}")
        assertTrue(clicks[0].accent)
    }

    @Test
    fun demoSongsAreSane() {
        for (s in DemoSongs.all()) {
            assertTrue(s.notes.isNotEmpty(), s.title)
            assertTrue(s.notes.all { it.pitch in 21..108 })
        }
        val elise = DemoSongs.furElise()
        assertEquals(1.5, elise.measureLength, 1e-9)
        assertEquals(0, elise.measureIndexAt(0.25))
        assertEquals(1, elise.measureIndexAt(0.5))
        assertEquals(9, elise.measureCount)
    }

    private val chordSong = Song(
        id = "c", title = "c", bpm = 60.0,
        notes = listOf(
            // C+E together; E is held for 2 beats while C ends after 1 beat; then D
            Note(60, 0.0, 1.0), Note(64, 0.0, 2.0), Note(62, 1.0, 1.0),
        ),
    )

    @Test
    fun chordKeysMustBePlayedTogether() {
        val e = PracticeEngine(chordSong).apply { countIn = false; mode = PracticeMode.WAIT; seek(0.0); play() }
        e.update(0.1)
        e.onNotesPlayed(setOf(60))
        e.update(1.0) // C released and a second later E: not together
        e.onNotesPlayed(setOf(64))
        assertTrue(e.isWaiting, "a chord collected key by key must not count")
        assertEquals(setOf(60), e.expectedPitches)
        // both together
        e.onNotesPlayed(setOf(60))
        assertFalse(e.isWaiting && e.expectedPitches.isNotEmpty())
    }

    @Test
    fun heldChordKeyCountsWhileHeld() {
        val e = PracticeEngine(chordSong).apply { countIn = false; mode = PracticeMode.WAIT; seek(0.0); play() }
        e.update(0.1)
        e.heldPitches = setOf(60)
        e.onNotesPlayed(setOf(60), setOf(60))
        e.update(1.5) // still holding C
        e.heldPitches = setOf(60, 64)
        val ev = e.onNotesPlayed(setOf(64), setOf(60, 64))
        assertTrue(ev.any { it is EngineEvent.StepHit })
    }

    @Test
    fun releaseAndHoldHints() {
        val e = PracticeEngine(chordSong).apply { countIn = false; mode = PracticeMode.WAIT; seek(0.0); play() }
        e.update(0.05)
        e.heldPitches = setOf(60, 64)
        e.onNotesPlayed(setOf(60, 64), setOf(60, 64))
        e.update(1.0) // now at the D: C has ended, E goes on
        assertEquals(1.0, e.position, 1e-9)
        val h = e.holdHints()
        assertEquals(setOf(60), h.release, "C must be let go")
        assertTrue(h.keepHolding.isEmpty())
        e.heldPitches = emptySet() // both let go
        assertEquals(setOf(64), e.holdHints().keepHolding, "E should still be held")
        e.heldIsExact = false // microphone: no "hold" hints
        assertTrue(e.holdHints().keepHolding.isEmpty())
    }
}
