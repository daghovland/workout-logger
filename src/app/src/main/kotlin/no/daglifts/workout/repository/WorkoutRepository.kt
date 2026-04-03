package no.daglifts.workout.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import no.daglifts.workout.data.db.DailyLogDao
import no.daglifts.workout.data.db.DailyLogEntity
import no.daglifts.workout.data.db.SessionDao
import no.daglifts.workout.data.db.SessionEntity
import no.daglifts.workout.data.model.DailyLog
import no.daglifts.workout.data.model.Session

/**
 * WorkoutRepository wraps the Room DAOs and converts between DB entities and
 * domain models.  The ViewModel only talks to this class, never to Room directly.
 */
class WorkoutRepository(
    private val sessionDao: SessionDao,
    private val dailyLogDao: DailyLogDao,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ── Sessions ──────────────────────────────────────────────────────────────

    /** Live stream of sessions; the UI collects this. */
    val sessions: Flow<List<Session>> =
        sessionDao.observeAll().map { entities -> entities.map { it.toDomain(json) } }

    suspend fun getRecentSessions(limit: Int = 50): List<Session> =
        sessionDao.getRecent(limit).map { it.toDomain(json) }

    suspend fun getSession(id: Long): Session? =
        sessionDao.getById(id)?.toDomain(json)

    suspend fun saveSession(session: Session) =
        sessionDao.upsert(session.toEntity(json))

    suspend fun deleteSession(id: Long) =
        sessionDao.deleteById(id)

    suspend fun getUnsyncedSessions(): List<Session> =
        sessionDao.getUnsynced().map { it.toDomain(json) }

    suspend fun markSynced(id: Long) =
        sessionDao.markSynced(id)

    // ── Daily logs ────────────────────────────────────────────────────────────

    val dailyLogs: Flow<List<DailyLog>> =
        dailyLogDao.observeRecent().map { entities -> entities.map { it.toDomain() } }

    suspend fun getDailyLog(date: String): DailyLog? =
        dailyLogDao.getByDate(date)?.toDomain()

    suspend fun saveDailyLog(log: DailyLog) =
        dailyLogDao.upsert(log.toEntity())

    suspend fun deleteDailyLog(date: String) =
        dailyLogDao.deleteByDate(date)
}

// ── Mappers ───────────────────────────────────────────────────────────────────

private fun SessionEntity.toDomain(json: Json): Session = Session(
    id = id,
    date = date,
    type = type,
    durationMs = durationMs,
    exercises = json.decodeFromString(exercises),
    notes = notes,
    synced = synced,
)

private fun Session.toEntity(json: Json): SessionEntity = SessionEntity(
    id = id,
    date = date,
    type = type,
    durationMs = durationMs,
    exercises = json.encodeToString(exercises),
    notes = notes,
    synced = synced,
)

private fun DailyLogEntity.toDomain(): DailyLog = DailyLog(
    date = date,
    sleepHours = sleepHours,
    activity = activity,
    declineSquats = declineSquats,
    notes = notes,
)

private fun DailyLog.toEntity(): DailyLogEntity = DailyLogEntity(
    date = date,
    sleepHours = sleepHours,
    activity = activity,
    declineSquats = declineSquats,
    notes = notes,
)
