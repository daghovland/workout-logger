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

object ExerciseDefinitions {

    val GYM_REHAB = listOf(
        Exercise("rdl_sling",  "Single-leg RDL (sling)",    "kg",  2.0),
        Exercise("leg_ext_hs", "Leg Ext – Hammer Strength", "kg",  2.5),
        Exercise("leg_ext_tg", "Leg Ext – Technogym",       "kg",  2.5),
        Exercise("step_ups",   "Step-ups BB",               "kg",  5.0),
        Exercise("side_plank", "Side Plank (sling)",        null,  null),
    )

    val GYM_MAIN = listOf(
        Exercise("deadlift", "Deadlift", "kg",     2.5),
        Exercise("ohp",      "OHP",      "kg",     2.5,
            checklist = listOf(
                "Band pull-aparts × 15",
                "Face pulls × 15",
                "External rotation × 12 each",
                "Prone Y/T/W × 10",
            )
        ),
        Exercise("pullups",  "Pull-ups", "BW",     null),
        Exercise("bb_row",   "BB Row",   "kg",     2.5),
        Exercise("db_bench", "DB Bench", "kg/arm", 2.5),
    )

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
