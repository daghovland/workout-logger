package no.daglifts.workout.repository

import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.ktor.client.call.body
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import no.daglifts.workout.data.model.CoachResponse
import no.daglifts.workout.data.model.HomeBriefResponse
import no.daglifts.workout.data.model.Session

private const val TAG = "SupabaseRepository"

/**
 * All Supabase interactions: auth, session sync, and AI edge function calls.
 * Uses Supabase Kotlin SDK 3.x API.
 */
class SupabaseRepository(private val client: SupabaseClient) {

    val auth get() = client.auth

    // ── Auth ──────────────────────────────────────────────────────────────────

    suspend fun signInWithGoogle() {
        client.auth.signInWith(Google) {
            redirectTo = "no.daglifts.workout://login-callback"
        }
    }

    suspend fun signOut() = client.auth.signOut()

    fun currentUser() = client.auth.currentUserOrNull()

    // ── Session sync ──────────────────────────────────────────────────────────

    suspend fun pushSession(session: Session): Boolean {
        val user = client.auth.currentUserOrNull() ?: return false
        return try {
            // Supabase 3.x: onConflict moved into the builder block
            val dbSession = client.from("sessions").upsert(
                mapOf(
                    "user_id"     to user.id,
                    "local_id"    to session.id,
                    "type"        to session.type,
                    "date"        to session.date,
                    "duration_ms" to session.durationMs,
                    "notes"       to session.notes,
                )
            ) {
                onConflict = "user_id,local_id"
                select()          // ask Supabase to return the upserted row
            }.decodeSingle<RemoteSession>()

            // Replace all sets for this session
            client.from("sets").delete {
                filter { eq("session_id", dbSession.id) }
            }

            val rows = session.exercises.flatMap { (exId, exData) ->
                exData.sets.mapIndexed { idx, set ->
                    mapOf(
                        "session_id"    to dbSession.id,
                        "exercise_id"   to exId,
                        "set_index"     to idx,
                        "weight"        to set.weight,
                        "weight_l"      to set.weightL,
                        "weight_r"      to set.weightR,
                        "reps"          to set.reps,
                        "notes"         to set.notes,
                        "logged_at"     to if (set.timestamp > 0)
                            java.time.Instant.ofEpochMilli(set.timestamp).toString()
                        else null,
                    )
                }
            }
            if (rows.isNotEmpty()) client.from("sets").insert(rows)
            true
        } catch (e: Exception) {
            Log.e(TAG, "pushSession failed", e)
            false
        }
    }

