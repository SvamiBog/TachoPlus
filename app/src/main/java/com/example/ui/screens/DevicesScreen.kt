package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import android.widget.Toast
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.link.LinkState
import com.example.data.link.LinkStatus
import com.example.domain.protocol.ProtocolMode
import com.example.domain.protocol.VehicleLiveState
import com.example.ui.components.IconBadge
import com.example.ui.components.InfoRow
import com.example.ui.components.NoteText
import com.example.ui.components.Panel
import com.example.ui.components.SectionTitle
import com.example.ui.theme.ColorDriving
import com.example.ui.theme.TachoAmber
import com.example.ui.theme.TachoCyan
import com.example.ui.theme.TachoRed
import java.util.Locale

@Composable
fun DevicesScreen(
    link: LinkStatus,
    vehicle: VehicleLiveState,
    terminal: List<String>,
    hasBluetoothPermission: Boolean,
    onRequestPermission: () -> Unit,
    onSelectProtocol: (ProtocolMode) -> Unit,
    onSendCommand: (String) -> Unit,
    onClearTerminal: () -> Unit,
    onOpenBluetoothDialog: () -> Unit,
    onDisconnect: () -> Unit,
    onStartDemo: () -> Unit,
    onStopDemo: () -> Unit,
    modifier: Modifier = Modifier
) {
    var command by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val connected = link.state == LinkState.CONNECTED

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(6.dp))
            if (!hasBluetoothPermission) {
                Panel(background = TachoAmber.copy(alpha = 0.12f), border = TachoAmber.copy(alpha = 0.5f)) {
                    Text("Нужно разрешение Bluetooth", fontWeight = FontWeight.Bold, color = TachoAmber, fontSize = 13.sp)
                    Text("Без него нельзя найти адаптер и подключиться к нему.", fontSize = 11.sp, color = Color(0xFFCBD5E1))
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onRequestPermission, colors = ButtonDefaults.buttonColors(containerColor = TachoAmber)) {
                        Text("Разрешить", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }
            ConnectionPanel(link, onOpenBluetoothDialog, onDisconnect, onStartDemo, onStopDemo)
        }

        item { SectionTitle("ПРОТОКОЛ АДАПТЕРА") }
        item {
            Panel {
                ProtocolMode.entries.forEach { mode ->
                    val selected = mode == link.requestedProtocol
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) TachoCyan.copy(alpha = 0.15f) else Color(0xFF1E293B))
                            .border(1.dp, if (selected) TachoCyan else Color(0xFF334155), RoundedCornerShape(10.dp))
                            .clickable { onSelectProtocol(mode) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(mode.title, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = Color.White)
                            Text(mode.description, fontSize = 10.sp, color = Color(0xFF94A3B8), lineHeight = 13.sp)
                        }
                        if (selected) Icon(Icons.Default.CheckCircle, contentDescription = null, tint = TachoCyan, modifier = Modifier.size(18.dp))
                    }
                }
                if (link.isLinkActive && link.activeProtocol != null && link.activeProtocol != link.requestedProtocol && link.requestedProtocol != ProtocolMode.AUTO) {
                    Text("Новый протокол применится при следующем подключении.", fontSize = 10.sp, color = TachoAmber)
                }
            }
        }

        item { SectionTitle("ДАННЫЕ ОТ АДАПТЕРА") }
        item {
            Panel {
                InfoRow("Скорость (тахограф)", vehicle.tachographSpeedKmh?.let { fmt1(it) + " км/ч" })
                InfoRow("Скорость (колёса/OBD)", vehicle.wheelSpeedKmh?.let { fmt1(it) + " км/ч" })
                InfoRow("Обороты двигателя", vehicle.engineRpm?.let { "%.0f об/мин".format(Locale.US, it) })
                InfoRow("Одометр", vehicle.odometerKm?.let { fmt1(it) + " км" })
                InfoRow("Движение ТС", vehicle.vehicleMotion?.let { if (it) "да" else "нет" })
                InfoRow("Превышение скорости", vehicle.overspeed?.let { if (it) "да" else "нет" })
                InfoRow("Режим водителя 1", vehicle.driver1Activity?.titleRu, highlight = true)
                InfoRow("Режим водителя 2", vehicle.driver2Activity?.titleRu)
                InfoRow("VIN", vehicle.vin)
                InfoRow("Рег. номер", vehicle.registration)
                InfoRow("Адаптер", link.adapterInfo)
                Spacer(modifier = Modifier.height(4.dp))
                NoteText("«нет данных» — адаптер или шина этот параметр не передают. Значения не подставляются.")
            }
        }

        item { SectionTitle("ТЕРМИНАЛ") }
        item {
            Panel(background = Color(0xFF0A0F1A)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Принято ${link.bytesReceived} Б · декодировано ${link.messagesDecoded}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF94A3B8),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(terminal.reversed().joinToString("\n")))
                            Toast.makeText(context, "Журнал скопирован", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(28.dp).testTag("copy_terminal")
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Скопировать журнал", tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onClearTerminal, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Очистить", tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp, max = 260.dp)
                        .verticalScroll(rememberScrollState())
                        .testTag("terminal_log")
                ) {
                    if (terminal.isEmpty()) {
                        Text("Журнал обмена пуст", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF475569))
                    }
                    terminal.forEach { line ->
                        Text(
                            text = line,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 13.sp,
                            color = when {
                                line.contains("[ERR]") -> TachoRed
                                line.contains(" TX ") -> TachoCyan
                                line.contains("[SYS]") -> TachoAmber
                                else -> Color(0xFFCBD5E1)
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        enabled = connected,
                        singleLine = true,
                        placeholder = { Text(if (!connected) "Подключите адаптер" else if (link.activeProtocol == ProtocolMode.ITS_TACHOGRAPH) "REQ 1…7 или hex" else "Команда, напр. ATRV", fontSize = 11.sp) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = Color.White),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TachoCyan),
                        modifier = Modifier.weight(1f).testTag("terminal_input")
                    )
                    IconButton(
                        enabled = connected && command.isNotBlank(),
                        onClick = {
                            onSendCommand(command.trim())
                            command = ""
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить", tint = if (connected) TachoCyan else Color(0xFF475569))
                    }
                }
            }
        }

        item { SectionTitle("ЧТО ПОДДЕРЖИВАЕТСЯ") }
        item {
            Panel {
                NoteText(
                    "• Грузовик: адаптер ELM327/STN/OBDLink с поддержкой J1939, подключённый к шине FMS (интерфейс для систем мониторинга). " +
                        "Тахограф передаёт туда режимы водителей, наличие карт и скорость (TCO1).\n" +
                        "• Фургон/легковой: OBD-II — только скорость, обороты и VIN; режим «Управление» включается по движению.\n" +
                        "• Свой шлюз: строки KEY=VALUE или candump по Bluetooth SPP/BLE (см. README).\n" +
                        "• Тахограф с Bluetooth (например, «DTCO-…»): открытый ITS-интерфейс по Регламенту 2016/799, прил. 13 — экспериментально. " +
                        "Нужны сопряжение, PIN тахографа и согласие водителя на передачу данных.\n" +
                        "• Фирменные сервисы (VDO SmartLink по BLE, приложения производителей) закрыты и не поддерживаются."
                )
            }
        }

        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

