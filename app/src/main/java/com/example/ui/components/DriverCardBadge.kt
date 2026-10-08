package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Eject
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DriverCardInfo
import com.example.ui.theme.ColorDriving
import com.example.ui.theme.TachoCyan
import com.example.ui.theme.TachoRed

@Composable
fun DriverCardBadge(
    cardInfo: DriverCardInfo,
    onToggleCard: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isSlot1 = cardInfo.slotNumber == 1
    val slotTitle = if (isSlot1) "СЛОТ 1 • ГЛАВНЫЙ ВОДИТЕЛЬ" else "СЛОТ 2 • ЭКИПАЖ / СМЕНЩИК"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    colors = if (cardInfo.isInserted) {
                        listOf(Color(0xFF1E293B), Color(0xFF0F172A))
                    } else {
                        listOf(Color(0xFF1A1F2C), Color(0xFF11141E))
                    }
                )
            )
            .border(
                1.dp,
                if (cardInfo.isInserted) Color(0xFF334155) else Color(0xFF1E293B),
                RoundedCornerShape(20.dp)
            )
            .padding(16.dp)
            .testTag("driver_card_slot_${cardInfo.slotNumber}")
    ) {
        // Slot Header & Status Pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (cardInfo.isInserted) ColorDriving else Color(0xFF64748B))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = slotTitle,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (cardInfo.isInserted) TachoCyan else Color(0xFF94A3B8),
                    letterSpacing = 0.5.sp
                )
            }

            // Card status pill
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (cardInfo.isInserted) ColorDriving.copy(alpha = 0.2f)
                        else Color(0xFF334155).copy(alpha = 0.4f)
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = if (cardInfo.isInserted) "ВСТАВЛЕНА" else "ИЗВЛЕЧЕНА",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (cardInfo.isInserted) ColorDriving else Color(0xFF94A3B8)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (cardInfo.isInserted) {
            // Smart Card Body Graphic
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0D1424))
                    .border(1.dp, Color(0xFF1E2E4A), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Gold Chip representation
                Box(
                    modifier = Modifier
                        .size(36.dp, 28.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFFFBBF24), Color(0xFFD97706))
                            )
                        )
                        .border(1.dp, Color(0xFFB45309), RoundedCornerShape(4.dp))
                )

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = cardInfo.driverName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "№ ${cardInfo.cardNumber}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = TachoCyan
                    )
                    Text(
                        text = "Действует до: ${cardInfo.expiryDate} (${cardInfo.issuingAuthority})",
                        fontSize = 10.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
            }
        } else {
            // Empty Slot Graphic
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF090D15))
                    .border(1.dp, Color(0xFF192233), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Слот свободен. Вставьте карту тахографа.",
                    fontSize = 12.sp,
                    color = Color(0xFF64748B)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Action Button: Eject or Insert
        OutlinedButton(
            onClick = onToggleCard,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("toggle_card_button_slot_${cardInfo.slotNumber}"),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (cardInfo.isInserted) TachoRed else ColorDriving
            )
        ) {
            Icon(
                imageVector = if (cardInfo.isInserted) Icons.Default.Eject else Icons.Default.CreditCard,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (cardInfo.isInserted) "Извлечь карту из слота ${cardInfo.slotNumber}"
                else "Вставить карту в слот ${cardInfo.slotNumber}",
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
