package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.bluetooth.ConnectTransport
import com.example.data.bluetooth.DeviceKind
import com.example.data.bluetooth.FoundBtDevice
import com.example.ui.theme.ColorDriving
import com.example.ui.theme.TachoAmber
import com.example.ui.theme.TachoCyan
import com.example.ui.theme.TachoDarkSurface

@Composable
fun BluetoothScannerDialog(
    isScanning: Boolean,
    devices: List<FoundBtDevice>,
    hasBluetoothPermission: Boolean,
    isBluetoothEnabled: Boolean,
    onRequestPermission: () -> Unit,
    onRequestEnableBluetooth: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onPairDevice: (FoundBtDevice) -> Unit,
    onConnectDevice: (FoundBtDevice, ConnectTransport) -> Unit,
    onStartDemo: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = TachoDarkSurface,
            border = BorderStroke(1.dp, Color(0xFF334155)),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("bluetooth_scanner_dialog")
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.BluetoothSearching, contentDescription = null, tint = TachoCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Адаптер Bluetooth", fontWeight = FontWeight.Bold, color = Color.White)
                        Text(
                            "ELM327/STN/OBDLink в разъёме FMS или OBD, либо свой CAN-шлюз",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                when {
                    !hasBluetoothPermission -> Prompt(
                        text = "Нужно разрешение на поиск и подключение Bluetooth-устройств.",
                        action = "Разрешить",
                        onClick = onRequestPermission
                    )
                    !isBluetoothEnabled -> Prompt(
                        text = "Bluetooth выключен.",
                        action = "Включить Bluetooth",
                        onClick = onRequestEnableBluetooth
                    )
                    else -> {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (isScanning) "Поиск устройств…" else "Найдено: ${devices.size}",
                                fontSize = 12.sp,
                                color = if (isScanning) TachoCyan else Color(0xFF94A3B8),
                                modifier = Modifier.weight(1f)
                            )
                            if (isScanning) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = TachoCyan)
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            OutlinedButton(
                                onClick = if (isScanning) onStopScan else onStartScan,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(32.dp).testTag("scan_button")
                            ) {
                                Icon(if (isScanning) Icons.Default.Stop else Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (isScanning) "Стоп" else "Искать", fontSize = 11.sp)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        if (devices.isEmpty()) {
                            Text(
                                text = if (isScanning) "Ищу адаптеры поблизости…" else "Нажмите «Искать». Сопряжённые устройства появятся сразу.",
                                fontSize = 12.sp,
                                color = Color(0xFF94A3B8),
                                modifier = Modifier.padding(vertical = 16.dp)
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.heightIn(max = 360.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(devices, key = { it.address }) { device ->
                                    DeviceRow(
                                        device = device,
                                        onPair = { onPairDevice(device) },
                                        onConnect = { transport -> onConnectDevice(device, transport) }
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onStartDemo,
                    modifier = Modifier.fillMaxWidth().testTag("enable_demo_mode_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Демо-режим (вымышленные данные)")
                }
            }
        }
    }
}

@Composable
private fun Prompt(text: String, action: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(TachoAmber.copy(alpha = 0.12f))
            .border(1.dp, TachoAmber.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(text = text, fontSize = 12.sp, color = Color.White)
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = TachoAmber)) {
            Text(action, color = Color(0xFF0F172A), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DeviceRow(device: FoundBtDevice, onPair: () -> Unit, onConnect: (ConnectTransport) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0F172A))
            .border(1.dp, if (device.likelyAdapter) TachoCyan.copy(alpha = 0.6f) else Color(0xFF1E2E4A), RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(50))
                    .background((if (device.likelyAdapter) TachoCyan else Color(0xFF334155)).copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (device.likelyAdapter) Icons.Default.SettingsInputAntenna else Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = if (device.likelyAdapter) TachoCyan else Color(0xFF94A3B8),
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = device.name, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp, maxLines = 1)
                Text(
                    text = "${device.address} · ${device.kindLabel}" + (device.signalPercent?.let { " · сигнал $it%" } ?: ""),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF94A3B8)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (device.isPaired) {
                Icon(Icons.Default.Check, contentDescription = null, tint = ColorDriving, modifier = Modifier.size(14.dp))
                Text("Сопряжено", fontSize = 10.sp, color = ColorDriving, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            } else if (device.kind != DeviceKind.BLE) {
                OutlinedButton(
                    onClick = onPair,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(30.dp).testTag("pair_btn_${device.address.replace(':', '_')}"),
                    border = BorderStroke(1.dp, TachoAmber.copy(alpha = 0.6f))
                ) {
                    Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(12.dp), tint = TachoAmber)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Сопрячь", fontSize = 10.sp, color = TachoAmber)
                }
                Spacer(modifier = Modifier.weight(1f))
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            if (device.kind != DeviceKind.BLE) {
                TransportButton("SPP") { onConnect(ConnectTransport.SPP_RFCOMM) }
            }
            if (device.kind != DeviceKind.CLASSIC) {
                TransportButton("BLE") { onConnect(ConnectTransport.BLE_GATT) }
            }
            Button(
                onClick = { onConnect(ConnectTransport.AUTO) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(30.dp).testTag("connect_btn_${device.address.replace(':', '_')}"),
                colors = ButtonDefaults.buttonColors(containerColor = TachoCyan)
            ) {
                Text("Подключить", fontSize = 11.sp, color = Color(0xFF0F172A), fontWeight = FontWeight.Bold)
            }
        }
        if (!device.isPaired && device.kind != DeviceKind.BLE) {
            Text(
                text = "Классическим адаптерам обычно нужно сопряжение. PIN вводится в системном окне (часто 0000 или 1234).",
                fontSize = 9.sp,
                color = TachoAmber.copy(alpha = 0.9f),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun TransportButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        modifier = Modifier.height(30.dp)
    ) {
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
    }
}
