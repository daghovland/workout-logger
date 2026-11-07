package no.daglifts.workout.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import no.daglifts.workout.data.body.BodyCalc
import no.daglifts.workout.data.body.BodyEntry
import no.daglifts.workout.data.body.BodyMetric
import no.daglifts.workout.data.body.BodyTarget
import no.daglifts.workout.repository.BodyRepository
import java.time.LocalDate

/** Parsed CSV waiting for the user's confirmation. */
data class BodyCsvPreview(val rows: List<BodyEntry>, val errors: List<String>, val overlap: Int)

/** Parsed plan text waiting for confirmation (only needed when replacing an existing plan). */
data class BodyPlanPreview(val rows: List<BodyTarget>, val replace: Boolean, val existing: Int)

class BodyViewModel(private val repo: BodyRepository) : ViewModel() {

    val entries: StateFlow<List<BodyEntry>> =
        repo.measurements.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val targets: StateFlow<List<BodyTarget>> =
        repo.targets.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()
    fun clearToast() { _toast.value = null }

    private fun syncInBackground() = viewModelScope.launch { repo.sync() }

    /**
     * Validate and save one day's form. [raw] maps each metric to the text typed
     * (comma or dot decimals). Returns true on success. All fields blank deletes the day.
     */
    fun save(date: LocalDate, raw: Map<BodyMetric, String>, note: String): Boolean {
        if (date.isAfter(LocalDate.now())) { _toast.value = "Date is in the future"; return false }
        val values = linkedMapOf<BodyMetric, Double>()
        for (m in BodyMetric.entries) {
            val n = BodyCalc.parseNum(raw[m])
            when {
                n == null -> {}
                n.isNaN() -> { _toast.value = "${m.label}: not a number"; return false }
                n < m.min || n > m.max -> {
                    _toast.value = "${m.label}: ${BodyCalc.fmt(n)} ${m.unit} looks wrong"; return false
                }
                else -> values[m] = n
            }
        }
        val cleanNote = note.trim().ifEmpty { null }
        viewModelScope.launch {
            if (values.isEmpty() && cleanNote == null) {
                if (repo.getEntry(date) != null) { repo.deleteEntry(date); _toast.value = "Deleted" }
                else _toast.value = "Nothing to save"
            } else {
                repo.saveEntries(listOf(BodyEntry(date, values, cleanNote)))
                _toast.value = "Saved"
            }
            repo.sync()
        }
        return true
    }

    fun delete(date: LocalDate) {
        viewModelScope.launch {
            repo.deleteEntry(date)
            _toast.value = "Deleted"
            repo.sync()
        }
    }

    // ── Plan ──────────────────────────────────────────────────────────────────

    /** Parse pasted plan text. Returns a preview, or null (with a toast) if it can't be used. */
    fun previewPlan(text: String, replace: Boolean): BodyPlanPreview? {
        val (rows, errors) = BodyCalc.parsePlan(text)
        if (errors.isNotEmpty()) { _toast.value = "Could not read: ${errors.first()}"; return null }
        if (rows.isEmpty()) { _toast.value = "Paste at least one checkpoint"; return null }
        return BodyPlanPreview(rows, replace, targets.value.size)
    }

    fun commitPlan(p: BodyPlanPreview) {
        viewModelScope.launch {
            repo.saveTargets(p.rows, p.replace)
            _toast.value = "Plan: ${p.rows.size} checkpoint${if (p.rows.size == 1) "" else "s"} saved"
            repo.sync()
        }
    }

    fun deleteTarget(date: LocalDate) {
        viewModelScope.launch { repo.deleteTarget(date); repo.sync() }
    }

    // ── CSV import ────────────────────────────────────────────────────────────

    fun previewCsv(text: String): BodyCsvPreview? {
        val (rows, errors) = BodyCalc.parseCsv(text)
        if (rows.isEmpty()) {
            _toast.value = "No rows found" + (errors.firstOrNull()?.let { " ($it)" } ?: "")
            return null
        }
        val have = entries.value.map { it.date }.toSet()
        return BodyCsvPreview(rows, errors, rows.count { it.date in have })
    }

    /** Merge: blank CSV cells keep any existing value for that date. */
    fun commitCsv(p: BodyCsvPreview) {
        viewModelScope.launch {
            val existing = entries.value.associateBy { it.date }
            val merged = p.rows.map { r ->
                val base = existing[r.date]
                BodyEntry(r.date, (base?.values ?: emptyMap()) + r.values, base?.note)
            }
            repo.saveEntries(merged)
            _toast.value = "Imported ${merged.size} rows"
            repo.sync()
        }
    }

    init {
        syncInBackground()
    }
}
