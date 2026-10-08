package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.TachoApplication
import com.example.data.bluetooth.ConnectTransport
import com.example.data.bluetooth.FoundBtDevice
import com.example.data.db.SavedShiftSession
import com.example.data.link.LinkState
import com.example.data.link.LinkStatus
import com.example.data.model.ActivityPeriod
import com.example.data.model.ActivityTimelineSegment
import com.example.data.model.DriverActivity
import com.example.data.model.TachographEvent
import com.example.data.repo.ActivityGroup
import com.example.domain.compliance.AlertSeverity
import com.example.domain.compliance.ComplianceStatus
import com.example.domain.protocol.ProtocolMode
import com.example.domain.protocol.VehicleLiveState
import com.example.domain.report.ActivityReportBuilder
import com.example.domain.report.TimelineBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class TachographViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as TachoApplication).container

    val link: StateFlow<LinkStatus> = container.link.status
    val vehicle: StateFlow<VehicleLiveState> = container.link.vehicle
    val terminal: StateFlow<List<String>> = container.link.terminal
    val compliance: StateFlow<ComplianceStatus> = container.compliance.status
    val periods: StateFlow<List<ActivityPeriod>> = container.compliance.periods

    val devices: StateFlow<List<FoundBtDevice>> = container.scanner.devices
    val isScanning: StateFlow<Boolean> = container.scanner.isScanning
    val bluetoothEnabled: StateFlow<Boolean> = container.scanner.bluetoothEnabled

    val events: StateFlow<List<TachographEvent>> = container.events.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val savedSessions: StateFlow<List<SavedShiftSession>> = container.archive.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Today's timeline, refreshed once a minute. */
    val todayTimeline: StateFlow<List<ActivityTimelineSegment>> = combine(
        periods,
        compliance.map { it.nowMs / 60_000 }.distinctUntilChanged()
    ) { list, minute ->
        val now = minute * 60_000
        TimelineBuilder.segmentsForDay(list, localDayStart(now), System.currentTimeMillis())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _localMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: Flow<String> = merge(container.scanner.messages, container.link.messages, _localMessages)

    private val _report = MutableStateFlow<String?>(null)
    val report: StateFlow<String?> = _report.asStateFlow()

    fun refreshBluetoothState() = container.scanner.refreshAdapterState()
    fun startScan() = container.scanner.start()
    fun stopScan() = container.scanner.stop()
    fun pair(device: FoundBtDevice) = container.scanner.pair(device)

    fun connect(device: FoundBtDevice, transport: ConnectTransport) {
        container.scanner.stop()
        container.link.connect(device, transport)
    }

    fun disconnect() = container.link.disconnect()
    fun startDemo() = container.link.startDemo()
    fun stopDemo() = container.link.stopDemo()
    fun setProtocol(mode: ProtocolMode) = container.link.setProtocol(mode)
    fun sendCommand(cmd: String) {
        if (!container.link.sendCommand(cmd)) _localMessages.tryEmit("Команды можно отправлять только при подключённом адаптере")
    }
    fun clearTerminal() = container.link.clearTerminal()

    fun setActivity(activity: DriverActivity) {
        if (container.link.setManualActivity(activity)) _localMessages.tryEmit("Режим: ${activity.titleRu}")
    }

    fun buildReport() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val group = if (link.value.state == LinkState.DEMO) ActivityGroup.DEMO else ActivityGroup.REAL
            val text = withContext(Dispatchers.Default) {
                val list = container.activities.load(group, now - 30L * 24 * 3600 * 1000)
                ActivityReportBuilder.build(list, compliance.value, vehicle.value, now)
            }
            _report.value = if (group == ActivityGroup.DEMO) "ДЕМО-ДАННЫЕ (вымышленные)\n\n$text" else text
        }
    }

    fun dismissReport() {
        _report.value = null
    }

    fun saveCurrentShift() {
        viewModelScope.launch {
            if (link.value.state == LinkState.DEMO) {
                _localMessages.tryEmit("Демо-данные не сохраняются в архив")
                return@launch
            }
            val status = compliance.value
            val shift = status.shifts.lastOrNull()
            if (shift == null || (shift.drivingMs + shift.workMs + shift.availableMs) == 0L) {
                _localMessages.tryEmit("В журнале нет данных о смене")
                return@launch
            }
            val car = vehicle.value
            val startOdometer = container.activities.odometerAt(ActivityGroup.REAL, shift.startMs)
            val distance = if (startOdometer != null && car.odometerKm != null) (car.odometerKm - startOdometer).coerceAtLeast(0.0) else 0.0
            val problems = status.alerts.filter { it.severity == AlertSeverity.VIOLATION }.map { it.title }
            val notes = buildString {
                append(if (problems.isEmpty()) "Нарушений по журналу не обнаружено." else "Нарушения: ${problems.joinToString("; ")}.")
                if (!shift.startKnown) append(" Начало смены не записано — данные неполные.")
                if (startOdometer == null || car.odometerKm == null) append(" Пробег неизвестен.")
            }
            container.archive.save(
                SavedShiftSession(
                    dateString = SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date(shift.startMs)),
                    startTime = shift.startMs,
                    endTime = shift.endMs,
                    totalDrivingMinutes = (shift.drivingMs / 60_000).toInt(),
                    totalWorkMinutes = ((shift.workMs + shift.availableMs) / 60_000).toInt(),
                    totalRestMinutes = (shift.restMs / 60_000).toInt(),
                    totalDistanceKm = distance,
                    driverName = car.driver1Name ?: "—",
                    driverCardNumber = car.driver1Id ?: "—",
                    vehiclePlate = car.registration ?: car.vin ?: "—",
                    summaryNotes = notes
                )
            )
            _localMessages.tryEmit("Смена сохранена в архив")
        }
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch { container.archive.delete(sessionId) }
    }

    private fun localDayStart(nowMs: Long): Long = Calendar.getInstance().apply {
        timeInMillis = nowMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
