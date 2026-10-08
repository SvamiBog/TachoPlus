package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.bluetooth.ConnectionState
import com.example.data.model.DriverActivity
import com.example.data.model.DriverCardInfo
import com.example.data.model.TachographLiveTelemetry
import com.example.data.model.WorkRestCompliance
import com.example.ui.components.ActivitySelector
import com.example.ui.components.ComplianceOverviewCard
import com.example.ui.components.SpeedGauge
import com.example.ui.theme.ColorDriving
import com.example.ui.theme.TachoAmber
import com.example.ui.theme.TachoCyan
import com.example.ui.theme.TachoRed

@Composable
fun DashboardScreen(
    telemetry: TachographLiveTelemetry,
    compliance: WorkRestCompliance,
    currentActivity: DriverActivity,
    driverCard1: DriverCardInfo,
    connectionState: ConnectionState,
    isRealDataActive: Boolean = false,
    activeProtocolName: String = "",
    streamPacketsReceived: Long = 0,
    streamBytesReceived: Long = 0,
    lastRawPacket: String = "",
    onSelectActivity: (DriverActivity) -> Unit,
    onPollTachograph: () -> Unit = {},
    onOpenBluetoothDialog: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isConnected = connectionState == ConnectionState.CONNECTED
    val isDemo = connectionState == ConnectionState.DEMO_MODE

    val pulseTransition = rememberInfiniteTransition(label = "LivePulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(6.dp))

            // Quick Status Header Pill: Real Driver Card & Local Time
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.CreditCard,
                        contentDescription = null,
                        tint = if (driverCard1.isInserted) ColorDriving else Color(0xFF94A3B8),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = if (driverCard1.isInserted) driverCard1.driverName else "Карта не вставлена в Слот 1",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1
                        )
                        if (driverCard1.isInserted) {
                            Text(
                                text = "Номер: ${driverCard1.cardNumber} • ${driverCard1.issuingCountry}",
                                fontSize = 10.sp,
                                color = TachoCyan
                            )
                        } else {
                            Text(
                                text = if (isConnected) "Ожидание вставки карты в тахограф..." else "Подключитесь к тахографу",
                                fontSize = 10.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }
                }

                Text(
                    text = if (telemetry.localTime.length >= 8) telemetry.localTime.takeLast(8) else "00:00:00",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = TachoCyan,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Live Real Tachograph Stream Telemetry Card
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        when {
                            isRealDataActive -> Color(0xFF064E3B).copy(alpha = 0.35f)
                            isConnected -> Color(0xFF1E293B)
                            isDemo -> Color(0xFF0F172A)
                            else -> Color(0xFF1E1E24)
                        }
                    )
                    .border(
                        1.dp,
                        when {
                            isRealDataActive -> ColorDriving
                            isConnected -> TachoCyan
                            isDemo -> Color(0xFF38BDF8)
                            else -> Color(0xFF334155)
                        },
                        RoundedCornerShape(16.dp)
                    )
                    .padding(12.dp)
                    .testTag("tachograph_stream_status_card")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .scale(if (isRealDataActive) pulseScale else 1f)
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
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when {
                                isRealDataActive -> "РЕАЛЬНЫЙ ПОТОК ТАХОГРАФА (ОНЛАЙН)"
                                isConnected -> "КАНАЛ ОТКРЫТ (ОПРОС ТАХОГРАФА)"
                                isDemo -> "РЕЖИМ СИМУЛЯЦИИ (ДЕМО)"
                                else -> "ТАХОГРАФ НЕ ПОДКЛЮЧЕН"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                isRealDataActive -> ColorDriving
                                isConnected -> TachoAmber
                                isDemo -> TachoCyan
                                else -> Color(0xFFCBD5E1)
                            }
                        )
                    }

                    if (isConnected) {
                        Button(
                            onClick = onPollTachograph,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = TachoCyan),
                            modifier = Modifier.height(30.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, tint = Color(0xFF0F172A), modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Опросить", color = Color(0xFF0F172A), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = onOpenBluetoothDialog,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = TachoCyan),
                            modifier = Modifier.height(30.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Bluetooth, contentDescription = null, tint = Color(0xFF0F172A), modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Подключить", color = Color(0xFF0F172A), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (isConnected || isDemo) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (activeProtocolName.isNotEmpty()) activeProtocolName else "Определение протокола...",
                            fontSize = 11.sp,
                            color = Color(0xFFCBD5E1),
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Пакетов: $streamPacketsReceived ($streamBytesReceived B)",
                            fontSize = 11.sp,
                            color = TachoCyan,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    if (lastRawPacket.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Кадр: $lastRawPacket",
                            fontSize = 10.sp,
                            color = Color(0xFF94A3B8),
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // 1. Cockpit Speedometer Gauge (Real-time speed & RPM)
        item {
            SpeedGauge(
                speedKmh = telemetry.speedKmh,
                speedLimitKmh = telemetry.speedLimitKmh,
                engineRpm = telemetry.engineRpm,
                totalOdometerKm = telemetry.totalOdometerKm,
                tripOdometerKm = telemetry.tripOdometerKm,
                utcTime = telemetry.utcTime
            )
        }

        // 2. Physical Tachograph Activity Mode Buttons (Drive / Rest / Work / Available)
        item {
            ActivitySelector(
                currentActivity = currentActivity,
                onSelectActivity = onSelectActivity
            )
        }

        // 3. Work/Rest Compliance Dials & Counters (EU Regulation 561/2006)
        item {
            ComplianceOverviewCard(compliance = compliance)
        }

        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}
