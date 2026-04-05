package no.daglifts.workout.repository

import android.app.Activity
import android.content.Context
import android.util.Log
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.data.DataTypes
import com.samsung.android.sdk.health.data.error.HealthDataException
import com.samsung.android.sdk.health.data.error.ResolvablePlatformException
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.LocalTimeFilter
import com.samsung.android.sdk.health.data.request.Ordering
import no.daglifts.workout.data.model.HealthSnapshot
import java.time.LocalDateTime

private const val TAG = "SamsungHealthRepo"

/**
 * Reads health metrics from Samsung Health via the Samsung Health Data SDK 1.x.
 *
 * Entry point: HealthDataService.getStore(context)
 * No persistent connection — the store is obtained per-call (SDK caches internally).
 *
 * Data classes (from com.samsung.android.sdk.health.data.data.entries):
 *   HeartRate    — .heartRate: Float (bpm), .min: Float, .max: Float,
 *                  .startTime: Instant, .endTime: Instant
 *   SleepSession — .duration: Duration (in minutes), .startTime: Instant, .endTime: Instant
 *
 * [SDK] on a line = property name confirmed from the API Reference.
 * TODO comments = property name not yet confirmed; check DataTypes.<X> in the API Reference.
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
     * Shows the Samsung Health permission popup if needed.
     * Must be called from an Activity. Returns the granted permissions set.
     */
    suspend fun requestPermissions(activity: Activity): Set<Permission> = try {
        val store = HealthDataService.getStore(activity.applicationContext)
        val granted = store.getGrantedPermissions(readPermissions)
        if (granted.containsAll(readPermissions)) granted
        else store.requestPermissions(readPermissions, activity)
    } catch (e: ResolvablePlatformException) {
        Log.w(TAG, "ResolvablePlatformException (${e.message})")
        if (e.hasResolution) e.resolve(activity)
        emptySet()
    } catch (e: HealthDataException) {
        Log.e(TAG, "requestPermissions failed", e)
        emptySet()
    }

    // ── Snapshot ──────────────────────────────────────────────────────────────

    suspend fun readSnapshot(): HealthSnapshot {
        if (!hasPermissions()) return HealthSnapshot()
        return try {
            val store     = HealthDataService.getStore(context)
            val now       = LocalDateTime.now()
            val today     = now.toLocalDate().atStartOfDay()
            val yesterday = today.minusDays(1)

            HealthSnapshot(
                stepCountToday      = readSteps(store, today, now),
                restingHeartRate    = readRestingHr(store, today, now),
                sleepHoursLastNight = readSleep(store, yesterday, today.plusHours(12)),
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
        val request = DataTypes.STEPS.readDataRequestBuilder
            .setLocalTimeFilter(LocalTimeFilter.of(start, end))
            .setOrdering(Ordering.ASC)
            .build()
        val list = store.readData(request).dataList
        if (list.isEmpty()) return null
        // TODO: verify property name for the step-count field on DataTypes.STEPS data points
        // Likely .count (Long) — check the STEPS data class in the API Reference
        return list.sumOf { it.count }
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
        // [SDK] HeartRate.heartRate: Float (confirmed from API Reference)
        // Taking the minimum as a resting-HR proxy (lowest reading of the day)
        return list.minOf { it.heartRate }.toInt()
    }

    private suspend fun readSleep(
        store: HealthDataStore,
        start: LocalDateTime,
        end: LocalDateTime,
    ): Double? {
        val request = DataTypes.SLEEP.readDataRequestBuilder
            .setLocalTimeFilter(LocalTimeFilter.of(start, end))
            .setOrdering(Ordering.DESC)
            .build()
        val list = store.readData(request).dataList
        if (list.isEmpty()) return null
        // [SDK] SleepSession.duration: Duration, in minutes (confirmed from API Reference)
        val totalMinutes = list.sumOf { it.duration.toMinutes() }
        return if (totalMinutes > 0) totalMinutes / 60.0 else null
    }
}
