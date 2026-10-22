package de.pianotiles.core.practice

import de.pianotiles.core.model.Hand
import de.pianotiles.core.model.Song
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class PracticeMode {
    /** Notes stop at the hit line until the expected keys are played (needs microphone or on-screen keys). */
    WAIT,
    /** Notes run continuously at the selected speed. With microphone enabled, hits are scored. */
    FLOW,
    /** The app plays the piece itself. */
    LISTEN,
}

enum class HandSelection(val left: Boolean, val right: Boolean) {
    BOTH(true, true), LEFT(true, false), RIGHT(false, true);

    fun includes(hand: Hand) = if (hand == Hand.LEFT) left else right
}

enum class NoteState { PENDING, HIT, MISSED, AUTO, INACTIVE }

data class LoopSettings(
    val enabled: Boolean = false,
    val startBeat: Double = 0.0,
    val endBeat: Double = 0.0,
    /** 0 = endless. */
    val repetitions: Int = 0,
    /** "Tempo-Treppe": after a pass with accuracy >= [rampThreshold] the speed grows by [rampStep]. */
    val tempoRamp: Boolean = false,
    val rampStep: Double = 0.05,
    val rampTarget: Double = 1.0,
    val rampThreshold: Int = 90,
)

sealed class EngineEvent {
    /** [delaySeconds]: the note is due this much later than now (playback scheduled ahead). */
    data class PlayNote(val pitch: Int, val velocity: Int, val durationSeconds: Double, val hand: Hand, val delaySeconds: Double = 0.0) : EngineEvent()
    data class Click(val accent: Boolean) : EngineEvent()
    data class PassCompleted(val pass: Int, val accuracy: Int, val newSpeed: Double?) : EngineEvent()
    data class LoopFinished(val passes: Int) : EngineEvent()
    data class SongFinished(val accuracy: Int) : EngineEvent()
    data class StepHit(val pitches: Set<Int>) : EngineEvent()
    data class WrongNote(val pitch: Int, val measure: Int) : EngineEvent()
}

/**
 * Pure, platform independent practice logic. Not thread-safe: call all methods from one thread
 * (the UI thread in the app). Time is measured in beats (quarter notes).
 */
class PracticeEngine(val song: Song) {

    var mode: PracticeMode = PracticeMode.WAIT
        set(value) { field = value; resetRange() }
    var hands: HandSelection = HandSelection.BOTH
        set(value) { field = value; rebuildSteps(); resetRange() }
    /** Tempo factor (1.0 = original tempo). */
    var speed: Double = 1.0
        set(value) { field = value.coerceIn(0.1, 2.5) }
    var metronome: Boolean = false
    var countIn: Boolean = true
    /** Play the hand the user does not practise. */
    var accompaniment: Boolean = true
    /** FLOW mode: also sound the practised hand (useful without microphone, so the whole piece is heard). */
    var playOwnHand: Boolean = false
    /** How far ahead (seconds) notes are handed to the sound output in FLOW/LISTEN (0 = when they are due). */
    var audioLookaheadSeconds: Double = 0.0
    /** Score hits in FLOW mode (requires microphone or on-screen input). */
    var scoreInFlow: Boolean = true
    /** Accept a played note one octave off (microphone harmonics can confuse octaves). */
    var octaveTolerant: Boolean = true
    /** Allowed timing error in FLOW mode, in seconds. */
    var flowToleranceSeconds: Double = 0.30
    /** How early (seconds) a note may be played before it reaches the line in WAIT mode. */
    var waitEarlySeconds: Double = 0.35
    /**
     * Keys of a chord must be played together: struck within this many seconds of each other, or held down
     * together. A key struck earlier and released again no longer counts.
     */
    var chordWindowSeconds: Double = 0.6
    /** Keys the player holds right now (on-screen keys pressed, or still sounding for the microphone). */
    var heldPitches: Set<Int> = emptySet()
    /** False with the microphone: a released piano key keeps ringing, so "let go too early" cannot be told. */
    var heldIsExact: Boolean = true

    var loop: LoopSettings = LoopSettings()
        set(value) {
            val changedRange = value.startBeat != field.startBeat || value.endBeat != field.endBeat || value.enabled != field.enabled
            field = value
            if (changedRange) { passes = 0; if (value.enabled) seek(value.startBeat - preRollBeats()) }
        }

