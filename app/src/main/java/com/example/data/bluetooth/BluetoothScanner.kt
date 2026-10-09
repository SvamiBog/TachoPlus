package com.example.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DeviceKind { CLASSIC, BLE, DUAL, UNKNOWN }

data class FoundBtDevice(
    val name: String,
    val address: String,
    val isPaired: Boolean,
    val kind: DeviceKind,
    val rssi: Int? = null,
    /** Name suggests an OBD/ELM327/FMS adapter. Purely a hint for sorting. */
    val likelyAdapter: Boolean = false,
    val device: BluetoothDevice? = null
) {
    val signalPercent: Int?
        get() = rssi?.let {
            when {
                it >= -50 -> 100
                it <= -100 -> 0
                else -> (it + 100) * 2
            }
        }

    val isTachograph: Boolean
        get() = looksLikeTachograph(name)

    val kindLabel: String
        get() = when (kind) {
            DeviceKind.CLASSIC -> "Classic (SPP)"
            DeviceKind.BLE -> "BLE"
            DeviceKind.DUAL -> "Classic + BLE"
            DeviceKind.UNKNOWN -> "Bluetooth"
        }

    companion object {
        private val adapterHints = listOf(
            "obd", "elm", "vlink", "vgate", "icar", "veepeak", "konnwei", "kingbolen", "obdlink", "stn",
            "carly", "fms", "j1939", "can", "tacho", "truck"
        )

        fun looksLikeAdapter(name: String): Boolean {
            val lower = name.lowercase()
            return adapterHints.any { lower.contains(it) } || looksLikeTachograph(name)
        }

        /** Built-in Bluetooth of a tachograph, e.g. VDO DTCO 4.x advertises as "DTCO-<registration>". */
        fun looksLikeTachograph(name: String): Boolean {
            val upper = name.uppercase()
            return upper.startsWith("DTCO") || upper.contains("SE5000") || upper.contains("SMARTACH") || upper.contains("EFAS")
        }
    }
}

/**
 * Bluetooth Classic discovery plus a BLE scan. The scanning state is separate from the connection state,
 * and the scan stops on its own after [SCAN_DURATION_MS].
 */
