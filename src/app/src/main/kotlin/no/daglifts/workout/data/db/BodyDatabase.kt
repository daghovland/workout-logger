package no.daglifts.workout.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/*
 * Body-composition storage. Deliberately a separate Room database from WorkoutDatabase:
 * that one has no exported schema or migrations, so adding tables there would need a
 * hand-written migration (or a destructive one) on users' existing session history.
 *
 * Mirrors the PWA's IndexedDB 'body' / 'body_targets' stores: one row per date,
 * [synced] = pushed to Supabase, [remote] = exists on the server, [deleted] = tombstone.
 */

@Entity(tableName = "body_measurements")
data class BodyMeasurementEntity(
    @PrimaryKey val date: String,          // "YYYY-MM-DD"
    val weightKg: Double? = null,
    val waistCm: Double? = null,
    val suprailiacMm: Double? = null,
    val abdominalMm: Double? = null,
    val chestMm: Double? = null,
    val tricepsMm: Double? = null,
    val note: String? = null,
    val updatedAt: String,                 // ISO instant
    val synced: Boolean = false,
    val remote: Boolean = false,
    val deleted: Boolean = false,
)

@Entity(tableName = "body_targets")
data class BodyTargetEntity(
    @PrimaryKey val date: String,
    val weightKg: Double? = null,
    val waistCm: Double? = null,
    val suprailiacMm: Double? = null,
    val abdominalMm: Double? = null,
    val updatedAt: String,
    val synced: Boolean = false,
    val remote: Boolean = false,
    val deleted: Boolean = false,
)

@Dao
interface BodyDao {

    @Query("SELECT * FROM body_measurements WHERE deleted = 0 ORDER BY date")
    fun observeMeasurements(): Flow<List<BodyMeasurementEntity>>

    @Query("SELECT * FROM body_measurements ORDER BY date")
    suspend fun allMeasurements(): List<BodyMeasurementEntity>

    @Query("SELECT * FROM body_measurements WHERE date = :date")
    suspend fun measurement(date: String): BodyMeasurementEntity?

    // Explicit return types: see the KSP note in SessionDao.
    @Upsert
    suspend fun upsertMeasurements(rows: List<BodyMeasurementEntity>): List<Long>

    @Query("DELETE FROM body_measurements WHERE date = :date")
    suspend fun deleteMeasurement(date: String): Int

    @Query("SELECT * FROM body_targets WHERE deleted = 0 ORDER BY date")
    fun observeTargets(): Flow<List<BodyTargetEntity>>

    @Query("SELECT * FROM body_targets ORDER BY date")
    suspend fun allTargets(): List<BodyTargetEntity>

    @Upsert
    suspend fun upsertTargets(rows: List<BodyTargetEntity>): List<Long>

    @Query("DELETE FROM body_targets WHERE date = :date")
    suspend fun deleteTarget(date: String): Int
}

@Database(
    entities = [BodyMeasurementEntity::class, BodyTargetEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class BodyDatabase : RoomDatabase() {

    abstract fun bodyDao(): BodyDao

    companion object {
        @Volatile private var INSTANCE: BodyDatabase? = null

        fun getInstance(context: Context): BodyDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BodyDatabase::class.java,
                    "daglifts_body.db",
                ).build().also { INSTANCE = it }
            }
    }
}
