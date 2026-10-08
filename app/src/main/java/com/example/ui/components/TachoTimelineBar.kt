package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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

/** 24-hour bar of today's recorded activities (local time). Time without data is drawn faded. */
@Composable
fun TachoTimelineBar(segments: List<ActivityTimelineSegment>, modifier: Modifier = Modifier) {
    Panel(modifier = modifier.testTag("tacho_timeline_bar")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "СЕГОДНЯ (МЕСТНОЕ ВРЕМЯ)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold
            )
            Text(text = "00:00 — 24:00", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = Color(0xFF94A3B8))
        }

        Spacer(modifier = Modifier.height(10.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(6.dp))
        ) {
            val minutesPerDay = 1440f
            drawRoundRect(color = Color(0xFF0F172A), size = size, cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()))
            segments.forEach { seg ->
                val x = seg.startMinuteOfDay / minutesPerDay * size.width
                val w = (seg.durationMinutes / minutesPerDay * size.width).coerceAtLeast(1f)
                val color = activityColor(seg.activity)
                drawRect(
                    color = if (seg.assumed) color.copy(alpha = 0.25f) else color,
                    topLeft = Offset(x, if (seg.activity == DriverActivity.REST) size.height * 0.5f else 0f),
                    size = Size(w, if (seg.activity == DriverActivity.REST) size.height * 0.5f else size.height)
                )
            }
            for (h in 1..5) {
                val x = (h * 240) / minutesPerDay * size.width
                drawLine(Color.White.copy(alpha = 0.25f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("00", "04", "08", "12", "16", "20", "24").forEach {
                Text(text = it, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF64748B))
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DriverActivity.entries.forEach { LegendItem(activityColor(it), it.titleRu) }
            LegendItem(Color(0xFF8B5CF6).copy(alpha = 0.25f), "Нет данных")
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, fontSize = 10.sp, color = Color(0xFFCBD5E1))
    }
}
