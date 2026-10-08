package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ColorDriving
import com.example.ui.theme.TachoCyan
import com.example.ui.theme.TachoRed
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Speedometer. Null values are shown as "—": nothing is invented when the adapter does not report them. */
@Composable
fun SpeedGauge(
    speedKmh: Double?,
    engineRpm: Double?,
    odometerKm: Double?,
    nowMs: Long,
    overspeed: Boolean?,
    modifier: Modifier = Modifier,
    speedLimitKmh: Int = 90
) {
    val maxSpeed = 120f
    val speed = speedKmh?.roundToInt()
    val animatedSpeed by animateFloatAsState(
        targetValue = (speed ?: 0).coerceIn(0, 120).toFloat(),
        animationSpec = tween(durationMillis = 400),
        label = "SpeedGaugeAnimation"
    )
    val moving = (speed ?: 0) > 0
    val tooFast = overspeed == true || (speed ?: 0) > speedLimitKmh

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(24.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (moving) ColorDriving else Color(0xFF64748B))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = when {
                        speed == null -> "СКОРОСТЬ НЕИЗВЕСТНА"
                        moving -> "В ДВИЖЕНИИ"
                        else -> "СТОЯНКА"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (moving) ColorDriving else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = fmtUtcClock(nowMs),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .size(220.dp)
                .testTag("speed_gauge_box"),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 14.dp.toPx()
                val radius = (size.minDimension - strokeWidth) / 2
                val center = Offset(size.width / 2, size.height / 2)
                val startAngle = 140f
                val sweepAngle = 260f
                val legalFraction = speedLimitKmh / maxSpeed

                drawArc(
                    color = Color(0xFF1E293B),
                    startAngle = startAngle,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                drawArc(
                    color = TachoRed.copy(alpha = 0.35f),
                    startAngle = startAngle + sweepAngle * legalFraction,
                    sweepAngle = sweepAngle * (1f - legalFraction),
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                )
                val activeSweep = (animatedSpeed / maxSpeed) * sweepAngle
                if (activeSweep > 0f) {
                    drawArc(
                        color = if (animatedSpeed > speedLimitKmh) TachoRed else TachoCyan,
                        startAngle = startAngle,
                        sweepAngle = activeSweep,
                        useCenter = false,
                        style = Stroke(width = strokeWidth + 2.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
                val ticks = 12
                for (i in 0..ticks) {
                    val angle = Math.toRadians((startAngle + i.toFloat() / ticks * sweepAngle).toDouble())
                    val inner = radius - 18.dp.toPx()
                    val outer = radius - 8.dp.toPx()
                    drawLine(
                        color = if (i * 10 >= speedLimitKmh) TachoRed.copy(alpha = 0.8f) else Color(0xFF64748B),
                        start = Offset((center.x + inner * cos(angle)).toFloat(), (center.y + inner * sin(angle)).toFloat()),
                        end = Offset((center.x + outer * cos(angle)).toFloat(), (center.y + outer * sin(angle)).toFloat()),
                        strokeWidth = if (i % 3 == 0) 3.dp.toPx() else 1.5.dp.toPx()
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(
                    text = speed?.toString() ?: "—",
                    fontSize = 54.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = if (tooFast) TachoRed else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "КМ / Ч",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 2.sp
                )
                if (overspeed == true) {
                    Text(text = "ПРЕВЫШЕНИЕ (тахограф)", fontSize = 10.sp, color = TachoRed, fontWeight = FontWeight.Bold)
                } else {
                    Text(text = "ограничитель $speedLimitKmh", fontSize = 10.sp, color = Color(0xFF64748B))
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF0A0F1A))
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GaugeValue("ОДОМЕТР", odometerKm?.let { String.format(Locale.US, "%,.1f км", it).replace(',', ' ') }, Alignment.Start, TachoCyan)
            GaugeValue("ОБОРОТЫ", engineRpm?.roundToInt()?.toString(), Alignment.End, Color(0xFFE2E8F0))
        }
    }
}

@Composable
private fun GaugeValue(label: String, value: String?, alignment: Alignment.Horizontal, color: Color) {
    Column(horizontalAlignment = alignment) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B), fontSize = 9.sp)
        Text(
            text = value ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = if (value == null) Color(0xFF64748B) else color
        )
    }
}
