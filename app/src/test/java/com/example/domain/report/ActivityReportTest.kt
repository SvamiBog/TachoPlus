package com.example.domain.report

import com.example.data.model.ActivityPeriod
import com.example.data.model.DriverActivity
import com.example.domain.compliance.ComplianceCalculator
import com.example.domain.compliance.ComplianceRules.HOUR
import com.example.domain.protocol.VehicleLiveState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ActivityReportTest {
    private val monday = 1_791_158_400_000L

    private val periods = listOf(
        ActivityPeriod(DriverActivity.REST, monday - 12 * HOUR, monday + 6 * HOUR),
        ActivityPeriod(DriverActivity.DRIVING, monday + 6 * HOUR, monday + 10 * HOUR),
        ActivityPeriod(DriverActivity.REST, monday + 10 * HOUR, null)
    )

    @Test
    fun timelineIsClippedToTheDay() {
        val now = monday + 11 * HOUR
        val segments = TimelineBuilder.segmentsForDay(periods, monday, now)
        assertEquals(3, segments.size)
        assertEquals(0, segments[0].startMinuteOfDay)
        assertEquals(360, segments[0].durationMinutes)
        assertEquals(DriverActivity.DRIVING, segments[1].activity)
        assertEquals(240, segments[1].durationMinutes)
        assertEquals(60, segments[2].durationMinutes)
    }

    @Test
    fun reportIsHonestAboutWhatItIs() {
        val now = monday + 11 * HOUR
        val status = ComplianceCalculator.calculate(periods, now)
        val text = ActivityReportBuilder.build(
            periods, status, VehicleLiveState(vin = "WDB9634031L894102"), now, TimeZone.getTimeZone("UTC")
        )
        assertTrue(text.contains("не файл DDD"))
        assertTrue(text.contains("VIN: WDB9634031L894102"))
        assertTrue(text.contains("Непрерывное вождение: 00:00 из 04:30"))
        assertFalse("no claims about signatures", text.contains("ЭЦП"))
    }
}
