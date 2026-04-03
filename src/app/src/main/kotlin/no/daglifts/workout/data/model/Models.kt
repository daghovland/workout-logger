package no.daglifts.workout.data.model

import kotlinx.serialization.Serializable

/**
 * Domain models — serializable so they can be stored in Room (via JSON columns)
 * and sent to/from Supabase.
 *
 * The data shape mirrors the PWA's IndexedDB structure exactly so that
 * exported JSON is interoperable between the PWA and this app.
 */

@Serializable
data class ExerciseSet(
    val timestamp: Long = System.currentTimeMillis(),
    val weight: Double? = null,
    val weightL: Double? = null,  // left side (asym exercises like squat machine)
    val weightR: Double? = null,  // right side
    val reps: Int = 5,
    val notes: String? = null,
)

@Serializable
data class ExerciseData(
    val sets: List<ExerciseSet> = emptyList(),
)

/**
 * One workout session. [id] is a Unix epoch millis timestamp, matching the PWA.
 * [exercises] maps exercise IDs to their logged sets.
 */
@Serializable
data class Session(
    val id: Long = System.currentTimeMillis(),
    val date: String,   // ISO-8601, e.g. "2025-04-02T18:30:00.000Z"
    val type: String,   // "gym" | "outdoor" | "noequip"
    val durationMs: Long = 0,
    val exercises: Map<String, ExerciseData> = emptyMap(),
    val notes: String? = null,
    val synced: Boolean = false,
)

@Serializable
data class DailyLog(
    val id: Long = System.currentTimeMillis(),
    val date: String,       // "YYYY-MM-DD"
    val type: String = "daily_log",
    val sleepHours: Double? = null,
    val activity: String? = null,
    val declineSquats: Int = 0,
    val notes: String? = null,
)

/**
 * Current in-progress set inputs per exercise (transient, not persisted).
 */
data class SetInputs(
    val weight: Double? = null,
    val weightL: Double? = null,
    val weightR: Double? = null,
    val reps: Int = 5,
    val notes: String = "",
)

/**
 * AI coaching suggestion for a single exercise (from Supabase edge function).
 */
@Serializable
data class ExerciseSuggestion(
    val weight: Double? = null,
    val reps: Int? = null,
    val note: String? = null,
)

/**
 * Full coach response from the session-coach edge function.
 */
@Serializable
data class CoachResponse(
    val brief: String? = null,
    val exercises: Map<String, ExerciseSuggestion> = emptyMap(),
    val error: String? = null,
)

/**
 * Daily training recommendation from the home-brief edge function.
 */
@Serializable
data class HomeBriefResponse(
    val message: String? = null,
    val recommendation: String? = null,  // "gym" | "outdoor" | "home" | "rest" | "light"
    val error: String? = null,
)

/**
 * Health data snapshot read from Samsung Health, passed to the AI for planning.
 */
data class HealthSnapshot(
    val stepCountToday: Long? = null,
    val restingHeartRate: Int? = null,
    val sleepHoursLastNight: Double? = null,
    val activeCaloriesToday: Int? = null,
    val stressScore: Int? = null,        // Samsung Health stress score 1-100 if available
    val bodyWeightKg: Double? = null,
)
