package com.example.data.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import com.example.data.bluetooth.BleUartTransport
import com.example.data.bluetooth.BluetoothPermissionManager
import com.example.data.bluetooth.ConnectTransport
import com.example.data.bluetooth.DeviceKind
import com.example.data.bluetooth.FoundBtDevice
import com.example.data.bluetooth.SppTransport
import com.example.data.model.ActivityPeriod
import com.example.data.model.ActivitySource
import com.example.data.model.DriverActivity
import com.example.data.model.EventSeverity
import com.example.data.repo.ActivityGroup
import com.example.data.repo.ActivityRepository
import com.example.data.repo.EventRepository
import com.example.data.repo.SettingsStore
import com.example.domain.compliance.ComplianceRules.HOUR
import com.example.domain.compliance.ComplianceRules.MINUTE
import com.example.domain.protocol.ByteTransport
import com.example.domain.protocol.ProtocolException
import com.example.domain.protocol.MotionActivityDetector
import com.example.domain.protocol.ProtocolMode
import com.example.domain.protocol.TachoSession
import com.example.domain.protocol.VehicleLiveState
import com.example.domain.protocol.VehicleUpdate
import com.example.service.TachoLinkService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.random.Random

enum class LinkState(val titleRu: String) {
    DISCONNECTED("Не подключено"),
    CONNECTING("Подключение…"),
    CONNECTED("Подключено"),
    RECONNECTING("Переподключение…"),
    DEMO("Демо-режим"),
    ERROR("Ошибка подключения")
}

data class LinkStatus(
    val state: LinkState = LinkState.DISCONNECTED,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val transport: ConnectTransport? = null,
    val requestedProtocol: ProtocolMode = ProtocolMode.AUTO,
    val activeProtocol: ProtocolMode? = null,
    val adapterInfo: String? = null,
    val message: String? = null,
    val bytesReceived: Long = 0,
    val messagesDecoded: Long = 0,
    val lastDataMs: Long? = null,
    /** The tachograph reports the driver's working state, so manual selection is disabled. */
    val tachographProvidesActivity: Boolean = false
) {
    val isLinkActive: Boolean
        get() = state == LinkState.CONNECTING || state == LinkState.CONNECTED || state == LinkState.RECONNECTING
}

/**
 * Owns the single Bluetooth session: connects, runs [TachoSession], records activity changes and reconnects
 * after unexpected drops. All public methods may be called from the main thread.
 */
class TachoLinkManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val activities: ActivityRepository,
    private val events: EventRepository,
    private val settings: SettingsStore
) {
    private val _status = MutableStateFlow(LinkStatus(requestedProtocol = settings.protocolMode))
    val status: StateFlow<LinkStatus> = _status.asStateFlow()

    private val _vehicle = MutableStateFlow(VehicleLiveState())
    val vehicle: StateFlow<VehicleLiveState> = _vehicle.asStateFlow()

    private val _terminal = MutableStateFlow<List<String>>(emptyList())
    val terminal: StateFlow<List<String>> = _terminal.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var sessionJob: Job? = null
    private var commands: Channel<String>? = null
    private val motionDetector = MotionActivityDetector()

    /** Current activity of the driver's own record and where it came from (for de-duplication and motion detection). */
    @Volatile private var lastRealActivity: DriverActivity? = null
    @Volatile private var lastRealSource: ActivitySource? = null
    @Volatile private var drivingWithoutCardReported = false

    /** Incremented whenever a session is stopped, so late messages of an old session are dropped. */
    @Volatile private var generation = 0

    @Volatile private var lastHeartbeatMs = 0L

    @Volatile private var demoActivity = DriverActivity.DRIVING
    @Volatile private var demoPhaseStart = 0L

    /** Activity changes are written by one consumer so their order is preserved. */
    private data class ActivityChange(val activity: DriverActivity?, val source: ActivitySource, val atMs: Long, val odometer: Double?)

    private val activityChanges = Channel<ActivityChange>(Channel.UNLIMITED)

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    init {
        scope.launch(Dispatchers.IO) {
            val open = activities.current(ActivityGroup.REAL)
            if (open != null && (open.source == ActivitySource.TACHOGRAPH || open.source == ActivitySource.VEHICLE_MOTION)) {
                // The process died while a link was open: the record ends where the data ended.
                val lastData = settings.lastLinkDataMs.takeIf { it > open.startMs } ?: open.startMs
                activities.closeOpen(ActivityGroup.REAL, lastData)
            } else if (open != null) {
                lastRealActivity = open.activity
                lastRealSource = open.source
            }
            // A demo left running when the process died must not stay in the database.
            activities.clear(ActivityGroup.DEMO)
            for (change in activityChanges) {
                runCatching {
                    if (change.activity == null) {
                        activities.closeOpen(ActivityGroup.of(change.source), change.atMs, onlySource = change.source)
                    } else {
                        activities.record(change.activity, change.source, change.atMs, change.odometer)
                    }
                }
            }
        }
    }

    // region public API

    fun setProtocol(mode: ProtocolMode) {
        settings.protocolMode = mode
        _status.update { it.copy(requestedProtocol = mode) }
    }

    fun connect(device: FoundBtDevice, transport: ConnectTransport) {
        if (!BluetoothPermissionManager.hasConnectPermission(context)) {
            _messages.tryEmit("Нет разрешения на подключение Bluetooth")
            return
        }
        stopSessionJob()
        settings.lastDeviceAddress = device.address
        settings.lastDeviceName = device.name
        val protocol = settings.protocolMode
        _vehicle.value = VehicleLiveState()
        _status.value = LinkStatus(
            state = LinkState.CONNECTING,
            deviceName = device.name,
            deviceAddress = device.address,
            transport = transport,
            requestedProtocol = protocol
        )
        TachoLinkService.start(context)
        val gen = generation
        sessionJob = scope.launch { runConnectionLoop(device, transport, protocol, gen) }
    }

    fun disconnect() {
        val wasActive = _status.value.isLinkActive
        stopSessionJob()
        endTachographActivity()
        _status.update { LinkStatus(requestedProtocol = it.requestedProtocol) }
        if (wasActive) log("[SYS] Отключено пользователем")
    }

    fun startDemo() {
        stopSessionJob()
        endTachographActivity()
        _vehicle.value = VehicleLiveState()
        _status.value = LinkStatus(
            state = LinkState.DEMO,
            deviceName = "Демо-тахограф",
            requestedProtocol = settings.protocolMode,
            activeProtocol = ProtocolMode.TEXT_GATEWAY,
            adapterInfo = "Симулятор (данные не настоящие)",
            tachographProvidesActivity = true
        )
        val gen = generation
        sessionJob = scope.launch { runDemo(gen) }
        _messages.tryEmit("Демо-режим: данные вымышленные и хранятся отдельно от вашего журнала")
    }

    fun stopDemo() {
        if (_status.value.state != LinkState.DEMO) return
        stopSessionJob()
        _vehicle.value = VehicleLiveState()
        _status.update { LinkStatus(requestedProtocol = it.requestedProtocol) }
        scope.launch(Dispatchers.IO) { activities.clear(ActivityGroup.DEMO) }
    }

    /** Manual activity entry; refused while the tachograph itself reports the working state. */
    fun setManualActivity(activity: DriverActivity): Boolean {
        val status = _status.value
        if (status.state == LinkState.DEMO) {
            demoActivity = activity
            demoPhaseStart = System.currentTimeMillis()
            enqueue(activity, ActivitySource.DEMO)
            return true
        }
        if (status.state == LinkState.CONNECTED && status.tachographProvidesActivity) {
            _messages.tryEmit("Режим задаёт тахограф — переключите его на тахографе")
            return false
        }
        enqueue(activity, ActivitySource.MANUAL)
        motionDetector.reset()
        return true
    }

    fun sendCommand(cmd: String): Boolean {
        val channel = commands
        if (channel == null || _status.value.state != LinkState.CONNECTED || cmd.isBlank()) return false
        return channel.trySend(cmd).isSuccess
    }

    fun clearTerminal() {
        _terminal.value = emptyList()
    }

    // endregion

    private fun stopSessionJob() {
        generation++
        sessionJob?.cancel()
        sessionJob = null
        commands?.close()
        commands = null
        motionDetector.reset()
        drivingWithoutCardReported = false
    }

    private fun endTachographActivity() {
        val now = System.currentTimeMillis()
        activityChanges.trySend(ActivityChange(null, ActivitySource.TACHOGRAPH, now, null))
        activityChanges.trySend(ActivityChange(null, ActivitySource.VEHICLE_MOTION, now, null))
        if (lastRealSource == ActivitySource.TACHOGRAPH || lastRealSource == ActivitySource.VEHICLE_MOTION) {
            lastRealActivity = null
            lastRealSource = null
        }
    }

    private fun enqueue(activity: DriverActivity, source: ActivitySource, atMs: Long = System.currentTimeMillis()) {
        if (source != ActivitySource.DEMO) {
            lastRealActivity = activity
            lastRealSource = source
        }
        activityChanges.trySend(ActivityChange(activity, source, atMs, _vehicle.value.odometerKm))
    }

    private suspend fun runConnectionLoop(device: FoundBtDevice, transport: ConnectTransport, protocol: ProtocolMode, gen: Int) {
        var attempt = 0
        var everConnected = false
        while (currentCoroutineContext().isActive) {
            val error = try {
                val link = openTransport(device, transport)
                everConnected = true
                attempt = 0
                runSession(link, protocol, gen)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
            endTachographActivity()
            val reason = error?.message ?: "Соединение закрыто"
            if ((!everConnected && attempt == 0) || error is ProtocolException || !settings.autoReconnect) {
                log("[ERR] $reason")
                _status.update {
                    it.copy(
                        state = LinkState.ERROR,
                        message = reason + "\n" + adviceFor(device),
                        tachographProvidesActivity = false
                    )
                }
                events.log("LINK", "Ошибка подключения", "${device.name}: $reason", EventSeverity.WARNING)
                return
            }
            if (everConnected && attempt == 0) {
                events.log("LINK", "Связь с адаптером потеряна", "${device.name}: $reason", EventSeverity.WARNING)
                _messages.tryEmit("Связь с адаптером потеряна — переподключение")
            }
            attempt++
            if (attempt > MAX_RECONNECT_ATTEMPTS) {
                _status.update { it.copy(state = LinkState.ERROR, message = "Не удалось восстановить связь: $reason") }
                return
            }
            val wait = minOf(5_000L * attempt, 30_000L)
            _status.update {
                it.copy(
                    state = LinkState.RECONNECTING,
                    message = "$reason. Повтор через ${wait / 1000} с (попытка $attempt)",
                    tachographProvidesActivity = false
                )
            }
            log("[SYS] Повторное подключение через ${wait / 1000} с")
            delay(wait)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun openTransport(device: FoundBtDevice, transport: ConnectTransport): ByteTransport {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: throw IOException("Bluetooth недоступен")
        if (!adapter.isEnabled) throw IOException("Bluetooth выключен")
        val remote = device.device ?: adapter.getRemoteDevice(device.address)
        val log: (String) -> Unit = ::log
        // withTimeout would throw a CancellationException and end the loop silently; a timeout is a normal failure.
        return withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            when (transport) {
                ConnectTransport.SPP_RFCOMM -> SppTransport.connect(adapter, remote, log)
                ConnectTransport.BLE_GATT -> BleUartTransport.connect(context, remote, log)
                ConnectTransport.AUTO -> when (device.kind) {
                    DeviceKind.BLE -> BleUartTransport.connect(context, remote, log)
                    DeviceKind.CLASSIC -> SppTransport.connect(adapter, remote, log)
                    else -> try {
                        SppTransport.connect(adapter, remote, log)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("[SYS] SPP не ответил, пробую BLE")
                        BleUartTransport.connect(context, remote, log)
                    }
                }
            }
        } ?: throw IOException("Адаптер не ответил за ${CONNECT_TIMEOUT_MS / 1000} с")
    }

    private suspend fun runSession(link: ByteTransport, protocol: ProtocolMode, gen: Int) {
        val channel = Channel<String>(Channel.UNLIMITED)
        commands = channel
        _status.update { it.copy(state = LinkState.CONNECTED, message = null, bytesReceived = 0, messagesDecoded = 0) }
        val name = _status.value.deviceName ?: "адаптер"
        events.log("LINK", "Подключено", name, EventSeverity.INFO)
        _messages.tryEmit("Подключено: $name")
        val listener = object : TachoSession.Listener {
            override fun onUpdate(update: VehicleUpdate) = handleUpdate(update, ActivitySource.TACHOGRAPH, gen)
            override fun onLog(line: String) = log(line)
            override fun onModeResolved(mode: ProtocolMode, adapterInfo: String?) {
                _status.update { it.copy(activeProtocol = mode, adapterInfo = adapterInfo) }
                log("[SYS] Протокол: ${mode.title}${adapterInfo?.let { " · $it" } ?: ""}")
            }
            override fun onBytes(count: Int) {
                _status.update { it.copy(bytesReceived = it.bytesReceived + count, lastDataMs = System.currentTimeMillis()) }
            }
        }
        try {
            TachoSession(link, protocol, listener, channel).run()
        } finally {
            link.close()
            channel.close()
            if (commands === channel) commands = null
        }
    }

    private fun handleUpdate(update: VehicleUpdate, source: ActivitySource, gen: Int) {
        if (gen != generation) return
        val now = System.currentTimeMillis()
        val previous = _vehicle.value
        val next = previous.merge(update, now)
        _vehicle.value = next
        _status.update { it.copy(messagesDecoded = it.messagesDecoded + 1) }

        val reported = update.driver1Activity
        if (reported != null) {
            if (!_status.value.tachographProvidesActivity) _status.update { it.copy(tachographProvidesActivity = true) }
            if (source == ActivitySource.DEMO || reported != lastRealActivity || lastRealSource != source) {
                enqueue(reported, source, now)
            }
        } else if (!_status.value.tachographProvidesActivity && source != ActivitySource.DEMO) {
            next.speedKmh?.let { speed ->
                motionDetector.onSpeed(speed, now, lastRealActivity)?.let { activity ->
                    enqueue(activity, ActivitySource.VEHICLE_MOTION, now)
                    log("[SYS] По движению ТС: ${activity.titleRu}")
                }
            }
        }

        if (source == ActivitySource.DEMO) return
        if (now - lastHeartbeatMs >= HEARTBEAT_INTERVAL_MS) {
            lastHeartbeatMs = now
            settings.lastLinkDataMs = now
        }
        cardChange(1, previous.driver1CardPresent, next.driver1CardPresent, next.driver1Id)
        cardChange(2, previous.driver2CardPresent, next.driver2CardPresent, next.driver2Id)
        val moving = next.vehicleMotion == true || (next.speedKmh ?: 0.0) > 5.0
        if (moving && next.driver1CardPresent == false && !drivingWithoutCardReported) {
            drivingWithoutCardReported = true
            scope.launch {
                events.log("CARD", "Движение без карты водителя", "Тахограф сообщает: карта в слоте 1 отсутствует", EventSeverity.CRITICAL)
            }
        }
        if (next.driver1CardPresent == true) drivingWithoutCardReported = false
    }

    private fun cardChange(slot: Int, before: Boolean?, after: Boolean?, id: String?) {
        if (before == null || after == null || before == after) return
        scope.launch {
            events.log(
                "CARD",
                if (after) "Карта вставлена в слот $slot" else "Карта извлечена из слота $slot",
                id ?: "Номер карты не передаётся",
                EventSeverity.INFO
            )
        }
    }

    private fun adviceFor(device: FoundBtDevice): String =
        if (!device.isPaired && device.kind != DeviceKind.BLE) {
            "Сопрягите адаптер в списке или в настройках Android (PIN обычно 0000 или 1234)."
        } else {
            "Проверьте, что адаптер получает питание (зажигание), и что его не занимает другое приложение."
        }

    private fun log(line: String) {
        val entry = "${timeFormat.format(Date())} $line"
        _terminal.update { (listOf(entry) + it).take(MAX_TERMINAL_LINES) }
    }

    // region demo

    private suspend fun runDemo(gen: Int) {
        val now = System.currentTimeMillis()
        activities.clear(ActivityGroup.DEMO)
        activities.insertHistory(demoHistory(now))
        enqueue(DriverActivity.DRIVING, ActivitySource.DEMO, now - DEMO_DRIVING_SO_FAR_MS)
        handleUpdate(
            VehicleUpdate(
                driver1Activity = DriverActivity.DRIVING,
                driver1CardPresent = true,
                driver2CardPresent = false,
                driver1Id = "DEMO0000000000001",
                driver1Name = "Демо-водитель",
                vin = "DEMO0000000000000",
                registration = "DEMO 001",
                odometerKm = 123_456.0
            ),
            ActivitySource.DEMO,
            gen
        )
        demoActivity = DriverActivity.DRIVING
        demoPhaseStart = now - DEMO_DRIVING_SO_FAR_MS
        var odometer = 123_456.0
        var speed = 82.0
        while (currentCoroutineContext().isActive) {
            delay(1_000)
            val t = System.currentTimeMillis()
            when (demoActivity) {
                DriverActivity.DRIVING -> {
                    speed = (speed + Random.nextDouble(-3.0, 3.0)).coerceIn(68.0, 89.0)
                    odometer += speed / 3600.0
                    // Stop for the break shortly before the 4.5 h limit, as a careful driver would.
                    if (t - demoPhaseStart >= 4 * HOUR + 20 * MINUTE) {
                        demoActivity = DriverActivity.REST
                        demoPhaseStart = t
                    }
                }
                DriverActivity.REST -> {
                    speed = 0.0
                    if (t - demoPhaseStart >= 46 * MINUTE) {
                        demoActivity = DriverActivity.DRIVING
                        demoPhaseStart = t
                        speed = 75.0
                    }
                }
                else -> speed = 0.0
            }
            val activity = demoActivity
            handleUpdate(
                VehicleUpdate(
                    driver1Activity = activity,
                    vehicleMotion = speed > 0,
                    tachographSpeedKmh = speed,
                    wheelSpeedKmh = speed,
                    engineRpm = if (speed > 0) max(900.0, speed * 15) else 600.0,
                    odometerKm = odometer
                ),
                ActivitySource.DEMO,
                gen
            )
            _status.update { it.copy(bytesReceived = it.bytesReceived + 24, lastDataMs = t) }
        }
    }

    /** Five previous working days with regular rests, so weekly totals and the timeline have content. */
    private fun demoHistory(now: Long): List<ActivityPeriod> {
        val periods = ArrayList<ActivityPeriod>()
        val todayShiftStart = now - DEMO_DRIVING_SO_FAR_MS - 20 * MINUTE
        var cursor = todayShiftStart - 5 * 24 * HOUR
        fun add(activity: DriverActivity, duration: Long) {
            periods += ActivityPeriod(activity, cursor, cursor + duration, ActivitySource.DEMO)
            cursor += duration
        }
        repeat(5) {
            add(DriverActivity.WORK, 20 * MINUTE)
            add(DriverActivity.DRIVING, 4 * HOUR + 10 * MINUTE)
            add(DriverActivity.REST, 45 * MINUTE)
            add(DriverActivity.DRIVING, 3 * HOUR + 40 * MINUTE)
            add(DriverActivity.WORK, 30 * MINUTE)
            add(DriverActivity.REST, todayShiftStart - cursor - (4 - it) * 24 * HOUR)
        }
        add(DriverActivity.WORK, 20 * MINUTE)
        return periods
    }

    // endregion

    private companion object {
        const val CONNECT_TIMEOUT_MS = 45_000L
        const val MAX_RECONNECT_ATTEMPTS = 120
        const val MAX_TERMINAL_LINES = 300
        const val DEMO_DRIVING_SO_FAR_MS = 4 * HOUR + 5 * MINUTE
        const val HEARTBEAT_INTERVAL_MS = 30_000L
    }
}
