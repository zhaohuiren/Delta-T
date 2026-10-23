@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.deltat.commute.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.deltat.commute.data.LeaderboardRow
import app.deltat.commute.domain.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

internal fun statisticsPeriodStart(date: LocalDate, monthly: Boolean): LocalDate =
    if (monthly) date.withDayOfMonth(1) else date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

internal fun statisticsPeriodRows(rows: List<LeaderboardRow>, start: LocalDate, monthly: Boolean, zone: ZoneId): List<LeaderboardRow> {
    val end = if (monthly) start.plusMonths(1) else start.plusWeeks(1)
    val range = PeriodRange(start.atStartOfDay(zone).toInstant().toEpochMilli(), end.atStartOfDay(zone).toInstant().toEpochMilli())
    return rows.filter { range.contains(it.startedWallTimeMillis) }.sortedWith(compareBy<LeaderboardRow> { it.startedWallTimeMillis }.thenBy { it.sessionId })
}

internal fun gapWindowStart(requested: Int?, count: Int): Int = (requested ?: (count - 5)).coerceIn(0, (count - 5).coerceAtLeast(0))

internal data class GapAxis(val stepNanos: Long, val unitNanos: Long, val unitLabel: String) {
    val topNanos: Long get() = if (stepNanos > Long.MAX_VALUE / 3) Long.MAX_VALUE else stepNanos * 3
    fun tick(index: Int): Long = if (index == 3) topNanos else stepNanos * index
    fun label(index: Int): String {
        val value = BigDecimal.valueOf(tick(index)).divide(BigDecimal.valueOf(unitNanos), 3, RoundingMode.DOWN).stripTrailingZeros().toPlainString()
        return if (index == 0) "0" else "+$value"
    }
}

/** Integer tick generation keeps 151.10s from becoming 151.09 through Float rounding. */
internal fun gapAxis(maxGapNanos: Long): GapAxis {
    val target = (maxGapNanos.coerceAtLeast(30_000_000L) / 3) + if (maxGapNanos % 3L > 0) 1 else 0
    var base = 1L
    while (base <= target / 10) base *= 10
    val step = listOf(1L, 2L, 3L, 6L, 10L).map { if (base > Long.MAX_VALUE / it) Long.MAX_VALUE else base * it }
        .firstOrNull { it >= target } ?: Long.MAX_VALUE
    val top = if (step > Long.MAX_VALUE / 3) Long.MAX_VALUE else step * 3
    val unit = when {
        top >= 3_600_000_000_000L -> 3_600_000_000_000L to "小时"
        top >= 600_000_000_000L -> 60_000_000_000L to "分钟"
        top < 1_000_000_000L -> 1_000_000L to "毫秒"
        else -> 1_000_000_000L to "秒"
    }
    // Hour/minute ticks must also be round in their displayed unit (10h, not 8.333h).
    val displayStep = if (unit.first >= 60_000_000_000L && target >= unit.first) {
        val targetUnits = target / unit.first + if (target % unit.first > 0) 1 else 0
        var unitBase = 1L
        while (unitBase <= targetUnits / 10) unitBase *= 10
        val units = listOf(1L, 2L, 3L, 6L, 10L).map { unitBase * it }.first { it >= targetUnits }
        if (units > Long.MAX_VALUE / unit.first) Long.MAX_VALUE else units * unit.first
    } else step
    return GapAxis(minOf(displayStep, Long.MAX_VALUE / 3 + 1), unit.first, unit.second)
}

internal enum class StatisticsSort(val label: String) { DATE("时间先后"), FASTEST("用时从快到慢"), SLOWEST("用时从慢到快") }
internal fun medianDuration(rows: List<LeaderboardRow>): Long? {
    val values = rows.map { it.totalDurationNanos }.sorted()
    if (values.isEmpty()) return null
    val middle = values.size / 2
    return if (values.size % 2 == 1) values[middle] else values[middle-1] + (values[middle]-values[middle-1])/2
}
internal fun sortedStatisticsRows(rows: List<LeaderboardRow>, sort: StatisticsSort): List<LeaderboardRow> {
    val tie = compareBy<LeaderboardRow> { it.startedWallTimeMillis }.thenBy { it.sessionId }
    return rows.sortedWith(when(sort) {
        StatisticsSort.DATE -> tie
        StatisticsSort.FASTEST -> compareBy<LeaderboardRow> { it.totalDurationNanos }.then(tie)
        StatisticsSort.SLOWEST -> compareByDescending<LeaderboardRow> { it.totalDurationNanos }.then(tie)
    })
}
internal fun medianDifferenceLabel(delta: Long): String = when {
    delta < 0 -> "比中位数快 ${formatRecordDuration(-delta)}"
    delta > 0 -> "比中位数慢 ${formatRecordDuration(delta)}"
    else -> "与中位数持平"
}

