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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DriverActivity
import com.example.domain.protocol.TimeRelatedState
import com.example.ui.theme.ColorDriving
import com.example.ui.theme.TachoAmber
import com.example.ui.theme.TachoCyan

/**
 * State of one tachograph card slot as reported over FMS/J1939. Card expiry, issuer and the holder's name are
 * not transmitted by the tachograph, so they are not shown.
 */
@Composable
fun DriverCardBadge(
    slot: Int,
    present: Boolean?,
    cardId: String?,
    driverName: String?,
    activity: DriverActivity?,
    timeState: TimeRelatedState?,
    modifier: Modifier = Modifier
) {
    val title = if (slot == 1) "СЛОТ 1 • ВОДИТЕЛЬ" else "СЛОТ 2 • ВТОРОЙ ВОДИТЕЛЬ"
    val statusText = when (present) {
        true -> "ВСТАВЛЕНА"
        false -> "НЕТ КАРТЫ"
        null -> "НЕТ ДАННЫХ"
    }
    val statusColor = when (present) {
        true -> ColorDriving
        false -> Color(0xFF94A3B8)
        null -> Color(0xFF64748B)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF1E293B), Color(0xFF0F172A))))
            .border(1.dp, Color(0xFF334155), RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(statusColor))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFFCBD5E1))
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(statusColor.copy(alpha = 0.2f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(text = statusText, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = statusColor)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = driverName ?: if (present == true) "Водитель" else "—",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = cardId?.let { "№ $it" } ?: "Номер карты не передаётся",
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = if (cardId != null) TachoCyan else Color(0xFF64748B)
        )
        Spacer(modifier = Modifier.height(8.dp))
        InfoRow("Режим по тахографу", activity?.titleRu)
        InfoRow("Сигнал тахографа по времени", timeState?.titleRu, highlight = timeState != null && timeState != TimeRelatedState.NORMAL)
        if (timeState != null && timeState != TimeRelatedState.NORMAL) {
            Text(text = "Тахограф предупреждает: ${timeState.titleRu}", fontSize = 11.sp, color = TachoAmber)
        }
    }
}
