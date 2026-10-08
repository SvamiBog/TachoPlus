package com.example

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.example.data.bluetooth.BluetoothScanner
import com.example.data.db.TachographDatabase
import com.example.data.link.TachoLinkManager
import com.example.data.repo.ActivityRepository
import com.example.data.repo.EventRepository
import com.example.data.repo.SettingsStore
import com.example.data.repo.ShiftArchiveRepository
import com.example.domain.compliance.ComplianceRules
import com.example.service.AlertNotifier
import com.example.service.ComplianceMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TachoApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        container.notifier.ensureChannels()
    }
}

/** Application-wide singletons. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: TachographDatabase = Room.databaseBuilder(appContext, TachographDatabase::class.java, TachographDatabase.NAME)
        .addMigrations(TachographDatabase.MIGRATION_1_2)
        .build()
    private val dao = database.tachographDao()

    val settings = SettingsStore(appContext)
    val activities = ActivityRepository(dao)
    val events = EventRepository(dao)
    val archive = ShiftArchiveRepository(dao)
    val notifier = AlertNotifier(appContext)
    val scanner = BluetoothScanner(appContext, scope)
    val link = TachoLinkManager(appContext, scope, activities, events, settings)
    val compliance = ComplianceMonitor(appContext, scope, activities, events, settings, notifier, link)

    init {
        scope.launch {
            val cutoff = System.currentTimeMillis() - RETENTION_MS
            activities.prune(cutoff)
            events.prune(cutoff)
        }
    }

    private companion object {
        /** Keep two months: more than the 28 days a driver has to be able to show. */
        const val RETENTION_MS = 62 * ComplianceRules.DAY
    }
}
