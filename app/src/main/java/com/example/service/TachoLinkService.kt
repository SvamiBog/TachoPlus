package com.example.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.TachoApplication
import com.example.data.bluetooth.BluetoothPermissionManager
import com.example.data.link.LinkState
import com.example.data.model.formatSecondsToHhMm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while a Bluetooth link is open, so the connection and the timers keep running with
 * the screen off. Stops itself when the link is closed.
 */
class TachoLinkService : Service() {

    companion object {
        private const val TAG = "TachoLinkService"

        fun start(context: Context) {
            if (!BluetoothPermissionManager.hasConnectPermission(context)) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, TachoLinkService::class.java))
            } catch (e: Exception) {
                // Starting from the background is not allowed on Android 12+; the link still works in the foreground.
                Log.w(TAG, "Could not start foreground service: ${e.message}")
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as TachoApplication).container
        val notification = container.notifier.linkNotification("Тахограф Про", "Подключение к адаптеру…")
        try {
            ServiceCompat.startForeground(
                this,
                AlertNotifier.LINK_NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
            )
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (observer == null) {
            observer = scope.launch {
                combine(container.link.status, container.compliance.status) { link, compliance ->
                    if (!link.isLinkActive) return@combine null
                    val title = when (link.state) {
                        LinkState.CONNECTED -> "Подключено: ${link.deviceName ?: "адаптер"}"
                        LinkState.RECONNECTING -> "Переподключение к ${link.deviceName ?: "адаптеру"}"
                        else -> "Подключение к ${link.deviceName ?: "адаптеру"}"
                    }
                    val activity = compliance.currentActivity?.titleRu ?: "режим неизвестен"
                    val remaining = formatSecondsToHhMm(compliance.remainingContinuousDrivingMs / 1000)
                    title to "$activity · до перерыва $remaining"
                }.distinctUntilChanged().collect { content ->
                    if (content == null) {
                        ServiceCompat.stopForeground(this@TachoLinkService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        container.notifier.updateLinkNotification(container.notifier.linkNotification(content.first, content.second))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** Re-checks the limits when an alarm fires, after a reboot or an app update. */
class ComplianceAlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_CHECK = "com.example.action.CHECK_COMPLIANCE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(ACTION_CHECK, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val app = context.applicationContext as? TachoApplication ?: return
        val pending = goAsync()
        app.container.scope.launch {
            try {
                app.container.compliance.checkInBackground()
            } finally {
                pending.finish()
            }
        }
    }
}
