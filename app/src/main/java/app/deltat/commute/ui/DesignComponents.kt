package app.deltat.commute.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun DesignPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface, contentColor = TelemetryWhite), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun DesignAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = Icons.Default.PlayArrow, enabled: Boolean = true) {
    Button(onClick, modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = enabled, shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = ActionRed, contentColor = Color.White)) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(label)
    }
}

@Composable
internal fun DesignSection(text: String) { Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) }

@Composable
internal fun DesignSetting(title: String, sub: String, icon: ImageVector, last: Boolean = false, onClick: (() -> Unit)? = null) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(sub) }, leadingContent = { Icon(icon, null, tint = Muted) },
        trailingContent = if (onClick == null) null else ({ Icon(Icons.Default.ChevronRight, null, tint = Muted) }),
        colors = ListItemDefaults.colors(containerColor = Surface), modifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
    if (!last) HorizontalDivider(Modifier.padding(start = 56.dp, end = 16.dp), color = Outline.copy(alpha = .4f))
}

/** Keeps long, hour-inclusive durations on one line without clipping at larger system fonts. */
@Composable
internal fun DesignTimeValue(text: String, maximumSp: Float, modifier: Modifier = Modifier) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
        val size = minOf(maximumSp, (maxWidth.value - 2f) / (text.length.coerceAtLeast(1) * .63f * fontScale))
        Text(text, modifier.fillMaxWidth(), fontSize = size.sp, lineHeight = (size * 1.25f).sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1)
    }
}
