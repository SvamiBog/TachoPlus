package com.example.domain.compliance

import com.example.data.model.ActivityPeriod
import com.example.data.model.DriverActivity
import com.example.domain.compliance.ComplianceRules.BREAK_FULL
import com.example.domain.compliance.ComplianceRules.BREAK_SPLIT_FIRST
import com.example.domain.compliance.ComplianceRules.BREAK_SPLIT_SECOND
import com.example.domain.compliance.ComplianceRules.DAILY_DRIVING
import com.example.domain.compliance.ComplianceRules.DAILY_DRIVING_EXTENDED
import com.example.domain.compliance.ComplianceRules.DAILY_REST_REDUCED
import com.example.domain.compliance.ComplianceRules.DAILY_REST_REGULAR
import com.example.domain.compliance.ComplianceRules.GAP_TOLERANCE
import com.example.domain.compliance.ComplianceRules.MAX_BIWEEKLY_DRIVING
import com.example.domain.compliance.ComplianceRules.MAX_CONTINUOUS_DRIVING
import com.example.domain.compliance.ComplianceRules.MAX_EXTENSIONS_PER_WEEK
import com.example.domain.compliance.ComplianceRules.MAX_REDUCED_DAILY_RESTS
import com.example.domain.compliance.ComplianceRules.MAX_WEEKLY_DRIVING
import com.example.domain.compliance.ComplianceRules.SHIFT_WARNING_LEAD
import com.example.domain.compliance.ComplianceRules.SHIFT_WINDOW
import com.example.domain.compliance.ComplianceRules.SPLIT_DAILY_REST_FIRST
import com.example.domain.compliance.ComplianceRules.WARNING_LEAD
import com.example.domain.compliance.ComplianceRules.WEEK
import com.example.domain.compliance.ComplianceRules.WEEKLY_LIMIT_WARNING_LEAD
import com.example.domain.compliance.ComplianceRules.WEEKLY_REST_INTERVAL
import com.example.domain.compliance.ComplianceRules.WEEKLY_REST_REDUCED
import com.example.domain.compliance.ComplianceRules.WEEKLY_REST_REGULAR
import com.example.domain.compliance.ComplianceRules.WEEKLY_REST_WARNING_LEAD
import com.example.data.model.formatSecondsToHhMm

/**
 * Evaluates driving and rest times against Regulation (EC) No 561/2006 from a list of activity periods.
 *
 * The calculation only knows what was recorded. Gaps in the record are treated as rest (the usual case is the
 * adapter being switched off with the ignition), and the result reports how much time was assumed.
 * The tachograph remains the legal reference.
 */
object ComplianceCalculator {

    private class Block(
        val activity: DriverActivity,
        val startMs: Long,
        var endMs: Long,
        var assumedMs: Long
    ) {
        val durationMs: Long get() = endMs - startMs
    }

    /**
     * Sorts periods, clips them to [nowMs], resolves overlaps (a later start wins) and fills gaps with assumed rest.
     * Every returned period has a non-null end.
     */
    fun normalize(periods: List<ActivityPeriod>, nowMs: Long): List<ActivityPeriod> {
        val sorted = periods.filter { it.startMs < nowMs }.sortedBy { it.startMs }
        val out = ArrayList<ActivityPeriod>(sorted.size + 4)
        for ((index, period) in sorted.withIndex()) {
            val nextStart = sorted.getOrNull(index + 1)?.startMs
            var end = minOf(period.endMs ?: nowMs, nowMs)
            if (nextStart != null && end > nextStart) end = nextStart
            val previousEnd = out.lastOrNull()?.endMs
            var start = period.startMs
            if (previousEnd != null) {
                if (start - previousEnd >= GAP_TOLERANCE) {
                    out += ActivityPeriod(DriverActivity.REST, previousEnd, start, period.source, assumed = true)
                } else if (start > previousEnd) {
                    start = previousEnd
                }
                if (start < previousEnd) start = previousEnd
            }
            if (end <= start) continue
            out += period.copy(startMs = start, endMs = end)
        }
        val lastEnd = out.lastOrNull()?.endMs
        if (lastEnd != null && nowMs - lastEnd >= GAP_TOLERANCE) {
            out += ActivityPeriod(DriverActivity.REST, lastEnd, nowMs, out.last().source, assumed = true)
        }
        return out
    }

