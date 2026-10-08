package com.example.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.example.domain.protocol.ByteTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

enum class ConnectTransport(val title: String) {
    AUTO("Авто"),
    SPP_RFCOMM("Classic SPP"),
    BLE_GATT("BLE")
}

/** Bluetooth Classic serial port (RFCOMM SPP) — used by most ELM327/OBDLink adapters. */
class SppTransport private constructor(private val socket: BluetoothSocket) : ByteTransport {

    private val writeLock = Mutex()

    override val incoming: Flow<ByteArray> = channelFlow {
        launch(Dispatchers.IO) {
            val input = socket.inputStream
            val buffer = ByteArray(1024)
            try {
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) send(buffer.copyOf(n))
                }
                channel.close()
            } catch (e: IOException) {
                channel.close(e)
            }
        }
        // Closing the socket is the only way to unblock read() when the collector goes away.
        awaitClose { runCatching { socket.close() } }
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            socket.outputStream.write(data)
            socket.outputStream.flush()
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        @SuppressLint("MissingPermission")
        suspend fun connect(adapter: BluetoothAdapter?, device: BluetoothDevice, log: (String) -> Unit): SppTransport =
            withContext(Dispatchers.IO) {
                try {
                    adapter?.cancelDiscovery()
                } catch (_: SecurityException) {
                }
                val attempts: List<Pair<String, () -> BluetoothSocket>> = listOf(
                    "SPP" to { device.createRfcommSocketToServiceRecord(SPP_UUID) },
                    "SPP без шифрования" to { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
                    "RFCOMM канал 1" to {
                        device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                            .invoke(device, 1) as BluetoothSocket
                    }
                )
                var lastError: Exception? = null
                for ((label, factory) in attempts) {
                    ensureActive()
                    val socket = try {
                        factory()
                    } catch (e: Exception) {
                        lastError = e
                        continue
                    }
                    try {
                        log("[SPP] $label: подключение…")
                        connectCancellable(socket)
                        log("[SPP] $label: соединение установлено")
                        return@withContext SppTransport(socket)
                    } catch (e: CancellationException) {
                        runCatching { socket.close() }
                        throw e
                    } catch (e: Exception) {
                        lastError = e
                        runCatching { socket.close() }
                        log("[SPP] $label: ${e.message ?: e.javaClass.simpleName}")
                        delay(300)
                    }
                }
                throw IOException(lastError?.message ?: "Последовательный порт недоступен", lastError)
            }

        /** BluetoothSocket.connect() blocks; closing the socket is what aborts it on cancellation. */
        @SuppressLint("MissingPermission")
        private suspend fun connectCancellable(socket: BluetoothSocket) = coroutineScope {
            val connecting = async(Dispatchers.IO) { socket.connect() }
            try {
                connecting.await()
            } catch (e: CancellationException) {
                runCatching { socket.close() }
                throw e
            }
        }
    }
}

/**
 * Serial-over-BLE adapters (Nordic UART, HM-10 style FFE0, FFF0, OBDLink/Vgate). GATT allows one operation at
 * a time, so every write waits for its completion callback.
 */
