package com.example.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
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

@Dao
interface TachographDao {
    @Query("SELECT * FROM saved_shift_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<SavedShiftSession>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SavedShiftSession): Long

    @Query("SELECT * FROM tachograph_events_history ORDER BY timestamp DESC LIMIT 50")
    fun getRecentEvents(): Flow<List<SavedEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: SavedEventEntity)

    @Query("DELETE FROM saved_shift_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)
}

@Database(entities = [SavedShiftSession::class, SavedEventEntity::class], version = 1, exportSchema = false)
abstract class TachographDatabase : RoomDatabase() {
    abstract fun tachographDao(): TachographDao

    companion object {
        @Volatile
        private var INSTANCE: TachographDatabase? = null

        fun getDatabase(context: Context): TachographDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TachographDatabase::class.java,
                    "tachograph_pro.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