    fun calculate(periods: List<ActivityPeriod>, nowMs: Long): ComplianceStatus {
        val normalized = normalize(periods, nowMs)
        if (normalized.isEmpty()) return ComplianceStatus.empty(nowMs)

        val blocks = mergeBlocks(normalized)
        val weekStart = UtcWeeks.weekStart(nowMs)
        val previousWeekStart = weekStart - WEEK

        var continuousDriving = 0L
        var splitBreakFirst = false
        var continuousAnchor = blocks.first().startMs

        var shiftStart = blocks.first().startMs
        var shiftStartKnown = false
        var dailyDriving = 0L
        var shiftWork = 0L
        var shiftAvailable = 0L
        var shiftRest = 0L
        var splitRestFirst = false

        var reducedDailyRests = 0
        var lastWeeklyRestEnd: Long? = null

        var weeklyDriving = 0L
        var previousWeekDriving = 0L

        var currentBreak = 0L
        var currentBreakRequired = BREAK_FULL
        var splitBreakFirstBeforeCurrent = false
        var currentRest = 0L
        var inDailyRest = false
        var inWeeklyRest = false

        val shifts = ArrayList<ShiftSummary>()

        fun closeShift(endMs: Long, restType: RestType, restMs: Long) {
            if (endMs > shiftStart && (dailyDriving + shiftWork + shiftAvailable + shiftRest) > 0) {
                shifts += ShiftSummary(
                    startMs = shiftStart,
                    endMs = endMs,
                    ongoing = false,
                    startKnown = shiftStartKnown,
                    drivingMs = dailyDriving,
                    workMs = shiftWork,
                    availableMs = shiftAvailable,
                    restMs = shiftRest,
                    endingRest = restType,
                    endingRestMs = restMs
                )
            }
            dailyDriving = 0
            shiftWork = 0
            shiftAvailable = 0
            shiftRest = 0
            splitRestFirst = false
            continuousDriving = 0
            splitBreakFirst = false
        }

        for ((index, block) in blocks.withIndex()) {
            val duration = block.durationMs
            val ongoing = index == blocks.lastIndex && block.endMs >= nowMs
            when (block.activity) {
                DriverActivity.DRIVING -> {
                    continuousDriving += duration
                    dailyDriving += duration
                    weeklyDriving += overlap(block.startMs, block.endMs, weekStart, nowMs)
                    previousWeekDriving += overlap(block.startMs, block.endMs, previousWeekStart, weekStart)
                }
                DriverActivity.WORK -> shiftWork += duration
                DriverActivity.AVAILABLE -> shiftAvailable += duration
                DriverActivity.REST -> {
                    if (ongoing) {
                        splitBreakFirstBeforeCurrent = splitBreakFirst
                        currentBreakRequired = if (splitBreakFirst) BREAK_SPLIT_SECOND else BREAK_FULL
                        currentBreak = duration
                        currentRest = duration
                    }
                    when {
                        duration >= WEEKLY_REST_REDUCED -> {
                            val type = if (duration >= WEEKLY_REST_REGULAR) RestType.WEEKLY_REGULAR else RestType.WEEKLY_REDUCED
                            closeShift(block.startMs, type, duration)
                            reducedDailyRests = 0
                            continuousAnchor = block.endMs
                            if (ongoing) inWeeklyRest = true else lastWeeklyRestEnd = block.endMs
                            shiftStart = block.endMs
                            shiftStartKnown = true
                        }
                        duration >= DAILY_REST_REDUCED -> {
                            val type = when {
                                duration >= DAILY_REST_REGULAR -> RestType.DAILY_REGULAR
                                splitRestFirst -> RestType.DAILY_SPLIT
                                else -> RestType.DAILY_REDUCED
                            }
                            // A rest still in progress may yet become a regular one, so it is not counted as reduced.
                            if (!ongoing && type == RestType.DAILY_REDUCED) reducedDailyRests++
                            closeShift(block.startMs, type, duration)
                            continuousAnchor = block.endMs
                            if (ongoing) inDailyRest = true
                            shiftStart = block.endMs
                            shiftStartKnown = true
                        }
                        else -> {
                            shiftRest += duration
                            if (duration >= SPLIT_DAILY_REST_FIRST) splitRestFirst = true
                            if (duration >= BREAK_FULL || (splitBreakFirst && duration >= BREAK_SPLIT_SECOND)) {
                                continuousDriving = 0
                                splitBreakFirst = false
                                continuousAnchor = block.endMs
                            } else if (duration >= BREAK_SPLIT_FIRST) {
                                splitBreakFirst = true
                            }
                        }
                    }
                }
            }
        }

        val last = blocks.last()
        val currentActivity = last.activity
        val currentActivityAssumed = last.assumedMs >= last.durationMs
        val resting = currentActivity == DriverActivity.REST
        val inLongRest = inDailyRest || inWeeklyRest

        if (!inLongRest) {
            shifts += ShiftSummary(
                startMs = shiftStart,
                endMs = nowMs,
                ongoing = true,
                startKnown = shiftStartKnown,
                drivingMs = dailyDriving,
                workMs = shiftWork,
                availableMs = shiftAvailable,
                restMs = shiftRest,
                endingRest = null,
                endingRestMs = 0
            )
        }

        val extensionsUsed = shifts.count { !it.ongoing && it.startMs >= weekStart && it.extendedDriving }
        val dailyLimit = if (extensionsUsed < MAX_EXTENSIONS_PER_WEEK) DAILY_DRIVING_EXTENDED else DAILY_DRIVING

        val nextRestRequired = when {
            splitRestFirst -> DAILY_REST_REDUCED // second part of a 3 h + 9 h split rest
            reducedDailyRests < MAX_REDUCED_DAILY_RESTS -> DAILY_REST_REDUCED
            else -> DAILY_REST_REGULAR
        }
        val latestRestStart = if (inLongRest) null else shiftStart + SHIFT_WINDOW - nextRestRequired
        val weeklyRestDue = if (inWeeklyRest) null else lastWeeklyRestEnd?.plus(WEEKLY_REST_INTERVAL)
        val restStartedAt = if (resting) last.startMs else nowMs

        val alerts = ArrayList<ComplianceAlert>()

        // Art. 7: continuous driving
        val remainingContinuous = MAX_CONTINUOUS_DRIVING - continuousDriving
        if (continuousDriving > MAX_CONTINUOUS_DRIVING) {
            alerts += ComplianceAlert(
                key = "cont-viol:$continuousAnchor",
                kind = AlertKind.CONTINUOUS_DRIVING,
                severity = AlertSeverity.VIOLATION,
                title = "Превышено непрерывное вождение",
                message = "Вождение без перерыва ${hm(continuousDriving)} при лимите 04:30. Нужен перерыв 45 мин (ст. 7)."
            )
        } else if (!resting && continuousDriving > 0 && remainingContinuous <= WARNING_LEAD) {
            alerts += ComplianceAlert(
                key = "cont-warn:$continuousAnchor",
                kind = AlertKind.CONTINUOUS_DRIVING,
                severity = AlertSeverity.WARNING,
                title = "Скоро обязательный перерыв",
                message = "До предела 04:30 осталось ${hm(remainingContinuous)}. " +
                    if (splitBreakFirst) "Первая часть перерыва уже есть — нужно ещё 30 мин." else "Нужен перерыв 45 мин (или 15 + 30)."
            )
        }

        if (!inLongRest) {
            // Art. 6(1): daily driving
            if (dailyDriving > dailyLimit) {
                alerts += ComplianceAlert(
                    key = "daily-viol:$shiftStart",
                    kind = AlertKind.DAILY_DRIVING,
                    severity = AlertSeverity.VIOLATION,
                    title = "Превышено суточное вождение",
                    message = "Суточное вождение ${hm(dailyDriving)} при лимите ${hm(dailyLimit)}."
                )
            } else if (!resting && dailyLimit - dailyDriving <= WARNING_LEAD) {
                alerts += ComplianceAlert(
                    key = "daily-warn:$shiftStart",
                    kind = AlertKind.DAILY_DRIVING,
                    severity = AlertSeverity.WARNING,
                    title = "Суточное вождение на исходе",
                    message = "До предела ${hm(dailyLimit)} осталось ${hm(dailyLimit - dailyDriving)}."
                )
            } else if (dailyDriving > DAILY_DRIVING) {
                alerts += ComplianceAlert(
                    key = "daily-ext:$shiftStart",
                    kind = AlertKind.DAILY_DRIVING,
                    severity = AlertSeverity.INFO,
                    title = "Используется продление до 10 ч",
                    message = "Это продление №${extensionsUsed + 1} из $MAX_EXTENSIONS_PER_WEEK допустимых на неделе."
                )
            } else if (!resting && DAILY_DRIVING - dailyDriving <= WARNING_LEAD) {
                alerts += ComplianceAlert(
                    key = "daily-9h:$shiftStart",
                    kind = AlertKind.DAILY_DRIVING,
                    severity = AlertSeverity.INFO,
                    title = "Скоро 9 ч вождения",
                    message = "После 9 ч начнётся продление до 10 ч (доступно ${MAX_EXTENSIONS_PER_WEEK - extensionsUsed} на этой неделе)."
                )
            }

            // Art. 8(2): daily rest must be taken within 24 h
            if (shiftStartKnown && latestRestStart != null) {
                if (restStartedAt > latestRestStart) {
                    alerts += ComplianceAlert(
                        key = "shift-viol:$shiftStart",
                        kind = AlertKind.DAILY_REST,
                        severity = AlertSeverity.VIOLATION,
                        title = "Суточный отдых не начат вовремя",
                        message = "Отдых ${hm(nextRestRequired)} должен был начаться не позднее чем через " +
                            "${hm(SHIFT_WINDOW - nextRestRequired)} после начала смены."
                    )
                } else if (!resting && latestRestStart - nowMs <= SHIFT_WARNING_LEAD) {
                    alerts += ComplianceAlert(
                        key = "shift-warn:$shiftStart",
                        kind = AlertKind.DAILY_REST,
                        severity = AlertSeverity.WARNING,
                        title = "Пора начинать суточный отдых",
                        message = "Отдых ${hm(nextRestRequired)} нужно начать в течение ${hm(latestRestStart - nowMs)}."
                    )
                }
            }
        }

        // Art. 6(2), 6(3): weekly and two-weekly driving
        if (weeklyDriving > MAX_WEEKLY_DRIVING) {
            alerts += ComplianceAlert(
                key = "week-viol:$weekStart",
                kind = AlertKind.WEEKLY_DRIVING,
                severity = AlertSeverity.VIOLATION,
                title = "Превышено недельное вождение",
                message = "За неделю ${hm(weeklyDriving)} при лимите 56:00."
            )
        } else if (weeklyDriving > 0 && MAX_WEEKLY_DRIVING - weeklyDriving <= WEEKLY_LIMIT_WARNING_LEAD) {
            alerts += ComplianceAlert(
                key = "week-warn:$weekStart",
                kind = AlertKind.WEEKLY_DRIVING,
                severity = AlertSeverity.WARNING,
                title = "Недельное вождение на исходе",
                message = "До лимита 56:00 осталось ${hm(MAX_WEEKLY_DRIVING - weeklyDriving)}."
            )
        }
        val biweekly = weeklyDriving + previousWeekDriving
        if (biweekly > MAX_BIWEEKLY_DRIVING) {
            alerts += ComplianceAlert(
                key = "biweek-viol:$weekStart",
                kind = AlertKind.BIWEEKLY_DRIVING,
                severity = AlertSeverity.VIOLATION,
                title = "Превышено вождение за 2 недели",
                message = "За текущую и прошлую неделю ${hm(biweekly)} при лимите 90:00."
            )
        } else if (weeklyDriving > 0 && MAX_BIWEEKLY_DRIVING - biweekly <= WEEKLY_LIMIT_WARNING_LEAD) {
            alerts += ComplianceAlert(
                key = "biweek-warn:$weekStart",
                kind = AlertKind.BIWEEKLY_DRIVING,
                severity = AlertSeverity.WARNING,
                title = "Вождение за 2 недели на исходе",
                message = "До лимита 90:00 осталось ${hm(MAX_BIWEEKLY_DRIVING - biweekly)}."
            )
        }

        // Art. 8(6): weekly rest
        if (weeklyRestDue != null && lastWeeklyRestEnd != null) {
            if (restStartedAt > weeklyRestDue) {
                alerts += ComplianceAlert(
                    key = "wrest-viol:$lastWeeklyRestEnd",
                    kind = AlertKind.WEEKLY_REST,
                    severity = AlertSeverity.VIOLATION,
                    title = "Просрочен еженедельный отдых",
                    message = "Еженедельный отдых должен начаться не позднее 6×24 ч после окончания предыдущего."
                )
            } else if (!resting && weeklyRestDue - nowMs <= WEEKLY_REST_WARNING_LEAD) {
                alerts += ComplianceAlert(
                    key = "wrest-warn:$lastWeeklyRestEnd",
                    kind = AlertKind.WEEKLY_REST,
                    severity = AlertSeverity.WARNING,
                    title = "Скоро еженедельный отдых",
                    message = "Еженедельный отдых нужно начать в течение ${hm(weeklyRestDue - nowMs)}."
                )
            }
        }

        val nextAlertAt = nextAlertTime(
            nowMs = nowMs,
            currentActivity = currentActivity,
            continuousDriving = continuousDriving,
            dailyDriving = dailyDriving,
            dailyLimit = dailyLimit,
            weeklyDriving = weeklyDriving,
            biweeklyDriving = biweekly,
            latestRestStart = if (shiftStartKnown && !resting) latestRestStart else null,
            weeklyRestDue = if (!resting) weeklyRestDue else null,
            inLongRest = inLongRest
        )

        val assumedRest = normalized
            .filter { it.assumed }
            .sumOf { overlap(it.startMs, it.endMs ?: nowMs, previousWeekStart, nowMs) }

        return ComplianceStatus(
            nowMs = nowMs,
            hasData = true,
            trackingSinceMs = normalized.first().startMs,
            assumedRestMs = assumedRest,
            currentActivity = currentActivity,
            currentActivitySinceMs = last.startMs,
            currentActivityAssumed = currentActivityAssumed,
            continuousDrivingMs = continuousDriving,
            continuousBlockStartMs = continuousAnchor,
            splitBreakFirstPartTaken = if (resting) splitBreakFirstBeforeCurrent else splitBreakFirst,
            currentBreakMs = currentBreak,
            currentBreakRequiredMs = currentBreakRequired,
            inDailyRest = inDailyRest,
            inWeeklyRest = inWeeklyRest,
            currentRestMs = currentRest,
            shiftStartMs = if (inLongRest) null else shiftStart,
            shiftStartKnown = shiftStartKnown,
            dailyDrivingMs = if (inLongRest) 0 else dailyDriving,
            dailyDrivingLimitMs = dailyLimit,
            extensionsUsedThisWeek = extensionsUsed,
            reducedDailyRestsUsed = reducedDailyRests,
            splitDailyRestFirstPartTaken = splitRestFirst,
            nextDailyRestRequiredMs = nextRestRequired,
            latestDailyRestStartMs = if (shiftStartKnown) latestRestStart else null,
            weekStartMs = weekStart,
            weeklyDrivingMs = weeklyDriving,
            previousWeekDrivingMs = previousWeekDriving,
            lastWeeklyRestEndMs = lastWeeklyRestEnd,
            weeklyRestDueMs = weeklyRestDue,
            shifts = shifts,
            alerts = alerts,
            nextAlertAtMs = nextAlertAt
        )
    }