    suspend fun pullSessions(localIds: Set<Long>): List<Session> {
        val user = client.auth.currentUserOrNull() ?: return emptyList()
        return try {
            val remote = client.from("sessions")
                .select(columns = Columns.list("id", "local_id", "type", "date", "duration_ms", "notes")) {
                    filter { eq("user_id", user.id) }
                    order("date", Order.DESCENDING)
                }.decodeList<RemoteSessionFull>()

            remote.filter { it.localId !in localIds }.mapNotNull { rs ->
                try {
                    val sets = client.from("sets")
                        .select(columns = Columns.list(
                            "exercise_id", "set_index", "weight",
                            "weight_l", "weight_r", "reps", "notes", "logged_at"
                        )) {
                            filter { eq("session_id", rs.id) }
                            order("set_index", Order.ASCENDING)
                        }.decodeList<RemoteSet>()
                    rs.toSession(sets)
                } catch (e: Exception) {
                    Log.e(TAG, "pull sets failed for session ${rs.id}", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "pullSessions failed", e)
            emptyList()
        }
    }

    // ── Daily logs ────────────────────────────────────────────────────────────

    suspend fun fetchDailyLogs(limit: Int = 60): List<RemoteDailyLog> {
        val user = client.auth.currentUserOrNull() ?: return emptyList()
        return try {
            client.from("daily_logs")
                .select(columns = Columns.list("id", "date", "sleep_hours", "activity", "decline_squats")) {
                    filter { eq("user_id", user.id) }
                    order("date", Order.DESCENDING)
                    limit(limit.toLong())
                }.decodeList()
        } catch (e: Exception) {
            Log.e(TAG, "fetchDailyLogs failed", e)
            emptyList()
        }
    }

    suspend fun upsertDailyLog(
        date: String,
        sleepHours: Double?,
        activity: String?,
        declineSquats: Int? = null,
    ) {
        val user = client.auth.currentUserOrNull() ?: return
        try {
            val payload = buildMap<String, Any?> {
                put("user_id", user.id)
                put("date", date)
                put("sleep_hours", sleepHours)
                put("activity", activity)
                if (declineSquats != null) put("decline_squats", declineSquats)
                put("updated_at", java.time.Instant.now().toString())
            }
            client.from("daily_logs").upsert(payload) {
                onConflict = "user_id,date"
            }
        } catch (e: Exception) {
            Log.e(TAG, "upsertDailyLog failed", e)
        }
    }

    // ── AI edge functions ─────────────────────────────────────────────────────

    suspend fun fetchCoach(
        type: String,
        exerciseIds: List<String>,
        exerciseNames: List<String>,
        exerciseUnits: List<String>,
        richLogs: List<Map<String, String>>,
    ): CoachResponse? {
        return try {
            val body = buildJsonObject {
                put("type", type)
                putJsonArray("exercises") {
                    exerciseIds.indices.forEach { i ->
                        add(buildJsonObject {
                            put("id",   exerciseIds[i])
                            put("name", exerciseNames[i])
                            put("unit", exerciseUnits[i])
                        })
                    }
                }
                put("rich_logs", Json.encodeToJsonElement(richLogs))
            }
            client.functions.invoke("session-coach", body = body).body<CoachResponse>()
        } catch (e: Exception) {
            Log.e(TAG, "fetchCoach failed", e)
            null
        }
    }

    suspend fun fetchHomeBrief(richLogs: List<Map<String, String>>): HomeBriefResponse? {
        return try {
            val body = buildJsonObject {
                put("rich_logs", Json.encodeToJsonElement(richLogs))
            }
            client.functions.invoke("home-brief", body = body).body<HomeBriefResponse>()
        } catch (e: Exception) {
            Log.e(TAG, "fetchHomeBrief failed", e)
            null
        }
    }

    // ── Remote DTOs ───────────────────────────────────────────────────────────

    @Serializable private data class RemoteSession(val id: Long)

    @Serializable private data class RemoteSessionFull(
        val id: Long,
        val localId: Long,
        val type: String,
        val date: String,
        val durationMs: Long? = null,
        val notes: String? = null,
    )

    @Serializable private data class RemoteSet(
        val exerciseId: String,
        val setIndex: Int,
        val weight: Double? = null,
        val weightL: Double? = null,
        val weightR: Double? = null,
        val reps: Int? = null,
        val notes: String? = null,
        val loggedAt: String? = null,
    )

    @Serializable data class RemoteDailyLog(
        val id: Long? = null,
        val date: String,
        val sleepHours: Double? = null,
        val activity: String? = null,
        val declineSquats: Int? = null,
    )
}

// ── Mapper ────────────────────────────────────────────────────────────────────

private fun SupabaseRepository.RemoteSessionFull.toSession(
    sets: List<SupabaseRepository.RemoteSet>
): Session {
    val exercises = mutableMapOf<String, no.daglifts.workout.data.model.ExerciseData>()
    for (set in sets) {
        val existing = exercises.getOrPut(set.exerciseId) {
            no.daglifts.workout.data.model.ExerciseData()
        }.sets.toMutableList()
        existing.add(no.daglifts.workout.data.model.ExerciseSet(
            timestamp = set.loggedAt?.let { java.time.Instant.parse(it).toEpochMilli() } ?: localId,
            weight    = set.weight,
            weightL   = set.weightL,
            weightR   = set.weightR,
            reps      = set.reps ?: 0,
            notes     = set.notes,
        ))
        exercises[set.exerciseId] = no.daglifts.workout.data.model.ExerciseData(existing)
    }
    return Session(
        id         = localId,
        date       = date,
        type       = type,
        durationMs = durationMs ?: 0,
        exercises  = exercises,
        notes      = notes,
        synced     = true,
    )
}
