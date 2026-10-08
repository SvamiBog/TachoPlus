package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.bluetooth.ConnectionState
import com.example.data.bluetooth.TachographProtocol
import com.example.data.model.TachographDeviceInfo
import com.example.data.model.TachographLiveTelemetry
import com.example.ui.theme.ColorDriving
import com.example.ui.theme.TachoAmber
import com.example.ui.theme.TachoCyan
import com.example.ui.theme.TachoRed

@Composable
fun DevicesScreen(
    connectionState: ConnectionState,
    connectedDeviceName: String?,
    deviceInfo: TachographDeviceInfo,
    telemetry: TachographLiveTelemetry,
    isRealDataActive: Boolean = false,
    activeProtocolName: String = "",
    selectedProtocol: TachographProtocol = TachographProtocol.AUTO_DETECT,
    streamBytesReceived: Long = 0,
    streamPacketsReceived: Long = 0,
    lastRawPacket: String = "",
    lastErrorMessage: String? = null,
    rawTerminalLogs: List<String> = emptyList(),
    hasBluetoothPermission: Boolean = true,
    isBluetoothEnabled: Boolean = true,
    onRequestPermission: () -> Unit = {},
    onRequestEnableBluetooth: () -> Unit = {},
    onSelectProtocol: (TachographProtocol) -> Unit = {},
    onPollTachograph: () -> Unit = {},
    onSendCustomCommand: (String) -> Unit = {},
    onClearTerminalLogs: () -> Unit = {},
    onOpenBluetoothDialog: () -> Unit,
    onDisconnect: () -> Unit,
    onEnableDemoMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isConnected = connectionState == ConnectionState.CONNECTED
    val isDemo = connectionState == ConnectionState.DEMO_MODE
    val isError = connectionState == ConnectionState.ERROR
    var customCommandText by remember { mutableStateOf("") }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(6.dp))

            // Permission Warning Card if not granted
            if (!hasBluetoothPermission) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF78350F).copy(alpha = 0.3f))
                        .border(1.dp, Color(0xFFF59E0B), RoundedCornerShape(16.dp))
                        .padding(14.dp)
                ) {
                    Text(
                        text = "Внимание: Требуется разрешение Bluetooth",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Для обнаружения физических тахографов и адаптеров VDO/Stoneridge/FMS предоставьте доступ.",
                        fontSize = 11.sp,
                        color = Color(0xFFCBD5E1)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onRequestPermission,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B))
                    ) {
                        Text("Запросить разрешение", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }

            // Main Bluetooth Connection Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF131D2E))
                    .border(1.dp, Color(0xFF1E2E4A), RoundedCornerShape(20.dp))
                    .padding(16.dp)
                    .testTag("bluetooth_connection_card")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (isConnected || isDemo) ColorDriving.copy(alpha = 0.2f) else Color(0xFF334155)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isConnected || isDemo) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                                contentDescription = null,
                                tint = if (isConnected || isDemo) ColorDriving else Color(0xFF94A3B8),
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Text(
                                text = when (connectionState) {
                                    ConnectionState.CONNECTED -> if (isRealDataActive) "Тахограф подключен (Поток активен)" else "Подключено к адаптеру"
                                    ConnectionState.DEMO_MODE -> "Симулятор тахографа (Демо)"
                                    ConnectionState.CONNECTING -> "Установка соединения..."
                                    ConnectionState.SCANNING -> "Поиск устройств Bluetooth..."
                                    ConnectionState.ERROR -> "Ошибка подключения к тахографу"
                                    else -> "Тахограф отключен"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isError) TachoRed else Color.White
                            )
                            Text(
                                text = connectedDeviceName ?: if (isError) "Проверьте сопряжение и PIN" else "Устройство не выбрано",
                                fontSize = 11.sp,
                                color = when {
                                    isError -> TachoRed.copy(alpha = 0.85f)
                                    isConnected || isDemo -> TachoCyan
                                    else -> Color(0xFF94A3B8)
                                }
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isRealDataActive -> ColorDriving
                                    isConnected -> TachoAmber
                                    isDemo -> TachoCyan
                                    else -> TachoRed
                                }
                            )
                    )
                }

                if (isError && !lastErrorMessage.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(TachoRed.copy(alpha = 0.15f))
                            .border(1.dp, TachoRed.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = "Причина сбоя:",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = TachoRed
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = lastErrorMessage,
                            fontSize = 11.sp,
                            color = Color(0xFFFCA5A5),
                            lineHeight = 15.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "💡 Рекомендации:\n1. В меню поиска нажмите 'Сопряжение' (PIN обычно 0000 или 1234).\n2. Попробуйте режим подключения 'Classic SPP' или 'BLE UART'.\n3. Убедитесь, что зажигание автомобиля включено и адаптер активен.",
                            fontSize = 10.sp,
                            color = Color(0xFFCBD5E1),
                            lineHeight = 14.sp
                        )
                    }
                }

                if (isConnected || isDemo) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF0F172A))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(text = "Активный протокол:", fontSize = 10.sp, color = Color(0xFF94A3B8))
                            Text(text = activeProtocolName, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(text = "Прием данных:", fontSize = 10.sp, color = Color(0xFF94A3B8))
                            Text(text = "$streamPacketsReceived пак. ($streamBytesReceived B)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TachoCyan)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onOpenBluetoothDialog,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag("open_bluetooth_scanner_btn"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TachoCyan)
                    ) {
                        Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = Color(0xFF0F172A), modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Поиск тахографа", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    if (isConnected || isDemo) {
                        Button(
                            onClick = onPollTachograph,
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .testTag("poll_tachograph_btn"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ColorDriving)
                        ) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, tint = Color(0xFF0F172A), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Опросить сейчас", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = onDisconnect,
                            modifier = Modifier
                                .height(44.dp)
                                .testTag("disconnect_btn"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TachoRed)
                        ) {
                            Text("Откл.", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    } else {
                        Button(
                            onClick = onEnableDemoMode,
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .testTag("enable_demo_btn"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = ColorDriving, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Вкл. Демо", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Section: Protocol Selection & Diagnostic Interrogation
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF0F172A))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(18.dp))
                    .padding(14.dp)
            ) {
                Text(
                    text = "ПРОТОКОЛ ОБМЕНА С ТАХОГРАФОМ ЕС",
                    style = MaterialTheme.typography.labelSmall,
                    color = TachoCyan,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
                Spacer(modifier = Modifier.height(10.dp))

                listOf(
                    TachographProtocol.AUTO_DETECT,
                    TachographProtocol.VDO_SMARTLINK,
                    TachographProtocol.STONERIDGE_TACHO_LINK,
                    TachographProtocol.J1939_FMS_CAN,
                    TachographProtocol.OBD2_TRUCK
                ).forEach { proto ->
                    val isProtoSelected = selectedProtocol == proto
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isProtoSelected) TachoCyan.copy(alpha = 0.15f) else Color(0xFF1E293B))
                            .border(
                                1.dp,
                                if (isProtoSelected) TachoCyan else Color(0xFF334155),
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { onSelectProtocol(proto) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = proto.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (isProtoSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isProtoSelected) Color.White else Color(0xFFCBD5E1)
                        )
                        if (isProtoSelected) {
                            Text(text = "АКТИВЕН", fontSize = 10.sp, color = TachoCyan, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Live Diagnostic Terminal View (Real-time serial/packet inspection)
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF090D16))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(18.dp))
                    .padding(14.dp)
                    .testTag("diagnostic_terminal_card")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Terminal, contentDescription = null, tint = TachoCyan, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "ДИАГНОСТИЧЕСКИЙ ЖУРНАЛ ПОТОКА",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(
                        onClick = onClearTerminalLogs,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Clear, contentDescription = "Очистить", tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Scrollable log area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF030712))
                        .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(10.dp))
                        .padding(8.dp)
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(rawTerminalLogs) { logEntry ->
                            val color = when {
                                logEntry.contains("[RX]") -> ColorDriving
                                logEntry.contains("[TX]") -> TachoCyan
                                logEntry.contains("[CARD]") || logEntry.contains("[DRIVER]") -> TachoAmber
                                logEntry.contains("[ERR]") -> TachoRed
                                else -> Color(0xFF94A3B8)
                            }
                            Text(
                                text = logEntry,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = color,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Custom Command Sender
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = customCommandText,
                        onValueChange = { customCommandText = it },
                        placeholder = { Text("Команда (например, CARD1? или 010D)", fontSize = 11.sp, color = Color(0xFF64748B)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TachoCyan,
                            unfocusedBorderColor = Color(0xFF334155),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )

                    Button(
                        onClick = {
                            if (customCommandText.isNotBlank()) {
                                onSendCustomCommand(customCommandText.trim())
                                customCommandText = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = TachoCyan),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(50.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Send, contentDescription = "Отправить", tint = Color(0xFF0F172A), modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // Diagnostic Sensors & Hardware Status
        item {
            Text(
                text = "СОСТОЯНИЕ ДАТЧИКОВ И ТЕЛЕМЕТРИИ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SensorStatusCard(
                    title = "Датчик движения",
                    status = telemetry.motionSensorStatus,
                    isActive = true,
                    icon = Icons.Default.Sensors,
                    modifier = Modifier.weight(1f)
                )

                SensorStatusCard(
                    title = "Galileo OSNMA / GNSS",
                    status = if (telemetry.gnssSignal) "Зафиксирован (Авто-границы)" else "Поиск сигнала",
                    isActive = telemetry.gnssSignal,
                    icon = Icons.Default.GpsFixed,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Section: Tachograph Hardware Info & Calibration Parameters
        item {
            Text(
                text = "ПАСПОРТ ТАХОГРАФА ЕС (SMART TACHO 2 / ANNEX 1C)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF0F172A))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(18.dp))
                    .padding(14.dp)
            ) {
                InfoParamRow(label = "Модель тахографа", value = deviceInfo.model)
                InfoParamRow(label = "Серийный номер ЕС", value = deviceInfo.serialNumber)
                InfoParamRow(label = "Рег. номер ТС", value = deviceInfo.regNumber)
                InfoParamRow(label = "VIN автомобиля", value = deviceInfo.vin)
                InfoParamRow(label = "Версия стандарта", value = deviceInfo.softwareVersion)
                InfoParamRow(label = "Размерность шин", value = deviceInfo.tyreSize)
                InfoParamRow(label = "K-фактор тахографа", value = "${deviceInfo.kFactor} imp/km")
                InfoParamRow(label = "W-фактор автомобиля", value = "${deviceInfo.wFactor} imp/km")
                InfoParamRow(label = "Дата калибровки", value = deviceInfo.calibrationDate)
                InfoParamRow(label = "Следующая поверка", value = "${deviceInfo.nextInspectionDate} (§ 57b StVZO)", isHighlighted = true)
                InfoParamRow(label = "Станция калибровки", value = deviceInfo.workshopName)
                InfoParamRow(label = "Дистанционный DSRC", value = deviceInfo.dsrcRemoteCompliance, isHighlighted = true)
                InfoParamRow(label = "Пересечения границ ЕС", value = "${deviceInfo.euBorderCrossingsRegistered} (Авто-регистрация)")
            }
        }

        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
private fun SensorStatusCard(
    title: String,
    status: String,
    isActive: Boolean,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0F172A))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isActive) ColorDriving else Color(0xFF94A3B8),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = title, fontSize = 11.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = status, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
private fun InfoParamRow(
    label: String,
    value: String,
    isHighlighted: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 11.sp, color = Color(0xFF94A3B8))
        Text(
            text = value,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (isHighlighted) TachoCyan else Color.White
        )
    }
}
