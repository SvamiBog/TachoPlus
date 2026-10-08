package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DriverCardInfo
import com.example.ui.components.DriverCardBadge
import com.example.ui.theme.ColorDriving
import com.example.ui.theme.TachoCyan

@Composable
fun DriverCardsScreen(
    driverCard1: DriverCardInfo,
    driverCard2: DriverCardInfo,
    onToggleCard: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val isCrewMode = driverCard1.isInserted && driverCard2.isInserted

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(6.dp))

            // Crew Mode Status Banner under EU rules
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isCrewMode) Color(0xFF14532D) else Color(0xFF0F172A))
                    .border(
                        1.dp,
                        if (isCrewMode) ColorDriving else Color(0xFF1E293B),
                        RoundedCornerShape(14.dp)
                    )
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Group,
                    contentDescription = null,
                    tint = if (isCrewMode) ColorDriving else Color(0xFF94A3B8),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = if (isCrewMode) "РЕЖИМ ЭКИПАЖА ЕС (MULTI-MANNING)" else "ОДИНОЧНЫЙ РЕЖИМ (1 ВОДИТЕЛЬ)",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = if (isCrewMode) "Ст. 8(5) Регламента ЕС 561/2006: окно 30 часов, суточный отдых не менее 9 часов для каждого водителя"
                        else "Для экипажа вставьте вторую карту европейского образца в Слот 2",
                        fontSize = 11.sp,
                        color = Color(0xFFCBD5E1)
                    )
                }
            }
        }

        // Slot 1: Driver 1 Smart Card (EU Annex 1C)
        item {
            DriverCardBadge(
                cardInfo = driverCard1,
                onToggleCard = { onToggleCard(1) }
            )
        }

        // Slot 2: Driver 2 / Co-driver Smart Card (EU Annex 1C)
        item {
            DriverCardBadge(
                cardInfo = driverCard2,
                onToggleCard = { onToggleCard(2) }
            )
        }

        // European Crypto Verification Certificate Card
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF111827))
                    .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = TachoCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Европейская сертификация карты (ERCA / MSCA)",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "• Корневой центр сертификации: ERCA (European Root Certification Authority)\n" +
                            "• Закрытые ключи карты: Защищены криптопроцессором SmartCard Gen2 V2 (Brainpool ECC-256)\n" +
                            "• Общеевропейская сеть TACHOnet: Карта верифицирована во всех странах ЕС без ограничений.",
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8),
                    lineHeight = 16.sp
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}
