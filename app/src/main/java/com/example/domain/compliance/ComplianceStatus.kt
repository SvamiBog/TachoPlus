package com.example.domain.compliance

import com.example.data.model.DriverActivity

enum class AlertSeverity { INFO, WARNING, VIOLATION }

enum class AlertKind {
    CONTINUOUS_DRIVING,
    DAILY_DRIVING,
    DAILY_REST,
    WEEKLY_DRIVING,
    BIWEEKLY_DRIVING,
    WEEKLY_REST
}

/**
 * A warning or violation. [key] is stable for the situation that caused it (it embeds the start of the
 * driving block, shift or week), so the same alert is notified only once.
 */
data class ComplianceAlert(
    val key: String,
    val kind: AlertKind,
    val severity: AlertSeverity,
    val title: String,
    val message: String
)

enum class RestType(val titleRu: String) {
    DAILY_REGULAR("Ежедневный отдых (обычный)"),
    DAILY_SPLIT("Ежедневный отдых (3 ч + 9 ч)"),
    DAILY_REDUCED("Ежедневный отдых (сокращённый)"),
    WEEKLY_REGULAR("Еженедельный отдых (обычный)"),
    WEEKLY_REDUCED("Еженедельный отдых (сокращённый)")
}

/** Working period between two daily (or weekly) rests. */
data class ShiftSummary(
    val startMs: Long,
    val endMs: Long,
    val ongoing: Boolean,
    /** False when the record starts inside this shift, so the real start is earlier. */
    val startKnown: Boolean,
    val drivingMs: Long,
    val workMs: Long,
    val availableMs: Long,
    val restMs: Long,
    val endingRest: RestType?,
    val endingRestMs: Long
) {
    val extendedDriving: Boolean get() = drivingMs > ComplianceRules.DAILY_DRIVING
}

data class ComplianceStatus(
    val nowMs: Long,
    val hasData: Boolean,
    val trackingSinceMs: Long?,
    /** Time without any record in the current and previous week that is counted as rest. */
    val assumedRestMs: Long,

    val currentActivity: DriverActivity?,
    val currentActivitySinceMs: Long?,
    /** True when the current activity is not recorded (no data since the last period) and is assumed to be rest. */
    val currentActivityAssumed: Boolean,

    // Art. 7 — driving since the last qualifying break
    val continuousDrivingMs: Long,
    val continuousBlockStartMs: Long?,
    val splitBreakFirstPartTaken: Boolean,
    val currentBreakMs: Long,
    val currentBreakRequiredMs: Long,

    // Art. 6(1), 8 — the current shift
    val inDailyRest: Boolean,
    val inWeeklyRest: Boolean,
    val currentRestMs: Long,
    val shiftStartMs: Long?,
    val shiftStartKnown: Boolean,
    val dailyDrivingMs: Long,
    val dailyDrivingLimitMs: Long,
    val extensionsUsedThisWeek: Int,
    val reducedDailyRestsUsed: Int,
    val splitDailyRestFirstPartTaken: Boolean,
    val nextDailyRestRequiredMs: Long,
    val latestDailyRestStartMs: Long?,

    // Art. 6(2), 6(3), 8(6) — weeks
    val weekStartMs: Long,
    val weeklyDrivingMs: Long,
    val previousWeekDrivingMs: Long,
    val lastWeeklyRestEndMs: Long?,
    val weeklyRestDueMs: Long?,

    val shifts: List<ShiftSummary>,
    val alerts: List<ComplianceAlert>,
    /** Earliest future moment when a new alert can appear if the current activity continues. */
    val nextAlertAtMs: Long?
) {
    val remainingContinuousDrivingMs: Long
        get() = (ComplianceRules.MAX_CONTINUOUS_DRIVING - continuousDrivingMs).coerceAtLeast(0)

    val remainingDailyDrivingMs: Long
        get() = (dailyDrivingLimitMs - dailyDrivingMs).coerceAtLeast(0)

    val biweeklyDrivingMs: Long
        get() = weeklyDrivingMs + previousWeekDrivingMs

    val remainingWeeklyDrivingMs: Long
        get() = minOf(
            ComplianceRules.MAX_WEEKLY_DRIVING - weeklyDrivingMs,
            ComplianceRules.MAX_BIWEEKLY_DRIVING - biweeklyDrivingMs
        ).coerceAtLeast(0)

    val shiftElapsedMs: Long
        get() = if (shiftStartMs == null || inDailyRest || inWeeklyRest) 0 else (nowMs - shiftStartMs).coerceAtLeast(0)

    val continuousDrivingProgress: Float
        get() = fraction(continuousDrivingMs, ComplianceRules.MAX_CONTINUOUS_DRIVING)

    val dailyDrivingProgress: Float
        get() = fraction(dailyDrivingMs, ComplianceRules.DAILY_DRIVING)

    val breakProgress: Float
        get() = if (currentBreakMs <= 0) 0f else fraction(currentBreakMs, currentBreakRequiredMs)

    val hasViolation: Boolean get() = alerts.any { it.severity == AlertSeverity.VIOLATION }
    val hasWarning: Boolean get() = alerts.any { it.severity == AlertSeverity.WARNING }

    /** The most important alert to show in a banner. */
    val topAlert: ComplianceAlert?
        get() = alerts.maxByOrNull { it.severity.ordinal }

    companion object {
        fun empty(nowMs: Long) = ComplianceStatus(
            nowMs = nowMs,
            hasData = false,
            trackingSinceMs = null,
            assumedRestMs = 0,
            currentActivity = null,
            currentActivitySinceMs = null,
            currentActivityAssumed = false,
            continuousDrivingMs = 0,
            continuousBlockStartMs = null,
            splitBreakFirstPartTaken = false,
            currentBreakMs = 0,
            currentBreakRequiredMs = ComplianceRules.BREAK_FULL,
            inDailyRest = false,
            inWeeklyRest = false,
            currentRestMs = 0,
            shiftStartMs = null,
            shiftStartKnown = false,
            dailyDrivingMs = 0,
            dailyDrivingLimitMs = ComplianceRules.DAILY_DRIVING_EXTENDED,
            extensionsUsedThisWeek = 0,
            reducedDailyRestsUsed = 0,
            splitDailyRestFirstPartTaken = false,
            nextDailyRestRequiredMs = ComplianceRules.DAILY_REST_REGULAR,
            latestDailyRestStartMs = null,
            weekStartMs = UtcWeeks.weekStart(nowMs),
            weeklyDrivingMs = 0,
            previousWeekDrivingMs = 0,
            lastWeeklyRestEndMs = null,
            weeklyRestDueMs = null,
            shifts = emptyList(),
            alerts = emptyList(),
            nextAlertAtMs = null
        )

        private fun fraction(value: Long, max: Long): Float =
            if (max <= 0) 0f else (value.toFloat() / max).coerceIn(0f, 1f)
    }
}
