package app.deltat.commute.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
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
    val color = gnssStatusColor(runtime.gnssHealth.displayLabel())
    Row(Modifier.background(color.copy(alpha = .13f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(50)))
        Spacer(Modifier.width(6.dp))
        Text(runtime.gnssHealth.displaySummary(), color = color, fontSize = 14.sp)
    }
}

@Composable
internal fun TimingStatusRow(runtime: RuntimeSnapshot) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CompactGps(runtime)
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("MAX ", color = Muted, fontSize = 11.sp)
                Text(runtime.maxSpeedMps?.let { "%.1f".format(it * 3.6) } ?: "--", fontSize = 17.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text(" km/h", color = Muted, fontSize = 11.sp)
            }
            Text("过滤峰值", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
internal fun PreparedTimingContent(runtime: RuntimeSnapshot, fixState: FixPresentation, onSwitchRoute: () -> Unit, onCancel: () -> Unit) {
    val detecting = runtime.routeId == null
    val message = preparingMessage(runtime, fixState)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row { CompactGps(runtime) }
        Card(Modifier.fillMaxWidth().heightIn(min = 236.dp), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Surface)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("距 A 直线距离", color = Muted, fontSize = 13.sp)
                Text(if (detecting || fixState != FixPresentation.FRESH) "--" else runtime.routeDetectionDistanceMeters?.let { "%.0f m".format(it) } ?: "--", fontSize = 47.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black)
                Text(message.reason, color = Muted, fontSize = 14.sp)
            }
        }
        if (detecting) Box(Modifier.fillMaxWidth().aspectRatio(TIMING_MAP_ASPECT).background(Surface, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Text("识别路线后显示地图", color = Muted)
        } else TimingMap(runtime, fixState, MapPhase.PREPARING, Modifier.fillMaxWidth().aspectRatio(TIMING_MAP_ASPECT))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!detecting) OutlinedButton(onSwitchRoute, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("更换路线") }
            OutlinedButton(onCancel, Modifier.weight(1f).heightIn(min = 48.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF7777))) { Text("取消准备") }
        }
        Text("计时尚未开始 · 可锁屏等待", color = Muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
internal fun TimingMap(runtime: RuntimeSnapshot, fixState: FixPresentation, phase: MapPhase, modifier: Modifier) {
    val colors = remember(runtime.sectors) { (0..2).map { runtime.sectors.getOrNull(it)?.color ?: app.deltat.commute.domain.ResultColor.NEUTRAL } }
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
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val scale = LocalDensity.current.fontScale
        val fontSize = minOf(47f, maxWidth.value / (text.length * .61f * scale)).sp
        Text(text, Modifier.testTag("live-timer"), fontSize = fontSize, lineHeight = 54.sp,
            fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}
