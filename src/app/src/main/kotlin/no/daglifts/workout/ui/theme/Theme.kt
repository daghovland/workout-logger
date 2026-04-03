package no.daglifts.workout.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ── Brand colours — matches the PWA CSS variables exactly ────────────────────

val BgColor        = Color(0xFF0A0A0A)   // --bg
val SurfaceColor   = Color(0xFF141414)   // --surface
val Surface2Color  = Color(0xFF1C1C1C)   // --surface2
val BorderColor    = Color(0xFF282828)   // --border
val TextColor      = Color(0xFFF0F0F0)   // --text
val MutedColor     = Color(0xFF777777)   // --muted
val DangerColor    = Color(0xFFFF4757)   // --danger

// Accent colours per session type
val AccentGym      = Color(0xFFC8F542)   // lime-yellow
val AccentOutdoor  = Color(0xFFFF6B2B)   // orange
val AccentNoEquip  = Color(0xFF5BA4F5)   // blue

data class WorkoutColors(
    val bg: Color       = BgColor,
    val surface: Color  = SurfaceColor,
    val surface2: Color = Surface2Color,
    val border: Color   = BorderColor,
    val text: Color     = TextColor,
    val muted: Color    = MutedColor,
    val accent: Color   = AccentGym,
    val danger: Color   = DangerColor,
)

val LocalWorkoutColors = staticCompositionLocalOf { WorkoutColors() }

@Composable
fun WorkoutTheme(
    accent: Color = AccentGym,
    content: @Composable () -> Unit,
) {
    val colors = WorkoutColors(accent = accent)

    val materialColors = darkColorScheme(
        primary        = accent,
        onPrimary      = Color.Black,
        background     = BgColor,
        onBackground   = TextColor,
        surface        = SurfaceColor,
        onSurface      = TextColor,
        surfaceVariant = Surface2Color,
        outline        = BorderColor,
        error          = DangerColor,
    )

    CompositionLocalProvider(LocalWorkoutColors provides colors) {
        MaterialTheme(colorScheme = materialColors, content = content)
    }
}
