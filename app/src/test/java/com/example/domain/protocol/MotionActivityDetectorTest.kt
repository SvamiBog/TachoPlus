package com.example.domain.protocol

import com.example.data.model.DriverActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MotionActivityDetectorTest {

    @Test
    fun drivingStartsAfterSustainedMotionAndEndsAfterStop() {
        val detector = MotionActivityDetector(startDelayMs = 10_000, stopDelayMs = 60_000)
        assertNull(detector.onSpeed(40.0, 0, DriverActivity.WORK))
        assertNull(detector.onSpeed(40.0, 9_000, DriverActivity.WORK))
        assertEquals(DriverActivity.DRIVING, detector.onSpeed(40.0, 10_000, DriverActivity.WORK))

        assertNull(detector.onSpeed(0.0, 20_000, DriverActivity.DRIVING))
        assertNull(detector.onSpeed(0.0, 79_000, DriverActivity.DRIVING))
        assertEquals(DriverActivity.WORK, detector.onSpeed(0.0, 80_000, DriverActivity.DRIVING))
    }

    @Test
    fun shortMovementsAndStopsAreIgnored() {
        val detector = MotionActivityDetector(startDelayMs = 10_000, stopDelayMs = 60_000)
        assertNull(detector.onSpeed(20.0, 0, DriverActivity.REST))
        assertNull(detector.onSpeed(0.0, 5_000, DriverActivity.REST))
        assertNull(detector.onSpeed(20.0, 6_000, DriverActivity.REST))
        assertNull(detector.onSpeed(20.0, 15_000, DriverActivity.REST))

        // A traffic-light stop during driving does not end the driving period.
        assertNull(detector.onSpeed(0.0, 100_000, DriverActivity.DRIVING))
        assertNull(detector.onSpeed(30.0, 130_000, DriverActivity.DRIVING))
        assertNull(detector.onSpeed(0.0, 140_000, DriverActivity.DRIVING))
        assertNull(detector.onSpeed(0.0, 190_000, DriverActivity.DRIVING))
    }
}
