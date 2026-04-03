package no.daglifts.workout.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Query("SELECT * FROM sessions WHERE type != 'daily_log' ORDER BY id DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE type != 'daily_log' ORDER BY id DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: Long): SessionEntity?

    // Explicit Long return type avoids the KSP "unexpected jvm signature V" bug
    // that fires when @Upsert/@Insert functions have an implicit void/Unit return.
    @Upsert
    suspend fun upsert(session: SessionEntity): Long

    // Explicit Int (rows affected) for the same reason on @Query mutations.
    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("SELECT * FROM sessions WHERE synced = 0")
    suspend fun getUnsynced(): List<SessionEntity>

    @Query("UPDATE sessions SET synced = 1 WHERE id = :id")
    suspend fun markSynced(id: Long): Int
}

@Dao
interface DailyLogDao {

    @Query("SELECT * FROM daily_logs ORDER BY date DESC LIMIT :limit")
    fun observeRecent(limit: Int = 60): Flow<List<DailyLogEntity>>

    @Query("SELECT * FROM daily_logs WHERE date = :date")
    suspend fun getByDate(date: String): DailyLogEntity?

    @Upsert
    suspend fun upsert(log: DailyLogEntity): Long

    @Query("DELETE FROM daily_logs WHERE date = :date")
    suspend fun deleteByDate(date: String): Int
}
