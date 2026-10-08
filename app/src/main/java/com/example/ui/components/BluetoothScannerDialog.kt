package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.bluetooth.ConnectTransport
import com.example.data.bluetooth.ConnectionState
import com.example.data.bluetooth.FoundBtDevice
import com.example.ui.theme.ColorDriving
import com.example.ui.theme.TachoAmber
import com.example.ui.theme.TachoCyan
import com.example.ui.theme.TachoDarkSurface
import com.example.ui.theme.TachoRed

@Composable
fun BluetoothScannerDialog(
    connectionState: ConnectionState,
    discoveredDevices: List<FoundBtDevice>,
    hasBluetoothPermission: Boolean = true,
    isBluetoothEnabled: Boolean = true,
    onRequestPermission: () -> Unit,
    onRequestEnableBluetooth: () -> Unit,
    onStartScan: () -> Unit,
    onCancelScan: () -> Unit,
    onPairDevice: (FoundBtDevice) -> Unit,
    onConnectDevice: (FoundBtDevice, ConnectTransport) -> Unit,
    onEnableDemoMode: () -> Unit,
    onDismiss: () -> Unit
) {
    val isScanning = connectionState == ConnectionState.SCANNING
    var onlyTachoFilter by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val infiniteTransition = rememberInfiniteTransition(label = "RadarTransition")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RadarRotation"
    )

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "RadarPulse"
    )

    val filteredList = discoveredDevices.filter { dev ->
        val matchesTacho = !onlyTachoFilter || dev.isLikelyTachograph
        val matchesQuery = searchQuery.isEmpty() ||
                dev.name.contains(searchQuery, ignoreCase = true) ||
                dev.address.contains(searchQuery, ignoreCase = true) ||
                dev.tachographVendor.contains(searchQuery, ignoreCase = true)
        matchesTacho && matchesQuery
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = TachoDarkSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .testTag("bluetooth_scanner_dialog")
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(TachoCyan.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.BluetoothSearching,
                                contentDescription = null,
                                tint = TachoCyan,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Поиск тахографов",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Classic SPP RFCOMM & BLE 5.0",
                                fontSize = 10.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Закрыть",
                            tint = Color(0xFF94A3B8)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 1. Permission Warning Banner if missing
                AnimatedVisibility(visible = !hasBluetoothPermission) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(TachoAmber.copy(alpha = 0.15f))
                            .border(1.dp, TachoAmber, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = TachoAmber,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Требуется разрешение Bluetooth",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Для обнаружения тахографов рядом приложению необходим доступ к поиску Bluetooth устройств.",
                            fontSize = 11.sp,
                            color = Color(0xFFCBD5E1)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = onRequestPermission,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .testTag("request_bluetooth_permission_btn"),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = TachoAmber)
                        ) {
                            Text(
                                text = "Предоставить доступ",
                                color = Color(0xFF0F172A),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                // 2. Bluetooth Disabled Banner
                AnimatedVisibility(visible = hasBluetoothPermission && !isBluetoothEnabled) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(TachoRed.copy(alpha = 0.15f))
                            .border(1.dp, TachoRed, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.BluetoothDisabled,
                                contentDescription = null,
                                tint = TachoRed,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Bluetooth выключен на устройстве",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Включите Bluetooth для сканирования и соединения с тахографом.",
                            fontSize = 11.sp,
                            color = Color(0xFFCBD5E1)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = onRequestEnableBluetooth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .testTag("enable_bluetooth_btn"),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = TachoCyan)
                        ) {
                            Text(
                                text = "Включить Bluetooth",
                                color = Color(0xFF0F172A),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                // Quick Demo Mode Switch
                Button(
                    onClick = {
                        onEnableDemoMode()
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .testTag("enable_demo_mode_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = ColorDriving,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Включить симулятор тахографа (Демо-режим)",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Поиск по имени или MAC...", fontSize = 11.sp, color = Color(0xFF64748B)) },
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(16.dp))
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = "Очистить", tint = Color(0xFF94A3B8), modifier = Modifier.size(14.dp))
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF0F172A),
                        unfocusedContainerColor = Color(0xFF0F172A),
                        focusedBorderColor = TachoCyan,
                        unfocusedBorderColor = Color(0xFF334155),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Scan Action Row & Filter Chip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = onlyTachoFilter,
                        onClick = { onlyTachoFilter = !onlyTachoFilter },
                        label = { Text("Только тахографы", fontSize = 11.sp) },
                        leadingIcon = {
                            if (onlyTachoFilter) {
                                Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(12.dp))
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = TachoCyan.copy(alpha = 0.2f),
                            selectedLabelColor = TachoCyan
                        )
                    )

                    OutlinedButton(
                        onClick = if (isScanning) onCancelScan else onStartScan,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .height(36.dp)
                            .testTag("scan_toggle_button")
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = TachoCyan
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Остановить", fontSize = 11.sp)
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Сканировать", fontSize = 11.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Status banner: device count
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isScanning) "Сканирование эфира (RFCOMM/BLE)..." else "Найдено устройств: ${filteredList.size}",
                        fontSize = 11.sp,
                        color = if (isScanning) TachoCyan else Color(0xFF94A3B8),
                        fontWeight = if (isScanning) FontWeight.Bold else FontWeight.Normal
                    )

                    if (isScanning) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(TachoCyan)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Discovered Devices List
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF0B111E))
                        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (filteredList.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.BluetoothSearching,
                                        contentDescription = null,
                                        tint = if (isScanning) TachoCyan else Color(0xFF475569),
                                        modifier = Modifier
                                            .size(42.dp)
                                            .then(if (isScanning) Modifier.rotate(rotation) else Modifier)
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = if (isScanning) "Поиск европейских тахографов VDO, Stoneridge, Intellic, Actia..."
                                        else "Нажмите «Сканировать» для запуска поиска",
                                        fontSize = 12.sp,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                            }
                        }
                    } else {
                        items(filteredList) { device ->
                            EnhancedDeviceListItem(
                                device = device,
                                onPair = { onPairDevice(device) },
                                onConnect = { transport ->
                                    onConnectDevice(device, transport)
                                    onDismiss()
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Совместимо с тахографами ЕС: Continental VDO DTCO SmartLink (1381 / 4.0 / 4.1), Stoneridge SE5000 Smart 2, Tacho Link, DigiFob, Intellic EFAS-4, Actia SmarTach.",
                    fontSize = 10.sp,
                    color = Color(0xFF64748B),
                    lineHeight = 14.sp
                )
            }
        }
    }
}

