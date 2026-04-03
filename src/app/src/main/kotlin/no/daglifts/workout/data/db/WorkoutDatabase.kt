package no.daglifts.workout.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import no.daglifts.workout.data.model.ExerciseData

// ── Entities ──────────────────────────────────────────────────────────────────

/**
 * Stored sessions. The [exercises] column holds JSON (Map<String, ExerciseData>).
 * This matches the PWA's IndexedDB row shape and makes export/import interoperable.
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: Long,
    val date: String,
    val type: String,
    val durationMs: Long = 0,
    val exercises: String = "{}",   // JSON
    val notes: String? = null,
    val synced: Boolean = false,
)

/**
 * Daily log entry (sleep, activity, decline squats).
 * Keyed on date string "YYYY-MM-DD" for easy upserts.
 */
@Entity(tableName = "daily_logs")
data class DailyLogEntity(
    @PrimaryKey val date: String,
    val sleepHours: Double? = null,
    val activity: String? = null,
    val declineSquats: Int = 0,
    val notes: String? = null,
)

// ── Type converters ───────────────────────────────────────────────────────────

class Converters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun exercisesFromJson(value: String): Map<String, ExerciseData> =
        json.decodeFromString(value)

    @TypeConverter
    fun exercisesToJson(value: Map<String, ExerciseData>): String =
        json.encodeToString(value)
}

// ── Database ──────────────────────────────────────────────────────────────────

@Database(
    entities = [SessionEntity::class, DailyLogEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class WorkoutDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao
    abstract fun dailyLogDao(): DailyLogDao

    companion object {
        @Volatile private var INSTANCE: WorkoutDatabase? = null

        fun getInstance(context: Context): WorkoutDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    WorkoutDatabase::class.java,
                    "daglifts2.db",   // same logical name as the PWA's IndexedDB
                ).build().also { INSTANCE = it }
            }
    }
}
