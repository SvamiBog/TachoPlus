package com.example.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.example.data.model.ActivityTimelineSegment
import com.example.data.model.DriverActivity
import com.example.data.model.DriverCardInfo
import com.example.data.model.EventSeverity
import com.example.data.model.TachographDeviceInfo
import com.example.data.model.TachographEvent
import com.example.data.model.TachographLiveTelemetry
import com.example.data.model.WorkRestCompliance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class ConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED,
    DEMO_MODE,
    ERROR
}

enum class TachographProtocol(val displayName: String) {
    AUTO_DETECT("Автоопределение (Multi-Protocol)"),
    VDO_SMARTLINK("Continental VDO SmartLink / DTCO"),
    STONERIDGE_TACHO_LINK("Stoneridge SE5000 Tacho Link"),
    J1939_FMS_CAN("SAE J1939 CAN / FMS (TCO1 & TDM)"),
    OBD2_TRUCK("OBD-II / ELM327 Telematics")
}

enum class ConnectTransport(val title: String) {
    AUTO("Авто (SPP / BLE)"),
    SPP_RFCOMM("Classic SPP RFCOMM"),
    BLE_GATT("BLE GATT UART")
}

data class FoundBtDevice(
    val name: String,
    val address: String,
    val isPaired: Boolean,
    val bondState: Int = BluetoothDevice.BOND_NONE,
    val isLikelyTachograph: Boolean = false,
    val tachographVendor: String = "Bluetooth устройство",
    val rssi: Int = -70,
    val isBle: Boolean = false,
    val supportsSpp: Boolean = true,
    val supportsBle: Boolean = false,
    val device: BluetoothDevice? = null
) {
    val signalPercent: Int
        get() = when {
            rssi >= -50 -> 100
            rssi <= -100 -> 10
            else -> ((rssi + 100) * 2).coerceIn(10, 100)
        }

    val signalLabel: String
        get() = when {
            rssi >= -65 -> "Отличный ($rssi dBm)"
            rssi >= -80 -> "Хороший ($rssi dBm)"
            else -> "Слабый ($rssi dBm)"
        }
}

class TachographBluetoothManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "TachoBtManager"

        // Standard Serial Port Profile (SPP RFCOMM) UUID
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        // Specific tachograph / telemetry UUIDs (VDO SmartLink, Stoneridge, ELM)
        val VDO_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        // Nordic UART Service & generic Serial BLE service UUIDs
        val BLE_UART_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val BLE_UART_RX_CHAR: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // Peripheral TX -> Central RX
        val BLE_UART_TX_CHAR: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // Central TX -> Peripheral RX
        val CLIENT_CHAR_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        fun identifyTachographVendor(name: String): String? {
            val lower = name.lowercase()
            return when {
                lower.contains("vdo") || lower.contains("dtco") || lower.contains("smartlink") -> "Continental VDO"
                lower.contains("stoneridge") || lower.contains("se5000") || lower.contains("digifob") || lower.contains("ddl") || lower.contains("tacho link") -> "Stoneridge Electronics"
                lower.contains("efas") || lower.contains("intellic") -> "Intellic EFAS"
                lower.contains("actia") || lower.contains("smarttach") -> "Actia SmarTach"
                lower.contains("digidown") -> "Digidown EU"
                lower.contains("optac") -> "Stoneridge OPTAC"
                lower.contains("obd") || lower.contains("elm") || lower.contains("vlinker") || lower.contains("kingbolen") || lower.contains("icar") -> "Адаптер FMS / OBD"
                lower.contains("tacho") -> "Тахограф ЕС (Annex 1C)"
                else -> null
            }
        }
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val bleScanner: BluetoothLeScanner?
        get() = bluetoothAdapter?.bluetoothLeScanner

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _lastErrorMessage = MutableStateFlow<String?>(null)
    val lastErrorMessage: StateFlow<String?> = _lastErrorMessage.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<FoundBtDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<FoundBtDevice>> = _discoveredDevices.asStateFlow()

    private val _isBluetoothEnabled = MutableStateFlow(bluetoothAdapter?.isEnabled ?: false)
    val isBluetoothEnabled: StateFlow<Boolean> = _isBluetoothEnabled.asStateFlow()

    private val _selectedProtocol = MutableStateFlow(TachographProtocol.AUTO_DETECT)
    val selectedProtocol: StateFlow<TachographProtocol> = _selectedProtocol.asStateFlow()

    // Real Stream Telemetry
    private val _telemetry = MutableStateFlow(
        TachographLiveTelemetry(
            speedKmh = 0,
            engineRpm = 0,
            totalOdometerKm = 0,
            tripOdometerKm = 0.0,
            utcTime = "--:--:-- UTC",
            localTime = "Ожидание подключения к тахографу..."
        )
    )
    val telemetry: StateFlow<TachographLiveTelemetry> = _telemetry.asStateFlow()

    private val _compliance = MutableStateFlow(
        WorkRestCompliance(
            continuousDrivingSeconds = 0,
            accumulatedBreakSeconds = 0,
            dailyDrivingSeconds = 0,
            shiftDurationSeconds = 0,
            weeklyDrivingSeconds = 0,
            biweeklyDrivingSeconds = 0
        )
    )
    val compliance: StateFlow<WorkRestCompliance> = _compliance.asStateFlow()

    private val _currentActivity = MutableStateFlow(DriverActivity.REST)
    val currentActivity: StateFlow<DriverActivity> = _currentActivity.asStateFlow()

    // Individual timer accumulations for all 4 activities
    private val _drivingTimeSeconds = MutableStateFlow(0L)
    val drivingTimeSeconds: StateFlow<Long> = _drivingTimeSeconds.asStateFlow()

    private val _restTimeSeconds = MutableStateFlow(0L)
    val restTimeSeconds: StateFlow<Long> = _restTimeSeconds.asStateFlow()

    private val _availabilityTimeSeconds = MutableStateFlow(0L)
    val availabilityTimeSeconds: StateFlow<Long> = _availabilityTimeSeconds.asStateFlow()

    private val _workTimeSeconds = MutableStateFlow(0L)
    val workTimeSeconds: StateFlow<Long> = _workTimeSeconds.asStateFlow()

    private val _currentModeDurationSeconds = MutableStateFlow(0L)
    val currentModeDurationSeconds: StateFlow<Long> = _currentModeDurationSeconds.asStateFlow()

    // Driver Cards
    private val _driverCard1 = MutableStateFlow(
        DriverCardInfo(
            slotNumber = 1,
            isInserted = false,
            driverName = "Ожидание карты водителя...",
            cardNumber = "---",
            issuingCountry = "EU",
            issuingAuthority = "---",
            expiryDate = "---"
        )
    )
    val driverCard1: StateFlow<DriverCardInfo> = _driverCard1.asStateFlow()

    private val _driverCard2 = MutableStateFlow(
        DriverCardInfo(
            slotNumber = 2,
            isInserted = false,
            driverName = "Слот 2 свободен",
            cardNumber = "---"
        )
    )
    val driverCard2: StateFlow<DriverCardInfo> = _driverCard2.asStateFlow()

    private val _deviceInfo = MutableStateFlow(
        TachographDeviceInfo(
            model = "Тахограф ЕС (Annex 1C)",
            serialNumber = "---",
            vin = "---",
            regNumber = "Транспортное средство",
            softwareVersion = "Smart Tacho Gen2 V2",
            calibrationDate = "---",
            workshopName = "Ожидание данных тахографа..."
        )
    )
    val deviceInfo: StateFlow<TachographDeviceInfo> = _deviceInfo.asStateFlow()

    private val _events = MutableStateFlow<List<TachographEvent>>(emptyList())
    val events: StateFlow<List<TachographEvent>> = _events.asStateFlow()

    private val _timelineSegments = MutableStateFlow<List<ActivityTimelineSegment>>(emptyList())
    val timelineSegments: StateFlow<List<ActivityTimelineSegment>> = _timelineSegments.asStateFlow()

    private val _toastMessages = MutableSharedFlow<String>()
    val toastMessages: SharedFlow<String> = _toastMessages.asSharedFlow()

    // Real Stream Monitoring Metrics
    private val _streamBytesReceived = MutableStateFlow(0L)
    val streamBytesReceived: StateFlow<Long> = _streamBytesReceived.asStateFlow()

    private val _streamPacketsReceived = MutableStateFlow(0L)
    val streamPacketsReceived: StateFlow<Long> = _streamPacketsReceived.asStateFlow()

    private val _lastRawPacket = MutableStateFlow("Ожидание первого кадра...")
    val lastRawPacket: StateFlow<String> = _lastRawPacket.asStateFlow()

    private val _rawTerminalLogs = MutableStateFlow<List<String>>(
        listOf("[SYS] Bluetooth подсистема готова к приему потока тахографа ЕС")
    )
    val rawTerminalLogs: StateFlow<List<String>> = _rawTerminalLogs.asStateFlow()

    private val _activeProtocolName = MutableStateFlow("Инициализация соединения...")
    val activeProtocolName: StateFlow<String> = _activeProtocolName.asStateFlow()

    private val _isRealDataActive = MutableStateFlow(false)
    val isRealDataActive: StateFlow<Boolean> = _isRealDataActive.asStateFlow()

    // Hardware handles
    private var activeSocket: BluetoothSocket? = null
    private var activeOutputStream: OutputStream? = null
    private var activeGatt: BluetoothGatt? = null
    private var bleTxCharacteristic: BluetoothGattCharacteristic? = null
    private var socketReaderJob: Job? = null
    private var periodicQueryJob: Job? = null
    private var realTimeClockJob: Job? = null
    private var simulatorJob: Job? = null

    // Broadcast receiver for Bluetooth Classic discovery
    private var isReceiverRegistered = false
    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, (-70).toShort()).toInt()
                    val rawName = intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: device?.name

                    if (device != null) {
                        val devName = rawName ?: "Bluetooth устройство"
                        val vendor = identifyTachographVendor(devName)
                        val isTacho = vendor != null

                        addOrUpdateDevice(
                            FoundBtDevice(
                                name = devName,
                                address = device.address,
                                isPaired = device.bondState == BluetoothDevice.BOND_BONDED,
                                bondState = device.bondState,
                                isLikelyTachograph = isTacho,
                                tachographVendor = vendor ?: "Устройство Bluetooth",
                                rssi = rssi,
                                isBle = false,
                                device = device
                            )
                        )
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    if (device != null) {
                        updateDeviceBondState(device.address, bondState)
                        if (bondState == BluetoothDevice.BOND_BONDED) {
                            logTerminal("[BOND] Устройство ${device.name ?: device.address} успешно сопряжено!")
                            scope.launch {
                                _toastMessages.emit("Сопряжение успешно выполнено!")
                            }
                        }
                    }
                }
                BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val pairingVariant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                    val devName = device?.name ?: device?.address ?: "Тахограф"
                    logTerminal("[PAIR] Системный запрос PIN для $devName (вариант $pairingVariant)")
                    try {
                        device?.setPin("0000".toByteArray(Charsets.UTF_8))
                        logTerminal("[PAIR] Автоматически передан PIN 0000")
                    } catch (e: Exception) {
                        try {
                            device?.setPin("1234".toByteArray(Charsets.UTF_8))
                            logTerminal("[PAIR] Автоматически передан PIN 1234")
                        } catch (_: Exception) {}
                    }
                    scope.launch {
                        _toastMessages.emit("Запрос PIN для $devName: введите 0000 или 1234")
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    _connectionState.value = ConnectionState.SCANNING
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (_connectionState.value == ConnectionState.SCANNING) {
                        _connectionState.value = ConnectionState.DISCONNECTED
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    val isEnabled = state == BluetoothAdapter.STATE_ON
                    _isBluetoothEnabled.value = isEnabled
                    if (!isEnabled) {
                        cancelDiscovery()
                        if (_connectionState.value == ConnectionState.CONNECTED) {
                            disconnect()
                        }
                    }
                }
            }
        }
    }

    // BLE scan callback
    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let {
                val dev = it.device
                val name = dev.name ?: it.scanRecord?.deviceName ?: "BLE Тахограф"
                val vendor = identifyTachographVendor(name)
                val isTacho = vendor != null || it.scanRecord?.serviceUuids?.any { uuid ->
                    uuid.uuid == BLE_UART_SERVICE
                } == true

                addOrUpdateDevice(
                    FoundBtDevice(
                        name = name,
                        address = dev.address,
                        isPaired = dev.bondState == BluetoothDevice.BOND_BONDED,
                        bondState = dev.bondState,
                        isLikelyTachograph = isTacho,
                        tachographVendor = vendor ?: if (isTacho) "BLE Тахограф ЕС" else "BLE Устройство",
                        rssi = it.rssi,
                        isBle = true,
                        device = dev
                    )
                )
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed with code: $errorCode")
        }
    }

    init {
        registerBroadcastReceiver()
    }

    private fun registerBroadcastReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            try {
                context.registerReceiver(discoveryReceiver, filter)
                isReceiverRegistered = true
            } catch (e: Exception) {
                Log.e(TAG, "registerReceiver error: ${e.message}")
            }
        }
    }

    private fun addOrUpdateDevice(device: FoundBtDevice) {
        val currentList = _discoveredDevices.value.toMutableList()
        val index = currentList.indexOfFirst { it.address.equals(device.address, ignoreCase = true) }
        if (index >= 0) {
            val existing = currentList[index]
            currentList[index] = existing.copy(
                name = if (device.name.isNotBlank() && !device.name.startsWith("BLE ")) device.name else existing.name,
                isPaired = existing.isPaired || device.isPaired,
                bondState = if (device.bondState != BluetoothDevice.BOND_NONE) device.bondState else existing.bondState,
                isLikelyTachograph = existing.isLikelyTachograph || device.isLikelyTachograph,
                tachographVendor = if (device.isLikelyTachograph && !device.tachographVendor.startsWith("BLE")) device.tachographVendor else existing.tachographVendor,
                rssi = if (device.rssi != 0) device.rssi else existing.rssi,
                supportsSpp = existing.supportsSpp || device.supportsSpp,
                supportsBle = existing.supportsBle || device.supportsBle,
                device = device.device ?: existing.device
            )
        } else {
            if (device.isLikelyTachograph || device.isPaired) {
                currentList.add(0, device)
            } else {
                currentList.add(device)
            }
        }
        _discoveredDevices.value = currentList
    }

    private fun updateDeviceBondState(address: String, bondState: Int) {
        val currentList = _discoveredDevices.value.map { dev ->
            if (dev.address == address) {
                dev.copy(
                    bondState = bondState,
                    isPaired = bondState == BluetoothDevice.BOND_BONDED
                )
            } else dev
        }
        _discoveredDevices.value = currentList
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        registerBroadcastReceiver()
        val adapter = bluetoothAdapter

        if (adapter == null || !adapter.isEnabled) {
            scope.launch {
                _toastMessages.emit("Включите Bluetooth для сканирования тахографов")
            }
            return
        }

        _connectionState.value = ConnectionState.SCANNING
        val initialList = mutableListOf<FoundBtDevice>()

        // 1. Bonded / paired physical devices from Android system
        try {
            val bonded = adapter.bondedDevices
            bonded?.forEach { device ->
                val name = device.name ?: "Сопряженный адаптер"
                val vendor = identifyTachographVendor(name)
                initialList.add(
                    FoundBtDevice(
                        name = name,
                        address = device.address,
                        isPaired = true,
                        bondState = BluetoothDevice.BOND_BONDED,
                        isLikelyTachograph = vendor != null,
                        tachographVendor = vendor ?: "Сопряженное устройство",
                        rssi = -55,
                        isBle = false,
                        device = device
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching bonded devices: ${e.message}")
        }

        _discoveredDevices.value = initialList

        try {
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
            adapter.startDiscovery()
        } catch (e: Exception) {
            Log.e(TAG, "Could not start classic discovery: ${e.message}")
        }

        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            bleScanner?.startScan(null, settings, bleScanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Could not start BLE scan: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun cancelDiscovery() {
        try {
            bluetoothAdapter?.cancelDiscovery()
        } catch (e: Exception) {
            Log.e(TAG, "cancelDiscovery: ${e.message}")
        }
        try {
            bleScanner?.stopScan(bleScanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "stopScan: ${e.message}")
        }
        if (_connectionState.value == ConnectionState.SCANNING) {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    @SuppressLint("MissingPermission")
    fun pairDevice(device: FoundBtDevice) {
        val realDevice = device.device ?: try {
            bluetoothAdapter?.getRemoteDevice(device.address)
        } catch (e: Exception) {
            null
        }

        if (realDevice != null) {
            try {
                logTerminal("[PAIR] Инициализация сопряжения с ${device.name} (${device.address})...")
                val initiated = realDevice.createBond()
                scope.launch {
                    if (initiated) {
                        _toastMessages.emit("Запрос сопряжения отправлен. Введите PIN при появлении окна системы.")
                        logTerminal("[PAIR] Запрос PIN отправлен на экран системы")
                    } else {
                        _toastMessages.emit("Не удалось начать сопряжение. Проверьте настройки Bluetooth.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "pairDevice error: ${e.message}")
                logTerminal("[ERR] Ошибка сопряжения: ${e.message}")
            }
        } else {
            scope.launch {
                _toastMessages.emit("Устройство не найдено")
            }
        }
    }

    /**
     * Connect to a selected Bluetooth device.
     */
    @SuppressLint("MissingPermission")
    fun connectToDevice(foundDevice: FoundBtDevice, preferredTransport: ConnectTransport = ConnectTransport.AUTO) {
        cancelDiscovery()
        scope.launch {
            stopSimulator()
            disconnect()

            _lastErrorMessage.value = null
            _connectionState.value = ConnectionState.CONNECTING
            _connectedDeviceName.value = foundDevice.name
            logTerminal("[SYS] Попытка подключения к ${foundDevice.name} (${foundDevice.address}) [Режим: ${preferredTransport.title}]")

            val realDevice = foundDevice.device ?: try {
                bluetoothAdapter?.getRemoteDevice(foundDevice.address)
            } catch (e: Exception) {
                null
            }

            if (realDevice == null) {
                // If user selected demo device without hardware
                delay(800)
                _connectionState.value = ConnectionState.DEMO_MODE
                _activeProtocolName.value = "Симулятор VDO DTCO 4.1"
                _toastMessages.emit("Подключено к ${foundDevice.name} (Режим эмуляции)")
                logTerminal("[SYS] Запущен симулятор для проверки интерфейса")
                startSimulator(deviceLabel = foundDevice.name)
                return@launch
            }

            // Settle radio before connecting (crucial on Android RFCOMM)
            delay(350)

            // Reset live telemetry for clean incoming tachograph stream
            resetLiveTelemetryForRealConnection()

            when (preferredTransport) {
                ConnectTransport.SPP_RFCOMM -> {
                    connectClassicSppDevice(realDevice, foundDevice.name, allowBleFallback = false)
                }
                ConnectTransport.BLE_GATT -> {
                    connectBleDevice(realDevice, foundDevice.name, allowSppFallback = false)
                }
                ConnectTransport.AUTO -> {
                    // For tachographs (VDO, Stoneridge, DTCO, WGM, ELM, OBD) or paired devices, prioritize SPP RFCOMM
                    val isTachoOrPaired = foundDevice.isLikelyTachograph || foundDevice.isPaired ||
                            foundDevice.name.contains("DTCO", ignoreCase = true) ||
                            foundDevice.name.contains("WGM", ignoreCase = true) ||
                            foundDevice.name.contains("VDO", ignoreCase = true) ||
                            foundDevice.name.contains("Stoneridge", ignoreCase = true)

                    if (isTachoOrPaired || !foundDevice.isBle || foundDevice.supportsSpp) {
                        connectClassicSppDevice(realDevice, foundDevice.name, allowBleFallback = true)
                    } else {
                        connectBleDevice(realDevice, foundDevice.name, allowSppFallback = true)
                    }
                }
            }
        }
    }

    private fun resetLiveTelemetryForRealConnection() {
        _isRealDataActive.value = false
        _streamBytesReceived.value = 0L
        _streamPacketsReceived.value = 0L
        _lastRawPacket.value = "Соединение установлено. Запуск опроса тахографа..."
        _activeProtocolName.value = "Опрос параметров тахографа..."
        _telemetry.value = TachographLiveTelemetry(
            speedKmh = 0,
            engineRpm = 0,
            totalOdometerKm = 0,
            tripOdometerKm = 0.0,
            utcTime = "--:--:-- UTC",
            localTime = "Опрос тахографа..."
        )
        _compliance.value = WorkRestCompliance(
            continuousDrivingSeconds = 0,
            accumulatedBreakSeconds = 0,
            dailyDrivingSeconds = 0,
            shiftDurationSeconds = 0,
            weeklyDrivingSeconds = 0,
            biweeklyDrivingSeconds = 0
        )
        _drivingTimeSeconds.value = 0L
        _restTimeSeconds.value = 0L
        _availabilityTimeSeconds.value = 0L
        _workTimeSeconds.value = 0L
        _currentModeDurationSeconds.value = 0L
        _driverCard1.value = DriverCardInfo(
            slotNumber = 1,
            isInserted = false,
            driverName = "Ожидание карты водителя...",
            cardNumber = "---",
            issuingCountry = "EU",
            issuingAuthority = "---",
            expiryDate = "---"
        )
    }

    @SuppressLint("MissingPermission")
    suspend fun connectClassicSppDevice(
        device: BluetoothDevice,
        name: String,
        allowBleFallback: Boolean = true
    ) {
        withContext(Dispatchers.IO) {
            // Cancel discovery before connecting for stable RFCOMM throughput
            try {
                bluetoothAdapter?.cancelDiscovery()
            } catch (e: Exception) {
                Log.w(TAG, "cancelDiscovery error: ${e.message}")
            }
            delay(300)

            var socket: BluetoothSocket? = null
            var lastEx: Exception? = null

            // If device is not paired, warn user about PIN code
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                logTerminal("[WARN] Устройство не сопряжено в системе. Если появится запрос, введите PIN тахографа (0000 или 1234).")
            }

            // Strategy 0: Cached / Advertised SDP UUIDs
            val deviceUuids = try { device.uuids?.map { it.uuid } } catch (_: Exception) { null }
            if (!deviceUuids.isNullOrEmpty()) {
                for (uuid in deviceUuids) {
                    if (socket != null) break
                    var s: BluetoothSocket? = null
                    try {
                        logTerminal("[SPP] Попытка по SDP UUID ($uuid)...")
                        s = device.createInsecureRfcommSocketToServiceRecord(uuid)
                        s.connect()
                        socket = s
                        logTerminal("[SPP] RFCOMM сокет по SDP UUID успешно подключен!")
                    } catch (e: Exception) {
                        lastEx = e
                        try { s?.close() } catch (_: Exception) {}
                        delay(350)
                    }
                }
            }

            // Strategy 1: Standard Secure RFCOMM SPP socket
            if (socket == null) {
                var s: BluetoothSocket? = null
                try {
                    logTerminal("[SPP] Попытка 1: Стандартный SPP RFCOMM (UUID: $SPP_UUID)")
                    s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                    s.connect()
                    socket = s
                    logTerminal("[SPP] Стандартный RFCOMM сокет успешно подключен!")
                } catch (e: Exception) {
                    lastEx = e
                    Log.w(TAG, "Standard SPP secure failed: ${e.message}")
                    try { s?.close() } catch (_: Exception) {}
                    delay(350)
                    logTerminal("[SPP] Попытка 1 не удалась: ${e.message}")
                }
            }

            // Strategy 2: Insecure RFCOMM SPP socket (avoids encryption handshake errors on diagnostic dongles)
            if (socket == null) {
                var s: BluetoothSocket? = null
                try {
                    logTerminal("[SPP] Попытка 2: Незащищенный Insecure RFCOMM сокет...")
                    s = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                    s.connect()
                    socket = s
                    logTerminal("[SPP] Insecure RFCOMM сокет подключен успешно!")
                } catch (e: Exception) {
                    lastEx = e
                    Log.w(TAG, "Insecure SPP failed: ${e.message}")
                    try { s?.close() } catch (_: Exception) {}
                    delay(350)
                    logTerminal("[SPP] Попытка 2 не удалась: ${e.message}")
                }
            }

            // Strategy 3: Direct RFCOMM Channel 1 via hidden API reflection
            if (socket == null) {
                var s: BluetoothSocket? = null
                try {
                    logTerminal("[SPP] Попытка 3: Прямой RFCOMM канал 1 (диагностический порт)...")
                    val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    s = m.invoke(device, 1) as BluetoothSocket
                    s.connect()
                    socket = s
                    logTerminal("[SPP] RFCOMM Канал 1 подключен успешно!")
                } catch (e: Exception) {
                    lastEx = e
                    Log.w(TAG, "Channel 1 reflection failed: ${e.message}")
                    try { s?.close() } catch (_: Exception) {}
                    delay(350)
                    logTerminal("[SPP] Попытка 3 не удалась: ${e.message}")
                }
            }

            // Strategy 4: Insecure RFCOMM Channel 1 via hidden API reflection
            if (socket == null) {
                var s: BluetoothSocket? = null
                try {
                    logTerminal("[SPP] Попытка 4: Прямой Insecure RFCOMM канал 1...")
                    val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                    s = m.invoke(device, 1) as BluetoothSocket
                    s.connect()
                    socket = s
                    logTerminal("[SPP] Insecure Канал 1 подключен успешно!")
                } catch (e: Exception) {
                    lastEx = e
                    Log.w(TAG, "Insecure Channel 1 reflection failed: ${e.message}")
                    try { s?.close() } catch (_: Exception) {}
                    delay(350)
                    logTerminal("[SPP] Попытка 4 не удалась: ${e.message}")
                }
            }

            // Strategy 5: Direct RFCOMM Channel 2 fallback
            if (socket == null) {
                var s: BluetoothSocket? = null
                try {
                    logTerminal("[SPP] Попытка 5: Прямой RFCOMM канал 2...")
                    val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    s = m.invoke(device, 2) as BluetoothSocket
                    s.connect()
                    socket = s
                    logTerminal("[SPP] RFCOMM Канал 2 подключен успешно!")
                } catch (e: Exception) {
                    lastEx = e
                    Log.w(TAG, "Channel 2 reflection failed: ${e.message}")
                    try { s?.close() } catch (_: Exception) {}
                    delay(350)
                }
            }

            // Strategy 6: Direct RFCOMM Channel 3 fallback
            if (socket == null) {
                var s: BluetoothSocket? = null
                try {
                    val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    s = m.invoke(device, 3) as BluetoothSocket
                    s.connect()
                    socket = s
                    logTerminal("[SPP] RFCOMM Канал 3 подключен успешно!")
                } catch (e: Exception) {
                    lastEx = e
                    try { s?.close() } catch (_: Exception) {}
                    delay(350)
                }
            }

            if (socket != null) {
                activeSocket = socket
                activeOutputStream = socket.outputStream

                withContext(Dispatchers.Main) {
                    _connectionState.value = ConnectionState.CONNECTED
                    _activeProtocolName.value = "SPP RFCOMM (Поток открыт)"
                    _toastMessages.emit("Подключено к $name! Запрос данных...")
                    logTerminal("[SPP] Связь установлена! Запуск опроса тахографа...")
                }

                sendActiveTachographHandshake()
                startPeriodicPollingJob()
                startRealTimeDurationTickJob()
                startSocketByteStreamReader(socket.inputStream)
            } else if (allowBleFallback) {
                logTerminal("[SYS] RFCOMM порты не ответили. Проверка BLE GATT канала...")
                withContext(Dispatchers.Main) {
                    connectBleDevice(device, name, allowSppFallback = false)
                }
            } else {
                val errReason = lastEx?.message ?: "Не удалось установить соединение по радиоканалу"
                val helpfulAdvice = if (device.bondState != BluetoothDevice.BOND_BONDED) {
                    "Устройство не сопряжено! Нажмите «Сопрячь» в списке или выполните сопряжение в настройках Android (PIN обычно 0000, 1234 или PIN адаптера)."
                } else {
                    "Проверьте, включено ли зажигание и не занят ли тахограф другим приложением."
                }

                withContext(Dispatchers.Main) {
                    _connectionState.value = ConnectionState.ERROR
                    _lastErrorMessage.value = "$errReason\n$helpfulAdvice"
                    _toastMessages.emit("Ошибка подключения: $errReason")
                    logTerminal("[ERR] Ошибка подключения: $errReason")
                    logTerminal("[HINT] $helpfulAdvice")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectBleDevice(
        device: BluetoothDevice,
        name: String,
        allowSppFallback: Boolean = true
    ) {
        logTerminal("[BLE] Запуск GATT соединения с $name...")
        _connectionState.value = ConnectionState.CONNECTING

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    _connectionState.value = ConnectionState.CONNECTED
                    _activeProtocolName.value = "BLE GATT (Поиск сервисов...)"
                    logTerminal("[BLE] GATT подключен, поиск характеристик...")
                    gatt?.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    try {
                        gatt?.close()
                    } catch (_: Exception) {}
                    activeGatt = null

                    if (allowSppFallback) {
                        logTerminal("[BLE] GATT соединение не удалось (статус: $status). Переход на Classic SPP RFCOMM...")
                        scope.launch {
                            connectClassicSppDevice(device, name, allowBleFallback = false)
                        }
                    } else {
                        _connectionState.value = ConnectionState.ERROR
                        _lastErrorMessage.value = "GATT соединение разорвано (код $status)"
                        logTerminal("[BLE] GATT соединение закрыто (статус: $status)")
                        scope.launch {
                            _toastMessages.emit("Ошибка связи BLE (статус $status)")
                        }
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                    logTerminal("[BLE] Сервисы обнаружены! Поиск UART / Tacho каналов...")
                    var rxChar: BluetoothGattCharacteristic? = null
                    var txChar: BluetoothGattCharacteristic? = null

                    val uartService = gatt.getService(BLE_UART_SERVICE)
                    if (uartService != null) {
                        rxChar = uartService.getCharacteristic(BLE_UART_RX_CHAR)
                        txChar = uartService.getCharacteristic(BLE_UART_TX_CHAR)
                    } else {
                        // Scan all services for notify/indicate and write characteristics
                        for (service in gatt.services) {
                            for (c in service.characteristics) {
                                val props = c.properties
                                if ((props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                                    (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                                    rxChar = c
                                }
                                if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                                    (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                                    txChar = c
                                }
                            }
                        }
                    }

                    bleTxCharacteristic = txChar

                    if (rxChar != null) {
                        gatt.setCharacteristicNotification(rxChar, true)
                        val descriptor = rxChar.getDescriptor(CLIENT_CHAR_CONFIG)
                        descriptor?.let {
                            it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(it)
                        }
                        _activeProtocolName.value = "BLE Поток активен"
                        logTerminal("[BLE] Подписка на поток данных активирована!")
                        sendActiveTachographHandshake()
                        startRealTimeDurationTickJob()
                        startPeriodicPollingJob()
                    } else {
                        logTerminal("[BLE] Подходящие характеристики не найдены. Попытка отправки команд...")
                        sendActiveTachographHandshake()
                    }
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
                val data = characteristic?.value ?: return
                handleIncomingBytes(data)
            }
        }

        activeGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    private fun startSocketByteStreamReader(inputStream: InputStream) {
        socketReaderJob?.cancel()
        socketReaderJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(2048)
            logTerminal("[STREAM] Поток запущен. Ожидание данных от тахографа...")
            try {
                while (isActive) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead <= 0) break
                    val chunk = buffer.copyOf(bytesRead)
                    handleIncomingBytes(chunk)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Socket reader loop error: ${e.message}")
                withContext(Dispatchers.Main) {
                    if (_connectionState.value == ConnectionState.CONNECTED) {
                        disconnect()
                        _toastMessages.emit("Связь с тахографом прервана")
                        logTerminal("[ERR] Разрыв соединения: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Universal Incoming Byte Stream Parser:
     * - Parses both ASCII & UTF-8 / Latin-1 lines (without stripping European characters!)
     * - Splits on newline (\n, \r) AND prompt (>) for ELM327 / truck diagnostic dongles!
     * - Parses VDO SmartLink, Stoneridge Tacho Link, Intellic EFAS, J1939 CAN (TCO1, TDM, VDHR), OBD-II, KWP2000
     */
    private val rawStringBuilder = StringBuilder()

    private fun handleIncomingBytes(bytes: ByteArray) {
        _streamBytesReceived.value += bytes.size
        _isRealDataActive.value = true

        // 1. Check raw binary packet signatures (J1939 CAN / KWP2000 / VDO Binary)
        parseBinaryTachographPacket(bytes)

        // 2. Decode text stream supporting Latin-1 & UTF-8
        val decodedChunk = String(bytes, Charsets.UTF_8)
        rawStringBuilder.append(decodedChunk)

        // Process line by line and also handle prompt '>' delimiter from ELM327/OBD dongles
        while (rawStringBuilder.isNotEmpty()) {
            val delimiterIndex = rawStringBuilder.indexOfAny(charArrayOf('\n', '\r', '>'))
            if (delimiterIndex >= 0) {
                val line = rawStringBuilder.substring(0, delimiterIndex).trim()
                rawStringBuilder.delete(0, delimiterIndex + 1)
                if (line.isNotEmpty()) {
                    _streamPacketsReceived.value += 1
                    _lastRawPacket.value = line
                    logTerminal("[RX] $line")
                    parseIncomingLine(line)
                }
            } else {
                // If buffer is getting too large without delimiters, parse anyway
                if (rawStringBuilder.length > 256) {
                    val line = rawStringBuilder.toString().trim()
                    rawStringBuilder.clear()
                    if (line.isNotEmpty()) {
                        _streamPacketsReceived.value += 1
                        _lastRawPacket.value = line
                        logTerminal("[RX] $line")
                        parseIncomingLine(line)
                    }
                }
                break
            }
        }
    }

    private fun parseIncomingLine(line: String) {
        val upper = line.uppercase()

        // 1. J1939 CAN Frame representations:
        // Tachograph TCO1 (PGN 65260 / 0xFEEC)
        if (upper.contains("FEEC") || upper.contains("18FEEC")) {
            _activeProtocolName.value = "SAE J1939 (Тахограф TCO1)"
            parseJ1939Tco1Hex(line)
            return
        }

        // Driver 1 Identification (PGN 65227 / 0xFEAB) or (PGN 65261 / 0xFEED)
        if (upper.contains("FEAB") || upper.contains("18FEAB") || upper.contains("FEED") || upper.contains("18FEED")) {
            _activeProtocolName.value = "SAE J1939 (Карта водителя TDM)"
            parseJ1939DriverCardHex(line)
            return
        }

        // Vehicle Distance / Odometer (PGN 65248 / 0xFEE0)
        if (upper.contains("FEE0") || upper.contains("18FEE0")) {
            parseJ1939DistanceHex(line)
            return
        }

        // Speed CCVS (PGN 65265 / 0xFEF1)
        if (upper.contains("FEF1") || upper.contains("CCVS")) {
            parseJ1939CcvsHex(line)
            return
        }

        // 2. OBD-II / ELM327 Responses:
        // Speed PID 0D (e.g. "41 0D 52" -> 82 km/h)
        if (upper.startsWith("41 0D") || upper.contains(" 41 0D ")) {
            _activeProtocolName.value = "OBD-II Telematics PID 0D"
            val parts = upper.substringAfter("41 0D").trim().split(" ")
            if (parts.isNotEmpty()) {
                val spd = parts[0].toIntOrNull(16) ?: 0
                updateSpeedFromStream(spd)
            }
            return
        }

        // Engine RPM PID 0C (e.g. "41 0C 1A F8")
        if (upper.startsWith("41 0C") || upper.contains(" 41 0C ")) {
            val parts = upper.substringAfter("41 0C").trim().split(" ")
            if (parts.size >= 2) {
                val a = parts[0].toIntOrNull(16) ?: 0
                val b = parts[1].toIntOrNull(16) ?: 0
                val rpm = ((a * 256) + b) / 4
                _telemetry.value = _telemetry.value.copy(engineRpm = rpm)
            }
            return
        }

        // Total Distance PID 31 / A6
        if (upper.startsWith("41 31") || upper.contains(" 41 31 ")) {
            val parts = upper.substringAfter("41 31").trim().split(" ")
            if (parts.size >= 2) {
                val a = parts[0].toIntOrNull(16) ?: 0
                val b = parts[1].toIntOrNull(16) ?: 0
                val dist = (a * 256) + b
                _telemetry.value = _telemetry.value.copy(totalOdometerKm = dist.toLong())
            }
            return
        }

        // VIN Service 09 PID 02
        if (upper.startsWith("49 02") || upper.contains(" 49 02 ")) {
            parseVinFromHex(line)
            return
        }

        // 3. KWP2000 Diagnostic Responses (62 FD E0 / 62 00 01 / 62 F1 90)
        if (upper.contains("62 FD E0") || upper.contains("62FDE0")) {
            _activeProtocolName.value = "KWP2000 (Статус водителя)"
            parseKwp2000DriverState(line)
            return
        }
        if (upper.contains("62 00 01") || upper.contains("620001")) {
            _activeProtocolName.value = "KWP2000 (Номер карты)"
            parseKwp2000CardId(line)
            return
        }

        // 4. VDO SmartLink / Stoneridge Tacho Link / Telematics ASCII protocol
        parseAsciiTachographLine(line)
    }

    private fun parseAsciiTachographLine(line: String) {
        val upper = line.uppercase()

        // Detect VDO SmartLink greeting / response
        if (upper.contains("DTCO") || upper.contains("SMARTLINK") || upper.contains("VDO")) {
            _activeProtocolName.value = "Continental VDO SmartLink"
            if (upper.contains("4.1") || upper.contains("SMART 2") || upper.contains("ANNEX 1C")) {
                _deviceInfo.value = _deviceInfo.value.copy(
                    model = "Continental VDO DTCO 4.1 (Smart Tacho 2)",
                    softwareVersion = "v4.1.0 Annex 1C Gen2 V2"
                )
            }
        }

        // Detect Stoneridge greeting
        if (upper.contains("STONERIDGE") || upper.contains("SE5000") || upper.contains("TACHO LINK")) {
            _activeProtocolName.value = "Stoneridge Tacho Link"
            _deviceInfo.value = _deviceInfo.value.copy(
                model = "Stoneridge SE5000 Smart 2",
                softwareVersion = "Rev 8.0 Annex 1C Gen2 V2"
            )
        }

        // Driver Activity
        when {
            upper.contains("ACT=D") || upper.contains("MODE=DRIVE") || upper.contains("ACT:DRIVE") ||
                    upper.contains("ACT=3") || upper.contains("MODE=3") || upper.contains("STATUS=DRIVING") ||
                    upper.contains("JAZDA") || upper.contains("FAHREN") -> {
                setActivityFromStream(DriverActivity.DRIVING)
            }
            upper.contains("ACT=R") || upper.contains("MODE=REST") || upper.contains("ACT:REST") ||
                    upper.contains("ACT=0") || upper.contains("MODE=0") || upper.contains("PAUSE") ||
                    upper.contains("BREAK") || upper.contains("OTDYH") || upper.contains("RUHE") ||
                    upper.contains("REPOS") -> {
                setActivityFromStream(DriverActivity.REST)
            }
            upper.contains("ACT=W") || upper.contains("MODE=WORK") || upper.contains("ACT:WORK") ||
                    upper.contains("ACT=2") || upper.contains("MODE=2") || upper.contains("OTHER_WORK") ||
                    upper.contains("PRACA") || upper.contains("ARBEIT") || upper.contains("TRAVAIL") -> {
                setActivityFromStream(DriverActivity.WORK)
            }
            upper.contains("ACT=A") || upper.contains("MODE=AVAIL") || upper.contains("ACT:AVAIL") ||
                    upper.contains("ACT=1") || upper.contains("MODE=1") || upper.contains("READY") ||
                    upper.contains("GOTOWOSC") || upper.contains("DISPO") || upper.contains("BEREITSCHAFT") -> {
                setActivityFromStream(DriverActivity.AVAILABLE)
            }
        }

        // Driver Card Number & Name
        // Examples: "CARD1: DED00000849201 0", "CARD=DED00000849201 0", "D1=POL1234567890123"
        val cardRegex = Regex("""(?:CARD1|CARD|D1|KARTE|KARTA|ID)[:=\s]+([A-Z0-9\s]{10,20})""", RegexOption.IGNORE_CASE)
        val cardMatch = cardRegex.find(line)
        if (cardMatch != null) {
            val cardRaw = cardMatch.groupValues[1].trim()
            if (cardRaw.isNotEmpty() && cardRaw != "NONE" && cardRaw != "0" && cardRaw != "---") {
                val nation = extractCountryFromCard(cardRaw)
                _driverCard1.value = _driverCard1.value.copy(
                    isInserted = true,
                    cardNumber = cardRaw,
                    issuingCountry = nation,
                    driverName = if (_driverCard1.value.driverName.startsWith("Ожидание")) "ВОДИТЕЛЬ (КАРТА $cardRaw)" else _driverCard1.value.driverName
                )
                logTerminal("[CARD] Карта водителя обнаружена в слоте 1: $cardRaw ($nation)")
            }
        } else {
            // Check standalone token matching EU driver card format (e.g. DED0000084920100, POL1234567890123)
            val euCandidate = line.split(' ', ',', ';', ':', '=', '\t').map { it.trim() }.firstOrNull { token ->
                token.length in 13..18 && token.matches(Regex("""[A-Z]{1,3}[0-9A-Z]{11,16}"""))
            }
            if (euCandidate != null && !_driverCard1.value.isInserted) {
                val nation = extractCountryFromCard(euCandidate)
                _driverCard1.value = _driverCard1.value.copy(
                    isInserted = true,
                    cardNumber = euCandidate,
                    issuingCountry = nation,
                    driverName = if (_driverCard1.value.driverName.startsWith("Ожидание")) "ВОДИТЕЛЬ (КАРТА $euCandidate)" else _driverCard1.value.driverName
                )
                logTerminal("[CARD] Обнаружен номер карты ЕС: $euCandidate ($nation)")
            }
        }

        // Driver Name
        // Examples: "NAME=SCHMIDT HANS-PETER", "DRIVER1: KOWALSKI JAN", "FAHRER: MUELLER HANS"
        val nameRegex = Regex("""(?:NAME|DRIVER1|DRIVER|FAHRER|KIEROWCA|CONDUCTEUR)[:=\s]+([A-Za-zА-Яа-яЁё\s\-\.\,\'\u00C0-\u017F]{3,40})""", RegexOption.IGNORE_CASE)
        val nameMatch = nameRegex.find(line)
        if (nameMatch != null) {
            val nameRaw = nameMatch.groupValues[1].trim().trimEnd(',', ';')
            if (nameRaw.isNotEmpty() && !nameRaw.contains("UNKNOWN", ignoreCase = true)) {
                _driverCard1.value = _driverCard1.value.copy(
                    isInserted = true,
                    driverName = nameRaw
                )
                logTerminal("[DRIVER] Данные водителя: $nameRaw")
            }
        }

        // Speed
        when {
            upper.contains("SPD=") || upper.contains("SPEED=") || upper.contains("V=") || upper.contains("SPEED:") -> {
                val token = line.split(',', ';', ' ', '&').firstOrNull {
                    val u = it.uppercase()
                    u.startsWith("SPD=") || u.startsWith("SPEED=") || u.startsWith("V=") || u.startsWith("SPEED:")
                }
                token?.split('=', ':')?.getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull()?.let {
                    updateSpeedFromStream(it)
                }
            }
            upper.contains("KM/H") -> {
                val num = upper.substringBefore("KM/H").trim().takeLastWhile { it.isDigit() }.toIntOrNull()
                num?.let { updateSpeedFromStream(it) }
            }
        }

        // Odometer
        if (upper.contains("ODO=") || upper.contains("KM=") || upper.contains("ODO:") || upper.contains("ODOMETER:")) {
            val token = line.split(',', ';', ' ', '&').firstOrNull {
                val u = it.uppercase()
                u.startsWith("ODO=") || u.startsWith("KM=") || u.startsWith("ODO:") || u.startsWith("ODOMETER:")
            }
            token?.split('=', ':')?.getOrNull(1)?.filter { it.isDigit() }?.toLongOrNull()?.let {
                _telemetry.value = _telemetry.value.copy(totalOdometerKm = it)
            }
        }

        // Timers & Regulation 561/2006 compliance
        parseTimersFromText(line)

        // Vehicle Plate & VIN
        if (upper.contains("VIN=") || upper.contains("VIN:")) {
            val afterVin = if (upper.contains("VIN=")) line.substringAfter("VIN=") else line.substringAfter("VIN:")
            val vinVal = afterVin.substringBefore(',').substringBefore(';').substringBefore(' ').trim()
            if (vinVal.length >= 8) {
                _deviceInfo.value = _deviceInfo.value.copy(vin = vinVal)
            }
        }
        if (upper.contains("VRN=") || upper.contains("PLATE=") || upper.contains("REG=")) {
            val regVal = line.split(',', ';', ' ').firstOrNull {
                val u = it.uppercase()
                u.startsWith("VRN=") || u.startsWith("PLATE=") || u.startsWith("REG=")
            }?.split('=')?.getOrNull(1)?.trim()
            if (!regVal.isNullOrEmpty()) {
                _deviceInfo.value = _deviceInfo.value.copy(regNumber = regVal)
            }
        }
    }

    private fun parseTimersFromText(line: String) {
        var comp = _compliance.value
        var updated = false

        // Continuous Driving
        val cdVal = extractTimeSeconds(line, listOf("CD=", "CONT=", "CONT_DRIVE=", "DRIVE_CONT=", "CD:"))
        if (cdVal != null) {
            comp = comp.copy(continuousDrivingSeconds = cdVal)
            _drivingTimeSeconds.value = cdVal
            updated = true
        }

        // Accumulated Break
        val bdVal = extractTimeSeconds(line, listOf("BD=", "BREAK=", "ACC_BREAK=", "BREAK_ACC=", "BD:"))
        if (bdVal != null) {
            comp = comp.copy(accumulatedBreakSeconds = bdVal)
            _restTimeSeconds.value = bdVal
            updated = true
        }

        // Daily Driving
        val ddVal = extractTimeSeconds(line, listOf("DD=", "DAILY=", "DAY_DRIVE=", "DRIVE_DAY=", "DD:"))
        if (ddVal != null) {
            comp = comp.copy(dailyDrivingSeconds = ddVal)
            updated = true
        }

        // Weekly Driving
        val wdVal = extractTimeSeconds(line, listOf("WD=", "WEEK=", "WEEKLY=", "DRIVE_WEEK=", "WD:"))
        if (wdVal != null) {
            comp = comp.copy(weeklyDrivingSeconds = wdVal)
            updated = true
        }

        // Biweekly Driving
        val bwdVal = extractTimeSeconds(line, listOf("2WD=", "BIWEEKLY=", "DRIVE_2WEEK=", "BWD="))
        if (bwdVal != null) {
            comp = comp.copy(biweeklyDrivingSeconds = bwdVal)
            updated = true
        }

        if (updated) {
            _compliance.value = comp
            logTerminal("[TIMERS] Время труда: Непрерывное=${comp.continuousDrivingSeconds / 60}м, Перерыв=${comp.accumulatedBreakSeconds / 60}м, Суточное=${comp.dailyDrivingSeconds / 60}м")
        }
    }

    private fun extractTimeSeconds(line: String, prefixes: List<String>): Long? {
        val upper = line.uppercase()
        for (pref in prefixes) {
            if (upper.contains(pref)) {
                val token = line.substring(upper.indexOf(pref) + pref.length).substringBefore(',').substringBefore(';').substringBefore(' ').trim()
                if (token.contains(':')) {
                    val parts = token.split(':')
                    val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
                    val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
                    val s = parts.getOrNull(2)?.toIntOrNull() ?: 0
                    return (h * 3600L) + (m * 60L) + s
                } else {
                    val sec = token.toLongOrNull()
                    if (sec != null) return sec
                }
            }
        }
        return null
    }

    private fun parseJ1939Tco1Hex(hexLine: String) {
        try {
            val tokens = hexLine.replace("18FEEC00", "").replace("FEEC", "").trim().split(" ")
                .filter { it.length == 2 && it.toIntOrNull(16) != null }

            if (tokens.isNotEmpty()) {
                val byte0 = tokens[0].toIntOrNull(16) ?: return
                val actCode = byte0 and 0x07
                val cardPresent = (byte0 and 0x18) != 0

                val act = when (actCode) {
                    3 -> DriverActivity.DRIVING
                    2 -> DriverActivity.WORK
                    1 -> DriverActivity.AVAILABLE
                    else -> DriverActivity.REST
                }
                setActivityFromStream(act)

                _driverCard1.value = _driverCard1.value.copy(
                    isInserted = cardPresent,
                    driverName = if (cardPresent && _driverCard1.value.driverName.startsWith("Ожидание")) "ВОДИТЕЛЬ (СЛОТ 1)" else _driverCard1.value.driverName
                )

                // Byte 6-7: Tacho vehicle speed
                if (tokens.size >= 8) {
                    val spdByte = tokens[6].toIntOrNull(16) ?: 0
                    if (spdByte in 0..140) {
                        updateSpeedFromStream(spdByte)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseJ1939Tco1Hex error: ${e.message}")
        }
    }

    private fun parseJ1939DriverCardHex(hexLine: String) {
        try {
            val hexTokens = hexLine.split(" ").filter { it.length == 2 && it.toIntOrNull(16) != null }
            val sb = StringBuilder()
            for (token in hexTokens) {
                val charCode = token.toInt(16)
                if (charCode in 32..126) {
                    sb.append(charCode.toChar())
                }
            }
            val text = sb.toString().trim()
            if (text.length >= 10) {
                val nation = extractCountryFromCard(text)
                _driverCard1.value = _driverCard1.value.copy(
                    isInserted = true,
                    cardNumber = text,
                    issuingCountry = nation
                )
                logTerminal("[CARD] Карта водителя из J1939: $text ($nation)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseJ1939DriverCardHex error: ${e.message}")
        }
    }

    private fun parseJ1939DistanceHex(hexLine: String) {
        try {
            val tokens = hexLine.replace("18FEE000", "").replace("FEE0", "").trim().split(" ")
                .filter { it.length == 2 && it.toIntOrNull(16) != null }
            if (tokens.size >= 4) {
                val b0 = tokens[0].toLong(16)
                val b1 = tokens[1].toLong(16)
                val b2 = tokens[2].toLong(16)
                val b3 = tokens[3].toLong(16)
                val rawOdo = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
                val km = rawOdo * 5 / 1000 // 5m per bit
                if (km > 0) {
                    _telemetry.value = _telemetry.value.copy(totalOdometerKm = km)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseJ1939DistanceHex error: ${e.message}")
        }
    }

    private fun parseJ1939CcvsHex(hexLine: String) {
        try {
            val tokens = hexLine.split(" ").filter { it.length == 2 && it.toIntOrNull(16) != null }
            if (tokens.size >= 3) {
                val b1 = tokens[1].toInt(16)
                val b2 = tokens[2].toInt(16)
                val rawSpd = b1 or (b2 shl 8)
                val kmh = rawSpd / 256
                if (kmh in 0..140) {
                    updateSpeedFromStream(kmh)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseJ1939CcvsHex error: ${e.message}")
        }
    }

    private fun parseVinFromHex(hexLine: String) {
        try {
            val tokens = hexLine.replace("49 02", "").trim().split(" ")
                .filter { it.length == 2 && it.toIntOrNull(16) != null }
            val sb = StringBuilder()
            for (token in tokens) {
                val c = token.toInt(16)
                if (c in 32..126) sb.append(c.toChar())
            }
            val vin = sb.toString().trim()
            if (vin.length >= 8) {
                _deviceInfo.value = _deviceInfo.value.copy(vin = vin)
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseVinFromHex error: ${e.message}")
        }
    }

    private fun parseKwp2000DriverState(line: String) {
        try {
            val tokens = line.replace("62 FD E0", "").replace("62FDE0", "").trim().split(" ")
                .filter { it.length == 2 && it.toIntOrNull(16) != null }
            if (tokens.isNotEmpty()) {
                val b = tokens[0].toInt(16)
                val act = when (b and 0x03) {
                    3 -> DriverActivity.DRIVING
                    2 -> DriverActivity.WORK
                    1 -> DriverActivity.AVAILABLE
                    else -> DriverActivity.REST
                }
                setActivityFromStream(act)
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseKwp2000DriverState error: ${e.message}")
        }
    }

    private fun parseKwp2000CardId(line: String) {
        try {
            val tokens = line.replace("62 00 01", "").replace("620001", "").trim().split(" ")
                .filter { it.length == 2 && it.toIntOrNull(16) != null }
            val card = tokens.mapNotNull {
                val c = it.toIntOrNull(16) ?: 0
                if (c in 32..126) c.toChar() else null
            }.joinToString("").trim()
            if (card.length >= 10) {
                val nation = extractCountryFromCard(card)
                _driverCard1.value = _driverCard1.value.copy(
                    isInserted = true,
                    cardNumber = card,
                    issuingCountry = nation
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseKwp2000CardId error: ${e.message}")
        }
    }

    private fun parseBinaryTachographPacket(bytes: ByteArray) {
        if (bytes.size < 8) return
        // Scan for 0xEC 0xFE (J1939 TCO1)
        for (i in 0 until bytes.size - 8) {
            if ((bytes[i].toInt() and 0xFF) == 0xEC && (bytes[i + 1].toInt() and 0xFF) == 0xFE) {
                _activeProtocolName.value = "SAE J1939 Бинарный TCO1"
                val b0 = bytes[i + 2].toInt() and 0xFF
                val actCode = b0 and 0x07
                val cardPresent = (b0 and 0x18) != 0
                val act = when (actCode) {
                    3 -> DriverActivity.DRIVING
                    2 -> DriverActivity.WORK
                    1 -> DriverActivity.AVAILABLE
                    else -> DriverActivity.REST
                }
                setActivityFromStream(act)
                _driverCard1.value = _driverCard1.value.copy(isInserted = cardPresent)
                val spd = bytes[i + 8].toInt() and 0xFF
                if (spd in 0..140) {
                    updateSpeedFromStream(spd)
                }
                break
            }
        }
    }

    private fun extractCountryFromCard(cardNumber: String): String {
        val upper = cardNumber.uppercase()
        return when {
            upper.startsWith("DE") || upper.startsWith("DED") -> "DE (Германия)"
            upper.startsWith("PL") || upper.startsWith("POL") -> "PL (Польша)"
            upper.startsWith("LT") || upper.startsWith("LTU") -> "LT (Литва)"
            upper.startsWith("LV") || upper.startsWith("LVA") -> "LV (Латвия)"
            upper.startsWith("EE") || upper.startsWith("EST") -> "EE (Эстония)"
            upper.startsWith("FR") || upper.startsWith("FRA") -> "FR (Франция)"
            upper.startsWith("ES") || upper.startsWith("ESP") -> "ES (Испания)"
            upper.startsWith("IT") || upper.startsWith("ITA") -> "IT (Италия)"
            upper.startsWith("NL") || upper.startsWith("NLD") -> "NL (Нидерланды)"
            upper.startsWith("BE") || upper.startsWith("BEL") -> "BE (Бельгия)"
            upper.startsWith("CZ") || upper.startsWith("CZE") -> "CZ (Чехия)"
            upper.startsWith("SK") || upper.startsWith("SVK") -> "SK (Словакия)"
            upper.startsWith("RO") || upper.startsWith("ROU") -> "RO (Румыния)"
            upper.startsWith("BG") || upper.startsWith("BGR") -> "BG (Болгария)"
            upper.startsWith("HU") || upper.startsWith("HUN") -> "HU (Венгрия)"
            upper.startsWith("AT") || upper.startsWith("AUT") -> "AT (Австрия)"
            upper.startsWith("SE") || upper.startsWith("SWE") -> "SE (Швеция)"
            upper.startsWith("FI") || upper.startsWith("FIN") -> "FI (Финляндия)"
            upper.startsWith("DK") || upper.startsWith("DNK") -> "DK (Дания)"
            upper.startsWith("IE") || upper.startsWith("IRL") -> "IE (Ирландия)"
            upper.startsWith("PT") || upper.startsWith("PRT") -> "PT (Португалия)"
            upper.startsWith("GR") || upper.startsWith("GRC") -> "GR (Греция)"
            else -> "Европейский Союз (ЕС)"
        }
    }

    private fun updateSpeedFromStream(spd: Int) {
        val clampedSpeed = spd.coerceIn(0, 140)
        _telemetry.value = _telemetry.value.copy(
            speedKmh = clampedSpeed,
            engineRpm = if (clampedSpeed > 0) 1100 + (clampedSpeed * 10) else _telemetry.value.engineRpm
        )
        // Under EU law: if vehicle moves (> 1 km/h), tachograph automatically engages DRIVING!
        if (clampedSpeed > 1 && _currentActivity.value != DriverActivity.DRIVING) {
            setActivityFromStream(DriverActivity.DRIVING)
        }
    }

    private fun setActivityFromStream(activity: DriverActivity) {
        if (_currentActivity.value != activity) {
            _currentActivity.value = activity
            _currentModeDurationSeconds.value = 0L
            logTerminal("[STATUS] Режим работы изменен: ${activity.titleRu} (${activity.titleEn})")
        }
    }

    /**
     * Real-time timer ticker: increments driving, resting, availability, and other work timers
     * strictly adhering to European Regulation (EC) No 561/2006.
     */
    private fun startRealTimeDurationTickJob() {
        realTimeClockJob?.cancel()
        realTimeClockJob = scope.launch(Dispatchers.Default) {
            val timeFormatUtc = SimpleDateFormat("HH:mm:ss 'UTC'", Locale.getDefault())
            val timeFormatLocal = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())

            while (isActive && _connectionState.value == ConnectionState.CONNECTED) {
                delay(1000)
                val now = System.currentTimeMillis()

                _currentModeDurationSeconds.value += 1
                val act = _currentActivity.value

                var contDrive = _compliance.value.continuousDrivingSeconds
                var dailyDrive = _compliance.value.dailyDrivingSeconds
                var weeklyDrive = _compliance.value.weeklyDrivingSeconds
                var breakSec = _compliance.value.accumulatedBreakSeconds
                var shiftSec = _compliance.value.shiftDurationSeconds + 1

                when (act) {
                    DriverActivity.DRIVING -> {
                        _drivingTimeSeconds.value += 1
                        contDrive += 1
                        dailyDrive += 1
                        weeklyDrive += 1
                    }
                    DriverActivity.REST -> {
                        _restTimeSeconds.value += 1
                        breakSec += 1
                        // Under EU Reg 561/2006: 45 min (2700s) continuous rest resets the 4:30 driving timer!
                        if (breakSec >= 2700) {
                            contDrive = 0
                        }
                    }
                    DriverActivity.AVAILABLE -> {
                        _availabilityTimeSeconds.value += 1
                    }
                    DriverActivity.WORK -> {
                        _workTimeSeconds.value += 1
                    }
                }

                val remainingCont = (16200 - contDrive).coerceAtLeast(0)
                val hasWarn = remainingCont in 1..900 // <= 15 min left
                val isViol = contDrive >= 16200
                val warnMsg = when {
                    isViol -> "ПРЕВЫШЕНИЕ: Превышен лимит непрерывного вождения 04:30 (Регламент ЕС 561/2006)!"
                    hasWarn -> "ВНИМАНИЕ: До обязательного перерыва осталось ${remainingCont / 60} мин"
                    else -> ""
                }

                _compliance.value = _compliance.value.copy(
                    continuousDrivingSeconds = contDrive,
                    dailyDrivingSeconds = dailyDrive,
                    weeklyDrivingSeconds = weeklyDrive,
                    accumulatedBreakSeconds = breakSec,
                    shiftDurationSeconds = shiftSec,
                    hasWarning = hasWarn,
                    isViolation = isViol,
                    warningMessage = warnMsg
                )

                _telemetry.value = _telemetry.value.copy(
                    utcTime = timeFormatUtc.format(Date(now)),
                    localTime = timeFormatLocal.format(Date(now))
                )
            }
        }
    }

    /**
     * Send active multi-protocol tachograph handshake interrogation:
     * Queries Continental VDO, Stoneridge, Intellic, and CAN/J1939 dongles.
     */
    private fun sendActiveTachographHandshake() {
        scope.launch(Dispatchers.IO) {
            delay(300)
            logTerminal("[TX] Отправка стартовых запросов опроса тахографа ЕС...")

            // 1. Adapter config (ELM327 / OBD / FMS)
            writeStringToSocket("AT Z\r")
            delay(200)
            writeStringToSocket("AT E0\r")
            delay(150)
            writeStringToSocket("AT L0\r")
            delay(150)
            writeStringToSocket("AT H1\r") // Headers ON so CAN IDs like 18FEEC00 are visible
            delay(150)
            writeStringToSocket("AT S0\r")
            delay(150)

            // 2. Continental VDO SmartLink Commands
            writeStringToSocket("DTCO?\r\n")
            delay(150)
            writeStringToSocket("CARD1?\r\n")
            delay(150)
            writeStringToSocket("DRIVER1?\r\n")
            delay(150)
            writeStringToSocket("ACT?\r\n")
            delay(150)
            writeStringToSocket("TIMERS?\r\n")
            delay(150)
            writeStringToSocket("SPEED?\r\n")
            delay(150)
            writeStringToSocket("ODO?\r\n")
            delay(150)
            writeStringToSocket("REQ ALL\r\n")
            delay(150)

            // 3. Stoneridge Tacho Link Commands
            writeStringToSocket("GET STATUS\r\n")
            delay(150)
            writeStringToSocket("GET DRIVER1\r\n")
            delay(150)
            writeStringToSocket("GET DUO\r\n")
            delay(150)
            writeStringToSocket("GET SPEED\r\n")
            delay(150)

            // 4. J1939 / FMS / OBD Queries
            writeStringToSocket("010D\r") // Request speed PID
            delay(150)
            writeStringToSocket("010C\r") // Request RPM PID
            delay(150)
            writeStringToSocket("0902\r") // Request VIN
            delay(150)
            writeStringToSocket("AT CRA 18FEEC00\r") // Listen to Tachograph TCO1
            delay(150)
            writeStringToSocket("AT MP 18FEEC00\r")
            delay(150)
            writeStringToSocket("AT MP 18FEAB00\r")

            logTerminal("[TX] Все запросы опроса переданы в канал тахографа")
        }
    }

    private fun startPeriodicPollingJob() {
        periodicQueryJob?.cancel()
        periodicQueryJob = scope.launch(Dispatchers.IO) {
            var cycle = 0
            while (isActive && _connectionState.value == ConnectionState.CONNECTED) {
                delay(1200)
                cycle++
                when (cycle % 4) {
                    0 -> {
                        // Poll Speed & RPM
                        writeStringToSocket("010D\r")
                        writeStringToSocket("SPEED?\r\n")
                        writeStringToSocket("GET SPEED\r\n")
                    }
                    1 -> {
                        // Poll Driver 1 Card & Identity
                        writeStringToSocket("CARD1?\r\n")
                        writeStringToSocket("GET DRIVER1\r\n")
                        writeStringToSocket("D1?\r\n")
                        writeStringToSocket("AT MP 18FEAB00\r")
                    }
                    2 -> {
                        // Poll Activity Mode & Timers
                        writeStringToSocket("ACT?\r\n")
                        writeStringToSocket("TIMERS?\r\n")
                        writeStringToSocket("GET DUO\r\n")
                        writeStringToSocket("DUO?\r\n")
                        writeStringToSocket("AT MP 18FEEC00\r")
                    }
                    3 -> {
                        // Poll Odometer & Status
                        writeStringToSocket("ODO?\r\n")
                        writeStringToSocket("STATUS?\r\n")
                        writeStringToSocket("GET STATUS\r\n")
                        writeStringToSocket("0131\r")
                    }
                }
            }
        }
    }

    private fun writeStringToSocket(cmd: String) {
        try {
            activeOutputStream?.let { out ->
                out.write(cmd.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            bleTxCharacteristic?.let { char ->
                activeGatt?.let { gatt ->
                    char.value = cmd.toByteArray(Charsets.UTF_8)
                    gatt.writeCharacteristic(char)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "writeStringToSocket error: ${e.message}")
        }
    }

    fun setProtocol(protocol: TachographProtocol) {
        _selectedProtocol.value = protocol
        logTerminal("[PROTO] Выбран протокол: ${protocol.displayName}")
        sendActiveTachographHandshake()
    }

    /**
     * Send user manual command from Terminal
     */
    fun sendCustomCommand(cmd: String) {
        val sanitized = if (cmd.endsWith("\r") || cmd.endsWith("\n")) cmd else "$cmd\r\n"
        logTerminal("[TX] $cmd")
        scope.launch(Dispatchers.IO) {
            writeStringToSocket(sanitized)
        }
    }

    fun sendPollQuery() {
        logTerminal("[TX] Принудительный опрос: карта водителя, таймеры, скорость...")
        sendActiveTachographHandshake()
        scope.launch {
            _toastMessages.emit("Запрос данных отправлен на тахограф")
        }
    }

    fun clearTerminalLogs() {
        _rawTerminalLogs.value = listOf("[SYS] Журнал терминала очищен")
    }

    private fun logTerminal(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$time] $msg"
        val current = _rawTerminalLogs.value.toMutableList()
        current.add(0, entry)
        if (current.size > 120) current.removeAt(current.lastIndex)
        _rawTerminalLogs.value = current
    }

    fun disconnect() {
        socketReaderJob?.cancel()
        socketReaderJob = null
        periodicQueryJob?.cancel()
        periodicQueryJob = null
        realTimeClockJob?.cancel()
        realTimeClockJob = null

        try {
            activeSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing socket: ${e.message}")
        }
        activeSocket = null
        activeOutputStream = null

        try {
            activeGatt?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing gatt: ${e.message}")
        }
        activeGatt = null
        bleTxCharacteristic = null

        stopSimulator()
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedDeviceName.value = null
        _activeProtocolName.value = "Отключено"
        _isRealDataActive.value = false
        logTerminal("[SYS] Соединение разорвано")
    }

    fun enableDemoMode() {
        disconnect()
        _connectionState.value = ConnectionState.DEMO_MODE
        _connectedDeviceName.value = "Continental VDO DTCO 4.1 (Smart Tacho 2)"
        _activeProtocolName.value = "Эмуляция Smart Tacho 2"
        startSimulator()
        scope.launch {
            _toastMessages.emit("Включен симулятор европейского тахографа (VDO DTCO 4.1)")
        }
    }

    fun setDriverActivity(activity: DriverActivity) {
        setActivityFromStream(activity)
        scope.launch {
            _toastMessages.emit("Режим изменен: ${activity.titleRu}")
        }
    }

    fun toggleDriverCard(slot: Int) {
        if (slot == 1) {
            val current = _driverCard1.value
            val newInserted = !current.isInserted
            _driverCard1.value = current.copy(
                isInserted = newInserted,
                driverName = if (newInserted) "SCHMIDT HANS-PETER" else "Карта извлечена",
                cardNumber = if (newInserted) "DED00000849201 0" else "---"
            )
            if (!newInserted && _currentActivity.value == DriverActivity.DRIVING) {
                addEvent(
                    code = "!08",
                    title = "Движение без карты водителя ЕС",
                    description = "Зафиксировано движение ТС без установленной карты водителя в слоте 1",
                    severity = EventSeverity.CRITICAL
                )
            }
        } else if (slot == 2) {
            val current = _driverCard2.value
            _driverCard2.value = current.copy(isInserted = !current.isInserted)
        }
    }

    fun setSimulatedSpeed(targetSpeed: Int) {
        updateSpeedFromStream(targetSpeed)
    }

    fun addEvent(code: String, title: String, description: String, severity: EventSeverity) {
        val newEvent = TachographEvent(
            id = UUID.randomUUID().toString(),
            code = code,
            title = title,
            description = description,
            timestamp = System.currentTimeMillis(),
            severity = severity
        )
        val list = _events.value.toMutableList()
        list.add(0, newEvent)
        _events.value = list
    }

    // Simulator logic for Demo Mode
    private fun startSimulator(deviceLabel: String = "Continental VDO DTCO 4.1") {
        stopSimulator()
        simulatorJob = scope.launch(Dispatchers.Default) {
            _driverCard1.value = DriverCardInfo(
                slotNumber = 1,
                isInserted = true,
                driverName = "SCHMIDT HANS-PETER",
                cardNumber = "DED00000849201 0",
                issuingCountry = "DE (Германия)",
                issuingAuthority = "Kraftfahrt-Bundesamt",
                expiryDate = "28.02.2029"
            )
            _deviceInfo.value = TachographDeviceInfo(
                model = deviceLabel,
                serialNumber = "DTCO41-849102-EU",
                vin = "WDB9634031L894102",
                regNumber = "B-MW 5420 (DE)",
                softwareVersion = "v4.1.0 Annex 1C Gen2 V2",
                calibrationDate = "15.01.2026",
                workshopName = "Tachograph Service Berlin GmbH"
            )
            startRealTimeDurationTickJob()
        }
    }

    private fun stopSimulator() {
        simulatorJob?.cancel()
        simulatorJob = null
    }

    fun cleanup() {
        disconnect()
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(discoveryReceiver)
                isReceiverRegistered = false
            } catch (e: Exception) {
                Log.e(TAG, "unregisterReceiver error: ${e.message}")
            }
        }
    }
}
