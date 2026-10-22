package de.pianotiles.core.practice

/**
 * Practice time per calendar day (epoch day → seconds), for "today / this week / days in a row".
 * Stored as a compact string "day:seconds,day:seconds"; only the last [KEEP_DAYS] days are kept.
 */
data class PracticeLog(val days: Map<Long, Int> = emptyMap()) {

    fun add(day: Long, seconds: Int): PracticeLog {
        if (seconds <= 0) return this
        val m = HashMap(days)
        m[day] = (m[day] ?: 0) + seconds
        return PracticeLog(m.filterKeys { it > day - KEEP_DAYS })
    }

    fun today(day: Long): Int = days[day] ?: 0

    /** Seconds of the last 7 days including [day]. */
    fun week(day: Long): Int = (0 until 7).sumOf { days[day - it] ?: 0 }

    /** Days in a row with practice, ending today (or yesterday, if today has not been practised yet). */
    fun streak(day: Long): Int {
        var d = if (today(day) >= MIN_SECONDS) day else day - 1
        var n = 0
        while ((days[d] ?: 0) >= MIN_SECONDS) { n++; d-- }
        return n
    }

    fun encode(): String = days.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }

    companion object {
        const val KEEP_DAYS = 400
        /** A day counts for the streak from one minute on. */
        const val MIN_SECONDS = 60

        fun decode(s: String?): PracticeLog = PracticeLog(
            s.orEmpty().split(',').mapNotNull { e ->
                val p = e.split(':')
                if (p.size != 2) null else p[0].toLongOrNull()?.let { d -> p[1].toIntOrNull()?.let { d to it } }
            }.toMap()
        )
    }
}
