package app.deltat.commute.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.deltat.commute.data.LeaderboardRow
import app.deltat.commute.data.RouteProfileEntity
import app.deltat.commute.data.SessionListRow
import app.deltat.commute.data.comparisonPeriodBounds
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TelemetryRecordsScreen(
    routes: List<RouteProfileEntity>,
    sessions: List<SessionListRow>,
    leaderboard: List<LeaderboardRow> = emptyList(),
    onOpenSession: (String) -> Unit,
) {
    val displayRoutes = remember(routes) { routesForRecords(routes) }
    var selectedRouteId by remember(displayRoutes) { mutableStateOf(displayRoutes.firstOrNull()?.id) }
    val rows = remember(selectedRouteId, leaderboard, sessions, routes) {
        val projected = if (leaderboard.isNotEmpty()) leaderboard else sessions.mapNotNull { row ->
            val route = routes.firstOrNull { it.id == row.routeProfileId }
            row.totalDurationNanos?.takeIf {
                row.validity == "VALID" && row.qualityGrade in setOf("A", "B") && row.checkpointVersionId == route?.activeCheckpointVersionId
            }?.let { duration ->
                LeaderboardRow(row.id, row.routeProfileId, row.routeName, row.startedWallTimeMillis, duration, row.qualityGrade, null, null, null)
            }
        }
        projected.filter { it.routeProfileId == selectedRouteId }
            .sortedWith(compareBy<LeaderboardRow> { it.totalDurationNanos }.thenBy { it.startedWallTimeMillis }.thenBy { it.sessionId })
    }
    val theoreticalBest = listOf(
        rows.mapNotNull { it.sector1Nanos }.minOrNull(),
        rows.mapNotNull { it.sector2Nanos }.minOrNull(),
        rows.mapNotNull { it.sector3Nanos }.minOrNull(),
    ).takeIf { it.all { value -> value != null } }?.sumOf { requireNotNull(it) }
    val bounds = remember(rows) { comparisonPeriodBounds(System.currentTimeMillis()) }
    val monthBestId = rows.filter { it.startedWallTimeMillis in bounds.monthStartMillis until bounds.endExclusiveMillis }.firstOrNull()?.sessionId
    val weekBestId = rows.filter { it.startedWallTimeMillis in bounds.weekStartMillis until bounds.endExclusiveMillis }.firstOrNull()?.sessionId

    Surface(Modifier.fillMaxSize(), color = Background, contentColor = TelemetryWhite) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("个人纪录", fontSize = 25.sp, fontWeight = FontWeight.Bold)
            }
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    if (displayRoutes.isNotEmpty()) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        displayRoutes.take(4).forEachIndexed { index, route ->
                            SegmentedButton(
                                selected = selectedRouteId == route.id,
                                onClick = { selectedRouteId = route.id },
                                shape = SegmentedButtonDefaults.itemShape(index, displayRoutes.take(4).size),
                            ) { Text(route.name, maxLines = 1) }
                        }
                    }
                }
                if (displayRoutes.isEmpty()) item {
                    Box(Modifier.fillParentMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
                        Text("请先建立路线并完成有效行程", color = Muted)
                    }
                } else {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RecordMetric("PB", rows.firstOrNull()?.totalDurationNanos?.let(::formatRecordDuration) ?: "--", Modifier.weight(1f))
                            RecordMetric("理论最佳", theoreticalBest?.let(::formatRecordDuration) ?: "--", Modifier.weight(1f))
                            RecordMetric("有效行程", rows.size.toString(), Modifier.weight(1f))
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("全部历史有效成绩 · 从快到慢", color = Muted, fontSize = 13.sp)
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                                Text("排名", color = Muted, modifier = Modifier.width(54.dp), fontSize = 12.sp)
                                Text("成绩", color = Muted, modifier = Modifier.weight(1f), fontSize = 12.sp)
                                Text("GAP", color = Muted, fontSize = 12.sp)
                            }
                        }
                    }
                    if (rows.isEmpty()) item {
                        Card(colors = CardDefaults.cardColors(containerColor = Surface)) {
                            Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("还没有有效成绩", fontWeight = FontWeight.Bold)
                                Text("质量 A/B 的完成行程会进入排行榜", color = Muted, fontSize = 13.sp)
                            }
                        }
                    }
                    itemsIndexed(rows, key = { _, row -> row.sessionId }) { index, row ->
                        LeaderboardCard(
                            position = index + 1,
                            row = row,
                            gap = row.totalDurationNanos - rows.first().totalDurationNanos,
                            isMonthBest = row.sessionId == monthBestId,
                            isWeekBest = row.sessionId == weekBestId,
                            onClick = { onOpenSession(row.sessionId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordMetric(label: String, value: String, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, color = Muted, fontSize = 12.sp)
            Text(value, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LeaderboardCard(
    position: Int,
    row: LeaderboardRow,
    gap: Long,
    isMonthBest: Boolean,
    isWeekBest: Boolean,
    onClick: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().testTag("leaderboard-P$position").clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row {
            Box(Modifier.width(if (position == 1) 4.dp else 2.dp).heightIn(min = 142.dp).background(if (position == 1) RacingRed else Muted))
            Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("P$position", modifier = Modifier.width(54.dp), fontSize = if (position == 1) 25.sp else 21.sp, fontWeight = FontWeight.Black)
                    Text(formatRecordDuration(row.totalDurationNanos), modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = if (position == 1) 23.sp else 20.sp, fontWeight = FontWeight.Bold)
                    if (position == 1) SmallBadge("PB", TelemetryWhite) else Text("+${formatRecordDuration(gap)}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
                Row(Modifier.fillMaxWidth().padding(start = 54.dp)) {
                    SectorValue("S1", row.sector1Nanos, Modifier.weight(1f))
                    SectorValue("S2", row.sector2Nanos, Modifier.weight(1f))
                    SectorValue("S3", row.sector3Nanos, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().padding(start = 54.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${formatRecordDate(row.startedWallTimeMillis)} · 质量 ${row.qualityGrade}", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    if (isMonthBest) SmallBadge("本月最快", Purple)
                    if (isWeekBest) { Spacer(Modifier.width(6.dp)); SmallBadge("本周最快", Green) }
                }
            }
        }
    }
}

@Composable
private fun SectorValue(label: String, nanos: Long?, modifier: Modifier) {
    Column(modifier) {
        Text(label, color = Muted, fontSize = 10.sp)
        Text(nanos?.let(::formatRecordDuration) ?: "--", fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun SmallBadge(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(color.copy(alpha = .14f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 4.dp))
}

internal fun routesForRecords(routes: List<RouteProfileEntity>): List<RouteProfileEntity> =
    routes.sortedWith(
        compareBy<RouteProfileEntity> {
            when (it.directionType) {
                "TO_WORK" -> 0
                "FROM_WORK" -> 2
                else -> 1
            }
        }.thenBy { it.name },
    )

private fun formatRecordDuration(nanos: Long): String {
    val hundredths = nanos / 10_000_000
    return "%02d:%02d.%02d".format(hundredths / 6_000, (hundredths / 100) % 60, hundredths % 100)
}

private fun formatRecordDate(millis: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(millis))