    var position: Double = 0.0
        private set
    var isPlaying: Boolean = false
        private set
    /** Remaining count-in beats before the notes start moving. */
    var countInRemaining: Double = 0.0
        private set
    val noteStates: Array<NoteState> = Array(song.notes.size) { NoteState.PENDING }
    var passes: Int = 0
        private set
    var practiceSeconds: Double = 0.0
        private set

    // Statistics for the current pass / run.
    private var hitSteps = 0
    private var missedSteps = 0
    private var wrongNotes = 0
    val mistakesPerMeasure = HashMap<Int, Int>()

    private class PlayStep(val start: Double, val noteIdx: IntArray, val pitches: Set<Int>) {
        val satisfied = HashSet<Int>()
        /** When each satisfied pitch was struck (engine clock, seconds). */
        val satisfiedAt = HashMap<Int, Double>()
        var done = false
        var wrongWhileActive = false
    }

    private var steps: List<PlayStep> = emptyList()
    private var stepCursor = 0
    private val sortedIdx: IntArray = song.notes.indices.sortedBy { song.notes[it].start }.toIntArray()
    private var autoCursor = 0
    private var lastClickBeat = Double.NEGATIVE_INFINITY

    init { rebuildSteps(); resetRange() }

    val rangeStart: Double get() = if (loop.enabled) loop.startBeat else 0.0
    val rangeEnd: Double get() = if (loop.enabled) loop.endBeat else song.lengthBeats

    /** True when the engine is holding the notes at the line, waiting for input. */
    val isWaiting: Boolean get() = isPlaying && mode == PracticeMode.WAIT && currentStep()?.let { position >= it.start - 1e-9 } == true

    /** Pitches the user is expected to play right now (WAIT) or very soon (FLOW). */
    val expectedPitches: Set<Int>
        get() {
            val s = currentStep() ?: return emptySet()
            val windowBeats = secondsToBeats(if (mode == PracticeMode.WAIT) waitEarlySeconds else flowToleranceSeconds)
            return if (position >= s.start - windowBeats) s.pitches - s.satisfied else emptySet()
        }

    val currentMeasure: Int get() = song.measureIndexAt(max(0.0, position))

    fun accuracy(): Int {
        val total = hitSteps + missedSteps + wrongNotes
        return if (total == 0) 100 else (100.0 * hitSteps / total).roundToInt()
    }

    fun secondsToBeats(seconds: Double) = seconds * song.bpmAt(position) * speed / 60.0

    private fun preRollBeats(): Double = song.measureLength.coerceAtMost(4.0) * 0.5

    private fun rebuildSteps() {
        val groups = ArrayList<PlayStep>()
        var cur = ArrayList<Int>()
        var curStart = Double.NaN
        fun flush() {
            if (cur.isNotEmpty()) groups.add(PlayStep(curStart, cur.toIntArray(), cur.mapTo(HashSet()) { song.notes[it].pitch }))
        }
        for (i in sortedIdx) {
            val n = song.notes[i]
            if (!hands.includes(n.hand)) continue
            if (cur.isEmpty() || abs(n.start - curStart) <= 0.03) {
                if (cur.isEmpty()) curStart = n.start
                cur.add(i)
            } else {
                flush(); cur = arrayListOf(i); curStart = n.start
            }
        }
        flush()
        steps = groups
    }

    /** Resets note states for the active range and moves cursors to [position]. */
    private fun resetRange() {
        for (i in song.notes.indices) {
            val n = song.notes[i]
            val inRange = n.start >= rangeStart - 1e-6 && n.start < rangeEnd - 1e-6
            noteStates[i] = when {
                !inRange -> NoteState.INACTIVE
                mode == PracticeMode.LISTEN || !hands.includes(n.hand) -> NoteState.AUTO
                else -> NoteState.PENDING
            }
        }
        for (s in steps) { s.satisfied.clear(); s.done = false; s.wrongWhileActive = false }
        hitSteps = 0; missedSteps = 0; wrongNotes = 0
        syncCursors()
    }

