package no.daglifts.workout.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.jan.supabase.auth.SessionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import no.daglifts.workout.data.ExerciseDefinitions
import no.daglifts.workout.data.Exercise
import no.daglifts.workout.data.SessionType
import no.daglifts.workout.data.model.ChatMessage
import no.daglifts.workout.data.model.CoachResponse
import no.daglifts.workout.data.model.DailyLog
import no.daglifts.workout.data.model.ExerciseData
import no.daglifts.workout.data.model.ExerciseSet
import no.daglifts.workout.data.model.ExerciseSuggestion
import no.daglifts.workout.data.model.HealthSnapshot
import no.daglifts.workout.data.model.HomeBriefResponse
import no.daglifts.workout.repository.SupabaseRepository
import no.daglifts.workout.data.model.Session
import no.daglifts.workout.data.model.SetInputs
import no.daglifts.workout.repository.SamsungHealthRepository
import no.daglifts.workout.repository.SupabaseRepository
import no.daglifts.workout.repository.WorkoutRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ── UI state types ─────────────────────────────────────────────────────────────

sealed interface SessionUiState {
    data object Idle : SessionUiState
    data class Active(
        val session: Session,
        val type: SessionType,
        val startTimeMs: Long,
        val openExerciseId: String? = null,
        val inputs: Map<String, SetInputs> = emptyMap(),
        val suggestions: Map<String, SuggestionState> = emptyMap(),
        val coachBrief: String? = null,
        val lastVals: Map<String, ExerciseSet> = emptyMap(),
    ) : SessionUiState
    data class Summary(val session: Session, val type: SessionType) : SessionUiState
}

sealed interface SuggestionState {
    data object Loading : SuggestionState
    data class Ready(val suggestion: ExerciseSuggestion) : SuggestionState
    data object Unavailable : SuggestionState
}

data class HomeUiState(
    val lastGym: Session? = null,
    val lastOutdoor: Session? = null,
    val lastNoEquip: Session? = null,
    val homeBrief: HomeBriefResponse? = null,
    val homeBriefLoading: Boolean = false,
    val isSignedIn: Boolean = false,
    val healthSnapshot: HealthSnapshot? = null,
    val todayDeclineSquats: Int = 0,
    val profile: SupabaseRepository.UserProfile? = null,
)

// ── ViewModel ──────────────────────────────────────────────────────────────────

