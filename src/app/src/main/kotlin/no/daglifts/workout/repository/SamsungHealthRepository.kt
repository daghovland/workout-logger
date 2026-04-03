package no.daglifts.workout.repository

import android.app.Activity
import android.content.Context
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import no.daglifts.workout.data.model.HealthSnapshot
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "SamsungHealthRepo"

/**
 * SamsungHealthRepository reads health metrics from the Samsung Health app
 * via the Samsung Health SDK (classic, v1.5.0).
 *
 * ── SETUP REQUIRED ──────────────────────────────────────────────────────────
 * 1. Register at https://developer.samsung.com/health/android/overview.html
 * 2. Download "Samsung Health Data 1.5.0" AAR and copy it to app/libs/
 * 3. In app/build.gradle.kts uncomment:
 *        implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
 * 4. Uncomment all the real SDK code below (marked UNCOMMENT).
 * 5. Add your Samsung Health partner key as a string resource:
 *        <string name="samsung_health_partner_key">YOUR_KEY</string>
 *    (obtain the key from the Samsung Health developer portal after registering)
 * 6. Optionally add the service entry to AndroidManifest.xml (see manifest comments)
 *
 * ── DATA TYPES USED ─────────────────────────────────────────────────────────
 * - StepCount     → daily step total (activity proxy)
 * - Sleep         → last night's sleep duration
 * - HeartRate     → resting heart rate (if available)
 * - Exercise      → active calories burned today
 * - Weight        → most recent body weight
 *
 * All this data is surfaced to the AI coaching prompts to improve recommendations:
 * e.g. "only 4h sleep + high resting HR → suggest lighter session today"
 *
 * ── STUB MODE ────────────────────────────────────────────────────────────────
 * Until the SDK is set up, every read returns null. The app works fine without it;
 * the AI just won't have health context.
 */
class SamsungHealthRepository(private val context: Context) {

    // Samsung Health SDK connection state
    // UNCOMMENT when SDK is available:
    // private var store: com.samsung.android.sdk.healthdata.HealthDataStore? = null
    // private var isConnected = false

    /**
     * Connect to Samsung Health service.
     * Call this once when the app starts (e.g. from WorkoutViewModel.init).
     * Returns true if the connection succeeded.
     */
    suspend fun connect(): Boolean {
        // STUB: return false until SDK is available
        Log.d(TAG, "Samsung Health SDK not yet configured — returning stub snapshot")
        return false

        /* UNCOMMENT when SDK is available:
        return suspendCancellableCoroutine { cont ->
            val listener = object : com.samsung.android.sdk.healthdata.HealthDataStore.ConnectionListener {
                override fun onConnected() {
                    isConnected = true
                    Log.i(TAG, "Connected to Samsung Health")
                    if (cont.isActive) cont.resume(true)
                }
                override fun onConnectionFailed(error: com.samsung.android.sdk.healthdata.HealthConnectionErrorResult) {
                    Log.e(TAG, "Samsung Health connection failed: ${error.errorCode}")
                    if (cont.isActive) cont.resume(false)
                }
                override fun onDisconnected() {
                    isConnected = false
                    Log.w(TAG, "Disconnected from Samsung Health")
                }
            }
            store = com.samsung.android.sdk.healthdata.HealthDataStore(context, listener)
            store?.connectService()
            cont.invokeOnCancellation { store?.disconnectService() }
        }
        */
    }

