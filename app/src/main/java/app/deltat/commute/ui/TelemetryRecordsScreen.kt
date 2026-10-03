package app.deltat.commute.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
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
import app.deltat.commute.data.comparisonPeriodBounds
import app.deltat.commute.domain.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

private enum class RecordsTab(val title: String) { LEADERBOARD("排行榜"), MONTH("月统计"), WEEK("周统计") }
private data class RecordBadges(val monthlyBest: Boolean = false, val weeklyBest: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelemetryRecordsScreen(
    routes: List<RouteProfileEntity>,
    leaderboard: List<LeaderboardRow>?,
    onOpenSession: (String) -> Unit,
) {
    val displayRoutes = remember(routes) { routesForRecords(routes) }
    var selectedRouteId by rememberSaveable { mutableStateOf<String?>(null) }
    var tabName by rememberSaveable { mutableStateOf(RecordsTab.LEADERBOARD.name) }
    var filterName by rememberSaveable { mutableStateOf("MONTH") }
    var customStartIso by rememberSaveable { mutableStateOf<String?>(null) }
    var customEndIso by rememberSaveable { mutableStateOf<String?>(null) }
    var showDateRange by rememberSaveable { mutableStateOf(false) }
    val listStates = rememberSaveableStateHolder()
    val statisticsState = rememberSaveableStateHolder()
    val tab = RecordsTab.entries.firstOrNull { it.name == tabName } ?: RecordsTab.LEADERBOARD
    val customRange = remember(customStartIso, customEndIso) {
        runCatching {
            val start = LocalDate.parse(customStartIso)
            val end = LocalDate.parse(customEndIso)
            LeaderboardPeriodFilter.Custom(start, end)
        }.getOrNull()
    }
    val filter: LeaderboardPeriodFilter = when (filterName) {
        "WEEK" -> LeaderboardPeriodFilter.ThisWeek
        "ALL" -> LeaderboardPeriodFilter.All
        "CUSTOM" -> customRange ?: LeaderboardPeriodFilter.ThisMonth
        else -> LeaderboardPeriodFilter.ThisMonth
    }
    LaunchedEffect(displayRoutes) {
        if (displayRoutes.none { it.id == selectedRouteId }) selectedRouteId = displayRoutes.firstOrNull()?.id
    }

    var calendarNowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(leaderboard) { calendarNowMillis = System.currentTimeMillis() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) calendarNowMillis = System.currentTimeMillis()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val nextDay = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1)
                .atStartOfDay(zone).toInstant().toEpochMilli()
            delay((nextDay - now + 250L).coerceAtLeast(1_000L))
            calendarNowMillis = System.currentTimeMillis()
        }
    }

    val allRows = remember(selectedRouteId, leaderboard) {
        leaderboard.orEmpty().filter { it.routeProfileId == selectedRouteId }
    }
    val periodRange = remember(filter, calendarNowMillis) { StatisticsAggregator.range(filter, calendarNowMillis) }
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
    val recordBadges = remember(allRows, calendarNowMillis) {
        val bounds = comparisonPeriodBounds(calendarNowMillis)
        val monthBest = allRows.filter { it.startedWallTimeMillis in bounds.monthStartMillis until bounds.endExclusiveMillis }
            .minWithOrNull(compareBy<LeaderboardRow> { it.totalDurationNanos }.thenBy { it.startedWallTimeMillis }.thenBy { it.sessionId })?.sessionId
        val weekBest = allRows.filter { it.startedWallTimeMillis in bounds.weekStartMillis until bounds.endExclusiveMillis }
            .minWithOrNull(compareBy<LeaderboardRow> { it.totalDurationNanos }.thenBy { it.startedWallTimeMillis }.thenBy { it.sessionId })?.sessionId
        allRows.associate { row -> row.sessionId to RecordBadges(row.sessionId == monthBest, row.sessionId == weekBest) }
    }

    if (showDateRange) {
        DateRangeDialog(
            initial = customRange,
            onDismiss = { showDateRange = false },
            onConfirm = { selected ->
                customStartIso = selected.startDate.toString()
                customEndIso = selected.endDateInclusive.toString()
                filterName = "CUSTOM"
                showDateRange = false
            },
        )
    }

    key(selectedRouteId) {
    listStates.SaveableStateProvider("route-lists-$selectedRouteId") {
    val rankingListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val monthListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val weekListState = androidx.compose.foundation.lazy.rememberLazyListState()
    Surface(Modifier.fillMaxSize(), color = Background, contentColor = TelemetryWhite) {
        Column(Modifier.fillMaxSize()) {
            Text("个人纪录", fontSize = 27.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
            LazyColumn(
                modifier = Modifier.testTag("records-list"),
                state = when (tab) { RecordsTab.LEADERBOARD -> rankingListState; RecordsTab.MONTH -> monthListState; RecordsTab.WEEK -> weekListState },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                item {
                    if (displayRoutes.isNotEmpty()) RouteSelector(displayRoutes, selectedRouteId) { selectedRouteId = it }
                }
                item {
                    RecordsTabs(tab) { tabName = it.name }
                }
                if (displayRoutes.isEmpty()) item { RecordsEmpty("请先建立路线并完成有效行程") }
                else if (leaderboard == null) item { RecordsEmpty("正在加载有效成绩…") }
                else when (tab) {
                    RecordsTab.LEADERBOARD -> leaderboardContent(
                        rows = rankedRows,
                        theoreticalBest = theoreticalBest,
                        recordBadges = recordBadges,
                        filter = filter,
                        onFilter = { selected ->
                            when (selected) {
                                null -> showDateRange = true
                                LeaderboardPeriodFilter.ThisWeek -> filterName = "WEEK"
                                LeaderboardPeriodFilter.ThisMonth -> filterName = "MONTH"
                                LeaderboardPeriodFilter.All -> filterName = "ALL"
                                is LeaderboardPeriodFilter.Custom -> {
                                    customStartIso = selected.startDate.toString()
                                    customEndIso = selected.endDateInclusive.toString()
                                    filterName = "CUSTOM"
                                }
                            }
                        },
                        onOpenSession = onOpenSession,
                    )
                    RecordsTab.MONTH, RecordsTab.WEEK -> item(key = "statistics-${tab.name}") {
                        key(selectedRouteId, tab) {
                            statisticsState.SaveableStateProvider("$selectedRouteId-${tab.name}") {
                                PeriodStatisticsSection(allRows, monthly = tab == RecordsTab.MONTH, nowMillis = calendarNowMillis)
                            }
                        }
                    }
                }
            }
        }
    }
}
}
}

