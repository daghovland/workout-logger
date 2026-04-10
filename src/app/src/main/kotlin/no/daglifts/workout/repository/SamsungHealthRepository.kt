package no.daglifts.workout.repository

import android.app.Activity
import android.content.Context
import android.util.Log
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.HealthDataStore
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
 *   DataTypes (com.samsung.android.sdk.health.data.request.DataTypes) holds singleton
 *   instances of each DataType subtype:
 *     DataTypes.HEART_RATE → DataType.HeartRateType  (implements Readable, supports readData)
 *     DataTypes.SLEEP      → DataType.SleepType       (implements Readable, supports readData)
 *     DataTypes.STEPS      → DataType.StepsType       (aggregate-only, no readDataRequestBuilder)
 *
 *   readData()      → DataResponse<HealthDataPoint>    (heart rate, sleep)
 *   aggregateData() → DataResponse<AggregatedData<T>> (steps)
 *
 *   Field descriptors on companion objects:
 *     DataType.HeartRateType.HEART_RATE → Field<Float>   (bpm per data point)
 *     DataType.StepsType.TOTAL          → AggregateOperation<Long, LocalTimeBuilder>
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

    /**
     * StepsType is aggregate-only — no readDataRequestBuilder.
     * DataType.StepsType.TOTAL is AggregateOperation<Long, LocalTimeBuilder>.
     */
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

    /**
     * Heart rate: readData returns DataResponse<HealthDataPoint>.
     * The bpm value is in the HEART_RATE field (Field<Float>) on each point.
     * Taking the minimum as a resting-HR proxy.
     */
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

    /**
     * Sleep: readData returns DataResponse<HealthDataPoint>.
     * Duration is derived from HealthDataPoint.startTime / endTime (both Instant, non-null).
     */
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
        val totalMinutes = list.sumOf { point ->
            java.time.Duration.between(point.startTime, point.endTime)
                .toMinutes()
                .coerceAtLeast(0)
        }
        return if (totalMinutes > 0) totalMinutes / 60.0 else null
    }
}
