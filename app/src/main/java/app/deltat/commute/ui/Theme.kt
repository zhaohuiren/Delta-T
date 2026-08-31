package app.deltat.commute.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Surface as MaterialSurface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

val RacingRed = Color(0xFFFF1E2D)
val Purple = Color(0xFFB05CFF)
val Green = Color(0xFF38E86B)
val Yellow = Color(0xFFFFC247)
val Background = Color(0xFF08090B)
val Surface = Color(0xFF111318)
val Muted = Color(0xFF7F8790)
val TelemetryWhite = Color(0xFFF4F6F8)

private val DeltaColors = darkColorScheme(
    primary = RacingRed,
    primaryContainer = Color(0xFF431318),
    onPrimaryContainer = TelemetryWhite,
    secondary = Green,
    onSecondary = Background,
    secondaryContainer = Color(0xFF431318),
    onSecondaryContainer = TelemetryWhite,
    tertiary = Yellow,
    onTertiary = Background,
    tertiaryContainer = Color(0xFF3A2A0E),
    onTertiaryContainer = TelemetryWhite,
    background = Background,
    surface = Surface,
    surfaceVariant = Surface,
    onPrimary = Color.White,
    onBackground = TelemetryWhite,
    onSurface = TelemetryWhite,
    onSurfaceVariant = Muted,
    outline = Color(0xFF5D626A),
)

@Composable
fun DeltaTTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DeltaColors) {
        MaterialSurface(
            modifier = Modifier.fillMaxSize(),
            color = Background,
            contentColor = TelemetryWhite,
            content = content,
        )
    }
}