@Composable
private fun ConnectionPanel(
    link: LinkStatus,
    onOpenBluetoothDialog: () -> Unit,
    onDisconnect: () -> Unit,
    onStartDemo: () -> Unit,
    onStopDemo: () -> Unit
) {
    val active = link.isLinkActive || link.state == LinkState.DEMO
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                if (active) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                when (link.state) {
                    LinkState.CONNECTED, LinkState.DEMO -> ColorDriving
                    LinkState.ERROR -> TachoRed
                    LinkState.CONNECTING, LinkState.RECONNECTING -> TachoAmber
                    else -> Color(0xFF94A3B8)
                }
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(link.state.titleRu, fontWeight = FontWeight.Bold, color = if (link.state == LinkState.ERROR) TachoRed else Color.White)
                Text(
                    text = listOfNotNull(link.deviceName, link.deviceAddress, link.transport?.title).joinToString(" · ").ifEmpty { "Адаптер не выбран" },
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8)
                )
                link.activeProtocol?.let { Text(it.title, fontSize = 11.sp, color = TachoCyan) }
            }
        }
        link.message?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, fontSize = 11.sp, color = if (link.state == LinkState.ERROR) TachoRed else TachoAmber, lineHeight = 15.sp)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                link.state == LinkState.DEMO -> Button(
                    onClick = onStopDemo,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = TachoAmber)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, tint = Color(0xFF0F172A), modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Выйти из демо", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold)
                }
                link.isLinkActive -> Button(
                    onClick = onDisconnect,
                    modifier = Modifier.weight(1f).testTag("disconnect_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = TachoRed)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Отключить", fontWeight = FontWeight.Bold)
                }
                else -> {
                    Button(
                        onClick = onOpenBluetoothDialog,
                        modifier = Modifier.weight(1f).testTag("open_scanner_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = TachoCyan)
                    ) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null, tint = Color(0xFF0F172A), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Найти адаптер", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = onStartDemo, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Демо")
                    }
                }
            }
        }
    }
}

private fun fmt1(value: Double) = String.format(Locale.US, "%.1f", value)
