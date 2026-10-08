package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.example.data.link.LinkState
import com.example.data.link.LinkStatus
import com.example.data.model.DriverActivity
import com.example.domain.compliance.ComplianceStatus
import com.example.domain.protocol.VehicleLiveState
import com.example.ui.components.ActivitySelector
import com.example.ui.components.AlertBanner
import com.example.ui.components.ComplianceOverviewCard
import com.example.ui.components.SpeedGauge
import com.example.ui.components.fmtDuration
import com.example.ui.components.fmtLocalClock
import com.example.ui.theme.ColorDriving
import com.example.ui.theme.TachoAmber
import com.example.ui.theme.TachoCyan
import com.example.ui.theme.TachoRed

@Composable
fun DashboardScreen(
    link: LinkStatus,
    vehicle: VehicleLiveState,
    compliance: ComplianceStatus,
    onSelectActivity: (DriverActivity) -> Unit,
    onOpenBluetoothDialog: () -> Unit,
    onDisconnect: () -> Unit,
    onStopDemo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val manualAllowed = !(link.state == LinkState.CONNECTED && link.tachographProvidesActivity)
    val hint = when {
        link.state == LinkState.DEMO -> "Демо: режим можно менять, данные вымышленные."
        !manualAllowed -> "Режим передаёт тахограф. Меняйте его кнопками на тахографе."
        link.state == LinkState.CONNECTED -> "Тахограф режим не передаёт: выберите вручную. При движении включится «Управление»."
        else -> "Без тахографа отмечайте режим вручную — от этого зависят все таймеры."
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(6.dp))
            DriverHeader(vehicle = vehicle, nowMs = compliance.nowMs)
        }

        item { LinkCard(link, onOpenBluetoothDialog, onDisconnect, onStopDemo) }

        compliance.topAlert?.let { alert -> item { AlertBanner(alert) } }

        item {
            SpeedGauge(
                speedKmh = vehicle.speedKmh,
                engineRpm = vehicle.engineRpm,
                odometerKm = vehicle.odometerKm,
                nowMs = compliance.nowMs,
                overspeed = vehicle.overspeed
            )
        }

        item {
            val since = compliance.currentActivitySinceMs?.let { fmtDuration(compliance.nowMs - it) }
            ActivitySelector(
                currentActivity = if (compliance.currentActivityAssumed) null else compliance.currentActivity,
                sinceText = if (compliance.currentActivityAssumed) "нет данных ${since ?: ""}".trim() else since,
                enabled = manualAllowed,
                hint = hint,
                onSelectActivity = onSelectActivity
            )
        }

        item { ComplianceOverviewCard(status = compliance) }

        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

@Composable
private fun DriverHeader(vehicle: VehicleLiveState, nowMs: Long) {
    val present = vehicle.driver1CardPresent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0F172A))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(
                imageVector = Icons.Default.CreditCard,
                contentDescription = null,
                tint = if (present == true) ColorDriving else Color(0xFF94A3B8),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = when (present) {
                        true -> vehicle.driver1Name ?: "Карта водителя в слоте 1"
                        false -> "Карты в слоте 1 нет"
                        null -> "Данных о карте нет"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1
                )
                Text(
                    text = vehicle.driver1Id?.let { "№ $it" } ?: "Номер карты не передан",
                    fontSize = 10.sp,
                    color = if (vehicle.driver1Id != null) TachoCyan else Color(0xFF94A3B8)
                )
            }
        }
        Text(
            text = fmtLocalClock(nowMs),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = TachoCyan,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun LinkCard(link: LinkStatus, onConnect: () -> Unit, onDisconnect: () -> Unit, onStopDemo: () -> Unit) {
    val receiving = link.state == LinkState.CONNECTED && link.messagesDecoded > 0
    val color = when {
        link.state == LinkState.DEMO -> TachoCyan
        receiving -> ColorDriving
        link.state == LinkState.CONNECTED -> TachoAmber
        link.isLinkActive -> TachoAmber
        link.state == LinkState.ERROR -> TachoRed
        else -> Color(0xFF64748B)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF131D2E))
            .border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
            .padding(12.dp)
            .testTag("tachograph_stream_status_card")
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        link.state == LinkState.DEMO -> "ДЕМО-РЕЖИМ · данные вымышленные"
                        receiving -> "ДАННЫЕ ПОСТУПАЮТ"
                        else -> link.state.titleRu.uppercase()
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
                val details = listOfNotNull(link.deviceName, link.activeProtocol?.title).joinToString(" · ")
                if (details.isNotEmpty()) Text(text = details, fontSize = 11.sp, color = Color(0xFFCBD5E1), maxLines = 1)
            }
            when {
                link.state == LinkState.DEMO -> SmallButton("Выйти", Icons.Default.Stop, onStopDemo)
                link.isLinkActive -> SmallButton("Отключить", Icons.Default.Stop, onDisconnect)
                else -> SmallButton("Подключить", Icons.Default.Bluetooth, onConnect)
            }
        }
        if (link.state == LinkState.CONNECTED) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Принято ${link.bytesReceived} Б · сообщений ${link.messagesDecoded}" +
                    if (!link.tachographProvidesActivity) " · режим водителя от тахографа не получен" else "",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF94A3B8)
            )
        }
        link.message?.let {
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = it, fontSize = 11.sp, color = if (link.state == LinkState.ERROR) TachoRed else TachoAmber, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun SmallButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = TachoCyan),
        modifier = Modifier.height(30.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = Color(0xFF0F172A), modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(text, color = Color(0xFF0F172A), fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}
