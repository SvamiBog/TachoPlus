package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.link.LinkState
import com.example.data.link.LinkStatus
import com.example.domain.protocol.VehicleLiveState
import com.example.ui.components.DriverCardBadge
import com.example.ui.components.IconBadge
import com.example.ui.components.NoteText
import com.example.ui.components.Panel
import com.example.ui.theme.ColorDriving

@Composable
fun DriverCardsScreen(
    vehicle: VehicleLiveState,
    link: LinkStatus,
    modifier: Modifier = Modifier
) {
    val crew = vehicle.driver1CardPresent == true && vehicle.driver2CardPresent == true
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(6.dp))
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Default.Group, if (crew) ColorDriving else Color(0xFF94A3B8))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (crew) "ДВА ВОДИТЕЛЯ (ЭКИПАЖ)" else "ОДИН ВОДИТЕЛЬ",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (crew) "Экипаж: суточный отдых не менее 9 ч в течение 30 ч после окончания предыдущего отдыха (ст. 8(5))."
                    else "Таймеры приложения рассчитаны на одного водителя.",
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8),
                    lineHeight = 15.sp
                )
            }
        }

        item {
            DriverCardBadge(
                slot = 1,
                present = vehicle.driver1CardPresent,
                cardId = vehicle.driver1Id,
                driverName = vehicle.driver1Name,
                activity = vehicle.driver1Activity,
                timeState = vehicle.driver1TimeState
            )
        }

        item {
            DriverCardBadge(
                slot = 2,
                present = vehicle.driver2CardPresent,
                cardId = vehicle.driver2Id,
                driverName = null,
                activity = vehicle.driver2Activity,
                timeState = vehicle.driver2TimeState
            )
        }

        item {
            Panel {
                NoteText(
                    when (link.state) {
                        LinkState.CONNECTED -> "Наличие карт и режимы передаёт тахограф в сообщении TCO1 шины FMS. " +
                            "Номер карты — в сообщении DI, если его разрешено передавать. Имя, срок действия и орган выдачи тахограф не передаёт."
                        LinkState.DEMO -> "Демо-режим: данные карт вымышленные."
                        else -> "Подключите адаптер к шине FMS грузовика, чтобы видеть состояние слотов тахографа. " +
                            "Вставить или извлечь карту можно только в самом тахографе."
                    }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}
