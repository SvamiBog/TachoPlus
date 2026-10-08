package com.example.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.TachographDatabase
import com.example.data.model.ActivitySource
import com.example.data.model.DriverActivity
import com.example.data.repo.ActivityGroup
import com.example.data.repo.ActivityRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataLayerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun migrationFromVersion1KeepsUserDataAndDropsTheSeededDemoShift() = runBlocking {
        val name = "migration-test.db"
        context.deleteDatabase(name)
        context.getDatabasePath(name).parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `saved_shift_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`dateString` TEXT NOT NULL, `startTime` INTEGER NOT NULL, `endTime` INTEGER NOT NULL, " +
                    "`totalDrivingMinutes` INTEGER NOT NULL, `totalWorkMinutes` INTEGER NOT NULL, `totalRestMinutes` INTEGER NOT NULL, " +
                    "`totalDistanceKm` REAL NOT NULL, `driverName` TEXT NOT NULL, `driverCardNumber` TEXT NOT NULL, " +
                    "`vehiclePlate` TEXT NOT NULL, `isDddExported` INTEGER NOT NULL, `summaryNotes` TEXT NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `tachograph_events_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`timestamp` INTEGER NOT NULL, `code` TEXT NOT NULL, `title` TEXT NOT NULL, `description` TEXT NOT NULL, " +
                    "`severity` TEXT NOT NULL)"
            )
            val insert = "INSERT INTO saved_shift_sessions (dateString, startTime, endTime, totalDrivingMinutes, totalWorkMinutes, " +
                "totalRestMinutes, totalDistanceKm, driverName, driverCardNumber, vehiclePlate, isDddExported, summaryNotes) VALUES "
            repeat(3) { db.execSQL(insert + "('01.10.2026', 1, 2, 480, 75, 540, 594.3, 'SCHMIDT HANS-PETER', 'DED00000849201 0', 'B-MW 5420 (DE)', 1, 'seed')") }
            db.execSQL(insert + "('02.10.2026', 3, 4, 300, 60, 45, 250.0, 'IVANOV', 'RU000', 'A123BC', 0, 'mine')")
            db.version = 1
        }

        val room = Room.databaseBuilder(context, TachographDatabase::class.java, name)
            .addMigrations(TachographDatabase.MIGRATION_1_2)
            .build()
        try {
            val sessions = room.tachographDao().getAllSessions().first()
            assertEquals(listOf("IVANOV"), sessions.map { it.driverName })

            val repo = ActivityRepository(room.tachographDao())
            repo.record(DriverActivity.DRIVING, ActivitySource.MANUAL, 1_000, 100.0)
            assertEquals(DriverActivity.DRIVING, repo.current(ActivityGroup.REAL)?.activity)
        } finally {
            room.close()
        }
    }

    @Test
    fun activityRecordingDeduplicatesAndKeepsDemoSeparate() = runBlocking {
        val room = Room.inMemoryDatabaseBuilder(context, TachographDatabase::class.java).build()
        try {
            val repo = ActivityRepository(room.tachographDao())
            repo.record(DriverActivity.WORK, ActivitySource.MANUAL, 1_000, null)
            repo.record(DriverActivity.WORK, ActivitySource.MANUAL, 2_000, null) // repeat: no new period
            repo.record(DriverActivity.DRIVING, ActivitySource.TACHOGRAPH, 3_000, 500.0)
            repo.record(DriverActivity.REST, ActivitySource.DEMO, 3_500, null)

            val real = repo.load(ActivityGroup.REAL, 0)
            assertEquals(listOf(DriverActivity.WORK, DriverActivity.DRIVING), real.map { it.activity })
            assertEquals(3_000L, real[0].endMs)
            assertNull(real[1].endMs)
            assertEquals(500.0, repo.odometerAt(ActivityGroup.REAL, 3_000)!!, 0.0)

            repo.closeOpen(ActivityGroup.REAL, 4_000, onlySource = ActivitySource.TACHOGRAPH)
            assertNull(repo.current(ActivityGroup.REAL))

            assertEquals(1, repo.load(ActivityGroup.DEMO, 0).size)
            repo.clear(ActivityGroup.DEMO)
            assertEquals(0, repo.load(ActivityGroup.DEMO, 0).size)
            assertEquals(2, repo.load(ActivityGroup.REAL, 0).size)
        } finally {
            room.close()
        }
    }
}
