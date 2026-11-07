package no.daglifts.workout.repository

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import no.daglifts.workout.data.body.BodyCalc
import no.daglifts.workout.data.body.BodyEntry
import no.daglifts.workout.data.body.BodyMetric
import no.daglifts.workout.data.body.BodyTarget
import no.daglifts.workout.data.db.BodyDao
import no.daglifts.workout.data.db.BodyMeasurementEntity
import no.daglifts.workout.data.db.BodyTargetEntity
import java.time.Instant
import java.time.LocalDate

private const val TAG = "BodyRepository"
private const val T_MEAS = "body_measurements"
private const val T_TARGETS = "body_targets"

/**
 * Offline-first body-composition data: Room is the source of truth for the UI, changes are
 * marked unsynced and pushed to Supabase after each save and at sign-in (same model as the PWA).
 */
class BodyRepository(
    private val dao: BodyDao,
    private val remote: SupabaseRepository,
) {
    val measurements: Flow<List<BodyEntry>> =
        dao.observeMeasurements().map { rows -> rows.map { it.toEntry() } }

    val targets: Flow<List<BodyTarget>> =
        dao.observeTargets().map { rows -> rows.map { it.toTarget() } }

    /** Bumped on every local change, so the coach cache can be invalidated. */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private fun now() = Instant.now().toString()

    // ── Local writes ──────────────────────────────────────────────────────────

    suspend fun getEntry(date: LocalDate): BodyEntry? =
        dao.measurement(date.toString())?.takeIf { !it.deleted }?.toEntry()

    suspend fun saveEntries(entries: List<BodyEntry>) {
        if (entries.isEmpty()) return
        val existing = dao.allMeasurements().associateBy { it.date }
        val ts = now()
        dao.upsertMeasurements(entries.map { e ->
            e.toEntity(ts, remote = existing[e.date.toString()]?.remote ?: false)
        })
        _changes.update { it + 1 }
    }

    suspend fun deleteEntry(date: LocalDate) {
        val row = dao.measurement(date.toString()) ?: return
        // Never reached the server → just drop it; otherwise leave a tombstone for sync.
        if (!row.remote) dao.deleteMeasurement(row.date)
        else dao.upsertMeasurements(listOf(row.copy(deleted = true, synced = false, updatedAt = now())))
        _changes.update { it + 1 }
    }

    /** Add/update checkpoints; with [replace], checkpoints not in [targets] are deleted. */
    suspend fun saveTargets(targets: List<BodyTarget>, replace: Boolean) {
        val all = dao.allTargets()
        val existing = all.associateBy { it.date }
        val ts = now()
        if (replace) {
            val keep = targets.map { it.date.toString() }.toSet()
            for (row in all) {
                if (row.deleted || row.date in keep) continue
                if (!row.remote) dao.deleteTarget(row.date)
                else dao.upsertTargets(listOf(row.copy(deleted = true, synced = false, updatedAt = ts)))
            }
        }
        dao.upsertTargets(targets.map { t ->
            t.toEntity(ts, remote = existing[t.date.toString()]?.remote ?: false)
        })
        _changes.update { it + 1 }
    }

    suspend fun deleteTarget(date: LocalDate) {
        val row = dao.allTargets().firstOrNull { it.date == date.toString() } ?: return
        if (!row.remote) dao.deleteTarget(row.date)
        else dao.upsertTargets(listOf(row.copy(deleted = true, synced = false, updatedAt = now())))
        _changes.update { it + 1 }
    }

    /** Summary for the session-coach request, or null when there's nothing recent. */
    suspend fun coachPayload(): Map<String, Any?>? =
        BodyCalc.coachPayload(measurements.first(), targets.first())

    // ── Sync ──────────────────────────────────────────────────────────────────

    private val mutex = Mutex()
    @Volatile private var resync = false

    /** Push pending changes, then pull. Safe to call often; overlapping calls coalesce. */
    suspend fun sync() {
        if (remote.currentUser() == null) return
        if (!mutex.tryLock()) { resync = true; return }
        try {
            do {
                resync = false
                syncMeasurements()
                syncTargets()
            } while (resync)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun syncMeasurements() {
        val pending = dao.allMeasurements().filter { !it.synced }
        val dels = pending.filter { it.deleted }
        val ups = pending.filter { !it.deleted }
        if (dels.isNotEmpty() && !remote.deleteBodyRows(T_MEAS, dels.map { it.date })) return
        if (ups.isNotEmpty() && !remote.upsertBodyRows(T_MEAS, ups.map { it.toJson() })) return
        if (pending.isNotEmpty()) {
            // Settle only rows that weren't changed again while the requests were in flight
            val cur = dao.allMeasurements().associateBy { it.date }
            for (r in dels) {
                val c = cur[r.date]
                if (c != null && c.deleted && c.updatedAt == r.updatedAt) dao.deleteMeasurement(r.date)
            }
            val settled = ups.mapNotNull { r ->
                cur[r.date]?.takeIf { !it.deleted && it.updatedAt == r.updatedAt }
                    ?.copy(synced = true, remote = true)
            }
            if (settled.isNotEmpty()) dao.upsertMeasurements(settled)
        }
        // Pull: the server is the truth for every local row without a pending change
        val rows = remote.fetchBodyRows(
            T_MEAS, listOf("weight_kg", "waist_cm", "suprailiac_mm", "abdominal_mm", "chest_mm", "triceps_mm", "note"),
        ) ?: return
        val local = dao.allMeasurements().associateBy { it.date }
        val puts = rows.filter { local[it.date]?.synced != false }.map { r ->
            BodyMeasurementEntity(
                date = r.date, weightKg = r.weightKg, waistCm = r.waistCm, suprailiacMm = r.suprailiacMm,
                abdominalMm = r.abdominalMm, chestMm = r.chestMm, tricepsMm = r.tricepsMm, note = r.note,
                updatedAt = r.updatedAt ?: now(), synced = true, remote = true,
            )
        }
        if (puts.isNotEmpty()) dao.upsertMeasurements(puts)
        val remoteDates = rows.map { it.date }.toSet()
        for (l in local.values) {
            if (l.synced && l.date !in remoteDates) dao.deleteMeasurement(l.date)  // deleted elsewhere
        }
    }

    private suspend fun syncTargets() {
        val pending = dao.allTargets().filter { !it.synced }
        val dels = pending.filter { it.deleted }
        val ups = pending.filter { !it.deleted }
        if (dels.isNotEmpty() && !remote.deleteBodyRows(T_TARGETS, dels.map { it.date })) return
        if (ups.isNotEmpty() && !remote.upsertBodyRows(T_TARGETS, ups.map { it.toJson() })) return
        if (pending.isNotEmpty()) {
            val cur = dao.allTargets().associateBy { it.date }
            for (r in dels) {
                val c = cur[r.date]
                if (c != null && c.deleted && c.updatedAt == r.updatedAt) dao.deleteTarget(r.date)
            }
            val settled = ups.mapNotNull { r ->
                cur[r.date]?.takeIf { !it.deleted && it.updatedAt == r.updatedAt }
                    ?.copy(synced = true, remote = true)
            }
            if (settled.isNotEmpty()) dao.upsertTargets(settled)
        }
        val rows = remote.fetchBodyRows(T_TARGETS, listOf("weight_kg", "waist_cm", "suprailiac_mm", "abdominal_mm"))
            ?: return
        val local = dao.allTargets().associateBy { it.date }
        val puts = rows.filter { local[it.date]?.synced != false }.map { r ->
            BodyTargetEntity(
                date = r.date, weightKg = r.weightKg, waistCm = r.waistCm, suprailiacMm = r.suprailiacMm,
                abdominalMm = r.abdominalMm, updatedAt = r.updatedAt ?: now(), synced = true, remote = true,
            )
        }
        if (puts.isNotEmpty()) dao.upsertTargets(puts)
        val remoteDates = rows.map { it.date }.toSet()
        for (l in local.values) {
            if (l.synced && l.date !in remoteDates) dao.deleteTarget(l.date)
        }
        Log.d(TAG, "targets synced: ${rows.size} remote")
    }
}

// ── Mappers ───────────────────────────────────────────────────────────────────

private fun BodyMeasurementEntity.toEntry() = BodyEntry(
    date = LocalDate.parse(date),
    values = buildMap {
        weightKg?.let { put(BodyMetric.WEIGHT, it) }
        waistCm?.let { put(BodyMetric.WAIST, it) }
        suprailiacMm?.let { put(BodyMetric.SUPRAILIAC, it) }
        abdominalMm?.let { put(BodyMetric.ABDOMINAL, it) }
        chestMm?.let { put(BodyMetric.CHEST, it) }
        tricepsMm?.let { put(BodyMetric.TRICEPS, it) }
    },
    note = note,
)

private fun BodyEntry.toEntity(updatedAt: String, remote: Boolean) = BodyMeasurementEntity(
    date = date.toString(),
    weightKg = values[BodyMetric.WEIGHT],
    waistCm = values[BodyMetric.WAIST],
    suprailiacMm = values[BodyMetric.SUPRAILIAC],
    abdominalMm = values[BodyMetric.ABDOMINAL],
    chestMm = values[BodyMetric.CHEST],
    tricepsMm = values[BodyMetric.TRICEPS],
    note = note,
    updatedAt = updatedAt,
    synced = false,
    remote = remote,
)

private fun BodyTargetEntity.toTarget() = BodyTarget(
    date = LocalDate.parse(date),
    values = buildMap {
        weightKg?.let { put(BodyMetric.WEIGHT, it) }
        waistCm?.let { put(BodyMetric.WAIST, it) }
        suprailiacMm?.let { put(BodyMetric.SUPRAILIAC, it) }
        abdominalMm?.let { put(BodyMetric.ABDOMINAL, it) }
    },
)

private fun BodyTarget.toEntity(updatedAt: String, remote: Boolean) = BodyTargetEntity(
    date = date.toString(),
    weightKg = values[BodyMetric.WEIGHT],
    waistCm = values[BodyMetric.WAIST],
    suprailiacMm = values[BodyMetric.SUPRAILIAC],
    abdominalMm = values[BodyMetric.ABDOMINAL],
    updatedAt = updatedAt,
    synced = false,
    remote = remote,
)

private fun num(v: Double?) = v?.let { JsonPrimitive(it) } ?: JsonNull

private fun BodyMeasurementEntity.toJson() = JsonObject(mapOf(
    "date" to JsonPrimitive(date),
    "updated_at" to JsonPrimitive(updatedAt),
    "weight_kg" to num(weightKg),
    "waist_cm" to num(waistCm),
    "suprailiac_mm" to num(suprailiacMm),
    "abdominal_mm" to num(abdominalMm),
    "chest_mm" to num(chestMm),
    "triceps_mm" to num(tricepsMm),
    "note" to (note?.let { JsonPrimitive(it) } ?: JsonNull),
))

private fun BodyTargetEntity.toJson() = JsonObject(mapOf(
    "date" to JsonPrimitive(date),
    "updated_at" to JsonPrimitive(updatedAt),
    "weight_kg" to num(weightKg),
    "waist_cm" to num(waistCm),
    "suprailiac_mm" to num(suprailiacMm),
    "abdominal_mm" to num(abdominalMm),
))
