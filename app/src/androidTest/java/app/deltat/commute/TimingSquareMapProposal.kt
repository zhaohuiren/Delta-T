package app.deltat.commute

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.deltat.commute.domain.ResultColor
import app.deltat.commute.location.RuntimeSnapshot
import app.deltat.commute.ui.*
import kotlin.math.abs

/** R1 layout proposal ONLY. Kept in androidTest; never included in the installable product. */
@Composable
internal fun TimingSquareMapProposal(runtime: RuntimeSnapshot) {
    Column(Modifier.fillMaxSize().background(Background)) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Text("${runtime.routeName} · LIVE", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.background(Green.copy(alpha = .13f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(Green, RoundedCornerShape(50)))
                    Spacer(Modifier.width(6.dp))
                    Text(runtime.gnssHealth.displaySummary(), color = Green, fontSize = 14.sp)
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("MAX ", color = Muted, fontSize = 11.sp)
                        Text(runtime.maxSpeedMps?.let { "%.1f".format(it * 3.6) } ?: "--", fontSize = 17.sp,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Text(" km/h", color = Muted, fontSize = 11.sp)
                    }
                    Text("过滤峰值", color = Muted, fontSize = 11.sp)
                }
            }
            val shape = RoundedCornerShape(18.dp)
            Column(Modifier.fillMaxWidth().clip(shape).background(Color(0xFF0C0F14)).border(1.dp, Color(0xFF3A3F45), shape)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(formatRecordDuration(runtime.elapsedNanos), fontSize = 47.sp, lineHeight = 54.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black)
                    ProposalPeriodRow("月", runtime.monthlyDeltaNanos, Purple,
                        if (runtime.monthlyReferenceTrack.isEmpty()) "本月首条记录" else if ((runtime.monthlyDeltaNanos ?: 0) < 0) "有望本月最快 · 实时估算" else "月比较 · 实时估算")
                    ProposalPeriodRow("周", runtime.weeklyDeltaNanos, Green,
                        if (runtime.weeklyReferenceTrack.isEmpty()) "本周首条记录" else if ((runtime.weeklyDeltaNanos ?: 0) < 0) "有望本周最佳 · 实时估算" else "周比较 · 实时估算")
                }
                HorizontalDivider(color = Color(0xFF343940), thickness = 1.dp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(3) { index ->
                        val sector = runtime.sectors.getOrNull(index)
                        val current = runtime.currentSector == index + 1
                        val accent = if (current) RacingRed else sector?.color?.let(::telemetryColor) ?: Muted
                        val month = sector?.monthlyDeltaNanos ?: if (current) runtime.currentSectorMonthlyDeltaNanos else null
                        val week = sector?.weeklyDeltaNanos ?: if (current) runtime.currentSectorWeeklyDeltaNanos else null
                        Column(Modifier.weight(1f).testTag("proposal-sector-${index + 1}")
                            .background(Color(0xFF111419)).then(if (current) Modifier.border(1.5.dp, RacingRed) else Modifier),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Column(Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("S${index + 1}${if (sector != null) " ✓" else ""}", color = accent, fontSize = 16.sp,
                                    lineHeight = 20.sp, fontWeight = FontWeight.Black)
                                Text(sector?.durationNanos?.let(::formatRecordDuration) ?: if (current) "计时中" else "未开始",
                                    fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                Text("月 ${proposalDelta(month)}", color = proposalDeltaColor(month, Purple), fontSize = 12.sp,
                                    lineHeight = 16.sp, fontFamily = FontFamily.Monospace)
                                Text("周 ${proposalDelta(week)}", color = proposalDeltaColor(week, Green), fontSize = 12.sp,
                                    lineHeight = 16.sp, fontFamily = FontFamily.Monospace)
                            }
                            Box(Modifier.fillMaxWidth().height(3.dp).background(accent))
                        }
                    }
                }
            }
            TelemetryRoute(points = runtime.track, routeSourcePoints = runtime.routeSourceTrack,
                checkpoints = runtime.routeCheckpoints, currentFix = runtime.lastFix,
                actualPassElapsedNanos = runtime.actualPassElapsedNanos,
                sectorColors = (0..2).map { runtime.sectors.getOrNull(it)?.color ?: ResultColor.NEUTRAL },
                modifier = Modifier.fillMaxWidth().aspectRatio(974f / 960f))
            OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF7777))) {
                Text("结束本次（成绩无效）")
            }
            Text("可锁屏继续 · 驾驶中无需点击", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ProposalPeriodRow(label: String, delta: Long?, best: Color, status: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 23.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$label Δ ${proposalDelta(delta)}", color = proposalDeltaColor(delta, best), fontSize = 17.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(status, color = Muted, fontSize = 11.sp)
    }
}

private fun proposalDelta(delta: Long?) = delta?.let { (if (it < 0) "−" else "+") + formatRecordDuration(abs(it)) } ?: "--"
private fun proposalDeltaColor(delta: Long?, best: Color) = when {
    delta == null || delta == 0L -> Muted
    delta < 0 -> best
    else -> Yellow
}
