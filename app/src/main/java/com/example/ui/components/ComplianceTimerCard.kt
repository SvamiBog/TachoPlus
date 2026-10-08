package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AvTimer
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DriverActivity
import com.example.domain.compliance.ComplianceRules
import com.example.domain.compliance.ComplianceStatus
import com.example.ui.theme.ColorDriving
import com.example.ui.theme.ColorRest
import com.example.ui.theme.TachoAmber
import com.example.ui.theme.TachoCyan
import com.example.ui.theme.TachoRed

/** Summary of the most urgent limits for the dashboard. */
@Composable
fun ComplianceOverviewCard(status: ComplianceStatus, modifier: Modifier = Modifier) {
    Panel(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "РЕЖИМ ТРУДА И ОТДЫХА (561/2006)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
            Text(
                text = when {
                    !status.hasData -> "нет записей"
                    status.inWeeklyRest -> "еженед. отдых"
                    status.inDailyRest -> "суточный отдых"
                    else -> "Смена: ${fmtDuration(status.shiftElapsedMs)}"
                },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = TachoCyan,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            val continuous = status.continuousDrivingMs
            TimerDial(
                title = "Непрерывное",
                current = fmtDuration(continuous),
                max = "/ 04:30",
                remaining = "Осталось ${fmtDuration(status.remainingContinuousDrivingMs)}",
                progress = status.continuousDrivingProgress,
                color = when {
                    continuous > ComplianceRules.MAX_CONTINUOUS_DRIVING -> TachoRed
                    status.remainingContinuousDrivingMs <= ComplianceRules.WARNING_LEAD -> TachoAmber
                    else -> ColorDriving
                },
                icon = Icons.Default.AvTimer,
                testTag = "timer_continuous_dial"
            )
            val daily = status.dailyDrivingMs
            TimerDial(
                title = "Суточное",
                current = fmtDuration(daily),
                max = "/ 09:00",
                remaining = if (status.inDailyRest || status.inWeeklyRest) "в отдыхе" else "Осталось ${fmtDuration(status.remainingDailyDrivingMs)}",
                progress = status.dailyDrivingProgress,
                color = when {
                    daily > status.dailyDrivingLimitMs -> TachoRed
                    daily > ComplianceRules.DAILY_DRIVING -> TachoAmber
                    else -> TachoCyan
                },
                icon = Icons.Default.Alarm,
                testTag = "timer_daily_dial"
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        val resting = status.currentActivity == DriverActivity.REST && !status.inDailyRest && !status.inWeeklyRest
        MetricBar(
            title = when {
                resting -> "Текущий перерыв"
                status.splitBreakFirstPartTaken -> "Перерыв: 15 мин есть, нужно ещё 30"
                else -> "Обязательный перерыв 45 мин (или 15 + 30)"
            },
            value = if (resting) "${fmtDuration(status.currentBreakMs)} / ${fmtDuration(status.currentBreakRequiredMs)}" else "не начат",
            progress = if (resting) status.breakProgress else 0f,
            color = ColorRest,
            icon = Icons.Default.Coffee
        )
        Spacer(modifier = Modifier.height(10.dp))
        MetricBar(
            title = "Неделя (с пн 00:00 UTC)",
            value = "${fmtDuration(status.weeklyDrivingMs)} / 56:00",
            progress = (status.weeklyDrivingMs.toFloat() / ComplianceRules.MAX_WEEKLY_DRIVING).coerceIn(0f, 1f),
            color = TachoAmber,
            icon = Icons.Default.AvTimer
        )
    }
}

@Composable
private fun TimerDial(
    title: String,
    current: String,
    max: String,
    remaining: String,
    progress: Float,
    color: Color,
    icon: ImageVector,
    testTag: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.testTag(testTag)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(105.dp)) {
            CircularProgressIndicator(
                progress = { 1f },
                modifier = Modifier.size(105.dp),
                color = Color(0xFF1E293B),
                strokeWidth = 9.dp,
                strokeCap = StrokeCap.Round
            )
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(105.dp),
                color = color,
                strokeWidth = 9.dp,
                strokeCap = StrokeCap.Round
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                Text(text = current, fontSize = 17.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White)
                Text(text = max, fontSize = 10.sp, color = Color(0xFF94A3B8))
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.White)
        Text(text = remaining, style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8), fontSize = 10.sp)
    }
}

@Composable
fun MetricBar(title: String, value: String, progress: Float, color: Color, icon: ImageVector) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = title, style = MaterialTheme.typography.labelSmall, color = Color(0xFFCBD5E1))
            }
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape),
            color = color,
            trackColor = Color(0xFF1E293B),
            strokeCap = StrokeCap.Round
        )
    }
}