    private fun syncCursors() {
        stepCursor = steps.indexOfFirst { !it.done && it.start >= position - 1e-6 && inRange(it.start) }.let { if (it < 0) steps.size else it }
        autoCursor = sortedIdx.indexOfFirst { song.notes[it].start >= position - 1e-6 }.let { if (it < 0) sortedIdx.size else it }
        lastClickBeat = floor(position / clickInterval()) * clickInterval() - 1e-9
    }

    private fun inRange(beat: Double) = beat >= rangeStart - 1e-6 && beat < rangeEnd - 1e-6

    private fun currentStep(): PlayStep? {
        if (mode == PracticeMode.LISTEN) return null
        while (stepCursor < steps.size && (steps[stepCursor].done || !inRange(steps[stepCursor].start))) {
            if (steps[stepCursor].start >= rangeEnd) return null
            stepCursor++
        }
        return steps.getOrNull(stepCursor)
    }

    fun play() {
        if (isPlaying) return
        if (position >= rangeEnd - 1e-6) seek(rangeStart - preRollBeats())
        isPlaying = true
        countInRemaining = if (countIn && mode != PracticeMode.LISTEN) song.measureLength else 0.0
        lastCountInClick = Double.NEGATIVE_INFINITY
    }

    fun pause() { isPlaying = false; countInRemaining = 0.0 }

    fun togglePlay() = if (isPlaying) pause() else play()

    /** Jump to a beat. Note states from there on are reset so the passage can be played again. */
    fun seek(beat: Double) {
        position = beat.coerceIn(-song.measureLength, max(song.lengthBeats, 0.0))
        for (s in steps) if (s.start >= position - 1e-6) {
            s.done = false; s.satisfied.clear(); s.satisfiedAt.clear(); s.wrongWhileActive = false
            for (i in s.noteIdx) if (noteStates[i] != NoteState.INACTIVE) noteStates[i] = NoteState.PENDING
        }
        syncCursors()
    }

    fun seekMeasure(index: Int) = seek(song.measureStart(index.coerceIn(0, song.measureCount - 1)))

    fun restart() { seek(rangeStart - preRollBeats()); resetRange() }

    private var lastCountInClick = Double.NEGATIVE_INFINITY

    private fun clickInterval(): Double = if (song.beatUnit == 8 && song.beatsPerMeasure % 3 == 0) 1.5 else 4.0 / song.beatUnit

