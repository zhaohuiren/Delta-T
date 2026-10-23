@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
            TopAppBar(title = { Text("个人纪录") }, windowInsets = WindowInsets(0,0,0,0), colors = TopAppBarDefaults.topAppBarColors(containerColor = Background))
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
                                PeriodStatisticsSection(allRows, monthly = tab == RecordsTab.MONTH, nowMillis = calendarNowMillis, onOpenSession = onOpenSession)
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
private fun PeriodButton(label:String,selected:Boolean,modifier:Modifier,onClick:()->Unit) {
    FilterChip(selected=selected,onClick=onClick,label={Text(label,maxLines=1)},modifier=modifier.testTag("period-$label"))
}
@Composable
private fun RecordsHero(pb:Long?,theoreticalBest:Long?,count:Int) {
    DesignPanel { Text("筛选范围 PB",color=Muted,style=MaterialTheme.typography.bodySmall);Text(pb?.let(::formatRecordDuration)?:"—",fontFamily=FontFamily.Monospace,fontSize=40.sp,fontWeight=FontWeight.SemiBold);Text("$count 次有效行程",color=Muted,style=MaterialTheme.typography.bodySmall);Text("理论最佳 ${theoreticalBest?.let(::formatRecordDuration)?:"—"}",color=Muted,style=MaterialTheme.typography.bodySmall) }
}

@Composable
private fun RecordMetricInline(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Muted, fontSize = 13.sp)
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 21.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LeaderboardCard(position:Int,row:LeaderboardRow,gap:Long,badges:RecordBadges,onClick:()->Unit) {
    ListItem(headlineContent={Text("P$position   ${formatRecordDuration(row.totalDurationNanos)}",fontFamily=FontFamily.Monospace)},
        supportingContent={Column { Text("${formatRecordDate(row.startedWallTimeMillis)} · GAP ${if(gap==0L)"持平" else "+${formatRecordDuration(gap)}"}"); if(row.missingCheckpointMask!=0) Text("总程有效 · 部分检查点缺失",color=Yellow);FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){if(badges.monthlyBest)RecordIdentityTag("本月最快",Purple);if(badges.weeklyBest)RecordIdentityTag("本周最快",Green)} }},
        leadingContent={Icon(Icons.Default.EmojiEvents,null,tint=Muted)},trailingContent={Icon(Icons.Default.ChevronRight,null)},colors=ListItemDefaults.colors(containerColor=Surface),modifier=Modifier.clip(RoundedCornerShape(8.dp)).testTag("leaderboard-P$position").clickable(onClick=onClick))
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
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().then(if (routes.size > 3) Modifier.horizontalScroll(rememberScrollState()) else Modifier)) {
        routes.forEachIndexed { index, route ->
            SegmentedButton(selected = selectedRouteId == route.id, onClick = { onSelect(route.id) }, shape = SegmentedButtonDefaults.itemShape(index, routes.size), modifier = Modifier.testTag("route-option-${route.id}").then(if(routes.size>3)Modifier.widthIn(min=112.dp) else Modifier.weight(1f))) { Text(route.name,maxLines=1,overflow=TextOverflow.Ellipsis) }
        }
    }
}
@Composable
private fun RecordsTabs(selected: RecordsTab, onSelect: (RecordsTab) -> Unit) {
    PrimaryTabRow(selectedTabIndex=selected.ordinal,containerColor=Background,modifier=Modifier.testTag("records-tabs")) {
        RecordsTab.entries.forEach { tab -> Tab(selected=selected==tab,onClick={onSelect(tab)},unselectedContentColor=Muted,text={Text(tab.title)},modifier=Modifier.testTag("records-tab-${tab.name}")) }
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