@Composable
private fun EnhancedDeviceListItem(
    device: FoundBtDevice,
    onPair: () -> Unit,
    onConnect: (ConnectTransport) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF131D2E))
            .border(
                1.dp,
                if (device.isLikelyTachograph) TachoCyan.copy(alpha = 0.6f) else Color(0xFF1E2E4A),
                RoundedCornerShape(12.dp)
            )
            .padding(10.dp)
            .testTag("device_item_${device.address.replace(':', '_')}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(
                            if (device.isLikelyTachograph) TachoCyan.copy(alpha = 0.2f)
                            else Color(0xFF1E293B)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (device.isLikelyTachograph) Icons.Default.SettingsInputAntenna else Icons.Default.Bluetooth,
                        contentDescription = null,
                        tint = if (device.isLikelyTachograph) TachoCyan else Color(0xFF94A3B8),
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = device.name,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        if (device.supportsSpp) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF0284C7).copy(alpha = 0.25f))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text("SPP", fontSize = 8.sp, color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        if (device.supportsBle || device.isBle) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF8B5CF6).copy(alpha = 0.25f))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text("BLE", fontSize = 8.sp, color = Color(0xFFA78BFA), fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Text(
                        text = "${device.tachographVendor} • ${device.address}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF94A3B8)
                    )
                }
            }

            // Signal strength display
            Column(horizontalAlignment = Alignment.End) {
                SignalMeter(rssi = device.rssi)
                Text(
                    text = "${device.rssi} dBm",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF94A3B8)
                )
            }
        }

        if (!device.isPaired) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Для подключения адаптеров DTCO требуется сопряжение (PIN 0000 или 1234)",
                fontSize = 9.sp,
                color = TachoAmber.copy(alpha = 0.9f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Action Buttons Row (Pair / Connect)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!device.isPaired) {
                OutlinedButton(
                    onClick = onPair,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .height(32.dp)
                        .testTag("pair_btn_${device.address.replace(':', '_')}"),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = TachoAmber,
                        containerColor = TachoAmber.copy(alpha = 0.12f)
                    ),
                    border = BorderStroke(1.dp, TachoAmber.copy(alpha = 0.6f))
                ) {
                    Icon(imageVector = Icons.Default.Link, contentDescription = null, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Сопрячь (PIN 0000)", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 2.dp)
                ) {
                    Icon(imageVector = Icons.Default.Check, contentDescription = null, tint = ColorDriving, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Сопряжено", fontSize = 10.sp, color = ColorDriving, fontWeight = FontWeight.Bold)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { onConnect(ConnectTransport.SPP_RFCOMM) },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(32.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8))
                ) {
                    Text("SPP", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = { onConnect(ConnectTransport.AUTO) },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .height(32.dp)
                        .testTag("connect_btn_${device.address.replace(':', '_')}"),
                    colors = ButtonDefaults.buttonColors(containerColor = TachoCyan)
                ) {
                    Text(
                        text = "Подключить",
                        fontSize = 11.sp,
                        color = Color(0xFF0F172A),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun SignalMeter(rssi: Int) {
    val bars = when {
        rssi >= -60 -> 4
        rssi >= -75 -> 3
        rssi >= -88 -> 2
        else -> 1
    }
    val barColor = when {
        bars >= 3 -> ColorDriving
        bars == 2 -> TachoAmber
        else -> TachoRed
    }

    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        for (i in 1..4) {
            val h = (4 + i * 3).dp
            val active = i <= bars
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(h)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (active) barColor else Color(0xFF334155))
            )
        }
    }
}
