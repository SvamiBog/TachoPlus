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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AvTimer
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.HomeWork
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.example.ui.theme.ColorAvailable
import com.example.ui.theme.ColorDriving
import com.example.ui.theme.ColorRest
import com.example.ui.theme.TachoAmber
import com.example.ui.theme.TachoCyan
import com.example.ui.theme.TachoRed

@Composable
fun TimersScreen(
    compliance: WorkRestCompliance,
    onSaveCurrentShift: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(6.dp))
            // Regulation Info Header Banner
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0F172A))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = TachoCyan,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Регламент (ЕС) № 561/2006 • Mobility Package I",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Единые европейские правила времени вождения и отдыха водителей в странах ЕС",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
            }
        }

        // Section 1: Continuous Driving & Break
        item {
            TimerDetailCard(
                title = "Непрерывное вождение (Continuous Driving)",
                subtitle = "Лимит ЕС: максимум 04:30 без перерыва (ст. 7)",
                currentStr = formatSecondsToHhMm(compliance.continuousDrivingSeconds),
                maxStr = "04:30",
                remainingStr = "До перерыва: ${formatSecondsToHhMm(compliance.remainingContinuousSeconds)}",
                progress = compliance.continuousDrivingProgress,
                accentColor = if (compliance.continuousDrivingSeconds >= compliance.maxContinuousDrivingSeconds) TachoRed else ColorDriving,
                icon = Icons.Default.AvTimer,
                note = "Перерыв 45 мин непрерывно, либо сплит: СТРОГО сначала ≥15 мин, затем ≥30 мин (только в таком порядке согласно ст. 7 Регламента 561/2006)."
            )
        }

        item {
            TimerDetailCard(
                title = "Обязательный перерыв (Break / Pause)",
                subtitle = "Требуется ЕС: 45 минут (или 15+30 мин)",
                currentStr = formatSecondsToHhMm(compliance.accumulatedBreakSeconds),
                maxStr = "00:45",
                remainingStr = "Осталось отдыхать: ${formatSecondsToHhMm((2700 - compliance.accumulatedBreakSeconds).coerceAtLeast(0))}",
                progress = compliance.breakProgress,
                accentColor = ColorRest,
                icon = Icons.Default.Coffee,
                note = if (compliance.accumulatedBreakSeconds >= 2700) "Перерыв полностью выполнен! Таймер непрерывного вождения сброшен."
                else "После завершения 45 мин отдыха таймер вождения 04:30 обнулится."
            )
        }

        // Section 2: Daily Driving & Rest
        item {
            TimerDetailCard(
                title = "Суточное вождение (Daily Driving)",
                subtitle = "Базовый лимит: 9 часов (до 10 ч дважды в неделю, ст. 6)",
                currentStr = formatSecondsToHhMm(compliance.dailyDrivingSeconds),
                maxStr = "09:00",
                remainingStr = "Запас на сегодня: ${formatSecondsToHhMm(compliance.remainingDailyDrivingSeconds)}",
                progress = compliance.dailyDrivingProgress,
                accentColor = TachoCyan,
                icon = Icons.Default.Alarm,
                note = "Использовано продлений до 10 часов на этой неделе: ${compliance.extendedDailyDaysUsedThisWeek} из 2 разрешенных."
            )
        }

        item {
            TimerDetailCard(
                title = "Суточный межсменный отдых (Daily Rest)",
                subtitle = "Регулярный 11ч (или 3ч+9ч), сокращенный 9ч (ст. 8)",
                currentStr = formatSecondsToHhMm(compliance.requiredDailyRestSeconds),
                maxStr = "11:00",
                remainingStr = "Требуется перед следующей сменой: 11:00",
                progress = 1.0f,
                accentColor = ColorAvailable,
                icon = Icons.Default.Hotel,
                note = "Суточный отдых должен завершиться в течение 24 часов с момента начала смены (или 30ч для экипажа из 2 водителей)."
            )
        }

        // Section 3: Weekly & Bi-Weekly Limits
        item {
            TimerDetailCard(
                title = "Еженедельное вождение (Weekly Driving)",
                subtitle = "Лимит ЕС: не более 56 часов за календарную неделю (ст. 6)",
                currentStr = formatSecondsToHhMm(compliance.weeklyDrivingSeconds),
                maxStr = "56:00",
                remainingStr = "Осталось на неделю: ${formatSecondsToHhMm(compliance.remainingWeeklyDrivingSeconds)}",
                progress = (compliance.weeklyDrivingSeconds.toFloat() / compliance.maxWeeklyDrivingSeconds).coerceIn(0f, 1f),
                accentColor = TachoAmber,
                icon = Icons.Default.DateRange,
                note = "Календарная неделя в ЕС считается с 00:00 понедельника до 24:00 воскресенья по времени UTC."
            )
        }

        item {
            TimerDetailCard(
                title = "Двухнедельное вождение (Bi-weekly Driving)",
                subtitle = "Лимит ЕС: не более 90 часов за любые 2 недели подряд (ст. 6)",
                currentStr = formatSecondsToHhMm(compliance.biweeklyDrivingSeconds),
                maxStr = "90:00",
                remainingStr = "Осталось за 2 недели: ${formatSecondsToHhMm((324000 - compliance.biweeklyDrivingSeconds).coerceAtLeast(0))}",
                progress = (compliance.biweeklyDrivingSeconds.toFloat() / compliance.maxBiweeklyDrivingSeconds).coerceIn(0f, 1f),
                accentColor = Color(0xFF60A5FA),
                icon = Icons.Default.EventNote,
                note = "Сумма часов вождения за 1-ю и 2-ю неделю не должна превышать 90 часов."
            )
        }

        // Section 4: EU Mobility Package I rules (Ferry rule & Cabin rest prohibition)
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF131D2E))
                    .border(1.dp, Color(0xFF1E2E4A), RoundedCornerShape(18.dp))
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.HomeWork,
                        contentDescription = null,
                        tint = TachoAmber,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Пакет мобильности ЕС: Запрет отдыха 45ч в кабине",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "• Регулярный еженедельный отдых (≥45ч) СТРОГО ЗАПРЕЩЕН в кабине транспортного средства (ст. 8(8) Регламента 561/2006). Обязательно размещение в гостинице за счет работодателя.\n" +
                            "• Возврат водителя на базу: каждые 4 недели водитель должен возвращаться в страну регистрации или место проживания.",
                    fontSize = 11.sp,
                    color = Color(0xFFCBD5E1),
                    lineHeight = 15.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.DirectionsBoat,
                        contentDescription = null,
                        tint = TachoCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Правило парома / поезда (Ferry Rule, ст. 9):",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Прерывание регулярного суточного отдыха (11ч) разрешено не более 2 раз на суммарное время до 1 часа при наличии спального места в каюте.",
                    fontSize = 10.sp,
                    color = Color(0xFF94A3B8)
                )
            }
        }

        // Save Shift Button
        item {
            Button(
                onClick = onSaveCurrentShift,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("save_current_shift_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ColorDriving)
            ) {
                Icon(imageVector = Icons.Default.Save, contentDescription = null, tint = Color(0xFF0F172A))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Зафиксировать и сохранить смену ЕС в архив",
                    color = Color(0xFF0F172A),
                    fontWeight = FontWeight.Bold
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
private fun TimerDetailCard(
    title: String,
    subtitle: String,
    currentStr: String,
    maxStr: String,
    remainingStr: String,
    progress: Float,
    accentColor: Color,
    icon: ImageVector,
    note: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = subtitle,
                        fontSize = 10.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$currentStr / $maxStr",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
                Text(
                    text = remainingStr,
                    fontSize = 10.sp,
                    color = Color(0xFFCBD5E1)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape),
            color = accentColor,
            trackColor = Color(0xFF0F172A),
            strokeCap = StrokeCap.Round
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = note,
            fontSize = 11.sp,
            color = Color(0xFF64748B),
            lineHeight = 15.sp
        )
    }
}
