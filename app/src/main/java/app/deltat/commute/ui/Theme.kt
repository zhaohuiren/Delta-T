package app.deltat.commute.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Surface as MaterialSurface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val RacingRed = Color(0xFFFF594F)
val ActionRed = Color(0xFFCE3028)
val Purple = Color(0xFFC29BFF)
val Green = Color(0xFF71D7AB)
val Yellow = Color(0xFFE8C765)
val Background = Color(0xFF12161B)
val Surface = Color(0xFF1D232B)
val SurfaceHigh = Color(0xFF2A323C)
val Outline = Color(0xFF46515E)
val Muted = Color(0xFFAEB9C5)
val TelemetryWhite = Color(0xFFF1F4F7)
val ChartBlue = Color(0xFF9FB9CE)

private val DeltaColors = darkColorScheme(
    primary = RacingRed, onPrimary = Color(0xFF300400),
    primaryContainer = Color(0xFF35424E), onPrimaryContainer = TelemetryWhite,
    secondary = ChartBlue, onSecondary = Background,
    secondaryContainer = Color(0xFF35424E), onSecondaryContainer = TelemetryWhite,
    tertiary = Yellow, onTertiary = Background,
    background = Background, onBackground = TelemetryWhite,
    surface = Background, onSurface = TelemetryWhite,
    surfaceVariant = Surface, onSurfaceVariant = Muted,
    surfaceContainer = Surface, surfaceContainerLow = Surface,
    surfaceContainerHigh = SurfaceHigh, surfaceContainerHighest = SurfaceHigh,
    outline = Outline, outlineVariant = Outline,
)

@Composable
fun DeltaTTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DeltaColors, shapes = Shapes(
        extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(10.dp), extraLarge = RoundedCornerShape(12.dp),
    )) {
        MaterialSurface(Modifier.fillMaxSize(), color = Background, contentColor = TelemetryWhite, content = content)
    }
}