private fun androidx.compose.foundation.lazy.LazyListScope.leaderboardContent(
    rows: List<LeaderboardRow>,
    theoreticalBest: Long?,
    recordBadges: Map<String, RecordBadges>,
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
            badges = recordBadges[row.sessionId] ?: RecordBadges(),
            onClick = { onOpenSession(row.sessionId) },
        )
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
private fun LeaderboardCard(position: Int, row: LeaderboardRow, gap: Long, badges: RecordBadges, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().testTag("leaderboard-P$position").clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("P$position", color = TelemetryWhite, fontSize = if (position == 1) 28.sp else 23.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(62.dp))
                Column(Modifier.weight(1f)) {
                    Text(formatRecordDuration(row.totalDurationNanos), fontFamily = FontFamily.Monospace, fontSize = if (position == 1) 25.sp else 21.sp, fontWeight = FontWeight.Bold)
                    Text(if (position == 1) "GAP —" else "GAP +${formatRecordDuration(gap)}", color = Muted, fontSize = 12.sp)
                }
                Text("质量 ${row.qualityGrade}", color = Muted, fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (position == 1) RecordIdentityTag("PB", TelemetryWhite)
                if (badges.monthlyBest) RecordIdentityTag("本月最快", Purple)
                if (badges.weeklyBest) RecordIdentityTag("本周最快", Green)
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
private fun RecordIdentityTag(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.background(color.copy(alpha = .13f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
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
private fun RouteSelector(routes: List<RouteProfileEntity>, selectedRouteId: String?, onSelect: (String) -> Unit) {
    val outer = RoundedCornerShape(28.dp)
    if (routes.size <= 4) {
        Row(Modifier.fillMaxWidth().height(52.dp).clip(outer).border(1.dp, Color(0xFF5D626A), outer)) {
            routes.forEachIndexed { index, route ->
                RouteOption(route, selectedRouteId == route.id, index > 0, Modifier.weight(1f)) { onSelect(route.id) }
            }
        }
    } else {
        Box(Modifier.fillMaxWidth().height(52.dp).clip(outer).border(1.dp, Color(0xFF5D626A), outer)) {
            Row(Modifier.fillMaxHeight().horizontalScroll(rememberScrollState())) {
                routes.forEachIndexed { index, route ->
                    RouteOption(route, selectedRouteId == route.id, index > 0, Modifier.widthIn(min = 112.dp, max = 160.dp)) { onSelect(route.id) }
                }
            }
        }
    }
}

@Composable
private fun RouteOption(route: RouteProfileEntity, selected: Boolean, divider: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxHeight()
            .background(if (selected) Color(0xFF5C171D) else Color(0xFF090B0E))
            .then(if (divider) Modifier.border(width = 0.5.dp, color = Color(0xFF5D626A)) else Modifier)
            .testTag("route-option-${route.id}").semantics { this.selected = selected }
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) { Text("✓", color = Color.White, fontSize = 17.sp); Spacer(Modifier.width(8.dp)) }
        Text(
            route.name,
            color = if (selected) Color.White else TelemetryWhite.copy(alpha = .85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
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
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initial?.startDate?.let(::localDateToDatePickerMillis),
        initialSelectedEndDateMillis = initial?.endDateInclusive?.let(::localDateToDatePickerMillis),
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
                            val start = datePickerMillisToLocalDate(requireNotNull(state.selectedStartDateMillis))
                            val end = datePickerMillisToLocalDate(requireNotNull(state.selectedEndDateMillis))
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

internal fun formatRecordDuration(nanos: Long): String {
    val hundredths = nanos / 10_000_000
    val hours = hundredths / 360_000
    val minutes = (hundredths / 6_000) % 60
    val seconds = (hundredths / 100) % 60
    return if (hours > 0) "%d:%02d:%02d.%02d".format(hours, minutes, seconds, hundredths % 100)
    else "%02d:%02d.%02d".format(minutes, seconds, hundredths % 100)
}

private fun formatRecordDate(millis: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))
private fun formatRecordDateOnly(millis: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(millis))
private fun formatRecordGap(nanos: Long): String = if (nanos == 0L) "GAP 0" else "GAP +${formatRecordDuration(nanos)}"

internal fun localDateToDatePickerMillis(date: LocalDate): Long =
    date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun datePickerMillisToLocalDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
