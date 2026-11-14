package no.daglifts.workout.data

/**
 * Direct port of the EXERCISES, SIBLING_EXERCISES, and DEFAULTS constants from the PWA.
 *
 * Exercise.unit meanings:
 *   "kg"      — weight in kilograms, symmetric
 *   "kg/arm"  — weight per arm (dumbbells)
 *   "kg/side" — weight per side
 *   "BW"      — bodyweight, no weight field
 *   "sec"     — timed hold, reps field is seconds
 *   null      — no unit (same as BW)
 *   asym=true — asymmetric: separate weightL / weightR fields
 */
data class Exercise(
    val id: String,
    val name: String,
    val unit: String?,       // null or "BW" = bodyweight only
    val step: Double?,       // weight increment step
    val asym: Boolean = false,
    val checklist: List<String> = emptyList(),
    val defaultReps: Int = 5,
)

data class ExerciseGroup(
    val label: String,
    val exercises: List<Exercise>,
)

data class VariantGroup(
    val name: String,
    val variants: LinkedHashMap<String, String>, // label ("BB"/"DB") -> exercise id
)

private val OHP_WARMUP = listOf(
    "Band pull-aparts × 15",
    "Face pulls × 15",
    "External rotation × 12 each",
    "Prone Y/T/W × 10",
)

object ExerciseDefinitions {

    val GYM_REHAB = listOf(
        Exercise("rdl_sling",  "Single-leg RDL (sling)",    "kg",  2.0),
        Exercise("leg_ext_hs", "Leg Ext – Hammer Strength", "kg",  2.5),
        Exercise("leg_ext_tg", "Leg Ext – Technogym",       "kg",  2.5),
        Exercise("step_ups",    "Step-ups BB",              "kg",     5.0),
        Exercise("step_ups_db", "Step-ups DB",              "kg/arm", 2.0),
        Exercise("side_plank", "Side Plank (sling)",        null,  null),
    )

    val GYM_MAIN = listOf(
        Exercise("deadlift",    "Deadlift BB", "kg",     2.5),
        Exercise("db_deadlift", "Deadlift DB", "kg/arm", 2.0),
        Exercise("back_ext",    "Back Extensions", "kg", 2.5), // blank weight = bodyweight
        Exercise("ohp",         "OHP BB",      "kg",     2.5, checklist = OHP_WARMUP),
        Exercise("db_ohp",      "OHP DB",      "kg/arm", 2.0, checklist = OHP_WARMUP),
        Exercise("pullups",     "Pull-ups",    "BW",     null),
        Exercise("pulldown",    "Pull-down",   "kg",     2.5),
        Exercise("bb_row",      "Row BB",      "kg",     2.5),
        Exercise("db_row",      "Row DB",      "kg/arm", 2.0),
        Exercise("bb_bench",    "Bench BB",    "kg",     2.5),
        Exercise("db_bench",    "Bench DB",    "kg/arm", 1.0),
        Exercise("leg_press",   "Leg Press",   "kg",     5.0),
    )

    /**
     * Equipment variants shown as ONE card with a selector — BB/DB pairs and the two leg-extension
     * machines (mirrors VARIANT_GROUPS in the PWA). Each variant keeps its own exercise id and
     * therefore its own history.
     */
    val VARIANT_GROUPS = listOf(
        VariantGroup("Leg Ext",  linkedMapOf("Hammer Strength" to "leg_ext_hs", "Technogym" to "leg_ext_tg")),
        VariantGroup("Step-ups", linkedMapOf("BB" to "step_ups", "DB" to "step_ups_db")),
        VariantGroup("Deadlift", linkedMapOf("BB" to "deadlift", "DB" to "db_deadlift")),
        VariantGroup("OHP",      linkedMapOf("BB" to "ohp",      "DB" to "db_ohp")),
        VariantGroup("Row",      linkedMapOf("BB" to "bb_row",   "DB" to "db_row")),
        VariantGroup("Bench",    linkedMapOf("BB" to "bb_bench", "DB" to "db_bench")),
    )

