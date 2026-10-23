package app.deltat.commute.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.deltat.commute.domain.EngineStatus
import app.deltat.commute.location.RuntimeSnapshot
import app.deltat.commute.map.MapPhase
import kotlinx.coroutines.delay

internal const val TIMING_MAP_ASPECT = 974f / 960f
internal enum class FixPresentation { FRESH, MISSING, DISABLED, INACCURATE, STALE }

internal fun fixPresentation(runtime: RuntimeSnapshot, nowNanos: Long): FixPresentation = when {
    !runtime.gnssHealth.locationEnabled -> FixPresentation.DISABLED
    runtime.lastFix == null -> FixPresentation.MISSING
    !runtime.lastFix.accuracyMeters.isFinite() || runtime.lastFix.accuracyMeters < 0 || runtime.lastFix.accuracyMeters > 50.0 -> FixPresentation.INACCURATE
    nowNanos - runtime.lastFix.elapsedRealtimeNanos > 10_000_000_000L -> FixPresentation.STALE
    else -> FixPresentation.FRESH
}

@Composable
internal fun rememberFixPresentation(runtime: RuntimeSnapshot, clock: () -> Long): FixPresentation {
    // Only the discrete quality state is observed by the screen. A stationary valid
    // map is not invalidated on every timer frame (or every freshness poll).
    val status by produceState(fixPresentation(runtime, clock()), runtime.lastFix, runtime.gnssHealth.locationEnabled) {
        while (true) {
            value = fixPresentation(runtime, clock())
            delay(1_000)
        }
    }
    return status
}

internal data class PreparingMessage(val title: String, val reason: String)
internal fun preparingMessage(runtime: RuntimeSnapshot, fixState: FixPresentation): PreparingMessage = when (fixState) {
    FixPresentation.DISABLED -> PreparingMessage("定位已关闭", "开启定位后，等待新的有效位置")
    FixPresentation.MISSING -> PreparingMessage("等待有效定位", "获得有效定位后，自动识别最近路线")
    FixPresentation.INACCURATE -> PreparingMessage("定位精度不足", "等待更准确的位置后继续准备")
    FixPresentation.STALE -> PreparingMessage("等待更新定位", "上次定位已过期，收到新位置后继续")
    FixPresentation.FRESH -> when {
        runtime.routeId == null -> PreparingMessage("正在识别路线", "正在根据当前位置识别最近路线")
        runtime.startRequiresExit -> PreparingMessage("等待重新经过 A", "当前位于起点范围内，请先离开，再驶过 A")
        runtime.engineStatus == EngineStatus.START_CANDIDATE -> PreparingMessage("正在确认通过 A", "尚未开始计时，请等待确认")
        else -> PreparingMessage("等待驶过起点 A", "确认驶过 A 后自动开始计时")
    }
}

@Composable
internal fun CompactGps(runtime: RuntimeSnapshot) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.SatelliteAlt, null, Modifier.size(18.dp), tint = gnssStatusColor(runtime.gnssHealth.displayLabel()))
        Spacer(Modifier.width(6.dp))
        Text(runtime.gnssHealth.displaySummary(), color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun TimingStatusRow(runtime: RuntimeSnapshot) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CompactGps(runtime)
        Text(if (runtime.engineStatus == EngineStatus.COMPLETED) "已完成" else "S${runtime.currentSector.coerceIn(1, 3)} · 计时中", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun PreparedTimingContent(runtime: RuntimeSnapshot, fixState: FixPresentation, onSwitchRoute: () -> Unit, onCancel: () -> Unit) {
    val detecting = runtime.routeId == null
    val message = preparingMessage(runtime, fixState)
    val large = LocalDensity.current.fontScale > 1.15f
    Column(Modifier.fillMaxSize().then(if (large) Modifier.verticalScroll(rememberScrollState()) else Modifier).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CompactGps(runtime)
        DesignPanel {
            if (detecting) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp); Text(message.title, style = MaterialTheme.typography.headlineSmall) }
            else {
                Text("${runtime.routeName} · ${message.title}", color = Muted, style = MaterialTheme.typography.bodySmall)
                Text(if (fixState != FixPresentation.FRESH) "—" else runtime.routeDetectionDistanceMeters?.let { "%.0f m".format(it) } ?: "—", fontSize = 44.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            }
            Text(message.reason, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        val mapModifier = Modifier.fillMaxWidth().then(if (large) Modifier.height(330.dp) else Modifier.weight(1f))
        if (detecting) Box(mapModifier, contentAlignment = Alignment.CenterStart) { Text("识别路线后显示地图", color = Muted) }
        else TimingMap(runtime, fixState, MapPhase.PREPARING, mapModifier)
        if (!detecting) OutlinedButton(onSwitchRoute, Modifier.fillMaxWidth()) { Text("更换路线") }
        TextButton(onCancel, Modifier.fillMaxWidth()) { Text("取消准备") }
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Lock, null, Modifier.size(14.dp), tint = Muted); Spacer(Modifier.width(6.dp)); Text("计时尚未开始 · 可锁屏等待", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun TimingMap(runtime: RuntimeSnapshot, fixState: FixPresentation, phase: MapPhase, modifier: Modifier) {
    val colors = remember(runtime.sectors) { (0..2).map { runtime.sectors.firstOrNull { sector -> sector.index == it }?.color ?: app.deltat.commute.domain.ResultColor.NEUTRAL } }
    TelemetryRoute(runtime.track, routeSourcePoints = runtime.routeSourceTrack.ifEmpty { runtime.referenceTrack },
        checkpoints = runtime.routeCheckpoints, currentFix = runtime.lastFix,
        actualPassElapsedNanos = runtime.actualPassElapsedNanos, sectorColors = colors,
        phase = phase, nextCheckpointIndex = runtime.currentSector.coerceIn(1, 3),
        currentFixStale = fixState != FixPresentation.FRESH, modifier = modifier)
}

@Composable
internal fun LiveTimer(runtime: RuntimeSnapshot, completed: Boolean, clock: () -> Long = SystemClock::elapsedRealtimeNanos, animate: Boolean = true) {
    val elapsed by produceState(runtime.elapsedNanos, runtime.startedAtElapsedNanos, runtime.elapsedNanos, completed, animate) {
        if (runtime.startedAtElapsedNanos == null || completed || !animate) value = projectedElapsedNanos(runtime.startedAtElapsedNanos, runtime.elapsedNanos, clock(), completed)
        else while (true) withFrameNanos { value = projectedElapsedNanos(runtime.startedAtElapsedNanos, runtime.elapsedNanos, clock(), false) }
    }
    // The changing state is read only here; parent board, map and navigation stay stable.
    TimerDigits(formatRecordDuration(elapsed))
}

@Composable
private fun TimerDigits(text: String) {
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        val scale = LocalDensity.current.fontScale
        val fontSize = minOf(52f, maxWidth.value / (text.length * .61f * scale)).sp
        Text(text, Modifier.fillMaxWidth().testTag("live-timer"), fontSize = fontSize, lineHeight = 54.sp,
            fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}
