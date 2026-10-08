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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ColorDriving
import com.example.ui.theme.TachoAmber
import com.example.ui.theme.TachoCyan
import com.example.ui.theme.TachoRed
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun SpeedGauge(
    speedKmh: Int,
    speedLimitKmh: Int = 90,
    engineRpm: Int,
    totalOdometerKm: Long,
    tripOdometerKm: Double,
    utcTime: String,
    modifier: Modifier = Modifier
) {
    val maxSpeed = 120f
    val currentSpeedClamped = speedKmh.coerceIn(0, 120).toFloat()
    val animatedSpeed by animateFloatAsState(
        targetValue = currentSpeedClamped,
        animationSpec = tween(durationMillis = 400),
        label = "SpeedGaugeAnimation"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(24.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top row: UTC / Tachograph clock and GNSS status
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
                        .background(if (speedKmh > 0) ColorDriving else Color(0xFF64748B))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (speedKmh > 0) "В ДВИЖЕНИИ" else "СТОЯНКА",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (speedKmh > 0) ColorDriving else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Tachograph UTC Clock (standard on all tachographs)
            Text(
                text = if (utcTime.isNotEmpty()) utcTime else "--:--:-- UTC",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Main Speed Gauge Arc
        Box(
            modifier = Modifier
                .size(230.dp)
                .testTag("speed_gauge_box"),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 14.dp.toPx()
                val radius = (size.minDimension - strokeWidth) / 2
                val centerOffset = Offset(size.width / 2, size.height / 2)

                val startAngle = 140f
                val sweepAngle = 260f

                // Background track
                drawArc(
                    color = Color(0xFF1E293B),
                    startAngle = startAngle,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // Legal speed limit track portion (0 to 90 km/h)
                val legalFraction = (speedLimitKmh / maxSpeed)
                val legalSweep = sweepAngle * legalFraction
                drawArc(
                    brush = Brush.sweepGradient(
                        0.0f to Color(0xFF0284C7),
                        0.5f to Color(0xFF10B981),
                        1.0f to Color(0xFFF59E0B),
                        center = centerOffset
                    ),
                    startAngle = startAngle,
                    sweepAngle = legalSweep,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // Danger zone track portion (90 to 120 km/h)
                val dangerSweep = sweepAngle * (1f - legalFraction)
                drawArc(
                    color = TachoRed.copy(alpha = 0.85f),
                    startAngle = startAngle + legalSweep,
                    sweepAngle = dangerSweep,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // Active speed fill indicator
                val activeSweep = (animatedSpeed / maxSpeed) * sweepAngle
                if (activeSweep > 0f) {
                    val activeColor = if (animatedSpeed > speedLimitKmh) TachoRed else TachoCyan
                    drawArc(
                        color = activeColor,
                        startAngle = startAngle,
                        sweepAngle = activeSweep,
                        useCenter = false,
                        style = Stroke(width = strokeWidth + 2.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                // Ticks along the gauge
                val totalTicks = 12
                for (i in 0..totalTicks) {
                    val tickAngle = startAngle + (i.toFloat() / totalTicks) * sweepAngle
                    val tickRad = Math.toRadians(tickAngle.toDouble())
                    val innerR = radius - 18.dp.toPx()
                    val outerR = radius - 8.dp.toPx()

                    val startX = (centerOffset.x + innerR * cos(tickRad)).toFloat()
                    val startY = (centerOffset.y + innerR * sin(tickRad)).toFloat()
                    val endX = (centerOffset.x + outerR * cos(tickRad)).toFloat()
                    val endY = (centerOffset.y + outerR * sin(tickRad)).toFloat()

                    val tickColor = if (i >= 9) TachoRed.copy(alpha = 0.8f) else Color(0xFF64748B)
                    drawLine(
                        color = tickColor,
                        start = Offset(startX, startY),
                        end = Offset(endX, endY),
                        strokeWidth = if (i % 3 == 0) 3.dp.toPx() else 1.5.dp.toPx()
                    )
                }
            }

            // Gauge Center Display: Digital Speedometer
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "$speedKmh",
                    fontSize = 54.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = if (speedKmh > speedLimitKmh) TachoRed else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "КМ / Ч",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 2.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Speed limit badge (Traffic Sign Style: Red Circle)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0F172A))
                        .border(1.dp, Color(0xFF334155), RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(2.dp, TachoRed, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "$speedLimitKmh",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "ОГРАНИЧЕНИЕ",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Digital Odometer & Engine Telemetry bar (LCD style)
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
            Column {
                Text(
                    text = "ОБЩИЙ ПРОБЕГ",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF64748B),
                    fontSize = 9.sp
                )
                Text(
                    text = "$totalOdometerKm км",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = TachoCyan
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "ОБОРОТЫ RPM",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF64748B),
                    fontSize = 9.sp
                )
                Text(
                    text = "$engineRpm",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (engineRpm > 2100) TachoAmber else Color(0xFFE2E8F0)
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "СУТОЧНЫЙ РЕЙС",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF64748B),
                    fontSize = 9.sp
                )
                Text(
                    text = "$tripOdometerKm км",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF10B981)
                )
            }
        }
    }
}
