package de.pianotiles.core.model

import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

object SongJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun encode(song: Song): String = json.encodeToString(Song.serializer(), song)
    fun decode(text: String): Song = json.decodeFromString(Song.serializer(), text)
}

object SongUtils {
    /**
     * Assigns hands for notes that have no reliable hand information:
     * notes of a chord/step are split around middle C, single notes follow
     * the nearest previous note of the same "voice" (simple heuristic).
     */
    fun splitHands(notes: List<Note>, splitPitch: Int = 60): List<Note> =
        notes.map { it.copy(hand = if (it.pitch >= splitPitch) Hand.RIGHT else Hand.LEFT) }

    fun transpose(song: Song, semitones: Int): Song =
        song.copy(notes = song.notes.map { it.copy(pitch = (it.pitch + semitones).coerceIn(Pitch.LOWEST_PIANO, Pitch.HIGHEST_PIANO)) })

    /** Rounds note starts/durations to a grid (in beats, e.g. 0.25 = sixteenth). */
    fun quantize(notes: List<Note>, grid: Double = 0.125): List<Note> = notes.map {
        val s = (it.start / grid).roundToInt() * grid
        val e = ((it.end / grid).roundToInt() * grid).coerceAtLeast(s + grid)
        it.copy(start = s, duration = e - s)
    }

    /** Removes exact duplicates (same pitch + same start) which confuse the practice engine. */
    fun dedupe(notes: List<Note>): List<Note> =
        notes.groupBy { it.pitch to (it.start * 1000).roundToInt() }
            .map { (_, list) -> list.maxBy { it.duration } }
            .sortedWith(compareBy({ it.start }, { it.pitch }))

    /** Shifts everything so the first note starts at beat 0 (keeping the pick-up). */
    fun trimLeadingSilence(song: Song): Song {
        val first = song.notes.minOfOrNull { it.start } ?: return song
        val shift = Math.floor(first / song.measureLength) * song.measureLength
        if (shift <= 0) return song
        return song.copy(
            notes = song.notes.map { it.copy(start = it.start - shift) },
            sections = song.sections.map { it.copy(startBeat = it.startBeat - shift, endBeat = it.endBeat - shift) },
        )
    }

    fun newId(): String = java.util.UUID.randomUUID().toString()
}
