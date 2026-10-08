package com.example.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.data.link.LinkState
import com.example.data.link.TachoLinkManager
import com.example.data.model.ActivityPeriod
import com.example.data.model.EventSeverity
import com.example.data.repo.ActivityGroup
import com.example.data.repo.ActivityRepository
import com.example.data.repo.EventRepository
import com.example.data.repo.SettingsStore
import com.example.domain.compliance.AlertSeverity
import com.example.domain.compliance.ComplianceCalculator
import com.example.domain.compliance.ComplianceRules
import com.example.domain.compliance.ComplianceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Recalculates compliance every second from the activity log, notifies new warnings once and schedules an
 * alarm for the next threshold so warnings still arrive when the process is not running.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComplianceMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val activities: ActivityRepository,
    private val events: EventRepository,
    private val settings: SettingsStore,
    private val notifier: AlertNotifier,
    link: TachoLinkManager
) {
    private val group: StateFlow<ActivityGroup> = link.status
        .map { if (it.state == LinkState.DEMO) ActivityGroup.DEMO else ActivityGroup.REAL }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, ActivityGroup.REAL)

    val periods: StateFlow<List<ActivityPeriod>> = group
        .flatMapLatest { activities.observe(it, System.currentTimeMillis() - HISTORY_MS) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1_000)
        }
    }

    val status: StateFlow<ComplianceStatus> = combine(periods, ticker) { list, now ->
        ComplianceCalculator.calculate(list, now)
    }.stateIn(scope, SharingStarted.Eagerly, ComplianceStatus.empty(System.currentTimeMillis()))

    private val handled = HashSet<String>()
    private val lock = Mutex()
    private var scheduledAlarm: Long? = null

    init {
        scope.launch {
            status.collect { handle(it, demo = group.value == ActivityGroup.DEMO) }
        }
    }

    /** Called from the alarm/boot receiver when the app may not be running. */
    suspend fun checkInBackground() {
        val now = System.currentTimeMillis()
        val list = activities.load(ActivityGroup.REAL, now - HISTORY_MS)
        handle(ComplianceCalculator.calculate(list, now), demo = false)
    }

    private suspend fun handle(status: ComplianceStatus, demo: Boolean) = lock.withLock {
        for (alert in status.alerts) {
            if (alert.severity == AlertSeverity.INFO) continue
            val key = (if (demo) "demo:" else "") + alert.key
            if (!handled.add(key)) continue
            if (!settings.markAlertNotified(key)) continue
            notifier.notifyAlert(alert, demo)
            if (!demo) {
                events.log(
                    code = alert.kind.name,
                    title = alert.title,
                    description = alert.message,
                    severity = if (alert.severity == AlertSeverity.VIOLATION) EventSeverity.CRITICAL else EventSeverity.WARNING
                )
            }
        }
        if (!demo) scheduleAlarm(status.nextAlertAtMs)
    }

    private fun scheduleAlarm(atMs: Long?) {
        val previous = scheduledAlarm
        if (atMs == previous || (atMs != null && previous != null && kotlin.math.abs(atMs - previous) < 30_000)) return
        scheduledAlarm = atMs
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ComplianceAlarmReceiver::class.java).setAction(ComplianceAlarmReceiver.ACTION_CHECK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (atMs == null) {
            alarmManager.cancel(intent)
        } else {
            // Inexact on purpose: no exact-alarm permission is needed, Doze may delay it by a few minutes.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, intent)
        }
    }

    companion object {
        /** Enough for the two-week limit plus the previous weekly rest. */
        const val HISTORY_MS = 35 * ComplianceRules.DAY
    }
}
