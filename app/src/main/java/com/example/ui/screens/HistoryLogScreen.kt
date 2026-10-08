package com.example.ui.screens

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.db.SavedShiftSession
import com.example.data.model.ActivityTimelineSegment
import com.example.data.model.EventSeverity
import com.example.data.model.TachographEvent
import com.example.ui.components.NoteText
import com.example.ui.components.Panel
import com.example.ui.components.SectionTitle
import com.example.ui.components.TachoTimelineBar
import com.example.ui.components.fmtLocalDateTime
import com.example.ui.components.fmtLocalTime
import com.example.ui.theme.TachoAmber
import com.example.ui.theme.TachoCyan
import com.example.ui.theme.TachoDarkSurface
import com.example.ui.theme.TachoRed
import java.util.Locale

@Composable
fun HistoryLogScreen(
    timeline: List<ActivityTimelineSegment>,
    events: List<TachographEvent>,
    savedSessions: List<SavedShiftSession>,
    report: String?,
    onBuildReport: () -> Unit,
    onDismissReport: () -> Unit,
    onDeleteSession: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var sessionToDelete by remember { mutableStateOf<SavedShiftSession?>(null) }

    if (report != null) {
        Dialog(onDismissRequest = onDismissReport) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = TachoDarkSurface,
                border = BorderStroke(1.dp, Color(0xFF334155)),
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Отчёт по журналу", fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismissReport) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.White)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0A0F1A))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = report,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFE2E8F0),
                            lineHeight = 13.sp,
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = onDismissReport, modifier = Modifier.weight(1f)) { Text("Закрыть") }
                        Button(
                            onClick = {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "Отчёт по журналу активности")
                                    putExtra(Intent.EXTRA_TEXT, report)
                                }
                                context.startActivity(Intent.createChooser(send, "Отправить отчёт"))
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = TachoCyan)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, tint = Color(0xFF0F172A), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Отправить", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    sessionToDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = { Text("Удалить смену?") },
            text = { Text("Смена ${session.dateString} будет удалена из архива.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSession(session.id)
                    sessionToDelete = null
                }) { Text("Удалить", color = TachoRed) }
            },
            dismissButton = { TextButton(onClick = { sessionToDelete = null }) { Text("Отмена") } }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(6.dp))
            Panel {
                Text("Отчёт за 28 дней", fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.height(4.dp))
                NoteText(
                    "Текстовый отчёт из журнала приложения: смены, режимы по дням, нарушения. " +
                        "Это не файл DDD: выгрузку тахографа делают картой предприятия или специальным устройством."
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onBuildReport,
                    modifier = Modifier.fillMaxWidth().testTag("build_report_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = TachoCyan)
                ) {
                    Icon(Icons.Default.Description, contentDescription = null, tint = Color(0xFF0F172A), modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Сформировать отчёт", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold)
                }
            }
        }

        item { TachoTimelineBar(segments = timeline) }

        item { SectionTitle("СОБЫТИЯ") }
        if (events.isEmpty()) {
            item { Text("Событий пока нет", fontSize = 12.sp, color = Color(0xFF64748B)) }
        } else {
            items(events, key = { it.id }) { EventRow(it) }
        }

        item { SectionTitle("АРХИВ СМЕН") }
        if (savedSessions.isEmpty()) {
            item { Text("Архив пуст. Сохраните смену на вкладке «Таймеры».", fontSize = 12.sp, color = Color(0xFF64748B)) }
        } else {
            items(savedSessions, key = { it.id }) { session ->
                SessionCard(session = session, onDelete = { sessionToDelete = session })
            }
        }

        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

@Composable
private fun EventRow(event: TachographEvent) {
    val color = when (event.severity) {
        EventSeverity.CRITICAL -> TachoRed
        EventSeverity.WARNING -> TachoAmber
        EventSeverity.INFO -> Color(0xFF38BDF8)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(color).align(Alignment.CenterVertically))
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(event.title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                Text(fmtLocalDateTime(event.timestamp), fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF94A3B8))
            }
            Text(event.description, fontSize = 11.sp, color = Color(0xFFCBD5E1), lineHeight = 15.sp)
        }
    }
}

@Composable
private fun SessionCard(session: SavedShiftSession, onDelete: () -> Unit) {
    Panel {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.History, contentDescription = null, tint = TachoCyan, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "${session.dateString} ${fmtLocalTime(session.startTime)}–${fmtLocalTime(session.endTime)}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = Color(0xFF64748B), modifier = Modifier.size(16.dp))
            }
        }
        Text(
            text = "Вождение ${session.totalDrivingMinutes / 60} ч ${session.totalDrivingMinutes % 60} мин · " +
                "работа ${session.totalWorkMinutes / 60} ч ${session.totalWorkMinutes % 60} мин · " +
                "перерывы ${session.totalRestMinutes} мин",
            fontSize = 11.sp,
            color = Color(0xFFCBD5E1)
        )
        Text(
            text = "Водитель: ${session.driverName} · ТС: ${session.vehiclePlate}" +
                if (session.totalDistanceKm > 0) " · ${String.format(Locale.US, "%.1f", session.totalDistanceKm)} км" else "",
            fontSize = 11.sp,
            color = Color(0xFF94A3B8)
        )
        if (session.summaryNotes.isNotEmpty()) {
            Text(text = session.summaryNotes, fontSize = 11.sp, color = Color(0xFF64748B), lineHeight = 15.sp)
        }
    }
}