    private val _groupById: Map<String, VariantGroup> by lazy {
        VARIANT_GROUPS.flatMap { g -> g.variants.values.map { it to g } }.toMap()
    }

    fun variantGroupOf(exId: String): VariantGroup? = _groupById[exId]

    /** Collapse grouped variants to the selected one (default: first variant). */
    fun displayExercises(list: List<Exercise>, variantSel: Map<String, String>): List<Exercise> {
        val seen = mutableSetOf<String>()
        return list.mapNotNull { ex ->
            val g = variantGroupOf(ex.id) ?: return@mapNotNull ex
            if (!seen.add(g.name)) return@mapNotNull null
            val selId = variantSel[g.name] ?: g.variants.values.first()
            list.firstOrNull { it.id == selId } ?: ex
        }
    }

    val OUTDOOR = listOf(
        Exercise("squat_m", "Squat Machine", "kg",      5.0,  asym = true),
        Exercise("bench_o", "Bench",         "kg",      2.5),
        Exercise("dead_o",  "Deadlift",      "kg",      2.5),
        Exercise("ohp_o",   "OHP",           "kg",      2.5),
        Exercise("pu_o",    "Pull-ups",      "BW",      null),
        Exercise("row_o",   "Row",           "kg/side", 2.5),
    )

    val NOEQUIP = listOf(
        Exercise("ne_pushup",   "Push-ups",               "BW",  null),
        Exercise("ne_pullup",   "Pull-ups",               "BW",  null),
        Exercise("ne_stepup",   "Step-up",                "BW",  null),
        Exercise("ne_slrdl",    "1-leg RDL",              "BW",  null),
        Exercise("ne_invrow",   "Inverted Rows",          "BW",  null),
        Exercise("ne_wallsit",  "Wall Sit",               "sec", 5.0,  defaultReps = 30),
        Exercise("ne_birddog",  "Bird-dog",               "BW",  null),
        Exercise("ne_slgb",     "Single-leg Glute Bridge","BW",  null),
        Exercise("ne_superman", "Superman Hold",          "sec", 5.0,  defaultReps = 30),
        Exercise("ne_pikepush", "Pike Push-ups",          "BW",  null),
    )

    /**
     * Exercises that share the same movement pattern across session types.
     * Used to pre-fill weights from sister exercises in other sessions.
     */
    val SIBLINGS: Map<String, List<String>> = mapOf(
        "pullups"   to listOf("pu_o", "ne_pullup"),
        "pu_o"      to listOf("pullups", "ne_pullup"),
        "ne_pullup" to listOf("pullups", "pu_o"),
    )

    // ── Convenience accessors ────────────────────────────────────────────────

    fun allForType(type: SessionType): List<Exercise> = when (type) {
        SessionType.GYM     -> GYM_REHAB + GYM_MAIN
        SessionType.OUTDOOR -> OUTDOOR
        SessionType.NOEQUIP -> NOEQUIP
    }

    fun groupsForType(type: SessionType): List<ExerciseGroup> = when (type) {
        SessionType.GYM -> listOf(
            ExerciseGroup("REHAB",       GYM_REHAB),
            ExerciseGroup("MAIN LIFTS",  GYM_MAIN),
        )
        SessionType.OUTDOOR -> listOf(ExerciseGroup("", OUTDOOR))
        SessionType.NOEQUIP -> listOf(ExerciseGroup("", NOEQUIP))
    }

    private val _allById: Map<String, Exercise> by lazy {
        (GYM_REHAB + GYM_MAIN + OUTDOOR + NOEQUIP).associateBy { it.id }
    }

    fun findById(id: String): Exercise? = _allById[id]
}

enum class SessionType(val label: String, val accentHex: String) {
    GYM("Gym",                "#c8f542"),
    OUTDOOR("Outdoor / Street","#ff6b2b"),
    NOEQUIP("No Equipment",    "#5ba4f5"),
}
