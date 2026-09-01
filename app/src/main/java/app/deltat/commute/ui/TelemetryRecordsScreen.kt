package app.deltat.commute.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.deltat.commute.data.LeaderboardRow
import app.deltat.commute.data.RouteProfileEntity
import app.deltat.commute.data.SessionListRow
import app.deltat.commute.data.comparisonPeriodBounds
import app.deltat.commute.domain.*
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

private enum class RecordsTab(val title: String) { LEADERBOARD("排行榜"), MONTH("月统计"), WEEK("周统计") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelemetryRecordsScreen(
    routes: List<RouteProfileEntity>,
    sessions: List<SessionListRow>,
    leaderboard: List<LeaderboardRow> = emptyList(),
    onOpenSession: (String) -> Unit,
) {
    val displayRoutes = remember(routes) { routesForRecords(routes) }
    var selectedRouteId by remember(displayRoutes) { mutableStateOf(displayRoutes.firstOrNull()?.id) }
    var tab by remember { mutableStateOf(RecordsTab.LEADERBOARD) }
    var filter by remember { mutableStateOf<LeaderboardPeriodFilter>(LeaderboardPeriodFilter.ThisMonth) }
    var customRange by remember { mutableStateOf<LeaderboardPeriodFilter.Custom?>(null) }
    var showDateRange by remember { mutableStateOf(false) }

    val allRows = remember(selectedRouteId, leaderboard, sessions, routes) {
        val projected = if (leaderboard.isNotEmpty()) leaderboard else sessions.mapNotNull { row ->
            val route = routes.firstOrNull { it.id == row.routeProfileId }
            row.totalDurationNanos?.takeIf {
                row.validity == "VALID" && row.qualityGrade in setOf("A", "B") && row.checkpointVersionId == route?.activeCheckpointVersionId
            }?.let { duration ->
                LeaderboardRow(
                    row.id, row.routeProfileId, row.routeName, row.startedWallTimeMillis, duration, row.qualityGrade,
                    null, null, null, row.timingEngineVersion, row.missingCheckpointMask,
                )
            }
        }
        projected.filter { it.routeProfileId == selectedRouteId }
    }
    val periodRange = remember(filter) { StatisticsAggregator.range(filter) }
    val rankedRows = remember(allRows, periodRange) {
        allRows.filter { periodRange.contains(it.startedWallTimeMillis) }
            .sortedWith(compareBy<LeaderboardRow> { it.totalDurationNanos }.thenBy { it.startedWallTimeMillis }.thenBy { it.sessionId })
    }
    val leaderboardLaps = remember(rankedRows) {
        rankedRows.map { row ->
            LeaderboardLap(
                row.sessionId, row.totalDurationNanos,
                listOf(row.sector1Nanos, row.sector2Nanos, row.sector3Nanos),
                row.startedWallTimeMillis, row.timingEngineVersion,
            )
        }
    }
    val theoreticalBest = remember(leaderboardLaps) { LeaderboardProjector.theoreticalBestNanos(leaderboardLaps) }
    val timedResults = remember(allRows) { allRows.map { TimedResultSummary(it.sessionId, it.startedWallTimeMillis, it.totalDurationNanos) } }
    val monthly = remember(timedResults) { StatisticsAggregator.monthly(timedResults) }
    val weekly = remember(timedResults) { StatisticsAggregator.weekly(timedResults) }
    val resultColors = remember(allRows) {
        val bounds = comparisonPeriodBounds(System.currentTimeMillis())
        val monthBest = allRows.filter { it.startedWallTimeMillis in bounds.monthStartMillis until bounds.endExclusiveMillis }
            .minWithOrNull(compareBy<LeaderboardRow> { it.totalDurationNanos }.thenBy { it.startedWallTimeMillis }.thenBy { it.sessionId })?.sessionId
        val weekBest = allRows.filter { it.startedWallTimeMillis in bounds.weekStartMillis until bounds.endExclusiveMillis }
            .minWithOrNull(compareBy<LeaderboardRow> { it.totalDurationNanos }.thenBy { it.startedWallTimeMillis }.thenBy { it.sessionId })?.sessionId
        allRows.associate { row ->
            row.sessionId to when (row.sessionId) {
                monthBest -> ResultColor.PURPLE
                weekBest -> ResultColor.GREEN
                else -> ResultColor.YELLOW
            }
        }
    }

    if (showDateRange) {
        DateRangeDialog(
            initial = customRange,
            onDismiss = { showDateRange = false },
            onConfirm = { selected -> customRange = selected; filter = selected; showDateRange = false },
        )
    }

    Surface(Modifier.fillMaxSize(), color = Background, contentColor = TelemetryWhite) {
        Column(Modifier.fillMaxSize()) {
            Text("个人纪录", fontSize = 27.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                item {
                    if (displayRoutes.isNotEmpty()) RouteSelector(displayRoutes.take(4), selectedRouteId) { selectedRouteId = it }
                }
                item {
                    RecordsTabs(tab) { tab = it }
                }
                if (displayRoutes.isEmpty()) item { RecordsEmpty("请先建立路线并完成有效行程") }
                else when (tab) {
                    RecordsTab.LEADERBOARD -> leaderboardContent(
                        rows = rankedRows,
                        theoreticalBest = theoreticalBest,
                        resultColors = resultColors,
                        filter = filter,
                        onFilter = { selected ->
                            if (selected == null) showDateRange = true else filter = selected
                        },
                        onOpenSession = onOpenSession,
                    )
                    RecordsTab.MONTH -> aggregateContent(monthly, monthly = true)
                    RecordsTab.WEEK -> aggregateContent(weekly, monthly = false)
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.leaderboardContent(
    rows: List<LeaderboardRow>,
    theoreticalBest: Long?,
    resultColors: Map<String, ResultColor>,
    filter: LeaderboardPeriodFilter,
    onFilter: (LeaderboardPeriodFilter?) -> Unit,
    onOpenSession: (String) -> Unit,
) {
    item {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PeriodButton("本周", filter is LeaderboardPeriodFilter.ThisWeek, Modifier.weight(1f)) { onFilter(LeaderboardPeriodFilter.ThisWeek) }
            PeriodButton("本月", filter is LeaderboardPeriodFilter.ThisMonth, Modifier.weight(1f)) { onFilter(LeaderboardPeriodFilter.ThisMonth) }
            PeriodButton("全部", filter is LeaderboardPeriodFilter.All, Modifier.weight(1f)) { onFilter(LeaderboardPeriodFilter.All) }
            PeriodButton("自定义", filter is LeaderboardPeriodFilter.Custom, Modifier.weight(1f)) { onFilter(null) }
        }
    }
    item { RecordsHero(rows.firstOrNull()?.totalDurationNanos, theoreticalBest, rows.size) }
    if (rows.isEmpty()) item { RecordsEmpty("当前范围没有有效成绩") }
    itemsIndexed(rows, key = { _, row -> row.sessionId }) { index, row ->
        LeaderboardCard(
            position = index + 1,
            row = row,
            gap = row.totalDurationNanos - rows.first().totalDurationNanos,
            resultColor = resultColors[row.sessionId] ?: ResultColor.YELLOW,
            onClick = { onOpenSession(row.sessionId) },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.aggregateContent(rows: List<PeriodAggregate>, monthly: Boolean) {
    val latest = rows.lastOrNull()
    item {
        Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(if (monthly) "最近月平均" else "最近周平均", color = if (monthly) Purple else Green, fontWeight = FontWeight.Bold)
                Text(
                    latest?.averageDurationNanos?.let(::formatRecordDuration) ?: "--",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Black,
                )
                Text(latest?.let { "${periodLabel(it, monthly)} · ${it.tripCount} 次有效行程" } ?: "暂无有效周期", color = Muted)
            }
        }
    }
    if (rows.isEmpty()) item { RecordsEmpty(if (monthly) "还没有月统计数据" else "还没有周统计数据") }
    else {
        item { PeriodTrendChart(rows, monthly, Modifier.fillMaxWidth().height(220.dp)) }
        itemsIndexed(rows.asReversed(), key = { _, row -> row.periodStart.toString() }) { _, row -> PeriodAggregateCard(row, monthly) }
    }
}

@Composable
private fun PeriodButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(50.dp).testTag("period-$label").semantics { this.selected = selected },
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) Color(0xFF171B20) else Color.Transparent,
            contentColor = if (selected) TelemetryWhite else Muted,
            disabledContainerColor = Color(0xFF111318),
            disabledContentColor = Muted.copy(alpha = .5f),
        ),
        border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(brush = androidx.compose.ui.graphics.SolidColor(if (selected) RacingRed else Color(0xFF3B4147))),
        contentPadding = PaddingValues(horizontal = 2.dp),
    ) { Text(label, maxLines = 1, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) }
}

@Composable
private fun RecordsHero(pb: Long?, theoreticalBest: Long?, count: Int) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("筛选范围 PB", color = Purple, fontWeight = FontWeight.Bold)
            Text(pb?.let(::formatRecordDuration) ?: "--", fontFamily = FontFamily.Monospace, fontSize = 44.sp, fontWeight = FontWeight.Black)
            HorizontalDivider(color = Color(0xFF343A40))
            Row {
                RecordMetricInline("理论最佳", theoreticalBest?.let(::formatRecordDuration) ?: "--", Modifier.weight(1f))
                RecordMetricInline("有效行程", count.toString(), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RecordMetricInline(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Muted, fontSize = 13.sp)
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 21.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LeaderboardCard(position: Int, row: LeaderboardRow, gap: Long, resultColor: ResultColor, onClick: () -> Unit) {
    val accent = telemetryColor(resultColor)
    Card(
        Modifier.fillMaxWidth().testTag("leaderboard-P$position").clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("P$position", color = accent, fontSize = if (position == 1) 28.sp else 23.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(62.dp))
                Column(Modifier.weight(1f)) {
                    Text(formatRecordDuration(row.totalDurationNanos), fontFamily = FontFamily.Monospace, fontSize = if (position == 1) 25.sp else 21.sp, fontWeight = FontWeight.Bold)
                    Text(if (position == 1) "GAP —" else "GAP +${formatRecordDuration(gap)}", color = Muted, fontSize = 12.sp)
                }
                Text(
                    when (resultColor) {
                        ResultColor.PURPLE -> "PB · 本月最快 · 质量 ${row.qualityGrade}"
                        ResultColor.GREEN -> "本周最快 · 质量 ${row.qualityGrade}"
                        else -> "质量 ${row.qualityGrade}"
                    },
                    color = accent,
                    fontSize = 12.sp,
                )
            }
            HorizontalDivider(color = Color(0xFF343A40))
            Row {
                SectorValue("A→S1", row.sector1Nanos, row.missingCheckpointMask and (1 shl 1) != 0, Modifier.weight(1f))
                SectorValue("S1→S2", row.sector2Nanos, row.missingCheckpointMask and ((1 shl 1) or (1 shl 2)) != 0, Modifier.weight(1f))
                SectorValue("S2→B", row.sector3Nanos, row.missingCheckpointMask and (1 shl 2) != 0, Modifier.weight(1f))
            }
            Text(formatRecordDate(row.startedWallTimeMillis), color = Muted, fontSize = 12.sp)
            if (row.missingCheckpointMask != 0) Text("总程有效 · 部分检查点缺失", color = Yellow, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SectorValue(label: String, nanos: Long?, missing: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = Muted, fontSize = 10.sp)
        Text(nanos?.let(::formatRecordDuration) ?: "--", fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
        if (missing) Text("检查点缺失", color = Muted, fontSize = 9.sp)
    }
}

@Composable
private fun PeriodAggregateCard(row: PeriodAggregate, monthly: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(15.dp)) {
        Row(Modifier.fillMaxWidth().padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(periodLabel(row, monthly), fontWeight = FontWeight.Bold)
                Text("${row.tripCount} 次有效行程", color = Muted, fontSize = 12.sp)
            }
            Text(formatRecordDuration(row.averageDurationNanos), fontFamily = FontFamily.Monospace, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PeriodTrendChart(rows: List<PeriodAggregate>, monthly: Boolean, modifier: Modifier) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Text("平均用时趋势", fontWeight = FontWeight.Bold)
            Canvas(Modifier.fillMaxSize().padding(top = 16.dp, start = 4.dp, end = 4.dp, bottom = 4.dp)) {
                if (rows.isEmpty()) return@Canvas
                val durations = rows.map { it.averageDurationNanos }
                val min = durations.minOrNull()!!.toFloat()
                val max = durations.maxOrNull()!!.toFloat()
                val span = (max - min).coerceAtLeast(1f)
                val totalSlots = if (monthly) {
                    ChronoUnit.MONTHS.between(rows.first().periodStart.withDayOfMonth(1), rows.last().periodStart.withDayOfMonth(1)).toInt() + 1
                } else ChronoUnit.WEEKS.between(rows.first().periodStart, rows.last().periodStart).toInt() + 1
                val offsets = rows.map { row ->
                    val slot = if (monthly) ChronoUnit.MONTHS.between(rows.first().periodStart.withDayOfMonth(1), row.periodStart.withDayOfMonth(1)).toInt()
                    else ChronoUnit.WEEKS.between(rows.first().periodStart, row.periodStart).toInt()
                    Offset(
                        x = if (totalSlots == 1) size.width / 2 else size.width * slot / (totalSlots - 1),
                        y = size.height - (row.averageDurationNanos.toFloat() - min) / span * size.height,
                    )
                }
                offsets.zipWithNext().forEachIndexed { index, (a, b) ->
                    val gap = if (monthly) ChronoUnit.MONTHS.between(rows[index].periodStart.withDayOfMonth(1), rows[index + 1].periodStart.withDayOfMonth(1))
                    else ChronoUnit.WEEKS.between(rows[index].periodStart, rows[index + 1].periodStart)
                    if (gap == 1L) drawLine(if (monthly) Purple else Green, a, b, strokeWidth = 3.dp.toPx())
                }
                offsets.forEach { point -> drawCircle(if (monthly) Purple else Green, 5.dp.toPx(), point); drawCircle(Color.White, 2.dp.toPx(), point) }
            }
        }
    }
}

@Composable
private fun RouteSelector(routes: List<RouteProfileEntity>, selectedRouteId: String?, onSelect: (String) -> Unit) {
    val outer = RoundedCornerShape(28.dp)
    Row(Modifier.fillMaxWidth().height(52.dp).clip(outer).border(1.dp, Color(0xFF5D626A), outer)) {
        routes.forEachIndexed { index, route ->
            val selected = selectedRouteId == route.id
            Row(
                Modifier.weight(1f).fillMaxHeight()
                    .background(if (selected) Color(0xFF5C171D) else Color(0xFF090B0E))
                    .then(if (index > 0) Modifier.border(width = 0.5.dp, color = Color(0xFF5D626A)) else Modifier)
                    .testTag("route-option-${route.id}").semantics { this.selected = selected }
                    .clickable { onSelect(route.id) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selected) { Text("✓", color = Color.White, fontSize = 17.sp); Spacer(Modifier.width(8.dp)) }
                Text(route.name, color = if (selected) Color.White else TelemetryWhite.copy(alpha = .85f), maxLines = 1, fontSize = 16.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun RecordsTabs(selected: RecordsTab, onSelect: (RecordsTab) -> Unit) {
    val outer = RoundedCornerShape(26.dp)
    Row(Modifier.fillMaxWidth().height(54.dp).clip(outer).border(1.dp, Color(0xFF5D626A), outer).testTag("records-tabs")) {
        RecordsTab.entries.forEachIndexed { index, tab ->
            Box(
                Modifier.weight(1f).fillMaxHeight().background(Color(0xFF111318))
                    .then(if (index > 0) Modifier.border(width = 0.5.dp, color = Color(0xFF5D626A)) else Modifier)
                    .testTag("records-tab-${tab.name}").semantics { this.selected = selected == tab }
                    .clickable { onSelect(tab) },
            ) {
                Text(
                    tab.title,
                    color = if (selected == tab) Color.White else Muted,
                    fontWeight = if (selected == tab) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.align(Alignment.Center),
                )
                if (selected == tab) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth(.55f).height(2.dp).background(RacingRed))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeDialog(initial: LeaderboardPeriodFilter.Custom?, onDismiss: () -> Unit, onConfirm: (LeaderboardPeriodFilter.Custom) -> Unit) {
    val zone = ZoneId.systemDefault()
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initial?.startDate?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
        initialSelectedEndDateMillis = initial?.endDateInclusive?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
    )
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = Surface, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("选择日期范围", fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(12.dp))
                DateRangePicker(state = state, modifier = Modifier.fillMaxWidth().height(460.dp), title = null, headline = null)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(
                        enabled = state.selectedStartDateMillis != null && state.selectedEndDateMillis != null,
                        onClick = {
                            val start = Instant.ofEpochMilli(requireNotNull(state.selectedStartDateMillis)).atZone(zone).toLocalDate()
                            val end = Instant.ofEpochMilli(requireNotNull(state.selectedEndDateMillis)).atZone(zone).toLocalDate()
                            onConfirm(LeaderboardPeriodFilter.Custom(start, end))
                        },
                    ) { Text("应用") }
                }
            }
        }
    }
}

@Composable
private fun RecordsEmpty(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(16.dp)) {
        Box(Modifier.fillMaxWidth().padding(vertical = 34.dp, horizontal = 20.dp), contentAlignment = Alignment.Center) {
            Text(text, color = Muted)
        }
    }
}

internal fun routesForRecords(routes: List<RouteProfileEntity>): List<RouteProfileEntity> = routes.sortedWith(
    compareBy<RouteProfileEntity> {
        when (it.directionType) { "TO_WORK" -> 0; "FROM_WORK" -> 2; else -> 1 }
    }.thenBy { it.name },
)

private fun periodLabel(row: PeriodAggregate, monthly: Boolean): String = if (monthly) {
    row.periodStart.format(DateTimeFormatter.ofPattern("yyyy年M月"))
} else {
    "${row.periodStart.format(DateTimeFormatter.ofPattern("M月d日"))}–${row.periodEndInclusive.format(DateTimeFormatter.ofPattern("M月d日"))}"
}

private fun formatRecordDuration(nanos: Long): String {
    val hundredths = nanos / 10_000_000
    return "%02d:%02d.%02d".format(hundredths / 6_000, (hundredths / 100) % 60, hundredths % 100)
}

private fun formatRecordDate(millis: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))