@SuppressLint("MissingPermission")
class BleUartTransport private constructor(
    private val gatt: BluetoothGatt,
    private val callback: Callback,
    private val txCharacteristic: BluetoothGattCharacteristic,
    private val writeWithResponse: Boolean
) : ByteTransport {

    private val writeLock = Mutex()

    override val incoming: Flow<ByteArray> = callback.data.consumeAsFlow()

    override suspend fun write(data: ByteArray) {
        writeLock.withLock {
            val chunkSize = (callback.mtu - 3).coerceAtLeast(20)
            var offset = 0
            while (offset < data.size) {
                val chunk = data.copyOfRange(offset, minOf(data.size, offset + chunkSize))
                writeChunk(chunk)
                offset += chunk.size
            }
        }
    }

    private suspend fun writeChunk(chunk: ByteArray) {
        val type = if (writeWithResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        repeat(5) { attempt ->
            val pending = CompletableDeferred<Int>()
            callback.pendingWrite = pending
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(txCharacteristic, chunk, type) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                txCharacteristic.writeType = type
                @Suppress("DEPRECATION")
                txCharacteristic.value = chunk
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(txCharacteristic)
            }
            if (started) {
                val status = withTimeoutOrNull(2_000) { pending.await() }
                if (status == BluetoothGatt.GATT_SUCCESS || (status == null && !writeWithResponse)) return
            }
            if (callback.disconnected) throw IOException("BLE соединение разорвано")
            delay(30L * (attempt + 1))
        }
        throw IOException("Не удалось записать в BLE характеристику")
    }

    override fun close() {
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
        callback.data.close()
    }

    private class Callback : BluetoothGattCallback() {
        val connected = CompletableDeferred<Unit>()
        val servicesDiscovered = CompletableDeferred<Unit>()
        val mtuChanged = CompletableDeferred<Unit>()
        var descriptorWritten = CompletableDeferred<Int>()
        @Volatile var pendingWrite: CompletableDeferred<Int>? = null
        @Volatile var mtu = 23
        @Volatile var disconnected = false
        val data = Channel<ByteArray>(Channel.UNLIMITED)

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(Unit)
            } else {
                disconnected = true
                val error = IOException("BLE соединение закрыто (статус $status)")
                connected.completeExceptionally(error)
                servicesDiscovered.completeExceptionally(error)
                descriptorWritten.completeExceptionally(error)
                pendingWrite?.completeExceptionally(error)
                data.close(error)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) servicesDiscovered.complete(Unit)
            else servicesDiscovered.completeExceptionally(IOException("Сервисы BLE не найдены (статус $status)"))
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this.mtu = mtu
            mtuChanged.complete(Unit)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            descriptorWritten.complete(status)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            pendingWrite?.complete(status)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            data.trySend(value.copyOf())
        }

        @Deprecated("Used below Android 13")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            characteristic.value?.let { data.trySend(it.copyOf()) }
        }
    }

    companion object {
        private val CCCD: UUID = uuid16(0x2902)

        /** Known serial service/notify/write triples, checked in order. */
        private val KNOWN_UARTS = listOf(
            Triple(
                UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"),
                UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"),
                UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
            ),
            Triple(uuid16(0xFFF0), uuid16(0xFFF1), uuid16(0xFFF2)),
            Triple(uuid16(0xFFE0), uuid16(0xFFE1), uuid16(0xFFE1)),
            Triple(
                UUID.fromString("E7810A71-73AE-499D-8C15-FAA9AEF0C3F2"),
                UUID.fromString("BEF8D6C9-9C21-4C9E-B632-BD58C1009F9F"),
                UUID.fromString("BEF8D6C9-9C21-4C9E-B632-BD58C1009F9F")
            )
        )

        private val STANDARD_SERVICES = setOf(uuid16(0x1800), uuid16(0x1801), uuid16(0x180A), uuid16(0x180F))

        /** Like withTimeout, but a timeout is an I/O failure rather than a cancellation of the caller. */
        private suspend fun <T> awaitOrFail(timeoutMs: Long, message: String, block: suspend () -> T): T =
            withTimeoutOrNull(timeoutMs) { block() } ?: throw IOException(message)

        private fun uuid16(short: Int): UUID = UUID.fromString("%08X-0000-1000-8000-00805F9B34FB".format(short))

        suspend fun connect(context: Context, device: BluetoothDevice, log: (String) -> Unit): BleUartTransport {
            val callback = Callback()
            val gatt = withContext(Dispatchers.Main) {
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            } ?: throw IOException("Не удалось открыть GATT")
            try {
                log("[BLE] Подключение GATT…")
                awaitOrFail(15_000, "GATT не подключился") { callback.connected.await() }
                if (gatt.requestMtu(185)) withTimeoutOrNull(2_000) { callback.mtuChanged.await() }
                if (!gatt.discoverServices()) throw IOException("Не удалось запустить поиск сервисов")
                awaitOrFail(10_000, "Сервисы BLE не обнаружены") { callback.servicesDiscovered.await() }

                val (rx, tx) = pickCharacteristics(gatt) ?: throw IOException("Нет последовательного сервиса (UART) на устройстве")
                log("[BLE] Канал: приём ${rx.uuid}, передача ${tx.uuid}, MTU ${callback.mtu}")

                if (!gatt.setCharacteristicNotification(rx, true)) throw IOException("Не удалось включить уведомления")
                rx.getDescriptor(CCCD)?.let { descriptor ->
                    val value = if ((rx.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0)
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    callback.descriptorWritten = CompletableDeferred()
                    val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = value
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(descriptor)
                    }
                    if (!started) throw IOException("Не удалось подписаться на данные")
                    val status = awaitOrFail(5_000, "Нет ответа на подписку") { callback.descriptorWritten.await() }
                    if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("Подписка отклонена (статус $status)")
                }
                val withResponse = (tx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                log("[BLE] Соединение установлено")
                return BleUartTransport(gatt, callback, tx, withResponse)
            } catch (e: Throwable) {
                runCatching { gatt.disconnect() }
                runCatching { gatt.close() }
                throw e
            }
        }

        private fun pickCharacteristics(gatt: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
            for ((serviceId, rxId, txId) in KNOWN_UARTS) {
                val service = gatt.getService(serviceId) ?: continue
                val rx = service.getCharacteristic(rxId) ?: continue
                val tx = service.getCharacteristic(txId) ?: continue
                return rx to tx
            }
            for (service in gatt.services) {
                if (service.uuid in STANDARD_SERVICES) continue
                val rx = service.characteristics.firstOrNull {
                    (it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0
                }
                val tx = service.characteristics.firstOrNull {
                    (it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
                }
                if (rx != null && tx != null) return rx to tx
            }
            return null
        }
    }
}
