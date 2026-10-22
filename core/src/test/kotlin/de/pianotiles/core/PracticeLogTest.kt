package de.pianotiles.core

import de.pianotiles.core.practice.PracticeLog
import kotlin.test.Test
import kotlin.test.assertEquals

class PracticeLogTest {
    @Test
    fun todayWeekStreak() {
        val log = PracticeLog().add(100, 300).add(99, 120).add(98, 600).add(96, 900).add(100, 60)
        assertEquals(360, log.today(100))
        assertEquals(360 + 120 + 600 + 900, log.week(100))
        assertEquals(3, log.streak(100))
        // today not practised yet: the streak up to yesterday still counts
        assertEquals(3, log.streak(101))
        assertEquals(0, log.streak(102))
    }

    @Test
    fun shortSessionsDoNotCount() {
        assertEquals(0, PracticeLog().add(10, 20).streak(10))
    }

    @Test
    fun roundTrip() {
        val log = PracticeLog().add(5, 70).add(7, 3000)
        assertEquals(log, PracticeLog.decode(log.encode()))
        assertEquals(PracticeLog(), PracticeLog.decode("garbage,1:x"))
        // old days are dropped
        assertEquals(setOf(1000L), PracticeLog().add(1, 100).add(1000, 100).days.keys)
    }
}
