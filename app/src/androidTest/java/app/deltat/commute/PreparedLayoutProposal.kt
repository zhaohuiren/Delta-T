package app.deltat.commute

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.deltat.commute.domain.EngineStatus
import app.deltat.commute.location.RuntimeSnapshot
import app.deltat.commute.ui.*

/** Frozen R2 prepared-page layout. Test-only; underlying map still the before renderer. */
@Composable
internal fun PreparedLayoutProposal(runtime: RuntimeSnapshot) {
    val detecting = runtime.routeId == null
    val closed = !runtime.gnssHealth.locationEnabled
    val candidate = runtime.engineStatus == EngineStatus.START_CANDIDATE
    Column(Modifier.fillMaxSize().background(Background)) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Text(if (detecting) "准备路线" else "${runtime.routeName} · 准备", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.background((if (closed) Yellow else Green).copy(alpha = .13f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 7.dp)) {
                Text(runtime.gnssHealth.displaySummary(), color = if (closed) Yellow else Green, fontSize = 14.sp)
            }
            Card(Modifier.fillMaxWidth().heightIn(min = 236.dp), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Surface)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(when { closed -> "定位已关闭"; detecting -> "正在识别路线"; candidate -> "正在确认通过 A"; else -> "等待驶过起点 A" }, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("距 A 直线距离", color = Muted, fontSize = 13.sp)
                    Text(if (detecting || closed) "--" else runtime.routeDetectionDistanceMeters?.let { "%.0f m".format(it) } ?: "--", fontSize = 47.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black)
                    Text(when { closed -> "开启定位后，等待新的有效位置"; detecting -> "获得有效定位后，自动识别最近路线"; candidate -> "尚未开始计时，请等待确认"; else -> "确认驶过 A 后自动开始计时" }, color = Muted, fontSize = 14.sp)
                }
            }
            if (detecting) Box(Modifier.fillMaxWidth().aspectRatio(974f / 960f).background(Surface, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Text("识别路线后显示地图", color = Muted)
            } else TelemetryRoute(runtime.track, routeSourcePoints = runtime.routeSourceTrack,
                checkpoints = runtime.routeCheckpoints, currentFix = runtime.lastFix,
                modifier = Modifier.fillMaxWidth().aspectRatio(974f / 960f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!detecting) OutlinedButton({}, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("更换路线") }
                OutlinedButton({}, Modifier.weight(1f).heightIn(min = 48.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = ColorForCancel)) { Text("取消准备") }
            }
            Text("计时尚未开始 · 可锁屏等待", color = Muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}
private val ColorForCancel = androidx.compose.ui.graphics.Color(0xFFFF7777)