    /**
     * Request the permissions this app needs from Samsung Health.
     * Must be called from an Activity, not from a coroutine on a background thread.
     *
     * The permission dialog is shown to the user; grant results come back via
     * the permissionListener. In a real app you would wire this to a Flow that
     * the ViewModel observes, then call readSnapshot() after permissions are granted.
     */
    fun requestPermissions(activity: Activity) {
        // STUB — no-op until SDK is wired in

        /* UNCOMMENT when SDK is available:
        val permMgr = com.samsung.android.sdk.healthdata.HealthPermissionManager(store ?: return)
        val permissions = setOf(
            com.samsung.android.sdk.healthdata.HealthPermissionManager.PermissionKey(
                com.samsung.android.sdk.healthdata.HealthConstants.StepCount.HEALTH_DATA_TYPE,
                com.samsung.android.sdk.healthdata.HealthPermissionManager.PermissionType.READ,
            ),
            com.samsung.android.sdk.healthdata.HealthPermissionManager.PermissionKey(
                com.samsung.android.sdk.healthdata.HealthConstants.Sleep.HEALTH_DATA_TYPE,
                com.samsung.android.sdk.healthdata.HealthPermissionManager.PermissionType.READ,
            ),
            com.samsung.android.sdk.healthdata.HealthPermissionManager.PermissionKey(
                com.samsung.android.sdk.healthdata.HealthConstants.HeartRate.HEALTH_DATA_TYPE,
                com.samsung.android.sdk.healthdata.HealthPermissionManager.PermissionType.READ,
            ),
            com.samsung.android.sdk.healthdata.HealthPermissionManager.PermissionKey(
                com.samsung.android.sdk.healthdata.HealthConstants.Exercise.HEALTH_DATA_TYPE,
                com.samsung.android.sdk.healthdata.HealthPermissionManager.PermissionType.READ,
            ),
            com.samsung.android.sdk.healthdata.HealthPermissionManager.PermissionKey(
                com.samsung.android.sdk.healthdata.HealthConstants.Weight.HEALTH_DATA_TYPE,
                com.samsung.android.sdk.healthdata.HealthPermissionManager.PermissionType.READ,
            ),
        )
        permMgr.requestPermissions(permissions, activity).setResultListener { result ->
            val granted = result.resultMap.all { it.value == true }
            Log.i(TAG, "Permission request result: granted=$granted, map=${result.resultMap}")
        }
        */
    }

    /**
     * Read today's health snapshot from Samsung Health.
     * Returns [HealthSnapshot] with whatever data is available.
     * Any unavailable metric stays null.
     */
    suspend fun readSnapshot(): HealthSnapshot {
        // STUB: return empty snapshot until SDK is configured
        return HealthSnapshot()

        /* UNCOMMENT when SDK is available:
        val s = store ?: return HealthSnapshot()
        if (!isConnected) return HealthSnapshot()
        val resolver = com.samsung.android.sdk.healthdata.HealthDataResolver(s, null)
        val tz = ZoneId.systemDefault()
        val todayStart = LocalDate.now().atStartOfDay(tz).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        val yesterdayStart = LocalDate.now().minusDays(1).atStartOfDay(tz).toInstant().toEpochMilli()

        return HealthSnapshot(
            stepCountToday      = querySteps(resolver, todayStart, now),
            restingHeartRate    = queryRestingHr(resolver, todayStart, now),
            sleepHoursLastNight = querySleep(resolver, yesterdayStart, todayStart + 10 * 3600_000L),
            activeCaloriesToday = queryActiveCalories(resolver, todayStart, now),
            bodyWeightKg        = queryLatestWeight(resolver, now - 30L * 24 * 3600_000, now),
        )
        */
    }

    // ── Private query helpers — wire these up when the SDK is present ─────────

