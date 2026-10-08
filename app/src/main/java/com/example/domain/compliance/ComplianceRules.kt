package com.example.domain.compliance

/**
 * Limits of Regulation (EC) No 561/2006 (as amended by Regulation (EU) 2020/1054), in milliseconds.
 */
object ComplianceRules {
    const val MINUTE = 60_000L
    const val HOUR = 60 * MINUTE
    const val DAY = 24 * HOUR
    const val WEEK = 7 * DAY

    /** Art. 7: maximum driving before a break. */
    const val MAX_CONTINUOUS_DRIVING = 4 * HOUR + 30 * MINUTE
    const val BREAK_FULL = 45 * MINUTE
    /** Art. 7: a break may be split into at least 15 min followed by at least 30 min, in that order. */
    const val BREAK_SPLIT_FIRST = 15 * MINUTE
    const val BREAK_SPLIT_SECOND = 30 * MINUTE

    /** Art. 6(1): daily driving 9 h, extendable to 10 h at most twice a week. */
    const val DAILY_DRIVING = 9 * HOUR
    const val DAILY_DRIVING_EXTENDED = 10 * HOUR
    const val MAX_EXTENSIONS_PER_WEEK = 2

    /** Art. 4(g), 8: regular daily rest 11 h (or 3 h + 9 h), reduced 9 h at most 3 times between weekly rests. */
    const val DAILY_REST_REGULAR = 11 * HOUR
    const val DAILY_REST_REDUCED = 9 * HOUR
    const val SPLIT_DAILY_REST_FIRST = 3 * HOUR
    const val MAX_REDUCED_DAILY_RESTS = 3

    /** Art. 4(h), 8(6): weekly rest regular 45 h, reduced 24 h; must start within 6×24 h of the previous one. */
    const val WEEKLY_REST_REDUCED = 24 * HOUR
    const val WEEKLY_REST_REGULAR = 45 * HOUR
    const val WEEKLY_REST_INTERVAL = 6 * DAY

    /** Art. 6(2), 6(3): weekly driving 56 h, any two consecutive weeks 90 h. */
    const val MAX_WEEKLY_DRIVING = 56 * HOUR
    const val MAX_BIWEEKLY_DRIVING = 90 * HOUR

    /** Art. 8(2): the daily rest must be taken within 24 h after the end of the previous rest. */
    const val SHIFT_WINDOW = 24 * HOUR

    const val WARNING_LEAD = 15 * MINUTE
    const val SHIFT_WARNING_LEAD = 30 * MINUTE
    const val WEEKLY_LIMIT_WARNING_LEAD = HOUR
    const val WEEKLY_REST_WARNING_LEAD = 12 * HOUR

    /** Gaps in the record shorter than this are closed silently; longer gaps are treated as rest. */
    const val GAP_TOLERANCE = MINUTE
}

/** The tachograph week runs from Monday 00:00 to Sunday 24:00 UTC. */
object UtcWeeks {
    fun weekStart(ms: Long): Long {
        val day = ms.floorDiv(ComplianceRules.DAY)
        // 1970-01-01 was a Thursday: (day + 3) mod 7 == 0 on Mondays.
        val dayOfWeek = (day + 3).mod(7L)
        return (day - dayOfWeek) * ComplianceRules.DAY
    }
}
