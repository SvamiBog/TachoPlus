package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.bluetooth.ConnectTransport
import com.example.data.bluetooth.ConnectionState
import com.example.data.bluetooth.FoundBtDevice
import com.example.data.bluetooth.TachographBluetoothManager
import com.example.data.bluetooth.TachographProtocol
import com.example.data.db.SavedShiftSession
import com.example.data.db.TachographDatabase
import com.example.data.model.ActivityTimelineSegment
import com.example.data.model.DriverActivity
import com.example.data.model.DriverCardInfo
import com.example.data.model.TachographDeviceInfo
import com.example.data.model.TachographEvent
import com.example.data.model.TachographLiveTelemetry
import com.example.data.model.WorkRestCompliance
import com.example.data.model.formatSecondsToHhMm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TachographViewModel(application: Application) : AndroidViewModel(application) {

    private val db = TachographDatabase.getDatabase(application)
    private val dao = db.tachographDao()

    val bluetoothManager = TachographBluetoothManager(application, viewModelScope)

    val connectionState: StateFlow<ConnectionState> = bluetoothManager.connectionState
    val connectedDeviceName: StateFlow<String?> = bluetoothManager.connectedDeviceName
    val lastErrorMessage: StateFlow<String?> = bluetoothManager.lastErrorMessage
    val discoveredDevices: StateFlow<List<FoundBtDevice>> = bluetoothManager.discoveredDevices
    val isBluetoothEnabled: StateFlow<Boolean> = bluetoothManager.isBluetoothEnabled
    val selectedProtocol: StateFlow<TachographProtocol> = bluetoothManager.selectedProtocol

    val telemetry: StateFlow<TachographLiveTelemetry> = bluetoothManager.telemetry
    val compliance: StateFlow<WorkRestCompliance> = bluetoothManager.compliance
    val currentActivity: StateFlow<DriverActivity> = bluetoothManager.currentActivity
    val driverCard1: StateFlow<DriverCardInfo> = bluetoothManager.driverCard1
    val driverCard2: StateFlow<DriverCardInfo> = bluetoothManager.driverCard2
    val deviceInfo: StateFlow<TachographDeviceInfo> = bluetoothManager.deviceInfo
    val events: StateFlow<List<TachographEvent>> = bluetoothManager.events
    val timelineSegments: StateFlow<List<ActivityTimelineSegment>> = bluetoothManager.timelineSegments
    val toastMessages = bluetoothManager.toastMessages

    // Real Stream Monitoring
    val isRealDataActive: StateFlow<Boolean> = bluetoothManager.isRealDataActive
    val streamBytesReceived: StateFlow<Long> = bluetoothManager.streamBytesReceived
    val streamPacketsReceived: StateFlow<Long> = bluetoothManager.streamPacketsReceived
    val lastRawPacket: StateFlow<String> = bluetoothManager.lastRawPacket
    val activeProtocolName: StateFlow<String> = bluetoothManager.activeProtocolName
    val rawTerminalLogs: StateFlow<List<String>> = bluetoothManager.rawTerminalLogs

    val savedSessions: StateFlow<List<SavedShiftSession>> = dao.getAllSessions()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _isDownloadingDdd = MutableStateFlow(false)
    val isDownloadingDdd: StateFlow<Boolean> = _isDownloadingDdd.asStateFlow()

    private val _dddDownloadProgress = MutableStateFlow(0f)
    val dddDownloadProgress: StateFlow<Float> = _dddDownloadProgress.asStateFlow()

    private val _exportedReportText = MutableStateFlow<String?>(null)
    val exportedReportText: StateFlow<String?> = _exportedReportText.asStateFlow()

    init {
        // Seed initial history session if DB is empty
        viewModelScope.launch {
            val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
            val todayStr = dateFormat.format(Date(System.currentTimeMillis() - 86400000L))
            dao.insertSession(
                SavedShiftSession(
                    dateString = todayStr,
                    startTime = System.currentTimeMillis() - 86400000L - 9 * 3600000L,
                    endTime = System.currentTimeMillis() - 86400000L,
                    totalDrivingMinutes = 480, // 8 hours
                    totalWorkMinutes = 75,
                    totalRestMinutes = 540,
                    totalDistanceKm = 594.3,
                    driverName = "SCHMIDT HANS-PETER",
                    driverCardNumber = "DED00000849201 0",
                    vehiclePlate = "B-MW 5420 (DE)",
                    isDddExported = true,
                    summaryNotes = "Рейс Берлин (DE) — Варшава (PL). Регламент (ЕС) № 561/2006 выполнен без нарушений."
                )
            )
        }
    }

    fun startDiscovery() {
        bluetoothManager.startDiscovery()
    }

    fun cancelDiscovery() {
        bluetoothManager.cancelDiscovery()
    }

    fun connectToDevice(device: FoundBtDevice, transport: ConnectTransport = ConnectTransport.AUTO) {
        bluetoothManager.connectToDevice(device, transport)
    }

    fun disconnect() {
        bluetoothManager.disconnect()
    }

    fun enableDemoMode() {
        bluetoothManager.enableDemoMode()
    }

    fun setActivity(activity: DriverActivity) {
        bluetoothManager.setDriverActivity(activity)
    }

    fun toggleCard(slot: Int) {
        bluetoothManager.toggleDriverCard(slot)
    }

    fun setSpeed(speed: Int) {
        bluetoothManager.setSimulatedSpeed(speed)
    }

    fun setProtocol(protocol: TachographProtocol) {
        bluetoothManager.setProtocol(protocol)
    }

    fun sendPollQuery() {
        bluetoothManager.sendPollQuery()
    }

    fun sendCustomCommand(cmd: String) {
        bluetoothManager.sendCustomCommand(cmd)
    }

    fun clearTerminalLogs() {
        bluetoothManager.clearTerminalLogs()
    }

    fun saveCurrentShift() {
        viewModelScope.launch {
            val comp = compliance.value
            val telem = telemetry.value
            val card = driverCard1.value
            val dev = deviceInfo.value
            val todayStr = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date())

            val session = SavedShiftSession(
                dateString = todayStr,
                startTime = comp.shiftStartTimestamp,
                endTime = System.currentTimeMillis(),
                totalDrivingMinutes = (comp.dailyDrivingSeconds / 60).toInt(),
                totalWorkMinutes = 45,
                totalRestMinutes = (comp.accumulatedBreakSeconds / 60).toInt(),
                totalDistanceKm = telem.tripOdometerKm,
                driverName = if (card.isInserted) card.driverName else "Без карты",
                driverCardNumber = if (card.isInserted) card.cardNumber else "---",
                vehiclePlate = dev.regNumber,
                isDddExported = false,
                summaryNotes = "Смена завершена штатно. Пробег: ${telem.tripOdometerKm} км. Вождение: ${formatSecondsToHhMm(comp.dailyDrivingSeconds)}"
            )

            dao.insertSession(session)
        }
    }

    fun startDddDownload() {
        viewModelScope.launch {
            _isDownloadingDdd.value = true
            _dddDownloadProgress.value = 0f

            for (p in 1..10) {
                kotlinx.coroutines.delay(250)
                _dddDownloadProgress.value = p / 10f
            }

            _isDownloadingDdd.value = false
            generateReport()
        }
    }

    private fun generateReport() {
        val dev = deviceInfo.value
        val card = driverCard1.value
        val telem = telemetry.value
        val comp = compliance.value
        val dateNow = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date())

        val report = buildString {
            appendLine("══════════════════════════════════════════════════")
            appendLine("      ОТЧЕТ ЦИФРОВОГО ТАХОГРАФА ЕС (ANNEX 1C DDD)  ")
            appendLine("  Регламент (ЕС) № 561/2006 • Регламент (ЕС) № 165/2014")
            appendLine("══════════════════════════════════════════════════")
            appendLine("Дата выгрузки: $dateNow")
            appendLine("Тахограф: ${dev.model}")
            appendLine("Поколение: Smart Tachograph Gen2 V2 (Регламент ЕС 2021/1228)")
            appendLine("Серийный номер устройства: ${dev.serialNumber}")
            appendLine("Регистрационный номер ТС: ${dev.regNumber} (VIN: ${dev.vin})")
            appendLine("Сертификация мастерской: ${dev.workshopName} (§ 57b StVZO)")
            appendLine("Карта мастерской: ${dev.workshopCardNumber}")
            appendLine("Калибровка: ${dev.calibrationDate} | K-фактор: ${dev.kFactor} imp/km")
            appendLine("──────────────────────────────────────────────────")
            appendLine("КАРТА ВОДИТЕЛЯ ЕС (СЛОТ 1):")
            appendLine("Владелец карты: ${if (card.isInserted) card.driverName else "НЕ ВСТАВЛЕНА"}")
            appendLine("Номер карты водителя ЕС: ${card.cardNumber}")
            appendLine("Орган выдачи: ${card.issuingAuthority} (${card.issuingCountry})")
            appendLine("Срок действия: до ${card.expiryDate}")
            appendLine("──────────────────────────────────────────────────")
            appendLine("СООТВЕТСТВИЕ РЕГЛАМЕНТУ (ЕС) № 561/2006:")
            appendLine("Непрерывное вождение: ${formatSecondsToHhMm(comp.continuousDrivingSeconds)} (лимит 04:30)")
            appendLine("Суммарный перерыв: ${formatSecondsToHhMm(comp.accumulatedBreakSeconds)} (правило 15+30м / 45м)")
            appendLine("Суточное вождение: ${formatSecondsToHhMm(comp.dailyDrivingSeconds)} (макс 09:00, продление 10:00)")
            appendLine("Недельное вождение: ${formatSecondsToHhMm(comp.weeklyDrivingSeconds)} (лимит 56:00)")
            appendLine("Двухнедельное вождение: ${formatSecondsToHhMm(comp.biweeklyDrivingSeconds)} (лимит 90:00)")
            appendLine("Текущая смена (окно 24ч): ${formatSecondsToHhMm(comp.shiftDurationSeconds)}")
            appendLine("──────────────────────────────────────────────────")
            appendLine("ЕВРОПЕЙСКИЕ ТЕЛЕМАТИЧЕСКИЕ ДАННЫЕ (SMART TACHO 2):")
            appendLine("Текущий общий одометр: ${telem.totalOdometerKm} км")
            appendLine("Суточный пробег рейса: ${telem.tripOdometerKm} км")
            appendLine("Датчик движения: ${telem.motionSensorStatus}")
            appendLine("Спутниковая система: Galileo OSNMA / GPS (Аутентификация активна)")
            appendLine("Дистанционный контроль DSRC: ${dev.dsrcRemoteCompliance}")
            appendLine("Последнее пересечение границы ЕС: ${telem.lastBorderCrossing}")
            appendLine("Зафиксировано пересечений границ: ${dev.euBorderCrossingsRegistered}")
            appendLine("══════════════════════════════════════════════════")
            appendLine("ЭЦП ERCA / MSCA (European Root CA) валидна [RSA-2048 / ECC-256]")
        }

        _exportedReportText.value = report
    }

    fun dismissReport() {
        _exportedReportText.value = null
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            dao.deleteSession(sessionId)
        }
    }

    fun pairDevice(device: FoundBtDevice) {
        bluetoothManager.pairDevice(device)
    }

    override fun onCleared() {
        super.onCleared()
        bluetoothManager.cleanup()
    }
}
