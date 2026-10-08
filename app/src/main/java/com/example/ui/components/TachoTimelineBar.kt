package com.example.ui.components

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ActivityTimelineSegment
import com.example.data.model.DriverActivity
import com.example.ui.theme.ColorAvailable
import com.example.ui.theme.ColorDriving
import com.example.ui.theme.ColorRest
import com.example.ui.theme.ColorWork

@Composable
fun TachoTimelineBar(
    segments: List<ActivityTimelineSegment>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
            .padding(14.dp)
            .testTag("tacho_timeline_bar")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "СУТОЧНЫЙ ГРАФИК (24 ЧАСА)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "00:00 — 24:00",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF94A3B8)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 24-hour horizontal bar
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(6.dp))
        ) {
            val totalMinutesInDay = 1440f
            val barWidth = size.width
            val barHeight = size.height

            // Background empty track
            drawRoundRect(
                color = Color(0xFF0F172A),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
            )

            // Draw activity blocks
            segments.forEach { seg ->
                val startX = (seg.startMinuteOfDay / totalMinutesInDay) * barWidth
                val segWidth = (seg.durationMinutes / totalMinutesInDay) * barWidth
                val color = when (seg.activity) {
                    DriverActivity.DRIVING -> ColorDriving
                    DriverActivity.WORK -> ColorWork
                    DriverActivity.AVAILABLE -> ColorAvailable
                    DriverActivity.REST -> ColorRest
                }

                drawRect(
                    color = color,
                    topLeft = Offset(startX, 0f),
                    size = Size(segWidth.coerceAtLeast(1f), barHeight)
                )
            }

            // Draw hour tick divisions (every 4 hours: 04, 08, 12, 16, 20)
            for (h in 1..5) {
                val tickX = ((h * 4 * 60) / totalMinutesInDay) * barWidth
                drawLine(
                    color = Color.White.copy(alpha = 0.25f),
                    start = Offset(tickX, 0f),
                    end = Offset(tickX, barHeight),
                    strokeWidth = 1.dp.toPx()
                )
            }
        }

        // Hour labels underneath
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf("00:00", "04:00", "08:00", "12:00", "16:00", "20:00", "24:00").forEach { hourStr ->
                Text(
                    text = hourStr,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF64748B)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Legend chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            LegendItem(color = ColorDriving, label = "Вождение")
            LegendItem(color = ColorWork, label = "Работа")
            LegendItem(color = ColorAvailable, label = "Готовность")
            LegendItem(color = ColorRest, label = "Отдых")
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            color = Color(0xFFCBD5E1)
        )
    }
}
