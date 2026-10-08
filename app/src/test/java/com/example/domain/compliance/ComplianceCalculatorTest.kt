package com.example.domain.compliance

import com.example.data.model.ActivityPeriod
import com.example.data.model.DriverActivity
import com.example.domain.compliance.ComplianceRules.DAY
import com.example.domain.compliance.ComplianceRules.HOUR
import com.example.domain.compliance.ComplianceRules.MINUTE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComplianceCalculatorTest {

    /** Monday 2026-10-05 00:00 UTC. */
    private val monday = 1_791_158_400_000L

    /** Builds back-to-back periods; [open] leaves the last period without an end, as a live recording does. */
    private class Timeline(start: Long) {
        var cursor = start
        val periods = mutableListOf<ActivityPeriod>()

        fun add(activity: DriverActivity, hours: Int = 0, minutes: Int = 0): Timeline {
            val duration = hours * HOUR + minutes * MINUTE
            periods += ActivityPeriod(activity, cursor, cursor + duration)
            cursor += duration
            return this
        }

        fun drive(h: Int = 0, m: Int = 0) = add(DriverActivity.DRIVING, h, m)
        fun rest(h: Int = 0, m: Int = 0) = add(DriverActivity.REST, h, m)
        fun work(h: Int = 0, m: Int = 0) = add(DriverActivity.WORK, h, m)
        fun gap(h: Int = 0, m: Int = 0): Timeline { cursor += h * HOUR + m * MINUTE; return this }

        fun open(): List<ActivityPeriod> = periods.dropLast(1) + periods.last().copy(endMs = null)
    }

    private fun calc(timeline: Timeline, openLast: Boolean = true) =
        ComplianceCalculator.calculate(if (openLast) timeline.open() else timeline.periods, timeline.cursor)

    private fun ComplianceStatus.alert(prefix: String) = alerts.firstOrNull { it.key.startsWith(prefix) }

    @Test
    fun emptyRecordHasNoDataAndNoAlerts() {
        val status = ComplianceCalculator.calculate(emptyList(), monday)
        assertFalse(status.hasData)
        assertTrue(status.alerts.isEmpty())
        assertNull(status.nextAlertAtMs)
    }

    @Test
    fun fourHoursOfDrivingLeavesThirtyMinutes() {
        val status = calc(Timeline(monday + 6 * HOUR).drive(4))
        assertEquals(4 * HOUR, status.continuousDrivingMs)
        assertEquals(30 * MINUTE, status.remainingContinuousDrivingMs)
        assertTrue(status.alerts.none { it.kind == AlertKind.CONTINUOUS_DRIVING })
        // The 15-minute warning is the next thing that will happen.
        assertEquals(monday + 10 * HOUR + 15 * MINUTE, status.nextAlertAtMs)
    }

    @Test
    fun warningFifteenMinutesBeforeLimitAndViolationAfter() {
        val warn = calc(Timeline(monday + 6 * HOUR).drive(4, 20))
        assertEquals(AlertSeverity.WARNING, warn.alert("cont-warn")?.severity)

        val violation = calc(Timeline(monday + 6 * HOUR).drive(4, 31))
        assertEquals(AlertSeverity.VIOLATION, violation.alert("cont-viol")?.severity)
        assertTrue(violation.hasViolation)
    }

    @Test
    fun fullBreakResetsContinuousDriving() {
        val status = calc(Timeline(monday + 6 * HOUR).drive(3).rest(0, 45).drive(1))
        assertEquals(1 * HOUR, status.continuousDrivingMs)
    }

    @Test
    fun splitBreakFifteenThenThirtyResets() {
        val status = calc(Timeline(monday + 6 * HOUR).drive(2).rest(0, 15).drive(2).rest(0, 30).drive(1))
        assertEquals(1 * HOUR, status.continuousDrivingMs)
    }

    @Test
    fun splitBreakInWrongOrderDoesNotReset() {
        // 30 min then 15 min is not a valid split break (Art. 7): 5 h of driving is a violation.
        val status = calc(Timeline(monday + 6 * HOUR).drive(2).rest(0, 30).drive(2).rest(0, 15).drive(1))
        assertEquals(5 * HOUR, status.continuousDrivingMs)
        assertNotNull(status.alert("cont-viol"))
    }

    @Test
    fun breaksShorterThanFifteenMinutesDoNotCount() {
        val status = calc(Timeline(monday + 6 * HOUR).drive(4).rest(0, 10).drive(0, 40))
        assertEquals(4 * HOUR + 40 * MINUTE, status.continuousDrivingMs)
        assertNotNull(status.alert("cont-viol"))
    }

    @Test
    fun otherWorkIsNotABreak() {
        val status = calc(Timeline(monday + 6 * HOUR).drive(3).work(1).drive(1))
        assertEquals(4 * HOUR, status.continuousDrivingMs)
    }

    @Test
    fun ongoingBreakShowsProgressAndResetsAfterFortyFiveMinutes() {
        val partial = calc(Timeline(monday + 6 * HOUR).drive(3).rest(0, 20))
        assertEquals(20 * MINUTE, partial.currentBreakMs)
        assertEquals(45 * MINUTE, partial.currentBreakRequiredMs)
        assertEquals(3 * HOUR, partial.continuousDrivingMs)

        val done = calc(Timeline(monday + 6 * HOUR).drive(3).rest(0, 46))
        assertEquals(0, done.continuousDrivingMs)
    }

    @Test
    fun secondPartOfSplitBreakNeedsThirtyMinutes() {
        val status = calc(Timeline(monday + 6 * HOUR).drive(2).rest(0, 15).drive(1).rest(0, 10))
        assertTrue(status.splitBreakFirstPartTaken)
        assertEquals(30 * MINUTE, status.currentBreakRequiredMs)
    }

    @Test
    fun dailyRestStartsANewShift() {
        val status = calc(Timeline(monday + 6 * HOUR).drive(4).rest(0, 45).drive(4).rest(11).drive(2))
        assertEquals(2 * HOUR, status.dailyDrivingMs)
        assertTrue(status.shiftStartKnown)
        assertEquals(monday + 6 * HOUR + 8 * HOUR + 45 * MINUTE + 11 * HOUR, status.shiftStartMs)
        val closed = status.shifts.first { !it.ongoing }
        assertEquals(8 * HOUR, closed.drivingMs)
        assertEquals(RestType.DAILY_REGULAR, closed.endingRest)
    }

    @Test
    fun reducedDailyRestsAreCountedAndLimitTheNextRest() {
        val t = Timeline(monday + 6 * HOUR)
        repeat(3) { t.drive(4).rest(0, 45).drive(2).rest(9) }
        t.drive(1)
        val status = calc(t)
        assertEquals(3, status.reducedDailyRestsUsed)
        // No reduced rest left: the next daily rest must be 11 h, so it has to start 13 h into the shift.
        assertEquals(ComplianceRules.DAILY_REST_REGULAR, status.nextDailyRestRequiredMs)
        assertEquals(status.shiftStartMs!! + 13 * HOUR, status.latestDailyRestStartMs)
    }

    @Test
    fun splitDailyRestIsRegular() {
        val status = calc(Timeline(monday + 6 * HOUR).drive(3).rest(3).drive(4).rest(9).drive(1))
        assertEquals(0, status.reducedDailyRestsUsed)
        assertEquals(RestType.DAILY_SPLIT, status.shifts.first { !it.ongoing }.endingRest)
    }

    @Test
    fun weeklyRestSetsDueDateAndResetsReducedRests() {
        val t = Timeline(monday)
        t.drive(4).rest(9).drive(4).rest(45).drive(2)
        val status = calc(t)
        assertEquals(0, status.reducedDailyRestsUsed)
        val restEnd = monday + 4 * HOUR + 9 * HOUR + 4 * HOUR + 45 * HOUR
        assertEquals(restEnd, status.lastWeeklyRestEndMs)
        assertEquals(restEnd + 6 * DAY, status.weeklyRestDueMs)
    }

    @Test
    fun weeklyDrivingIsSplitAtMondayMidnightUtc() {
        // Sunday 22:00 → Monday 02:00 UTC: 2 h fall into the previous week, 2 h into the current one.
        val status = calc(Timeline(monday - 2 * HOUR).drive(4))
        assertEquals(2 * HOUR, status.weeklyDrivingMs)
        assertEquals(2 * HOUR, status.previousWeekDrivingMs)
        assertEquals(4 * HOUR, status.biweeklyDrivingMs)
    }

    @Test
    fun twoExtensionsPerWeekThenNineHoursIsTheLimit() {
        val t = Timeline(monday)
        repeat(2) { t.drive(4, 30).rest(0, 45).drive(4, 30).rest(0, 45).drive(1).rest(11) }
        t.drive(4, 30).rest(0, 45).drive(4, 30).rest(0, 45).drive(0, 30)
        val status = calc(t)
        assertEquals(2, status.extensionsUsedThisWeek)
        assertEquals(ComplianceRules.DAILY_DRIVING, status.dailyDrivingLimitMs)
        assertNotNull(status.alert("daily-viol"))
    }

    @Test
    fun firstExtensionIsInformationNotViolation() {
        val status = calc(Timeline(monday).drive(4, 30).rest(0, 45).drive(4, 30).rest(0, 45).drive(0, 30))
        assertEquals(DriverActivity.DRIVING, status.currentActivity)
        assertEquals(ComplianceRules.DAILY_DRIVING_EXTENDED, status.dailyDrivingLimitMs)
        assertEquals(AlertSeverity.INFO, status.alert("daily-ext")?.severity)
        assertFalse(status.hasViolation)
    }

    @Test
    fun gapsAreTreatedAsRest() {
        // Adapter off overnight: 12 h without data counts as a daily rest.
        val status = calc(Timeline(monday + 6 * HOUR).drive(2).gap(12).drive(1))
        assertEquals(1 * HOUR, status.dailyDrivingMs)
        assertEquals(12 * HOUR, status.assumedRestMs)
        assertTrue(status.shiftStartKnown)
    }

    @Test
    fun trailingGapIsAnAssumedRestInProgress() {
        val t = Timeline(monday + 6 * HOUR).drive(2)
        val status = ComplianceCalculator.calculate(t.periods, t.cursor + 30 * MINUTE)
        assertEquals(DriverActivity.REST, status.currentActivity)
        assertTrue(status.currentActivityAssumed)
        assertEquals(30 * MINUTE, status.currentBreakMs)
    }

    @Test
    fun shiftLongerThanTheWindowIsAViolation() {
        val t = Timeline(monday).rest(11)
        repeat(4) { t.work(3).rest(0, 45) }
        t.work(4)
        val status = calc(t)
        assertNotNull(status.alert("shift-viol"))
    }

    @Test
    fun restStartedInTimeIsNotAShiftViolation() {
        val t = Timeline(monday).rest(11).drive(4).rest(0, 45).drive(4).work(4).rest(2)
        val status = calc(t)
        assertNull(status.alert("shift-viol"))
    }

    @Test
    fun ongoingDailyRestMeansNoActiveShift() {
        val status = calc(Timeline(monday + 6 * HOUR).drive(4).rest(0, 45).drive(4).rest(10))
        assertTrue(status.inDailyRest)
        assertEquals(10 * HOUR, status.currentRestMs)
        assertNull(status.shiftStartMs)
        assertEquals(0, status.dailyDrivingMs)
        // A rest still in progress is not yet counted as a reduced one.
        assertEquals(0, status.reducedDailyRestsUsed)
    }

    @Test
    fun overlappingPeriodsAreResolvedLaterStartWins() {
        val periods = listOf(
            ActivityPeriod(DriverActivity.DRIVING, monday, monday + 3 * HOUR),
            ActivityPeriod(DriverActivity.REST, monday + 2 * HOUR, null)
        )
        val status = ComplianceCalculator.calculate(periods, monday + 3 * HOUR)
        assertEquals(2 * HOUR, status.dailyDrivingMs)
        assertEquals(1 * HOUR, status.currentRestMs)
    }

    @Test
    fun weeklyLimitWarningAndViolation() {
        val t = Timeline(monday)
        // 5 × (9 h driving in 4.5 h blocks) + 11 h rests = 45 h; then one 10 h day = 55 h; then 1 h 30 more.
        repeat(5) { t.drive(4, 30).rest(0, 45).drive(4, 30).rest(11) }
        t.drive(4, 30).rest(0, 45).drive(4, 30).rest(0, 45).drive(1).rest(11)
        t.drive(1, 30)
        val status = calc(t)
        assertEquals(56 * HOUR + 30 * MINUTE, status.weeklyDrivingMs)
        assertNotNull(status.alert("week-viol"))
    }
}
