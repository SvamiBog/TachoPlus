package com.example.data.model

import java.util.Locale

/**
 * Driver activities recorded by an EU tachograph (Regulation (EU) No 165/2014, Annex 1C).
 */
enum class DriverActivity(val titleRu: String, val titleEn: String, val code: String) {
    DRIVING("Управление", "Driving", "D"),
    WORK("Работа", "Other Work", "W"),
    AVAILABLE("Готовность", "Availability", "A"),
    REST("Отдых", "Break / Rest", "R")
}

/** Where an activity record came from. Only TACHOGRAPH data reflects what the tachograph itself recorded. */
enum class ActivitySource(val titleRu: String) {
    MANUAL("Вручную"),
    TACHOGRAPH("Тахограф (FMS/J1939)"),
    VEHICLE_MOTION("По движению ТС"),
    DEMO("Демо")
}

/** One continuous activity interval. [endMs] == null means the activity is still going on. */
data class ActivityPeriod(
    val activity: DriverActivity,
    val startMs: Long,
    val endMs: Long?,
    val source: ActivitySource = ActivitySource.MANUAL,
    /** True when the period was not recorded but inferred (a gap in the data treated as rest). */
    val assumed: Boolean = false
)

enum class EventSeverity {
    INFO, WARNING, CRITICAL
}

data class TachographEvent(
    val id: Long,
    val timestamp: Long,
    val code: String,
    val title: String,
    val description: String,
    val severity: EventSeverity
)

/** A coloured block on the 24-hour timeline. */
data class ActivityTimelineSegment(
    val activity: DriverActivity,
    val startMinuteOfDay: Int, // 0..1439
    val durationMinutes: Int,
    val assumed: Boolean = false
)

fun formatSecondsToHhMm(totalSeconds: Long): String {
    val sign = if (totalSeconds < 0) "-" else ""
    val abs = kotlin.math.abs(totalSeconds)
    val hours = abs / 3600
    val minutes = (abs % 3600) / 60
    return sign + String.format(Locale.US, "%02d:%02d", hours, minutes)
}

fun formatSecondsToHhMmSs(totalSeconds: Long): String {
    val sign = if (totalSeconds < 0) "-" else ""
    val abs = kotlin.math.abs(totalSeconds)
    val hours = abs / 3600
    val minutes = (abs % 3600) / 60
    val seconds = abs % 60
    return sign + String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}