@Composable
internal fun PeriodStatisticsSection(allRows: List<LeaderboardRow>, monthly: Boolean, nowMillis: Long, onOpenSession: (String) -> Unit = {}) {
    val zone = ZoneId.systemDefault()
    val eligibleRows = remember(allRows, nowMillis) { allRows.filter { it.startedWallTimeMillis <= nowMillis } }
    val periods = remember(eligibleRows, monthly, zone) { eligibleRows.groupBy { statisticsPeriodStart(Instant.ofEpochMilli(it.startedWallTimeMillis).atZone(zone).toLocalDate(), monthly) }.toSortedMap() }
    var selectedStartIso by rememberSaveable { mutableStateOf<String?>(null) }
    var requestedWindow by rememberSaveable { mutableStateOf<Int?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedIndexHint by rememberSaveable { mutableIntStateOf(Int.MAX_VALUE) }
    var sortName by rememberSaveable { mutableStateOf(StatisticsSort.DATE.name) }
    var historyExpanded by rememberSaveable { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val sort = StatisticsSort.valueOf(sortName)
    val currentPeriod = statisticsPeriodStart(Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate(), monthly)
    val start = selectedStartIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: periods.keys.lastOrNull() ?: currentPeriod
    LaunchedEffect(start, periods.isNotEmpty()) { if (selectedStartIso == null && periods.isNotEmpty()) selectedStartIso = start.toString() }
    val chronological = remember(eligibleRows, start, monthly, zone) { statisticsPeriodRows(eligibleRows, start, monthly, zone) }
    val rows = remember(chronological, sort) { sortedStatisticsRows(chronological, sort) }
    val median = remember(chronological) { medianDuration(chronological) }
    val axis = remember(chronological, median) { gapAxis(chronological.maxOfOrNull { kotlin.math.abs(it.totalDurationNanos - (median ?: 0)) } ?: 0) }
    val first = gapWindowStart(requestedWindow ?: if (sort == StatisticsSort.DATE) null else 0, rows.size)
    val visible = rows.drop(first).take(5)
    val selectedRow = rows.firstOrNull { it.sessionId == selectedId } ?: rows.getOrNull(selectedIndexHint.coerceIn(0, (rows.size-1).coerceAtLeast(0)))
    LaunchedEffect(selectedRow?.sessionId) { if (selectedRow == null) sheet = false }
    val ordinal = remember(chronological, zone) {
        val counts = mutableMapOf<LocalDate,Int>()
        chronological.associate { row -> val date = Instant.ofEpochMilli(row.startedWallTimeMillis).atZone(zone).toLocalDate(); val next = (counts[date] ?: 0)+1; counts[date] = next; row.sessionId to next }
    }
    fun selectPeriod(value: LocalDate) { selectedStartIso = value.toString(); requestedWindow = null; selectedId = null; selectedIndexHint = Int.MAX_VALUE; sheet = false }
    fun select(row: LeaderboardRow) { selectedId = row.sessionId; selectedIndexHint = rows.indexOf(row); sheet = true }
    fun shift(amount: Int) { requestedWindow = (first+amount).coerceIn(0,(rows.size-5).coerceAtLeast(0)) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { selectPeriod(if (monthly) start.minusMonths(1) else start.minusWeeks(1)) }, modifier = Modifier.testTag("stats-previous-period")) { Icon(Icons.Default.ChevronLeft,"上一个周期") }
            Text(periodStatisticsLabel(start,monthly),Modifier.weight(1f).testTag("stats-period"),style=MaterialTheme.typography.titleMedium)
            IconButton(onClick = { selectPeriod(if (monthly) start.plusMonths(1) else start.plusWeeks(1)) }, enabled=start<maxOf(currentPeriod,periods.keys.lastOrNull()?:currentPeriod),modifier=Modifier.testTag("stats-next-period")) { Icon(Icons.Default.ChevronRight,"下一个周期") }
        }
        if (median == null) { Text("这个周期还没有有效行程",Modifier.fillMaxWidth().padding(vertical=40.dp),color=Muted) }
        else {
            DesignPanel {
                FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("典型用时 · 中位数",color=Muted,style=MaterialTheme.typography.bodySmall);Text("${rows.size} 次有效行程",color=Muted,style=MaterialTheme.typography.bodySmall) }
                DesignTimeValue(formatRecordDuration(median),38f,Modifier.testTag("stats-median"))
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("与典型用时的差值",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
                Box {
                    TextButton(onClick={menu=true},modifier=Modifier.testTag("stats-sort")){Icon(Icons.Default.Sort,null,Modifier.size(18.dp));Spacer(Modifier.width(4.dp));Text(sort.label,style=MaterialTheme.typography.labelMedium)}
                    DropdownMenu(menu,{menu=false}) { StatisticsSort.entries.forEach { option -> DropdownMenuItem(text={Text(option.label)},onClick={sortName=option.name;requestedWindow=null;menu=false},leadingIcon=if(option==sort)({Icon(Icons.Default.Check,null)})else null) } }
                }
            }
            SignedGapBars(visible,median,axis,selectedRow?.sessionId,ordinal,zone,{shift(it)},::select)
            Text("上方较慢 · 下方较快 · 零线为本期中位数",color=Muted,style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("stats-gap-baseline"))
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={shift(-5)},enabled=first>0,modifier=Modifier.testTag("stats-earlier")){Text("较早")}
                Text("第 ${first+1}–${first+visible.size} 次／共 ${rows.size} 次 · 左右滑动",Modifier.weight(1f).testTag("stats-window"),color=Muted,fontSize=11.sp,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                TextButton(onClick={shift(5)},enabled=first+5<rows.size,modifier=Modifier.testTag("stats-later")){Text("较晚")}
            }
            selectedRow?.let { row -> ListItem(headlineContent={Text("${Instant.ofEpochMilli(row.startedWallTimeMillis).atZone(zone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))} · ${formatRecordDuration(row.totalDurationNanos)}")},supportingContent={Text(medianDifferenceLabel(row.totalDurationNanos-median))},leadingContent={Icon(Icons.Default.Timer,null)},trailingContent={Icon(Icons.Default.ExpandLess,null)},colors=ListItemDefaults.colors(containerColor=Surface),modifier=Modifier.testTag("stats-selected-trip").clickable{select(row)}) }
        }
        TextButton(onClick={historyExpanded=!historyExpanded},modifier=Modifier.testTag("stats-period-history")){Text("各${if(monthly)"月" else "周"}典型用时");Icon(if(historyExpanded)Icons.Default.ExpandLess else Icons.Default.ExpandMore,null)}
        if(historyExpanded) periods.entries.reversed().forEach { (date, group) ->
            ListItem(headlineContent={Text(periodStatisticsLabel(date,monthly))},supportingContent={Text("${group.size} 次有效行程")},trailingContent={Text(formatRecordDuration(medianDuration(group)!!),fontFamily=FontFamily.Monospace)},colors=ListItemDefaults.colors(containerColor=Surface))
        }
    }
    if(sheet && selectedRow!=null && median!=null) {
        val row=selectedRow
        ModalBottomSheet(onDismissRequest={sheet=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=SurfaceHigh) {
            Column(Modifier.fillMaxWidth().padding(start=24.dp,end=24.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text("${row.routeName} · ${Instant.ofEpochMilli(row.startedWallTimeMillis).atZone(zone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))}",color=Muted,style=MaterialTheme.typography.bodySmall)
                DesignTimeValue(formatRecordDuration(row.totalDurationNanos),40f)
                Text(medianDifferenceLabel(row.totalDurationNanos-median),color=ChartBlue)
                HorizontalDivider(color=Outline)
                listOf(row.sector1Nanos,row.sector2Nanos,row.sector3Nanos).forEachIndexed { index,nanos -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(listOf("A → S1","S1 → S2","S2 → B")[index]);Text(nanos?.let(::formatRecordDuration)?:"—",fontFamily=FontFamily.Monospace)} }
                Text("有效 · 质量 ${row.qualityGrade} · 当前计时点${if(row.missingCheckpointMask!=0)" · 部分检查点缺失" else ""}",color=Muted,style=MaterialTheme.typography.bodySmall)
                DesignAction("查看完整行程",{sheet=false;onOpenSession(row.sessionId)},icon=Icons.Default.Route)
            }
        }
    }
}

