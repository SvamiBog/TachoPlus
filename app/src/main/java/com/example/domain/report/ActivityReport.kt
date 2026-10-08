package com.example.domain.report

import com.example.data.model.ActivityPeriod
import com.example.data.model.ActivityTimelineSegment
import com.example.data.model.formatSecondsToHhMm
import com.example.domain.compliance.AlertSeverity
import com.example.domain.compliance.ComplianceCalculator
import com.example.domain.compliance.ComplianceRules
import com.example.domain.compliance.ComplianceStatus
import com.example.domain.protocol.VehicleLiveState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object TimelineBuilder {
    /** Coloured blocks of one day [dayStartMs, dayStartMs + 24 h) from the recorded periods. */
    fun segmentsForDay(periods: List<ActivityPeriod>, dayStartMs: Long, nowMs: Long): List<ActivityTimelineSegment> {
        val dayEnd = dayStartMs + ComplianceRules.DAY
        // Only fill gaps inside the recorded range; the time after "now" stays empty.
        return ComplianceCalculator.normalize(periods, nowMs).mapNotNull { period ->
            val start = maxOf(period.startMs, dayStartMs)
            val end = minOf(period.endMs ?: nowMs, dayEnd)
            if (end <= start) return@mapNotNull null
            ActivityTimelineSegment(
                activity = period.activity,
                startMinuteOfDay = ((start - dayStartMs) / ComplianceRules.MINUTE).toInt(),
                durationMinutes = ((end - start + ComplianceRules.MINUTE - 1) / ComplianceRules.MINUTE).toInt(),
                assumed = period.assumed
            )
        }
    }
}

/**
 * Plain-text report built from the app's own activity log. It is explicitly not a tachograph DDD file.
 */
object ActivityReportBuilder {
    fun build(
        periods: List<ActivityPeriod>,
        status: ComplianceStatus,
        vehicle: VehicleLiveState,
        nowMs: Long,
        zone: TimeZone = TimeZone.getDefault(),
        days: Int = 28
    ): String {
        val dateTime = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).apply { timeZone = zone }
        val time = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = zone }
        val day = SimpleDateFormat("EEE dd.MM.yyyy", Locale.forLanguageTag("ru")).apply { timeZone = zone }
        val utc = SimpleDateFormat("dd.MM.yyyy HH:mm 'UTC'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fun hm(ms: Long) = formatSecondsToHhMm(ms / 1000)

        val from = nowMs - days * ComplianceRules.DAY
        val normalized = ComplianceCalculator.normalize(periods, nowMs).filter { (it.endMs ?: nowMs) > from }

        return buildString {
            appendLine("ОТЧЁТ ПО ЖУРНАЛУ АКТИВНОСТИ — Тахограф Про")
            appendLine("Это не файл DDD и не документ для контроля. Юридическую силу имеют только данные тахографа.")
            appendLine("Сформирован: ${dateTime.format(Date(nowMs))} (${utc.format(Date(nowMs))})")
            vehicle.driver1Name?.let { appendLine("Водитель: $it") }
            vehicle.driver1Id?.let { appendLine("Карта водителя 1: $it") }
            vehicle.driver2Id?.let { appendLine("Карта водителя 2: $it") }
            vehicle.vin?.let { appendLine("VIN: $it") }
            vehicle.registration?.let { appendLine("Рег. номер: $it") }
            vehicle.odometerKm?.let { appendLine("Одометр: ${"%.1f".format(Locale.US, it)} км") }
            if (!status.hasData) {
                appendLine()
                appendLine("Записей активности нет.")
                return@buildString
            }
            status.trackingSinceMs?.let { appendLine("Записи ведутся с: ${dateTime.format(Date(it))}") }
            if (status.assumedRestMs > 0) {
                appendLine("Без данных за 2 недели (считается отдыхом): ${hm(status.assumedRestMs)}")
            }

            appendLine()
            appendLine("ТЕКУЩЕЕ СОСТОЯНИЕ (Регламент (ЕС) № 561/2006)")
            appendLine("Непрерывное вождение: ${hm(status.continuousDrivingMs)} из 04:30")
            if (status.inDailyRest || status.inWeeklyRest) {
                appendLine("Сейчас: ${if (status.inWeeklyRest) "еженедельный" else "ежедневный"} отдых ${hm(status.currentRestMs)}")
            } else {
                appendLine("Суточное вождение: ${hm(status.dailyDrivingMs)} из ${hm(status.dailyDrivingLimitMs)}")
                status.latestDailyRestStartMs?.let {
                    appendLine("Суточный отдых ${hm(status.nextDailyRestRequiredMs)} начать до: ${dateTime.format(Date(it))}")
                }
            }
            appendLine("Продлений до 10 ч на неделе: ${status.extensionsUsedThisWeek} из ${ComplianceRules.MAX_EXTENSIONS_PER_WEEK}")
            appendLine("Сокращённых суточных отдыхов: ${status.reducedDailyRestsUsed} из ${ComplianceRules.MAX_REDUCED_DAILY_RESTS}")
            appendLine("Вождение за неделю (UTC): ${hm(status.weeklyDrivingMs)} из 56:00")
            appendLine("Вождение за 2 недели: ${hm(status.biweeklyDrivingMs)} из 90:00")
            status.weeklyRestDueMs?.let { appendLine("Еженедельный отдых начать до: ${dateTime.format(Date(it))}") }

            val alerts = status.alerts.filter { it.severity != AlertSeverity.INFO }
            appendLine()
            appendLine("НАРУШЕНИЯ И ПРЕДУПРЕЖДЕНИЯ")
            if (alerts.isEmpty()) appendLine("Нет") else alerts.forEach {
                val mark = if (it.severity == AlertSeverity.VIOLATION) "НАРУШЕНИЕ" else "Внимание"
                appendLine("• $mark: ${it.title}. ${it.message}")
            }

            appendLine()
            appendLine("СМЕНЫ ЗА $days ДНЕЙ")
            status.shifts.filter { it.endMs > from }.asReversed().forEach { shift ->
                val start = if (shift.startKnown) dateTime.format(Date(shift.startMs)) else "до ${dateTime.format(Date(shift.startMs))}"
                val end = if (shift.ongoing) "сейчас" else time.format(Date(shift.endMs))
                appendLine("$start – $end")
                appendLine(
                    "  Вождение ${hm(shift.drivingMs)}, работа ${hm(shift.workMs)}, " +
                        "готовность ${hm(shift.availableMs)}, перерывы ${hm(shift.restMs)}" +
                        if (shift.extendedDriving) " (продление)" else ""
                )
                shift.endingRest?.let { appendLine("  Далее: ${it.titleRu} ${hm(shift.endingRestMs)}") }
            }

            appendLine()
            appendLine("ЖУРНАЛ ПО ДНЯМ")
            var currentDay: String? = null
            normalized.forEach { period ->
                val start = maxOf(period.startMs, from)
                val end = period.endMs ?: nowMs
                val dayLabel = day.format(Date(start))
                if (dayLabel != currentDay) {
                    currentDay = dayLabel
                    appendLine(dayLabel)
                }
                val source = if (period.assumed) "нет данных" else period.source.titleRu
                appendLine("  ${time.format(Date(start))}–${time.format(Date(end))}  ${period.activity.titleRu.padEnd(10)} ${hm(end - start)}  [$source]")
            }
        }
    }
}
