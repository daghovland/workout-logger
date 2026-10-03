package no.daglifts.workout.repository

import android.app.Activity
import android.content.Context
import android.util.Log
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.data.entries.SleepSession
import com.samsung.android.sdk.health.data.error.HealthDataException
import com.samsung.android.sdk.health.data.error.ResolvablePlatformException
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.LocalTimeFilter
import com.samsung.android.sdk.health.data.request.Ordering
import no.daglifts.workout.data.model.HealthSnapshot
import java.time.LocalDateTime

private const val TAG = "SamsungHealthRepo"

/**
 * Reads health metrics from Samsung Health via the Samsung Health Data SDK 1.1.0.
 *
 * SDK data model (confirmed from AAR bytecode inspection):
 *
 *   DataType.SleepType fields:
 *     SESSIONS     → Field<List<SleepSession>>   each point holds full session breakdown
 *     SLEEP_SCORE  → Field<Integer>              quality score 0-100
 *     DURATION     → Field<Duration>             total sleep duration
 *
 *   SleepSession has:
 *     getStartTime/getEndTime/getDuration  — session span
 *     getStages() → List<SleepStage>       — stage breakdown
 *
 *   SleepStage.getStage() → StageType enum: UNDEFINED, AWAKE, LIGHT, DEEP, REM
 */
class SamsungHealthRepository(private val context: Context) {

    private val readPermissions = setOf(
        Permission.of(DataTypes.HEART_RATE, AccessType.READ),
        Permission.of(DataTypes.STEPS,      AccessType.READ),
        Permission.of(DataTypes.SLEEP,      AccessType.READ),
    )

    // ── Permissions ───────────────────────────────────────────────────────────

    suspend fun hasPermissions(): Boolean = try {
        HealthDataService.getStore(context)
            .getGrantedPermissions(readPermissions)
            .containsAll(readPermissions)
    } catch (e: HealthDataException) {
        Log.w(TAG, "hasPermissions check failed: ${e.message}")
        false
    }

    /**
     * Shows the Samsung Health permission popup.
     * Must be called from an Activity (pass it from Compose via LocalActivity).
     */
    suspend fun requestPermissions(activity: Activity): Boolean = try {
        val store = HealthDataService.getStore(activity.applicationContext)
        val granted = store.getGrantedPermissions(readPermissions)
        if (granted.containsAll(readPermissions)) {
            true
        } else {
            val result = store.requestPermissions(readPermissions, activity)
            result.containsAll(readPermissions)
        }
    } catch (e: ResolvablePlatformException) {
        Log.w(TAG, "ResolvablePlatformException: ${e.message}")
        if (e.hasResolution) e.resolve(activity)
        false
    } catch (e: HealthDataException) {
        Log.e(TAG, "requestPermissions failed", e)
        false
    }

    // ── Snapshot ──────────────────────────────────────────────────────────────

    suspend fun readSnapshot(): HealthSnapshot {
        if (!hasPermissions()) return HealthSnapshot()
        return try {
            val store     = HealthDataService.getStore(context)
            val now       = LocalDateTime.now()
            val today     = now.toLocalDate().atStartOfDay()
            val yesterday = today.minusDays(1)

            val sleep = readSleep(store, yesterday, today.plusHours(12))

            HealthSnapshot(
                stepCountToday       = readSteps(store, today, now),
                restingHeartRate     = readRestingHr(store, today, now),
                sleepHoursLastNight  = sleep?.totalHours,
                sleepScore           = sleep?.score,
                sleepDeepMinutes     = sleep?.deepMinutes,
                sleepRemMinutes      = sleep?.remMinutes,
                sleepLightMinutes    = sleep?.lightMinutes,
            )
        } catch (e: HealthDataException) {
            Log.e(TAG, "readSnapshot failed", e)
            HealthSnapshot()
        }
    }

    // ── Individual queries ────────────────────────────────────────────────────

    private suspend fun readSteps(
        store: HealthDataStore,
        start: LocalDateTime,
        end: LocalDateTime,
    ): Long? {
        val request = DataType.StepsType.TOTAL.requestBuilder
            .setLocalTimeFilter(LocalTimeFilter.of(start, end))
            .build()
        val list = store.aggregateData(request).dataList
        if (list.isEmpty()) return null
        return list.sumOf { (it.value as? Long) ?: 0L }
    }

    private suspend fun readRestingHr(
        store: HealthDataStore,
        start: LocalDateTime,
        end: LocalDateTime,
    ): Int? {
        val request = DataTypes.HEART_RATE.readDataRequestBuilder
            .setLocalTimeFilter(LocalTimeFilter.of(start, end))
            .setOrdering(Ordering.ASC)
            .build()
        val list = store.readData(request).dataList
        if (list.isEmpty()) return null
        @Suppress("UNCHECKED_CAST")
        return list.mapNotNull { point ->
            try { point.getValue(DataType.HeartRateType.HEART_RATE) as? Float } catch (_: Exception) { null }
        }.minOrNull()?.toInt()
    }

    private data class SleepResult(
        val totalHours: Double,
        val score: Int?,
        val deepMinutes: Int,
        val remMinutes: Int,
        val lightMinutes: Int,
    )

    private suspend fun readSleep(
        store: HealthDataStore,
        start: LocalDateTime,
        end: LocalDateTime,
    ): SleepResult? {
        val request = DataTypes.SLEEP.readDataRequestBuilder
            .setLocalTimeFilter(LocalTimeFilter.of(start, end))
            .setOrdering(Ordering.DESC)
            .build()
        val list = store.readData(request).dataList
        if (list.isEmpty()) return null

        // Take the most recent sleep record (first because Ordering.DESC)
        val point = list.first()

        val score = try {
            @Suppress("UNCHECKED_CAST")
            point.getValue(DataType.SleepType.SLEEP_SCORE) as? Int
        } catch (_: Exception) { null }

        val sessions = try {
            @Suppress("UNCHECKED_CAST")
            point.getValue(DataType.SleepType.SESSIONS) as? List<SleepSession>
        } catch (_: Exception) { null }

        var deepMin  = 0
        var remMin   = 0
        var lightMin = 0
        var totalMin = 0L

        if (!sessions.isNullOrEmpty()) {
            for (session in sessions) {
                for (stage in session.stages.orEmpty()) {
                    val mins = java.time.Duration.between(stage.startTime, stage.endTime)
                        .toMinutes().coerceAtLeast(0)
                    when (stage.stage) {
                        DataType.SleepType.StageType.DEEP  -> deepMin  += mins.toInt()
                        DataType.SleepType.StageType.REM   -> remMin   += mins.toInt()
                        DataType.SleepType.StageType.LIGHT -> lightMin += mins.toInt()
                        DataType.SleepType.StageType.AWAKE -> { /* skip awake time */ }
                        else -> { /* UNDEFINED */ }
                    }
                    if (stage.stage != DataType.SleepType.StageType.AWAKE) totalMin += mins
                }
            }
        } else {
            // Fallback: use point start/end if no stage data available
            totalMin = java.time.Duration.between(point.startTime, point.endTime)
                .toMinutes().coerceAtLeast(0)
        }

        val hours = if (totalMin > 0) totalMin / 60.0 else return null
        return SleepResult(
            totalHours   = hours,
            score        = score,
            deepMinutes  = deepMin,
            remMinutes   = remMin,
            lightMinutes = lightMin,
        )
    }
}