    private fun nextAlertTime(
        nowMs: Long,
        currentActivity: DriverActivity,
        continuousDriving: Long,
        dailyDriving: Long,
        dailyLimit: Long,
        weeklyDriving: Long,
        biweeklyDriving: Long,
        latestRestStart: Long?,
        weeklyRestDue: Long?,
        inLongRest: Boolean
    ): Long? {
        val candidates = ArrayList<Long>()
        if (currentActivity == DriverActivity.DRIVING) {
            candidates += nowMs + (MAX_CONTINUOUS_DRIVING - WARNING_LEAD - continuousDriving)
            candidates += nowMs + (MAX_CONTINUOUS_DRIVING - continuousDriving) + 1_000
            if (!inLongRest) {
                candidates += nowMs + (DAILY_DRIVING - WARNING_LEAD - dailyDriving)
                candidates += nowMs + (dailyLimit - WARNING_LEAD - dailyDriving)
                candidates += nowMs + (dailyLimit - dailyDriving) + 1_000
            }
            candidates += nowMs + (MAX_WEEKLY_DRIVING - WEEKLY_LIMIT_WARNING_LEAD - weeklyDriving)
            candidates += nowMs + (MAX_BIWEEKLY_DRIVING - WEEKLY_LIMIT_WARNING_LEAD - biweeklyDriving)
        }
        if (latestRestStart != null) {
            candidates += latestRestStart - SHIFT_WARNING_LEAD
            candidates += latestRestStart + 1_000
        }
        if (weeklyRestDue != null) {
            candidates += weeklyRestDue - WEEKLY_REST_WARNING_LEAD
            candidates += weeklyRestDue + 1_000
        }
        return candidates.filter { it > nowMs }.minOrNull()
    }

    private fun mergeBlocks(periods: List<ActivityPeriod>): List<Block> {
        val blocks = ArrayList<Block>()
        for (period in periods) {
            val end = period.endMs ?: continue
            val duration = end - period.startMs
            val assumed = if (period.assumed) duration else 0L
            val last = blocks.lastOrNull()
            if (last != null && last.activity == period.activity && last.endMs == period.startMs) {
                last.endMs = end
                last.assumedMs += assumed
            } else {
                blocks += Block(period.activity, period.startMs, end, assumed)
            }
        }
        return blocks
    }

    private fun overlap(start: Long, end: Long, windowStart: Long, windowEnd: Long): Long =
        (minOf(end, windowEnd) - maxOf(start, windowStart)).coerceAtLeast(0)

    private fun hm(ms: Long): String = formatSecondsToHhMm(ms / 1000)
}