    /**
     * Advances time. [dtSeconds] is the real time since the last call.
     * Returns events the app should react to (sounds, toasts, ...).
     */
    fun update(dtSeconds: Double): List<EngineEvent> {
        val events = ArrayList<EngineEvent>()
        if (!isPlaying || dtSeconds <= 0) return events
        practiceSeconds += dtSeconds
        currentStep()?.let { expireChordKeys(it) }
        val beatsPerSecond = song.bpmAt(position) * speed / 60.0
        var delta = dtSeconds * beatsPerSecond

        if (countInRemaining > 0) {
            val before = song.measureLength - countInRemaining
            countInRemaining -= delta
            val after = song.measureLength - max(countInRemaining, 0.0)
            val ci = clickInterval()
            var k = floor(before / ci + 1e-9) * ci
            if (k < before - 1e-9) k += ci
            while (k < after) {
                if (k > lastCountInClick) { events.add(EngineEvent.Click(k < 1e-6)); lastCountInClick = k }
                k += ci
            }
            if (countInRemaining > 0) return events
            delta = -countInRemaining
            countInRemaining = 0.0
        }

        var target = position + delta

        // WAIT: never pass the next unresolved step.
        if (mode == PracticeMode.WAIT) {
            currentStep()?.let { s -> if (target > s.start) target = max(position, s.start) }
        }

        // FLOW: steps whose window has passed are missed.
        if (mode == PracticeMode.FLOW && scoreInFlow) {
            val tol = secondsToBeats(flowToleranceSeconds)
            while (true) {
                val s = currentStep() ?: break
                if (target <= s.start + tol) break
                s.done = true
                missedSteps++
                for (i in s.noteIdx) noteStates[i] = NoteState.MISSED
                addMistake(s.start)
                stepCursor++
            }
        } else if (mode == PracticeMode.FLOW) {
            while (true) {
                val s = currentStep() ?: break
                if (target < s.start) break
                s.done = true
                for (i in s.noteIdx) noteStates[i] = NoteState.HIT
                stepCursor++
            }
        }

        // Auto-played notes (accompaniment / listen mode). Outside WAIT they are handed out a little ahead of time
        // with their exact delay, so the sound does not jitter with the screen frames.
        val ahead = if (mode == PracticeMode.WAIT) 0.0 else audioLookaheadSeconds * beatsPerSecond
        while (autoCursor < sortedIdx.size && song.notes[sortedIdx[autoCursor]].start <= target + ahead + 1e-9) {
            val idx = sortedIdx[autoCursor]
            val n = song.notes[idx]
            if (n.start >= position - 1e-9 && inRange(n.start)) {
                val shouldPlay = mode == PracticeMode.LISTEN || (!hands.includes(n.hand) && accompaniment) ||
                    (mode == PracticeMode.FLOW && playOwnHand && hands.includes(n.hand))
                val delay = ((n.start - target) / beatsPerSecond).coerceAtLeast(0.0)
                if (shouldPlay) events.add(EngineEvent.PlayNote(n.pitch, n.velocity, n.duration / beatsPerSecond, n.hand, delay))
            }
            autoCursor++
        }

        // Metronome.
        if (metronome) {
            val ci = clickInterval()
            var k = floor(position / ci + 1e-9) * ci
            if (k <= lastClickBeat + 1e-9) k += ci
            while (k <= target + 1e-9) {
                if (k >= 0) {
                    val m = song.measureIndexAt(k)
                    events.add(EngineEvent.Click(abs(k - song.measureStart(m)) < 1e-6))
                }
                lastClickBeat = k
                k += ci
            }
        } else {
            lastClickBeat = target
        }

        position = target

        // End of range: loop or finish.
        if (position >= rangeEnd - 1e-9 && currentStep() == null) {
            val acc = accuracy()
            if (loop.enabled) {
                passes++
                var newSpeed: Double? = null
                if (loop.tempoRamp && acc >= loop.rampThreshold && speed < loop.rampTarget - 1e-6) {
                    speed = min(loop.rampTarget, speed + loop.rampStep)
                    newSpeed = speed
                }
                events.add(EngineEvent.PassCompleted(passes, acc, newSpeed))
                if (loop.repetitions in 1..passes) {
                    events.add(EngineEvent.LoopFinished(passes))
                    isPlaying = false
                }
                val keep = passes
                position = rangeStart - preRollBeats()
                resetRange()
                passes = keep
            } else {
                isPlaying = false
                events.add(EngineEvent.SongFinished(acc))
            }
        }
        return events
    }

    private fun addMistake(beat: Double) {
        val m = song.measureIndexAt(beat)
        mistakesPerMeasure[m] = (mistakesPerMeasure[m] ?: 0) + 1
    }

    private fun matches(heard: Int, expected: Int): Boolean =
        heard == expected || (octaveTolerant && abs(heard - expected) == 12)

    /**
     * Feed note onsets detected by the microphone or pressed on the on-screen keyboard.
     * Returns events (hit / wrong note).
     */
    fun onNotesPlayed(pitches: Set<Int>, sounding: Set<Int> = emptySet()): List<EngineEvent> {
        val events = ArrayList<EngineEvent>()
        if (!isPlaying || countInRemaining > 0 || mode == PracticeMode.LISTEN) return events
        if (mode == PracticeMode.FLOW && !scoreInFlow) return events
        val s = currentStep() ?: return events
        val window = secondsToBeats(if (mode == PracticeMode.WAIT) waitEarlySeconds else flowToleranceSeconds)
        if (position < s.start - window) {
            // Played too early / something unrelated – ignore notes still sounding from the previous step.
            return events
        }
        // Notes that are still sounding count as well (e.g. the key was pressed a moment before the
        // note reached the line) unless they were already part of the previous step (repeated notes
        // must be struck again).
        val now = practiceSeconds
        for (p in sounding) {
            if (previousStepContains(p)) continue
            s.pitches.firstOrNull { matches(p, it) }?.let { if (s.satisfied.add(it)) s.satisfiedAt[it] = now }
        }
        for (p in pitches) {
            val match = s.pitches.firstOrNull { matches(p, it) }
            if (match != null) {
                s.satisfied.add(match)
                s.satisfiedAt[match] = now
            } else if (!previousStepContains(p)) {
                wrongNotes++
                s.wrongWhileActive = true
                addMistake(s.start)
                events.add(EngineEvent.WrongNote(p, song.measureIndexAt(s.start)))
            }
        }
        expireChordKeys(s, sounding)
        if (s.satisfied.containsAll(s.pitches)) {
            s.done = true
            hitSteps++
            for (i in s.noteIdx) noteStates[i] = NoteState.HIT
            stepCursor++
            events.add(EngineEvent.StepHit(s.pitches))
        }
        return events
    }

