package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AvTimer
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Weekend
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
import com.example.data.model.DriverActivity
import com.example.domain.compliance.ComplianceRules
import com.example.domain.compliance.ComplianceStatus
import com.example.ui.components.AlertBanner
import com.example.ui.components.IconBadge
import com.example.ui.components.NoteText
import com.example.ui.components.Panel
import com.example.ui.components.fmtDuration
import com.example.ui.components.fmtLocalDateTime
import com.example.ui.theme.ColorAvailable
import com.example.ui.theme.ColorDriving
import com.example.ui.theme.ColorRest
import com.example.ui.theme.TachoAmber
import com.example.ui.theme.TachoCyan
import com.example.ui.theme.TachoRed

@Composable
fun TimersScreen(
    status: ComplianceStatus,
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
            Panel {
                Text(
                    text = "Регламент (ЕС) № 561/2006 · Пакет мобильности",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                NoteText(
                    "Расчёт по журналу приложения. Юридически значимы только данные тахографа." +
                        (status.trackingSinceMs?.let { " Записи с ${fmtLocalDateTime(it)}." } ?: " Записей пока нет.") +
                        if (status.assumedRestMs > 0) " Время без данных (${fmtDuration(status.assumedRestMs)} за 2 недели) считается отдыхом." else ""
                )
            }
        }

        items(status.alerts) { AlertBanner(it) }

        item {
            TimerDetailCard(
                title = "Непрерывное вождение",
                subtitle = "Не более 04:30 без перерыва (ст. 7)",
                current = fmtDuration(status.continuousDrivingMs),
                max = "04:30",
                remaining = "До перерыва: ${fmtDuration(status.remainingContinuousDrivingMs)}",
                progress = status.continuousDrivingProgress,
                color = if (status.continuousDrivingMs > ComplianceRules.MAX_CONTINUOUS_DRIVING) TachoRed else ColorDriving,
                icon = Icons.Default.AvTimer,
                note = "Перерыв 45 мин подряд или 15 мин, а затем 30 мин — именно в таком порядке."
            )
        }

        item {
            val resting = status.currentActivity == DriverActivity.REST && !status.inDailyRest && !status.inWeeklyRest
            TimerDetailCard(
                title = "Перерыв",
                subtitle = if (status.splitBreakFirstPartTaken) "Первая часть (15 мин) засчитана — нужно ещё 30 мин" else "Нужно 45 мин (или 15 + 30)",
                current = if (resting) fmtDuration(status.currentBreakMs) else "—",
                max = fmtDuration(status.currentBreakRequiredMs.takeIf { resting } ?: if (status.splitBreakFirstPartTaken) ComplianceRules.BREAK_SPLIT_SECOND else ComplianceRules.BREAK_FULL),
                remaining = if (resting) "Осталось: ${fmtDuration((status.currentBreakRequiredMs - status.currentBreakMs).coerceAtLeast(0))}" else "Сейчас не перерыв",
                progress = if (resting) status.breakProgress else 0f,
                color = ColorRest,
                icon = Icons.Default.Coffee,
                note = "Перерывы короче 15 мин не учитываются. Другая работа и готовность перерывом не являются."
            )
        }

        item {
            val extensionsLeft = ComplianceRules.MAX_EXTENSIONS_PER_WEEK - status.extensionsUsedThisWeek
            TimerDetailCard(
                title = "Суточное вождение",
                subtitle = "9 ч, до 10 ч не более 2 раз в неделю (ст. 6)",
                current = fmtDuration(status.dailyDrivingMs),
                max = fmtDuration(status.dailyDrivingLimitMs),
                remaining = if (status.inDailyRest || status.inWeeklyRest) "Идёт отдых" else "Запас: ${fmtDuration(status.remainingDailyDrivingMs)}",
                progress = status.dailyDrivingProgress,
                color = if (status.dailyDrivingMs > status.dailyDrivingLimitMs) TachoRed else TachoCyan,
                icon = Icons.Default.Alarm,
                note = "Продлений до 10 ч на этой неделе осталось: $extensionsLeft из ${ComplianceRules.MAX_EXTENSIONS_PER_WEEK}."
            )
        }

        item {
            val restTarget = if (status.inDailyRest || status.inWeeklyRest) ComplianceRules.DAILY_REST_REGULAR else status.nextDailyRestRequiredMs
            TimerDetailCard(
                title = "Суточный отдых",
                subtitle = "11 ч (или 3 ч + 9 ч), сокращённый 9 ч — не более 3 раз (ст. 8)",
                current = if (status.currentActivity == DriverActivity.REST) fmtDuration(status.currentRestMs) else "—",
                max = fmtDuration(restTarget),
                remaining = when {
                    status.inWeeklyRest -> "Идёт еженедельный отдых"
                    status.inDailyRest -> if (status.currentRestMs >= ComplianceRules.DAILY_REST_REGULAR) "Отдых выполнен" else "Идёт отдых"
                    status.latestDailyRestStartMs != null -> "Начать до ${fmtLocalDateTime(status.latestDailyRestStartMs)}"
                    else -> "Начало смены неизвестно"
                },
                progress = if (status.currentActivity == DriverActivity.REST) (status.currentRestMs.toFloat() / restTarget).coerceIn(0f, 1f) else 0f,
                color = ColorAvailable,
                icon = Icons.Default.Hotel,
                note = "Сокращённых суточных отдыхов после еженедельного: ${status.reducedDailyRestsUsed} из ${ComplianceRules.MAX_REDUCED_DAILY_RESTS}." +
                    (status.shiftStartMs?.takeIf { status.shiftStartKnown }?.let { " Смена началась ${fmtLocalDateTime(it)}." } ?: "")
            )
        }

        item {
            TimerDetailCard(
                title = "Недельное вождение",
                subtitle = "Не более 56 ч за неделю: пн 00:00 – вс 24:00 UTC (ст. 6)",
                current = fmtDuration(status.weeklyDrivingMs),
                max = "56:00",
                remaining = "Осталось: ${fmtDuration(status.remainingWeeklyDrivingMs)}",
                progress = (status.weeklyDrivingMs.toFloat() / ComplianceRules.MAX_WEEKLY_DRIVING).coerceIn(0f, 1f),
                color = if (status.weeklyDrivingMs > ComplianceRules.MAX_WEEKLY_DRIVING) TachoRed else TachoAmber,
                icon = Icons.Default.DateRange,
                note = "«Осталось» учитывает и лимит за две недели."
            )
        }

        item {
            TimerDetailCard(
                title = "Вождение за 2 недели",
                subtitle = "Не более 90 ч за две недели подряд (ст. 6)",
                current = fmtDuration(status.biweeklyDrivingMs),
                max = "90:00",
                remaining = "Прошлая неделя: ${fmtDuration(status.previousWeekDrivingMs)}",
                progress = (status.biweeklyDrivingMs.toFloat() / ComplianceRules.MAX_BIWEEKLY_DRIVING).coerceIn(0f, 1f),
                color = Color(0xFF60A5FA),
                icon = Icons.AutoMirrored.Filled.EventNote,
                note = "Если приложение использовалось меньше двух недель, значение может быть неполным."
            )
        }

        item {
            TimerDetailCard(
                title = "Еженедельный отдых",
                subtitle = "45 ч (сокращённый 24 ч), не позднее 6×24 ч после предыдущего (ст. 8)",
                current = if (status.inWeeklyRest) fmtDuration(status.currentRestMs) else "—",
                max = "45:00",
                remaining = when {
                    status.inWeeklyRest -> "Идёт еженедельный отдых"
                    status.weeklyRestDueMs != null -> "Начать до ${fmtLocalDateTime(status.weeklyRestDueMs)}"
                    else -> "Предыдущий отдых не записан"
                },
                progress = if (status.inWeeklyRest) (status.currentRestMs.toFloat() / ComplianceRules.WEEKLY_REST_REGULAR).coerceIn(0f, 1f) else 0f,
                color = ColorRest,
                icon = Icons.Default.Weekend,
                note = "Обычный еженедельный отдых нельзя проводить в кабине (ст. 8(8))."
            )
        }

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
                Text(text = "Сохранить текущую смену в архив", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold)
            }
        }

        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

@Composable
private fun TimerDetailCard(
    title: String,
    subtitle: String,
    current: String,
    max: String,
    remaining: String,
    progress: Float,
    color: Color,
    icon: ImageVector,
    note: String
) {
    Panel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                IconBadge(icon, color)
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(text = subtitle, fontSize = 10.sp, color = Color(0xFF94A3B8), lineHeight = 13.sp)
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$current / $max",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
                Text(text = remaining, fontSize = 10.sp, color = Color(0xFFCBD5E1))
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape),
            color = color,
            trackColor = Color(0xFF0F172A),
            strokeCap = StrokeCap.Round
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = note, fontSize = 11.sp, color = Color(0xFF64748B), lineHeight = 15.sp)
    }
}