class BluetoothScanner(private val context: Context, private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "BluetoothScanner"
        private const val SCAN_DURATION_MS = 15_000L
    }

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _devices = MutableStateFlow<List<FoundBtDevice>>(emptyList())
    val devices: StateFlow<List<FoundBtDevice>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _bluetoothEnabled = MutableStateFlow(BluetoothPermissionManager.isBluetoothEnabled(context))
    val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var stopJob: Job? = null
    private var bleScanning = false
    private var classicScanning = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.bluetoothDevice() ?: return
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                        .takeIf { it != Short.MIN_VALUE }?.toInt()
                    addDevice(device, intent.getStringExtra(BluetoothDevice.EXTRA_NAME), rssi, fromBle = false)
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = intent.bluetoothDevice() ?: return
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    _devices.update { list ->
                        list.map { if (it.address == device.address) it.copy(isPaired = state == BluetoothDevice.BOND_BONDED) else it }
                    }
                    if (state == BluetoothDevice.BOND_BONDED) _messages.tryEmit("Сопряжение выполнено")
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    classicScanning = true
                    refreshScanning()
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    classicScanning = false
                    refreshScanning()
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    _bluetoothEnabled.value = state == BluetoothAdapter.STATE_ON
                    if (state != BluetoothAdapter.STATE_ON) stop()
                }
            }
        }
    }

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val r = result ?: return
            addDevice(r.device, r.scanRecord?.deviceName, r.rssi, fromBle = true)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed: $errorCode")
            bleScanning = false
            refreshScanning()
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        // These are protected system broadcasts; the receiver lives as long as the application.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    fun refreshAdapterState() {
        _bluetoothEnabled.value = BluetoothPermissionManager.isBluetoothEnabled(context)
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val adapter = adapter
        refreshAdapterState()
        if (adapter == null) {
            _messages.tryEmit("На устройстве нет Bluetooth")
            return
        }
        if (!BluetoothPermissionManager.hasScanPermission(context)) {
            _messages.tryEmit("Нет разрешения на поиск Bluetooth-устройств")
            return
        }
        if (!_bluetoothEnabled.value) {
            _messages.tryEmit("Включите Bluetooth")
            return
        }
        if (!BluetoothPermissionManager.isLocationEnabledLegacy(context)) {
            _messages.tryEmit("На Android 11 и ниже для поиска нужно включить геолокацию")
        }

        stop()
        val bonded = try {
            adapter.bondedDevices.orEmpty().map { device ->
                val name = safeName(device) ?: device.address
                FoundBtDevice(
                    name = name,
                    address = device.address,
                    isPaired = true,
                    kind = kindOf(device),
                    likelyAdapter = FoundBtDevice.looksLikeAdapter(name),
                    device = device
                )
            }
        } catch (e: SecurityException) {
            emptyList()
        }
        _devices.value = sort(bonded)

        try {
            classicScanning = adapter.startDiscovery()
        } catch (e: SecurityException) {
            Log.w(TAG, "startDiscovery: ${e.message}")
        }
        try {
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            adapter.bluetoothLeScanner?.startScan(null, settings, bleCallback)
            bleScanning = adapter.bluetoothLeScanner != null
        } catch (e: Exception) {
            Log.w(TAG, "BLE scan: ${e.message}")
        }
        refreshScanning()
        stopJob = scope.launch {
            delay(SCAN_DURATION_MS)
            stop()
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        stopJob?.cancel()
        stopJob = null
        val adapter = adapter ?: return
        try {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
        } catch (e: SecurityException) {
            Log.w(TAG, "cancelDiscovery: ${e.message}")
        }
        if (bleScanning) {
            try {
                adapter.bluetoothLeScanner?.stopScan(bleCallback)
            } catch (e: Exception) {
                Log.w(TAG, "stopScan: ${e.message}")
            }
        }
        bleScanning = false
        classicScanning = false
        refreshScanning()
    }

    /** Starts system pairing. The PIN (often 0000 or 1234 for adapters) is entered by the user in the system dialog. */
    @SuppressLint("MissingPermission")
    fun pair(device: FoundBtDevice) {
        val target = device.device ?: runCatching { adapter?.getRemoteDevice(device.address) }.getOrNull()
        if (target == null) {
            _messages.tryEmit("Устройство не найдено")
            return
        }
        val started = try {
            target.createBond()
        } catch (e: SecurityException) {
            false
        }
        _messages.tryEmit(
            if (started) "Подтвердите сопряжение в системном окне. PIN адаптера обычно 0000 или 1234."
            else "Не удалось начать сопряжение"
        )
    }

    @SuppressLint("MissingPermission")
    fun remoteDevice(address: String): BluetoothDevice? = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()

    private fun refreshScanning() {
        _isScanning.value = classicScanning || bleScanning
    }

    @SuppressLint("MissingPermission")
    private fun addDevice(device: BluetoothDevice, advertisedName: String?, rssi: Int?, fromBle: Boolean) {
        val name = advertisedName ?: safeName(device) ?: return // nameless BLE beacons are noise
        val paired = try {
            device.bondState == BluetoothDevice.BOND_BONDED
        } catch (e: SecurityException) {
            false
        }
        val found = FoundBtDevice(
            name = name,
            address = device.address,
            isPaired = paired,
            kind = kindOf(device, fromBle),
            rssi = rssi,
            likelyAdapter = FoundBtDevice.looksLikeAdapter(name),
            device = device
        )
        _devices.update { list ->
            val existing = list.firstOrNull { it.address.equals(found.address, ignoreCase = true) }
            val merged = if (existing == null) found else existing.copy(
                name = found.name,
                isPaired = existing.isPaired || found.isPaired,
                kind = mergeKinds(existing.kind, found.kind),
                rssi = found.rssi ?: existing.rssi,
                likelyAdapter = existing.likelyAdapter || found.likelyAdapter,
                device = found.device ?: existing.device
            )
            sort(list.filterNot { it.address.equals(found.address, ignoreCase = true) } + merged)
        }
    }

    private fun sort(list: List<FoundBtDevice>) =
        list.sortedWith(compareByDescending<FoundBtDevice> { it.likelyAdapter }.thenByDescending { it.isPaired }.thenByDescending { it.rssi ?: -200 })

    private fun mergeKinds(a: DeviceKind, b: DeviceKind): DeviceKind = when {
        a == b -> a
        a == DeviceKind.UNKNOWN -> b
        b == DeviceKind.UNKNOWN -> a
        else -> DeviceKind.DUAL
    }

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice): String? = try {
        device.name
    } catch (e: SecurityException) {
        null
    }

    @SuppressLint("MissingPermission")
    private fun kindOf(device: BluetoothDevice, fromBle: Boolean = false): DeviceKind {
        val type = try {
            device.type
        } catch (e: SecurityException) {
            BluetoothDevice.DEVICE_TYPE_UNKNOWN
        }
        return when (type) {
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> DeviceKind.CLASSIC
            BluetoothDevice.DEVICE_TYPE_LE -> DeviceKind.BLE
            BluetoothDevice.DEVICE_TYPE_DUAL -> DeviceKind.DUAL
            else -> if (fromBle) DeviceKind.BLE else DeviceKind.UNKNOWN
        }
    }

    private fun Intent.bluetoothDevice(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
}
