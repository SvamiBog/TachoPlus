package com.example.data.repo

import android.content.Context
import androidx.core.content.edit
import com.example.data.db.ActivityPeriodEntity
import com.example.data.db.SavedEventEntity
import com.example.data.db.SavedShiftSession
import com.example.data.db.TachographDao
import com.example.data.model.ActivityPeriod
import com.example.data.model.ActivitySource
import com.example.data.model.DriverActivity
import com.example.data.model.EventSeverity
import com.example.data.model.TachographEvent
import com.example.domain.protocol.ProtocolMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Activity records are kept in two separate groups so the demo never mixes with the driver's own record. */
enum class ActivityGroup(val sources: Set<ActivitySource>) {
    REAL(setOf(ActivitySource.MANUAL, ActivitySource.TACHOGRAPH, ActivitySource.VEHICLE_MOTION)),
    DEMO(setOf(ActivitySource.DEMO));

    val sourceNames: List<String> get() = sources.map { it.name }

    companion object {
        fun of(source: ActivitySource) = if (source == ActivitySource.DEMO) DEMO else REAL
    }
}

class ActivityRepository(private val dao: TachographDao) {
    private val mutex = Mutex()

    fun observe(group: ActivityGroup, sinceMs: Long): Flow<List<ActivityPeriod>> =
        dao.observePeriods(sinceMs, group.sourceNames).map { list -> list.map { it.toModel() } }

    suspend fun load(group: ActivityGroup, sinceMs: Long): List<ActivityPeriod> =
        dao.periodsSince(sinceMs, group.sourceNames).map { it.toModel() }

    suspend fun current(group: ActivityGroup): ActivityPeriod? = dao.openPeriod(group.sourceNames)?.toModel()

    /**
     * Starts [activity] at [atMs]. The open period of the same group is closed. Repeating the current
     * activity from the same source does nothing, so this can be called for every incoming message.
     */
    suspend fun record(activity: DriverActivity, source: ActivitySource, atMs: Long, odometerKm: Double?) = mutex.withLock {
        val group = ActivityGroup.of(source)
        val open = dao.openPeriod(group.sourceNames)
        if (open != null && open.activity == activity.name && open.source == source.name) return@withLock
        dao.closeOpenPeriods(group.sourceNames, if (open != null) maxOf(atMs, open.startMs) else atMs)
        dao.insertPeriod(
            ActivityPeriodEntity(
                activity = activity.name,
                startMs = atMs,
                endMs = null,
                source = source.name,
                odometerKm = odometerKm
            )
        )
    }

    /** Ends the open period, e.g. when the tachograph link drops: what follows is unknown. */
    suspend fun closeOpen(group: ActivityGroup, atMs: Long, onlySource: ActivitySource? = null) = mutex.withLock {
        val open = dao.openPeriod(group.sourceNames) ?: return@withLock
        if (onlySource != null && open.source != onlySource.name) return@withLock
        dao.closeOpenPeriods(group.sourceNames, maxOf(atMs, open.startMs))
    }

    suspend fun insertHistory(periods: List<ActivityPeriod>) = mutex.withLock {
        periods.forEach {
            dao.insertPeriod(
                ActivityPeriodEntity(
                    activity = it.activity.name,
                    startMs = it.startMs,
                    endMs = it.endMs,
                    source = it.source.name,
                    odometerKm = null
                )
            )
        }
    }

    suspend fun clear(group: ActivityGroup) = mutex.withLock {
        group.sources.forEach { dao.deletePeriodsBySource(it.name) }
    }

    suspend fun prune(beforeMs: Long) = dao.prunePeriods(beforeMs)

    /** Odometer reading stored with the first period at or after [atMs], if any. */
    suspend fun odometerAt(group: ActivityGroup, atMs: Long): Double? =
        dao.periodsSince(atMs, group.sourceNames).firstOrNull { it.startMs >= atMs && it.odometerKm != null }?.odometerKm

    private fun ActivityPeriodEntity.toModel() = ActivityPeriod(
        activity = runCatching { DriverActivity.valueOf(activity) }.getOrDefault(DriverActivity.REST),
        startMs = startMs,
        endMs = endMs,
        source = runCatching { ActivitySource.valueOf(source) }.getOrDefault(ActivitySource.MANUAL)
    )
}

class EventRepository(private val dao: TachographDao) {
    fun observe(): Flow<List<TachographEvent>> = dao.getRecentEvents().map { list ->
        list.map {
            TachographEvent(
                id = it.id,
                timestamp = it.timestamp,
                code = it.code,
                title = it.title,
                description = it.description,
                severity = runCatching { EventSeverity.valueOf(it.severity) }.getOrDefault(EventSeverity.INFO)
            )
        }
    }

    suspend fun log(code: String, title: String, description: String, severity: EventSeverity, atMs: Long = System.currentTimeMillis()) {
        dao.insertEvent(
            SavedEventEntity(timestamp = atMs, code = code, title = title, description = description, severity = severity.name)
        )
    }

    suspend fun prune(beforeMs: Long) = dao.pruneEvents(beforeMs)
}

class ShiftArchiveRepository(private val dao: TachographDao) {
    fun observe(): Flow<List<SavedShiftSession>> = dao.getAllSessions()
    suspend fun save(session: SavedShiftSession) = dao.insertSession(session)
    suspend fun delete(id: Long) = dao.deleteSession(id)
}

/** Small persistent settings. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("tacho_settings", Context.MODE_PRIVATE)

    var protocolMode: ProtocolMode
        get() = runCatching { ProtocolMode.valueOf(prefs.getString(KEY_PROTOCOL, null) ?: "") }.getOrDefault(ProtocolMode.AUTO)
        set(value) = prefs.edit { putString(KEY_PROTOCOL, value.name) }

    var autoReconnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RECONNECT, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_RECONNECT, value) }

    var lastDeviceAddress: String?
        get() = prefs.getString(KEY_LAST_ADDRESS, null)
        set(value) = prefs.edit { putString(KEY_LAST_ADDRESS, value) }

    var lastDeviceName: String?
        get() = prefs.getString(KEY_LAST_NAME, null)
        set(value) = prefs.edit { putString(KEY_LAST_NAME, value) }

    /** Last time the adapter delivered data; used to close the record after the process was killed. */
    var lastLinkDataMs: Long
        get() = prefs.getLong(KEY_LAST_LINK_DATA, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_LINK_DATA, value) }

    /** Returns true if [key] was not notified before, and remembers it. */
    @Synchronized
    fun markAlertNotified(key: String): Boolean {
        val keys = prefs.getString(KEY_NOTIFIED, "")!!.split('\n').filter { it.isNotEmpty() }
        if (key in keys) return false
        val updated = (keys + key).takeLast(MAX_NOTIFIED_KEYS)
        prefs.edit { putString(KEY_NOTIFIED, updated.joinToString("\n")) }
        return true
    }

    private companion object {
        const val KEY_PROTOCOL = "protocol_mode"
        const val KEY_AUTO_RECONNECT = "auto_reconnect"
        const val KEY_LAST_ADDRESS = "last_device_address"
        const val KEY_LAST_NAME = "last_device_name"
        const val KEY_NOTIFIED = "notified_alert_keys"
        const val KEY_LAST_LINK_DATA = "last_link_data_ms"
        const val MAX_NOTIFIED_KEYS = 300
    }
}