    /* UNCOMMENT:

    private suspend fun querySteps(
        resolver: com.samsung.android.sdk.healthdata.HealthDataResolver,
        start: Long,
        end: Long,
    ): Long? = suspendCancellableCoroutine { cont ->
        val req = com.samsung.android.sdk.healthdata.HealthDataResolver.ReadRequest.Builder()
            .setDataType(com.samsung.android.sdk.healthdata.HealthConstants.StepCount.HEALTH_DATA_TYPE)
            .setLocalTimeRange(
                com.samsung.android.sdk.healthdata.HealthConstants.StepCount.START_TIME,
                com.samsung.android.sdk.healthdata.HealthConstants.StepCount.TIME_OFFSET,
                start, end,
            )
            .build()
        resolver.read(req).setResultListener { result ->
            var total = 0L
            try {
                val it = result.iterator()
                while (it.hasNext()) {
                    val data = it.next()
                    total += data.getLong(com.samsung.android.sdk.healthdata.HealthConstants.StepCount.COUNT)
                }
            } finally { result.close() }
            if (cont.isActive) cont.resume(if (total > 0) total else null)
        }
    }

    private suspend fun queryRestingHr(
        resolver: com.samsung.android.sdk.healthdata.HealthDataResolver,
        start: Long,
        end: Long,
    ): Int? = suspendCancellableCoroutine { cont ->
        // HeartRate data type gives instantaneous readings; take the min as resting proxy
        val req = com.samsung.android.sdk.healthdata.HealthDataResolver.ReadRequest.Builder()
            .setDataType(com.samsung.android.sdk.healthdata.HealthConstants.HeartRate.HEALTH_DATA_TYPE)
            .setLocalTimeRange(
                com.samsung.android.sdk.healthdata.HealthConstants.HeartRate.START_TIME,
                com.samsung.android.sdk.healthdata.HealthConstants.HeartRate.TIME_OFFSET,
                start, end,
            )
            .build()
        resolver.read(req).setResultListener { result ->
            var min: Int? = null
            try {
                val it = result.iterator()
                while (it.hasNext()) {
                    val data = it.next()
                    val hr = data.getInt(com.samsung.android.sdk.healthdata.HealthConstants.HeartRate.HEART_RATE)
                    if (hr > 0) min = if (min == null || hr < min!!) hr else min
                }
            } finally { result.close() }
            if (cont.isActive) cont.resume(min)
        }
    }

    private suspend fun querySleep(
        resolver: com.samsung.android.sdk.healthdata.HealthDataResolver,
        start: Long,
        end: Long,
    ): Double? = suspendCancellableCoroutine { cont ->
        val req = com.samsung.android.sdk.healthdata.HealthDataResolver.ReadRequest.Builder()
            .setDataType(com.samsung.android.sdk.healthdata.HealthConstants.Sleep.HEALTH_DATA_TYPE)
            .setLocalTimeRange(
                com.samsung.android.sdk.healthdata.HealthConstants.Sleep.START_TIME,
                com.samsung.android.sdk.healthdata.HealthConstants.Sleep.TIME_OFFSET,
                start, end,
            )
            .build()
        resolver.read(req).setResultListener { result ->
            var totalMs = 0L
            try {
                val it = result.iterator()
                while (it.hasNext()) {
                    val data = it.next()
                    val s = data.getLong(com.samsung.android.sdk.healthdata.HealthConstants.Sleep.START_TIME)
                    val e2 = data.getLong(com.samsung.android.sdk.healthdata.HealthConstants.Sleep.END_TIME)
                    if (e2 > s) totalMs += e2 - s
                }
            } finally { result.close() }
            if (cont.isActive) cont.resume(if (totalMs > 0) totalMs / 3_600_000.0 else null)
        }
    }

    private suspend fun queryActiveCalories(
        resolver: com.samsung.android.sdk.healthdata.HealthDataResolver,
        start: Long,
        end: Long,
    ): Int? = suspendCancellableCoroutine { cont ->
        val req = com.samsung.android.sdk.healthdata.HealthDataResolver.ReadRequest.Builder()
            .setDataType(com.samsung.android.sdk.healthdata.HealthConstants.Exercise.HEALTH_DATA_TYPE)
            .setLocalTimeRange(
                com.samsung.android.sdk.healthdata.HealthConstants.Exercise.START_TIME,
                com.samsung.android.sdk.healthdata.HealthConstants.Exercise.TIME_OFFSET,
                start, end,
            )
            .build()
        resolver.read(req).setResultListener { result ->
            var total = 0
            try {
                val it = result.iterator()
                while (it.hasNext()) {
                    val data = it.next()
                    total += data.getFloat(com.samsung.android.sdk.healthdata.HealthConstants.Exercise.CALORIE).toInt()
                }
            } finally { result.close() }
            if (cont.isActive) cont.resume(if (total > 0) total else null)
        }
    }

    private suspend fun queryLatestWeight(
        resolver: com.samsung.android.sdk.healthdata.HealthDataResolver,
        start: Long,
        end: Long,
    ): Double? = suspendCancellableCoroutine { cont ->
        val req = com.samsung.android.sdk.healthdata.HealthDataResolver.ReadRequest.Builder()
            .setDataType(com.samsung.android.sdk.healthdata.HealthConstants.Weight.HEALTH_DATA_TYPE)
            .setLocalTimeRange(
                com.samsung.android.sdk.healthdata.HealthConstants.Weight.START_TIME,
                com.samsung.android.sdk.healthdata.HealthConstants.Weight.TIME_OFFSET,
                start, end,
            )
            .setSort(com.samsung.android.sdk.healthdata.HealthConstants.Weight.START_TIME,
                com.samsung.android.sdk.healthdata.HealthDataResolver.SortOrder.DESC)
            .setResultCount(1)
            .build()
        resolver.read(req).setResultListener { result ->
            var weight: Double? = null
            try {
                if (result.iterator().hasNext()) {
                    val data = result.iterator().next()
                    weight = data.getFloat(com.samsung.android.sdk.healthdata.HealthConstants.Weight.WEIGHT).toDouble()
                }
            } finally { result.close() }
            if (cont.isActive) cont.resume(weight)
        }
    }
    */

    fun disconnect() {
        // store?.disconnectService()
    }
}
