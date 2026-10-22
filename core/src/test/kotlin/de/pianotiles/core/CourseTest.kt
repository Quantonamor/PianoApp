package de.pianotiles.core

import de.pianotiles.core.model.DemoSongs
import de.pianotiles.core.model.Hand
import de.pianotiles.core.practice.Course
import de.pianotiles.core.practice.HandSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CourseTest {
    @Test
    fun everyLessonHasItsPieceWithTheRightHand() {
        val songs = (DemoSongs.all() + Course.exerciseSongs()).associateBy { it.id }
        assertEquals(Course.lessons.size, Course.lessons.map { it.id }.toSet().size, "unique ids")
        for (l in Course.lessons) {
            val s = songs[l.songId]
            assertTrue(s != null, "${l.id}: piece ${l.songId}")
            if (l.hands == HandSelection.RIGHT) assertTrue(s.notes.any { it.hand == Hand.RIGHT }, "${l.id}: right hand notes")
            if (l.hands == HandSelection.LEFT) assertTrue(s.notes.any { it.hand == Hand.LEFT }, "${l.id}: left hand notes")
        }
    }

    @Test
    fun starsAndNextLesson() {
        val l = Course.lessons.first()
        assertEquals(0, l.stars(70)); assertEquals(1, l.stars(80)); assertEquals(2, l.stars(90)); assertEquals(3, l.stars(99))
        assertEquals("l01", Course.next { 0 }.id)
        assertEquals("l03", Course.next { if (it == "l01" || it == "l02") 2 else 0 }.id)
        assertEquals(Course.lessons.last().id, Course.next { 3 }.id)
    }
}