    /**
     * Chord keys struck too long ago and no longer held do not count any more: a chord has to be played together,
     * not collected one key after the other.
     */
    private fun expireChordKeys(s: PlayStep, alsoHeld: Set<Int> = emptySet()) {
        if (s.pitches.size < 2 || s.satisfied.isEmpty()) return
        val now = practiceSeconds
        val held = heldPitches + alsoHeld
        s.satisfied.removeAll { p ->
            val old = now - (s.satisfiedAt[p] ?: now) > chordWindowSeconds
            val stillHeld = held.any { matches(it, p) }
            if (old && !stillHeld) { s.satisfiedAt.remove(p); true } else false
        }
    }

    /** What the player should do with the keys right now, beyond striking the next notes. */
    data class HoldHints(
        /** Keys still held although their note has ended (e.g. one key of a held pair must be let go). */
        val release: Set<Int>,
        /** Keys of notes still sounding that were let go too early. */
        val keepHolding: Set<Int>,
    )

    fun holdHints(): HoldHints {
        if (!isPlaying || mode == PracticeMode.LISTEN) return HoldHints(emptySet(), emptySet())
        val held = heldPitches
        val early = secondsToBeats(if (mode == PracticeMode.WAIT) waitEarlySeconds else flowToleranceSeconds)
        val release = HashSet<Int>()
        val keep = HashSet<Int>()
        // notes of the practised hands around now
        var due = false
        for (i in song.notes.indices) {
            val n = song.notes[i]
            if (!hands.includes(n.hand) || noteStates[i] == NoteState.INACTIVE) continue
            if (n.start > position + early + 1e-9 || n.end < position - 4.0) continue
            due = true
        }
        if (!due) return HoldHints(emptySet(), emptySet())
        for (p in held) {
            val needed = song.notes.indices.any { i ->
                val n = song.notes[i]
                hands.includes(n.hand) && noteStates[i] != NoteState.INACTIVE && matches(p, n.pitch) &&
                    n.start <= position + early + 1e-9 && n.end > position + 0.05
            }
            // with the microphone a released key rings on a little: only complain a bit later
            val grace = if (heldIsExact) 0.05 else -0.5
            val endedRecently = song.notes.any { n -> hands.includes(n.hand) && matches(p, n.pitch) && n.end <= position + grace && n.end > position - 4.0 }
            if (!needed && endedRecently) release.add(p)
        }
        if (heldIsExact) for (i in song.notes.indices) {
            val n = song.notes[i]
            if (!hands.includes(n.hand) || noteStates[i] != NoteState.HIT) continue
            // a note that was played and should still be held for a while
            if (n.start <= position && n.end - position > 0.3 && n.duration >= 0.75 && held.none { matches(it, n.pitch) }) keep.add(n.pitch)
        }
        return HoldHints(release, keep)
    }

    private fun previousStepContains(p: Int): Boolean {
        val prev = steps.getOrNull(stepCursor - 1) ?: return false
        return prev.pitches.any { matches(p, it) }
    }

    /** Marks the current step as done without input ("skip this note"). */
    fun skipStep() {
        val s = currentStep() ?: return
        s.done = true
        for (i in s.noteIdx) noteStates[i] = NoteState.MISSED
        missedSteps++
        stepCursor++
    }

    /** All pitches of notes that are currently sounding (for keyboard highlighting). */
    fun soundingNotes(): List<Int> {
        val out = ArrayList<Int>()
        for (i in song.notes.indices) {
            val n = song.notes[i]
            if (n.start <= position + 1e-6 && n.end > position && noteStates[i] != NoteState.INACTIVE) out.add(i)
        }
        return out
    }
}
