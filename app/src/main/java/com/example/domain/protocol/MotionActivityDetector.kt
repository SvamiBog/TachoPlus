package com.example.domain.protocol

import com.example.data.model.DriverActivity

/**
 * For links that report speed but not the tachograph working state (OBD-II, CCVS without TCO1):
 * switches to DRIVING when the vehicle has been moving for [startDelayMs] and back to WORK after it has been
 * standing for [stopDelayMs] — the default behaviour of a tachograph for driver 1.
 */
class MotionActivityDetector(
    private val startDelayMs: Long = 10_000,
    private val stopDelayMs: Long = 60_000,
    private val movingKmh: Double = 5.0
) {
    private var movingSince: Long? = null
    private var stoppedSince: Long? = null

    /** Returns the activity to switch to, or null to keep [current]. */
    fun onSpeed(speedKmh: Double, nowMs: Long, current: DriverActivity?): DriverActivity? {
        if (speedKmh >= movingKmh) {
            stoppedSince = null
            val since = movingSince ?: nowMs.also { movingSince = it }
            if (current != DriverActivity.DRIVING && nowMs - since >= startDelayMs) return DriverActivity.DRIVING
        } else {
            movingSince = null
            if (current == DriverActivity.DRIVING) {
                val since = stoppedSince ?: nowMs.also { stoppedSince = it }
                if (nowMs - since >= stopDelayMs) {
                    stoppedSince = null
                    return DriverActivity.WORK
                }
            } else {
                stoppedSince = null
            }
        }
        return null
    }

    fun reset() {
        movingSince = null
        stoppedSince = null
    }
}
