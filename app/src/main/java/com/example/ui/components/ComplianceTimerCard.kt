package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AvTimer
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Warning
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
import com.example.data.model.WorkRestCompliance
import com.example.data.model.formatSecondsToHhMm
import com.example.ui.theme.ColorDriving
import com.example.ui.theme.ColorRest
import com.example.ui.theme.TachoAmber
import com.example.ui.theme.TachoCyan
import com.example.ui.theme.TachoRed

@Composable
fun ComplianceOverviewCard(
    compliance: WorkRestCompliance,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        // Warning Banner if violation or threshold warning
        AnimatedVisibility(visible = compliance.hasWarning || compliance.isViolation) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (compliance.isViolation) TachoRed.copy(alpha = 0.2f) else TachoAmber.copy(alpha = 0.2f))
                    .border(
                        1.dp,
                        if (compliance.isViolation) TachoRed else TachoAmber,
                        RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Предупреждение",
                    tint = if (compliance.isViolation) TachoRed else TachoAmber,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = compliance.warningMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (compliance.isViolation) TachoRed else TachoAmber,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "РЕЖИМ ТРУДА И ОТДЫХА (РЕГЛАМЕНТ ЕС 561/2006)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
            Text(
                text = "Смена: ${formatSecondsToHhMm(compliance.shiftDurationSeconds)}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = TachoCyan,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Two prominent Circular Progress Ring dials
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            // Dial 1: Continuous Driving (Непрерывное вождение)
            val contProgress = compliance.continuousDrivingProgress
            val contColor = when {
                compliance.continuousDrivingSeconds >= compliance.maxContinuousDrivingSeconds -> TachoRed
                compliance.continuousDrivingSeconds >= 14400 -> TachoAmber // > 4h
                else -> ColorDriving
            }

            TimerDialItem(
                title = "Непрерывное",
                currentText = formatSecondsToHhMm(compliance.continuousDrivingSeconds),
                maxText = "/ 04:30",
                remainingText = "Осталось: ${formatSecondsToHhMm(compliance.remainingContinuousSeconds)}",
                progress = contProgress,
                accentColor = contColor,
                icon = Icons.Default.AvTimer,
                testTag = "timer_continuous_dial"
            )

            // Dial 2: Daily Driving (Дневное вождение)
            val dailyProgress = compliance.dailyDrivingProgress
            val dailyColor = when {
                compliance.dailyDrivingSeconds >= compliance.maxDailyDrivingSeconds -> TachoRed
                compliance.dailyDrivingSeconds >= 28800 -> TachoAmber // > 8h
                else -> TachoCyan
            }

            TimerDialItem(
                title = "Суточное",
                currentText = formatSecondsToHhMm(compliance.dailyDrivingSeconds),
                maxText = "/ ${formatSecondsToHhMm(compliance.maxDailyDrivingSeconds)}",
                remainingText = "Осталось: ${formatSecondsToHhMm(compliance.remainingDailyDrivingSeconds)}",
                progress = dailyProgress,
                accentColor = dailyColor,
                icon = Icons.Default.Alarm,
                testTag = "timer_daily_dial"
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Horizontal Bars for Break and Shift
        LinearMetricBar(
            title = "Обязательный перерыв (отдых)",
            currentStr = formatSecondsToHhMm(compliance.accumulatedBreakSeconds),
            targetStr = "00:45",
            progress = compliance.breakProgress,
            accentColor = ColorRest,
            icon = Icons.Default.Coffee
        )

        Spacer(modifier = Modifier.height(10.dp))

        LinearMetricBar(
            title = "Длительность смены (макс 13/15ч)",
            currentStr = formatSecondsToHhMm(compliance.shiftDurationSeconds),
            targetStr = "13:00",
            progress = (compliance.shiftDurationSeconds.toFloat() / compliance.maxShiftDurationSeconds).coerceIn(0f, 1f),
            accentColor = TachoAmber,
            icon = Icons.Default.AvTimer
        )
    }
}

@Composable
private fun TimerDialItem(
    title: String,
    currentText: String,
    maxText: String,
    remainingText: String,
    progress: Float,
    accentColor: Color,
    icon: ImageVector,
    testTag: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.testTag(testTag)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(105.dp)) {
            // Background track
            CircularProgressIndicator(
                progress = { 1f },
                modifier = Modifier.size(105.dp),
                color = Color(0xFF1E293B),
                strokeWidth = 9.dp,
                strokeCap = StrokeCap.Round
            )
            // Active progress
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(105.dp),
                color = accentColor,
                strokeWidth = 9.dp,
                strokeCap = StrokeCap.Round
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = currentText,
                    fontSize = 17.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = maxText,
                    fontSize = 10.sp,
                    color = Color(0xFF94A3B8)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = remainingText,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF94A3B8),
            fontSize = 10.sp
        )
    }
}

@Composable
private fun LinearMetricBar(
    title: String,
    currentStr: String,
    targetStr: String,
    progress: Float,
    accentColor: Color,
    icon: ImageVector
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFCBD5E1)
                )
            }
            Text(
                text = "$currentStr / $targetStr",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = accentColor,
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
            color = accentColor,
            trackColor = Color(0xFF1E293B),
            strokeCap = StrokeCap.Round
        )
    }
}
