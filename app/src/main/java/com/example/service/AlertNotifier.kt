package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.R
import com.example.domain.compliance.AlertSeverity
import com.example.domain.compliance.ComplianceAlert

class AlertNotifier(private val context: Context) {

    companion object {
        const val CHANNEL_ALERTS = "compliance_alerts"
        const val CHANNEL_LINK = "link_status"
        const val LINK_NOTIFICATION_ID = 1
        private const val ALERT_ID_BASE = 1000
    }

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ALERTS, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("Режим труда и отдыха")
                .setDescription("Предупреждения о лимитах вождения и отдыха")
                .build()
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_LINK, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Связь с адаптером")
                .setDescription("Постоянное уведомление, пока приложение держит Bluetooth-соединение")
                .build()
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    @SuppressLint("MissingPermission") // guarded by areNotificationsEnabled()
    fun notifyAlert(alert: ComplianceAlert, demo: Boolean) {
        if (!manager.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_tacho)
            .setContentTitle((if (demo) "Демо: " else "") + alert.title)
            .setContentText(alert.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.message))
            .setPriority(if (alert.severity == AlertSeverity.VIOLATION) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(ALERT_ID_BASE + alert.kind.ordinal, notification)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    fun linkNotification(title: String, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_LINK)
            .setSmallIcon(R.drawable.ic_stat_tacho)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openAppIntent())
            .build()

    @SuppressLint("MissingPermission")
    fun updateLinkNotification(notification: Notification) {
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(LINK_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
        }
    }
}