@Composable
private fun SignedGapBars(rows:List<LeaderboardRow>,median:Long,axis:GapAxis,selectedId:String?,ordinal:Map<String,Int>,zone:ZoneId,onShift:(Int)->Unit,onSelect:(LeaderboardRow)->Unit) {
    var drag by remember { mutableFloatStateOf(0f) }
    val threshold=with(LocalDensity.current){48.dp.toPx()}
    Row(Modifier.fillMaxWidth().testTag("trip-gap-chart").pointerInput(rows.map{it.sessionId}) { detectHorizontalDragGestures(onDragStart={drag=0f},onDragCancel={drag=0f},onDragEnd={if(kotlin.math.abs(drag)>threshold)onShift(if(drag<0)5 else -5);drag=0f}){change,amount->change.consume();drag+=amount} }) {
        Column(Modifier.width(48.dp).height(186.dp),verticalArrangement=Arrangement.SpaceBetween) {
            Text("${axis.label(3)}${axis.unitLabel}",color=Muted,fontSize=10.sp,modifier=Modifier.fillMaxWidth().testTag("gap-axis-3"))
            Text("0",color=Muted,fontSize=10.sp)
            Text("−${axis.label(3).removePrefix("+")}${axis.unitLabel}",color=Muted,fontSize=10.sp)
        }
        Box(Modifier.weight(1f)) {
            Canvas(Modifier.fillMaxWidth().height(186.dp)) { listOf(0f,.5f,1f).forEach { ratio-> drawLine(if(ratio==.5f)Muted else Outline.copy(alpha=.4f),Offset(0f,size.height*ratio),Offset(size.width,size.height*ratio),if(ratio==.5f)2.dp.toPx() else 1.dp.toPx()) } }
            Row(Modifier.fillMaxWidth()) { rows.forEach { row ->
                val gap=row.totalDurationNanos-median;val date=Instant.ofEpochMilli(row.startedWallTimeMillis).atZone(zone)
                Column(Modifier.weight(1f).testTag("trip-bar-${row.sessionId}").semantics{selected=row.sessionId==selectedId;contentDescription="${date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}，第${ordinal[row.sessionId]}次，用时${formatRecordDuration(row.totalDurationNanos)}，${medianDifferenceLabel(gap)}"}.clickable{onSelect(row)},horizontalAlignment=Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(186.dp)) {
                        val h=(kotlin.math.abs(gap).toDouble()/axis.topNanos*92).toFloat().coerceIn(3f,92f)
                        Box(Modifier.align(Alignment.TopCenter).offset(y=(if(gap>=0)93-h else 93f).dp).width(30.dp).height(h.dp).background(if(row.sessionId==selectedId)TelemetryWhite else ChartBlue,RoundedCornerShape(4.dp)))
                    }
                    Text(date.format(DateTimeFormatter.ofPattern("MM-dd")),fontSize=11.sp,color=Muted,modifier=Modifier.padding(top=8.dp))
                    if((ordinal[row.sessionId]?:1)>1)Text("第${ordinal[row.sessionId]}次",fontSize=10.sp,color=Muted)
                }
            };repeat((5-rows.size).coerceAtLeast(0)){Spacer(Modifier.weight(1f))} }
        }
    }
}
internal fun periodStatisticsLabel(start: LocalDate, monthly: Boolean): String = if (monthly) start.format(DateTimeFormatter.ofPattern("yyyy年M月")) else "${start.format(DateTimeFormatter.ofPattern("MM.dd"))} — ${start.plusDays(6).format(DateTimeFormatter.ofPattern("MM.dd"))}"