class WorkoutViewModel(
    private val workoutRepo: WorkoutRepository,
    private val supabaseRepo: SupabaseRepository,
    private val healthRepo: SamsungHealthRepository,
) : ViewModel() {

    private val _home = MutableStateFlow(HomeUiState())
    val home: StateFlow<HomeUiState> = _home.asStateFlow()

    private val _session = MutableStateFlow<SessionUiState>(SessionUiState.Idle)
    val session: StateFlow<SessionUiState> = _session.asStateFlow()

    /** Live stream from Room — used in HistoryScreen. */
    val sessions = workoutRepo.sessions.stateIn(
        viewModelScope, SharingStarted.Lazily, emptyList()
    )

    val dailyLogs = workoutRepo.dailyLogs.stateIn(
        viewModelScope, SharingStarted.Lazily, emptyList()
    )

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    // ── Chat ───────────────────────────────────────────────────────────────────
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _chatLoading = MutableStateFlow(false)
    val chatLoading: StateFlow<Boolean> = _chatLoading.asStateFlow()

    init {
        viewModelScope.launch { refreshHome() }
        viewModelScope.launch { connectSamsungHealth() }
        // Observe Supabase auth state reactively so isSignedIn stays accurate across
        // OAuth callbacks, token refreshes, and sign-outs from other devices.
        viewModelScope.launch {
            supabaseRepo.auth.sessionStatus.collect { status ->
                val signedIn = status is SessionStatus.Authenticated
                _home.update { it.copy(isSignedIn = signedIn) }
                if (signedIn) onSignedIn()
                else _home.update { it.copy(homeBrief = null) }
            }
        }
    }

    // ── Home ───────────────────────────────────────────────────────────────────

    private suspend fun refreshHome() {
        val recent = workoutRepo.getRecentSessions(100)
        _home.update { s -> s.copy(
            lastGym      = recent.firstOrNull { it.type == "gym" },
            lastOutdoor  = recent.firstOrNull { it.type == "outdoor" },
            lastNoEquip  = recent.firstOrNull { it.type == "noequip" },
        )}
        loadDeclineSquatsToday()
    }

    private fun loadDeclineSquatsToday() {
        viewModelScope.launch {
            val today = LocalDate.now().toString()
            val count = workoutRepo.getDailyLog(today)?.declineSquats ?: 0
            _home.update { it.copy(todayDeclineSquats = count) }
        }
    }

    fun incrementDeclineSquat() {
        val today = LocalDate.now().toString()
        val current = _home.value.todayDeclineSquats
        if (current >= 6) return
        val next = current + 1
        _home.update { it.copy(todayDeclineSquats = next) }
        viewModelScope.launch {
            val log = workoutRepo.getDailyLog(today) ?: DailyLog(date = today)
            workoutRepo.saveDailyLog(log.copy(declineSquats = next))
            if (_home.value.isSignedIn) {
                supabaseRepo.upsertDailyLog(today, log.sleepHours, log.activity, next)
            }
        }
    }

    fun decrementDeclineSquat() {
        val today = LocalDate.now().toString()
        val current = _home.value.todayDeclineSquats
        if (current <= 0) return
        val next = current - 1
        _home.update { it.copy(todayDeclineSquats = next) }
        viewModelScope.launch {
            val log = workoutRepo.getDailyLog(today) ?: DailyLog(date = today)
            workoutRepo.saveDailyLog(log.copy(declineSquats = next))
            if (_home.value.isSignedIn) {
                supabaseRepo.upsertDailyLog(today, log.sleepHours, log.activity, next)
            }
        }
    }

    fun refreshHomeBrief() {
        viewModelScope.launch {
            if (!_home.value.isSignedIn) return@launch
            _home.update { it.copy(homeBriefLoading = true) }
            val richLogs = buildRichLogsPayload()
            val result = supabaseRepo.fetchHomeBrief(richLogs)
            _home.update { it.copy(homeBrief = result, homeBriefLoading = false) }
        }
    }

    // ── Samsung Health ─────────────────────────────────────────────────────────

    private suspend fun connectSamsungHealth() {
        // New SDK: no explicit connect — getStore() is called per-operation.
        // Just attempt a snapshot read; it returns empty if permissions aren't granted yet.
        val snapshot = healthRepo.readSnapshot()
        if (snapshot != HealthSnapshot()) {
            _home.update { it.copy(healthSnapshot = snapshot) }
        }
    }

    // ── Auth ───────────────────────────────────────────────────────────────────

    suspend fun signInWithGoogle() {
        supabaseRepo.signInWithGoogle()
        // Session is imported via handleDeeplinks in MainActivity;
        // sessionStatus flow above picks it up automatically.
    }

    /** Called by MainActivity after handleDeeplinks() imports the OAuth session. */
    fun onOAuthSessionImported() {
        // The sessionStatus flow collector handles the state update;
        // this is a no-op but kept so MainActivity compiles unchanged.
    }

    private suspend fun onSignedIn() {
        syncPending()
        refreshHomeBrief()
        loadChatHistoryFromSupabase()
        loadProfile()
        prefetchCoach(SessionType.GYM)
        prefetchCoach(SessionType.OUTDOOR)
        prefetchCoach(SessionType.NOEQUIP)
    }

    private suspend fun loadProfile() {
        val profile = supabaseRepo.fetchProfile()
        _home.update { it.copy(profile = profile) }
    }

    fun saveProfile(background: String, goals: String, injuries: String) {
        viewModelScope.launch {
            val profile = SupabaseRepository.UserProfile(
                trainingBackground = background.ifBlank { null },
                goals = goals.ifBlank { null },
                injuries = injuries.ifBlank { null },
            )
            supabaseRepo.saveProfile(profile)
            _home.update { it.copy(profile = profile) }
            _toast.value = "Profile saved"
        }
    }

    suspend fun signOut() {
        supabaseRepo.signOut()
        _home.update { it.copy(isSignedIn = false, homeBrief = null) }
    }

    // ── Session ────────────────────────────────────────────────────────────────

    fun startSession(type: SessionType) {
        val now = System.currentTimeMillis()
        val session = Session(
            id   = now,
            date = Instant.ofEpochMilli(now).toString(),
            type = type.name.lowercase(),
        )
        val lastVals = buildLastVals(type)
        val inputs = buildDefaultInputs(type, lastVals, null)
        _session.value = SessionUiState.Active(
            session    = session,
            type       = type,
            startTimeMs = now,
            lastVals   = lastVals,
            inputs     = inputs,
        )
        viewModelScope.launch {
            loadCoachForSession(type)
        }
    }

    fun openExercise(exId: String) {
        val s = _session.value as? SessionUiState.Active ?: return
        _session.value = s.copy(openExerciseId = if (s.openExerciseId == exId) null else exId)
    }

    fun updateInputs(exId: String, inputs: SetInputs) {
        val s = _session.value as? SessionUiState.Active ?: return
        _session.value = s.copy(inputs = s.inputs + (exId to inputs))
    }

    fun logSet(exId: String) {
        val s = _session.value as? SessionUiState.Active ?: return
        val inp = s.inputs[exId] ?: return
        val ex = ExerciseDefinitions.findById(exId) ?: return

        val set = ExerciseSet(
            weight  = inp.weight?.takeIf { ex.unit != null && ex.unit != "BW" && ex.unit != "sec" && !ex.asym },
            weightL = inp.weightL?.takeIf { ex.asym },
            weightR = inp.weightR?.takeIf { ex.asym },
            reps    = inp.reps,
            notes   = inp.notes.ifBlank { null },
        )

        val existingSets = s.session.exercises[exId]?.sets ?: emptyList()
        val updated = s.session.exercises.toMutableMap()
        updated[exId] = ExerciseData(existingSets + set)

        val newSession = s.session.copy(exercises = updated)
        _session.value = s.copy(
            session = newSession,
            inputs  = s.inputs + (exId to inp.copy(notes = "")), // keep weight/reps, clear notes
        )
        _toast.value = "${formatSetShort(ex, set)} ✓"
    }

    fun deleteSet(exId: String, index: Int) {
        val s = _session.value as? SessionUiState.Active ?: return
        val sets = (s.session.exercises[exId]?.sets ?: return).toMutableList()
        sets.removeAt(index)
        val updated = s.session.exercises.toMutableMap()
        if (sets.isEmpty()) updated.remove(exId) else updated[exId] = ExerciseData(sets)
        _session.value = s.copy(session = s.session.copy(exercises = updated))
    }

    fun finishSession() {
        val s = _session.value as? SessionUiState.Active ?: return
        val duration = System.currentTimeMillis() - s.startTimeMs
        val finished = s.session.copy(durationMs = duration)
        _session.value = SessionUiState.Summary(session = finished, type = s.type)
    }

    fun cancelSession() {
        _session.value = SessionUiState.Idle
    }

    fun saveSession(session: Session) {
        viewModelScope.launch {
            workoutRepo.saveSession(session)
            _session.value = SessionUiState.Idle
            refreshHome()
            _toast.value = "Session saved!"
            if (_home.value.isSignedIn) {
                val ok = supabaseRepo.pushSession(session)
                if (ok) workoutRepo.markSynced(session.id)
            }
        }
    }

    fun deleteSession(id: Long) {
        viewModelScope.launch {
            workoutRepo.deleteSession(id)
            refreshHome()
            _toast.value = "Session deleted"
        }
    }

    // ── Daily log ──────────────────────────────────────────────────────────────

    fun saveDailyLog(date: String, sleepHours: Double?, activity: String?) {
        viewModelScope.launch {
            val existing = workoutRepo.getDailyLog(date)
            val log = (existing ?: DailyLog(date = date)).copy(
                sleepHours = sleepHours,
                activity   = activity,
            )
            workoutRepo.saveDailyLog(log)
            _toast.value = "Logged"
            if (_home.value.isSignedIn) {
                supabaseRepo.upsertDailyLog(date, sleepHours, activity)
            }
        }
    }

    // ── Sync ───────────────────────────────────────────────────────────────────

    private suspend fun syncPending() {
        val pending = workoutRepo.getUnsyncedSessions()
        for (s in pending) {
            val ok = supabaseRepo.pushSession(s)
            if (ok) workoutRepo.markSynced(s.id)
        }
        val local = workoutRepo.getRecentSessions(200)
        val localIds = local.map { it.id }.toSet()
        val newRemote = supabaseRepo.pullSessions(localIds)
        for (s in newRemote) workoutRepo.saveSession(s)
        if (newRemote.isNotEmpty()) refreshHome()
    }

    fun clearToast() { _toast.value = null }

    private suspend fun loadChatHistoryFromSupabase() {
        val history = supabaseRepo.loadChatHistory()
        if (history.isNotEmpty()) _chatMessages.value = history
    }

    fun sendChatMessage(message: String) {
        if (!_home.value.isSignedIn || message.isBlank()) return
        val userMsg = ChatMessage(role = "user", content = message)
        val historyBeforeSend = _chatMessages.value
        _chatMessages.value = historyBeforeSend + userMsg
        _chatLoading.value = true

        val activeSession = _session.value as? SessionUiState.Active
        val sessionData = activeSession?.let { s ->
            val elapsed = (System.currentTimeMillis() - s.startTimeMs) / 60_000
            val setsLogged = s.session.exercises.values.sumOf { it.sets.size }
            "Mid-workout (${s.type.name.lowercase()}, ${elapsed}min elapsed, $setsLogged sets logged)"
        }

        viewModelScope.launch {
            supabaseRepo.saveChatMessage("user", message, if (activeSession != null) "session" else "general")

            val response = supabaseRepo.sendChat(
                message     = message,
                history     = historyBeforeSend,
                contextType = if (activeSession != null) "session" else "general",
                sessionData = sessionData,
            )
            _chatLoading.value = false
            if (response?.reply != null) {
                _chatMessages.value = _chatMessages.value +
                    ChatMessage(role = "assistant", content = response.reply)
                supabaseRepo.saveChatMessage("assistant", response.reply, "general")
                if (response.updatedCoachNotes != null) {
                    supabaseRepo.saveCoachNotes(response.updatedCoachNotes)
                    val updated = _home.value.profile?.copy(coachNotes = response.updatedCoachNotes)
                        ?: SupabaseRepository.UserProfile(coachNotes = response.updatedCoachNotes)
                    _home.update { it.copy(profile = updated) }
                    _toast.value = "Coach notes updated"
                }
            } else {
                val err = response?.error ?: "No response"
                _chatMessages.value = _chatMessages.value +
                    ChatMessage(role = "assistant", content = "Error: $err")
            }
        }
    }

    fun clearChat() { _chatMessages.value = emptyList() }

    // ── Coach / AI ─────────────────────────────────────────────────────────────

    // In-memory cache — keyed by "${type}-${YYYY-MM-DD}"
    private val coachCache = mutableMapOf<String, CoachResponse>()

    private fun coachCacheKey(type: SessionType): String =
        "${type.name}-${LocalDate.now()}"

    private fun prefetchCoach(type: SessionType) {
        viewModelScope.launch { fetchCoach(type) }
    }

    private suspend fun fetchCoach(type: SessionType): CoachResponse? {
        val key = coachCacheKey(type)
        coachCache[key]?.let { return it }
        if (!_home.value.isSignedIn) return null

        val exs = ExerciseDefinitions.allForType(type)
        val result = supabaseRepo.fetchCoach(
            type           = type.name.lowercase(),
            exerciseIds    = exs.map { it.id },
            exerciseNames  = exs.map { it.name },
            exerciseUnits  = exs.map { it.unit ?: "BW" },
            richLogs       = buildRichLogsPayload(),
        )
        if (result != null && result.error == null) coachCache[key] = result
        return result
    }

    private suspend fun loadCoachForSession(type: SessionType) {
        val s = _session.value as? SessionUiState.Active ?: return
        // Set all suggestions to Loading
        val loadingMap = ExerciseDefinitions.allForType(type).associate {
            it.id to SuggestionState.Loading
        }
        _session.value = s.copy(suggestions = loadingMap)

        val coach = fetchCoach(type)
        val sug = _session.value as? SessionUiState.Active ?: return

        val suggestions = ExerciseDefinitions.allForType(type).associate { ex ->
            val suggestion = coach?.exercises?.get(ex.id)
            ex.id to if (suggestion != null) SuggestionState.Ready(suggestion)
                      else SuggestionState.Unavailable
        }
        // Pre-fill inputs from suggestions where no sets logged yet
        val updatedInputs = sug.inputs.toMutableMap()
        for (ex in ExerciseDefinitions.allForType(type)) {
            val sugg = (suggestions[ex.id] as? SuggestionState.Ready)?.suggestion ?: continue
            if ((sug.session.exercises[ex.id]?.sets?.size ?: 0) > 0) continue
            val inp = updatedInputs[ex.id] ?: SetInputs()
            updatedInputs[ex.id] = inp.copy(
                weight = sugg.weight ?: inp.weight,
                reps   = sugg.reps   ?: inp.reps,
            )
        }

        _session.value = ((_session.value as? SessionUiState.Active) ?: return).copy(
            suggestions = suggestions,
            coachBrief  = coach?.brief,
            inputs      = updatedInputs,
        )
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun buildLastVals(type: SessionType): Map<String, ExerciseSet> {
        val all = sessions.value
        val result = mutableMapOf<String, ExerciseSet>()
        for (ex in ExerciseDefinitions.allForType(type)) {
            val candidates = listOf(ex.id) + (ExerciseDefinitions.SIBLINGS[ex.id] ?: emptyList())
            outer@ for (session in all) {
                for (cand in candidates) {
                    val sets = session.exercises[cand]?.sets
                    if (!sets.isNullOrEmpty()) {
                        result[ex.id] = bestSet(sets, ex)
                        break@outer
                    }
                }
            }
        }
        return result
    }

    private fun buildDefaultInputs(
        type: SessionType,
        lastVals: Map<String, ExerciseSet>,
        coach: CoachResponse?,
    ): Map<String, SetInputs> {
        return ExerciseDefinitions.allForType(type).associate { ex ->
            val last = lastVals[ex.id]
            val sug = coach?.exercises?.get(ex.id)
            ex.id to SetInputs(
                weight  = sug?.weight ?: last?.weight,
                weightL = last?.weightL,
                weightR = last?.weightR,
                reps    = sug?.reps ?: last?.reps ?: ex.defaultReps,
            )
        }
    }

    private fun buildRichLogsPayload(): List<Map<String, String>> {
        val cutoff = LocalDate.now().minusDays(30).toString()
        return dailyLogs.value
            .filter { it.date >= cutoff }
            .map { log ->
                buildMap {
                    put("date", log.date)
                    log.activity?.let { put("activity", it) }
                    log.notes?.let    { put("notes", it) }
                }
            }
    }

    private fun bestSet(sets: List<ExerciseSet>, ex: Exercise): ExerciseSet {
        return if (ex.unit == null || ex.unit == "BW" || ex.unit == "sec") {
            sets.maxByOrNull { it.reps } ?: sets.last()
        } else if (ex.asym) {
            sets.maxByOrNull { (it.weightL ?: 0.0) + (it.weightR ?: 0.0) } ?: sets.last()
        } else {
            sets.maxByOrNull { it.weight ?: 0.0 } ?: sets.last()
        }
    }
}

// ── Formatter (shared with UI) ────────────────────────────────────────────────

fun formatSetShort(ex: Exercise, set: ExerciseSet): String {
    val r = set.reps
    return when {
        ex.asym                              -> "${fmtW(set.weightL)}L / ${fmtW(set.weightR)}R × $r"
        ex.unit == "sec"                     -> "$r sec"
        ex.unit == null || ex.unit == "BW"   -> "$r reps"
        else                                 -> "${fmtW(set.weight)} ${ex.unit} × $r"
    }
}

private fun fmtW(w: Double?): String {
    if (w == null) return "BW"
    return if (w % 1.0 == 0.0) w.toInt().toString() else "%.1f".format(w)
}

// ── Factory ───────────────────────────────────────────────────────────────────

class WorkoutViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val app = context.applicationContext as no.daglifts.workout.WorkoutApp
        return WorkoutViewModel(app.workoutRepo, app.supabaseRepo, app.healthRepo) as T
    }
}
