package com.example.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "saved_shift_sessions")
data class SavedShiftSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val dateString: String,
    val startTime: Long,
    val endTime: Long,
    val totalDrivingMinutes: Int,
    val totalWorkMinutes: Int,
    val totalRestMinutes: Int,
    val totalDistanceKm: Double,
    val driverName: String,
    val driverCardNumber: String,
    val vehiclePlate: String,
    val isDddExported: Boolean = false,
    val summaryNotes: String = ""
)

@Entity(tableName = "tachograph_events_history")
data class SavedEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val code: String,
    val title: String,
    val description: String,
    val severity: String
)

/** One recorded activity interval; endMs == null while it is still going on. */
@Entity(tableName = "activity_periods", indices = [Index("startMs")])
data class ActivityPeriodEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val activity: String,
    val startMs: Long,
    val endMs: Long?,
    val source: String,
    val odometerKm: Double?
)

@Dao
interface TachographDao {
    @Query("SELECT * FROM saved_shift_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<SavedShiftSession>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SavedShiftSession): Long

    @Query("DELETE FROM saved_shift_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)

    @Query("SELECT * FROM tachograph_events_history ORDER BY timestamp DESC LIMIT 100")
    fun getRecentEvents(): Flow<List<SavedEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: SavedEventEntity)

    @Query("DELETE FROM tachograph_events_history WHERE timestamp < :beforeMs")
    suspend fun pruneEvents(beforeMs: Long)

    @Query(
        "SELECT * FROM activity_periods WHERE (endMs IS NULL OR endMs >= :sinceMs) AND source IN (:sources) ORDER BY startMs"
    )
    fun observePeriods(sinceMs: Long, sources: List<String>): Flow<List<ActivityPeriodEntity>>

    @Query(
        "SELECT * FROM activity_periods WHERE (endMs IS NULL OR endMs >= :sinceMs) AND source IN (:sources) ORDER BY startMs"
    )
    suspend fun periodsSince(sinceMs: Long, sources: List<String>): List<ActivityPeriodEntity>

    @Query("SELECT * FROM activity_periods WHERE endMs IS NULL AND source IN (:sources) ORDER BY startMs DESC LIMIT 1")
    suspend fun openPeriod(sources: List<String>): ActivityPeriodEntity?

    @Query("UPDATE activity_periods SET endMs = :endMs WHERE endMs IS NULL AND source IN (:sources)")
    suspend fun closeOpenPeriods(sources: List<String>, endMs: Long)

    @Insert
    suspend fun insertPeriod(period: ActivityPeriodEntity): Long

    @Query("DELETE FROM activity_periods WHERE source = :source")
    suspend fun deletePeriodsBySource(source: String)

    @Query("DELETE FROM activity_periods WHERE endMs IS NOT NULL AND endMs < :beforeMs")
    suspend fun prunePeriods(beforeMs: Long)
}

@Database(
    entities = [SavedShiftSession::class, SavedEventEntity::class, ActivityPeriodEntity::class],
    version = 2,
    exportSchema = false
)
abstract class TachographDatabase : RoomDatabase() {
    abstract fun tachographDao(): TachographDao

    companion object {
        const val NAME = "tachograph_pro.db"

        /**
         * Adds the activity log and removes the demo shift that version 1 inserted on every start
         * (driver "SCHMIDT HANS-PETER").
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `activity_periods` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`activity` TEXT NOT NULL, " +
                        "`startMs` INTEGER NOT NULL, " +
                        "`endMs` INTEGER, " +
                        "`source` TEXT NOT NULL, " +
                        "`odometerKm` REAL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_periods_startMs` ON `activity_periods` (`startMs`)")
                db.execSQL("DELETE FROM saved_shift_sessions WHERE driverName = 'SCHMIDT HANS-PETER'")
            }
        }
    }
}
