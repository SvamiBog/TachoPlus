package com.example.data.model

import java.util.Locale

/**
 * Standard Tachograph driver activities according to European Regulation (EC) No 561/2006 & Regulation (EU) No 165/2014
 */
enum class DriverActivity(val titleRu: String, val titleEn: String, val code: String) {
    DRIVING("Управление", "Driving", "D"),
    WORK("Работа", "Other Work", "W"),
    AVAILABLE("Готовность", "Availability", "A"),
    REST("Отдых", "Break / Rest", "R")
}

/**
 * EU Driver Smart Card information inserted into Slot 1 or Slot 2 according to EU Annex 1C / Smart Tacho 2
 */
data class DriverCardInfo(
    val slotNumber: Int,
    val isInserted: Boolean,
    val driverName: String = "SCHMIDT HANS-PETER",
    val cardNumber: String = "DED00000849201 0",
    val issuingCountry: String = "DE (Германия)",
    val issuingAuthority: String = "KBA Kraftfahrt-Bundesamt",
    val expiryDate: String = "15.10.2029",
    val insertionTime: Long = System.currentTimeMillis() - 4 * 3600 * 1000,
    val totalDrivingTimeCard: Long = 18450, // seconds
    val totalWorkTimeCard: Long = 9200
)

/**
 * Realtime vehicle and EU Smart Tachograph telemetry
 */
data class TachographLiveTelemetry(
    val speedKmh: Int = 0,
    val speedLimitKmh: Int = 90,
    val engineRpm: Int = 0,
    val totalOdometerKm: Long = 284190,
    val tripOdometerKm: Double = 168.4,
    val utcTime: String = "",
    val localTime: String = "",
    val motionSensorStatus: String = "KITAS 4.0 / 4.1 (Annex 1C EU)",
    val gnssSignal: Boolean = true,
    val galileoOsnmaLocked: Boolean = true,
    val dsrcStatus: String = "DSRC Активен (RSRC 5.8 GHz)",
    val currentEuCountry: String = "DE (Германия)",
    val lastBorderCrossing: String = "PL -> DE (A12 / Świecko)",
    val ignitionOn: Boolean = true
)

/**
 * European Union Regulation (EC) No 561/2006 & EU Mobility Package I Compliance Metrics
 */
data class WorkRestCompliance(
    // Continuous driving: Max 4h 30m = 16200 sec
    val continuousDrivingSeconds: Long = 7200,
    val maxContinuousDrivingSeconds: Long = 16200,
    
    // Break accumulated towards continuous driving reset
    // Under Reg (EC) 561/2006, split break must be: 1st >= 15 min, 2nd >= 30 min (total 45 min)
    val accumulatedBreakSeconds: Long = 900,
    val requiredBreakSeconds: Long = 2700,
    val breakSplit1Done: Boolean = true, // 15 min break done
    val isSplitBreakMode: Boolean = true,
    
    // Daily driving: Max 9h = 32400 sec, extendable to 10h twice a week = 36000 sec
    val dailyDrivingSeconds: Long = 19400,
    val maxDailyDrivingSeconds: Long = 32400,
    val extendedDailyDaysUsedThisWeek: Int = 1, // max 2
    
    // Shift duration in a 24-hour window (or 30h for multi-manning crew)
    val shiftStartTimestamp: Long = System.currentTimeMillis() - 6 * 3600 * 1000,
    val shiftDurationSeconds: Long = 21600,
    val maxShiftDurationSeconds: Long = 46800, // 13h (or 15h with reduced rest)
    
    // Daily rest requirement: Regular 11h = 39600s, or reduced 9h = 32400s (max 3 times between weekly rests)
    val requiredDailyRestSeconds: Long = 39600,
    val reducedDailyRestUsedThisWeek: Int = 1, // max 3
    
    // Weekly & Bi-weekly driving limits: 56h and 90h
    val weeklyDrivingSeconds: Long = 104400, // 29h
    val maxWeeklyDrivingSeconds: Long = 201600, // 56h
    val biweeklyDrivingSeconds: Long = 237600, // 66h
    val maxBiweeklyDrivingSeconds: Long = 324000, // 90h
    
    // EU Mobility Package I rules:
    // Regular 45h weekly rest cannot be spent in the vehicle cabin!
    val cabinRestWarning: Boolean = false,
    val ferryTrainInterruptionUsed: Boolean = false, // Max 2 interruptions, <= 1h total
    
    // Violations and warnings
    val hasWarning: Boolean = false,
    val isViolation: Boolean = false,
    val warningMessage: String = ""
) {
    val remainingContinuousSeconds: Long
        get() = (maxContinuousDrivingSeconds - continuousDrivingSeconds).coerceAtLeast(0)

    val remainingDailyDrivingSeconds: Long
        get() = (maxDailyDrivingSeconds - dailyDrivingSeconds).coerceAtLeast(0)

    val remainingWeeklyDrivingSeconds: Long
        get() = (maxWeeklyDrivingSeconds - weeklyDrivingSeconds).coerceAtLeast(0)

    val continuousDrivingProgress: Float
        get() = (continuousDrivingSeconds.toFloat() / maxContinuousDrivingSeconds).coerceIn(0f, 1f)

    val dailyDrivingProgress: Float
        get() = (dailyDrivingSeconds.toFloat() / maxDailyDrivingSeconds).coerceIn(0f, 1f)

    val breakProgress: Float
        get() = (accumulatedBreakSeconds.toFloat() / requiredBreakSeconds).coerceIn(0f, 1f)
}

/**
 * EU Smart Tachograph Hardware specifications (Gen2 V2 - EU Regulation 2021/1228)
 */
data class TachographDeviceInfo(
    val model: String = "Continental VDO DTCO 4.1 (Smart Tacho 2)",
    val serialNumber: String = "VDO-EU-89240194",
    val vin: String = "WMA06XZZ8LP092481",
    val regNumber: String = "B-MW 5420 (DE)",
    val softwareVersion: String = "v4.1.0 (Annex 1C Gen2 V2 - EU 2021/1228)",
    val kFactor: Int = 8012, // imp/km
    val wFactor: Int = 8014, // imp/km
    val tyreSize: String = "315/70 R22.5",
    val calibrationDate: String = "14.05.2025",
    val nextInspectionDate: String = "14.05.2027",
    val workshopName: String = "MAN Truck & Bus Service Berlin (§ 57b StVZO)",
    val workshopCardNumber: String = "DEW00000109248",
    val dsrcRemoteCompliance: String = "OK (DSRC RSRC 5.8 GHz активен)",
    val gnssGalileoStatus: String = "Galileo OSNMA Зафиксирован",
    val euBorderCrossingsRegistered: Int = 18
)

enum class EventSeverity {
    INFO, WARNING, CRITICAL
}

/**
 * EU Tachograph Events and Faults according to Annex 1B/1C
 */
data class TachographEvent(
    val id: String,
    val timestamp: Long,
    val code: String,
    val title: String,
    val description: String,
    val severity: EventSeverity
)

/**
 * Historical activity segment
 */
data class ActivityTimelineSegment(
    val id: Long,
    val activity: DriverActivity,
    val startMinuteOfDay: Int, // 0..1439
    val durationMinutes: Int,
    val dateString: String
)

/**
 * Helper formatters for seconds to HH:mm or HH:mm:ss
 */
fun formatSecondsToHhMm(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return String.format(Locale.getDefault(), "%02d:%02d", hours, minutes)
}

fun formatSecondsToHhMmSs(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
}
