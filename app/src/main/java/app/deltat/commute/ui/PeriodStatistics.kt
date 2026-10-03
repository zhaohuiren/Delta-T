package app.deltat.commute.ui

import androidx.compose.foundation.Canvas
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

@Composable
internal fun PeriodStatisticsSection(allRows: List<LeaderboardRow>, monthly: Boolean, nowMillis: Long) {
    val zone = ZoneId.systemDefault()
    val eligibleRows = remember(allRows, nowMillis) { allRows.filter { it.startedWallTimeMillis <= nowMillis } }
    val summaries = remember(eligibleRows) { eligibleRows.map { TimedResultSummary(it.sessionId, it.startedWallTimeMillis, it.totalDurationNanos) } }
    val aggregates = remember(summaries, monthly, zone) { if (monthly) StatisticsAggregator.monthly(summaries, zone) else StatisticsAggregator.weekly(summaries, zone) }
    var selectedStartIso by rememberSaveable { mutableStateOf<String?>(null) }
    var requestedWindow by rememberSaveable { mutableStateOf<Int?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedIndexHint by rememberSaveable { mutableIntStateOf(Int.MAX_VALUE) }
    var historyExpanded by rememberSaveable { mutableStateOf(false) }
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val currentPeriod = statisticsPeriodStart(today, monthly)
    val start = selectedStartIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: aggregates.lastOrNull()?.periodStart ?: currentPeriod
    LaunchedEffect(start, aggregates.isNotEmpty()) {
        if (selectedStartIso == null && aggregates.isNotEmpty()) selectedStartIso = start.toString()
    }
    val rows = remember(eligibleRows, start, monthly, zone) { statisticsPeriodRows(eligibleRows, start, monthly, zone) }
    val aggregate = aggregates.firstOrNull { it.periodStart == start }
    val best = remember(rows) { rows.minOfOrNull { it.totalDurationNanos } }
    val axis = remember(rows, best) { gapAxis(rows.maxOfOrNull { it.totalDurationNanos - (best ?: 0) } ?: 0) }
    val first = gapWindowStart(requestedWindow, rows.size)
    val visible = rows.drop(first).take(5)
    val selectedRow = visible.firstOrNull { it.sessionId == selectedId }
        ?: rows.getOrNull(selectedIndexHint.coerceIn(first, (first + visible.size - 1).coerceAtLeast(first)))
    val ordinal = remember(rows, zone) {
        val counts = mutableMapOf<LocalDate, Int>()
        rows.associate { row ->
            val date = Instant.ofEpochMilli(row.startedWallTimeMillis).atZone(zone).toLocalDate()
            val next = (counts[date] ?: 0) + 1; counts[date] = next; row.sessionId to next
        }
    }
    val accent = if (monthly) Purple else Green
    fun selectPeriod(value: LocalDate) {
        selectedStartIso = value.toString(); requestedWindow = null; selectedId = null; selectedIndexHint = Int.MAX_VALUE
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { selectPeriod(if (monthly) start.minusMonths(1) else start.minusWeeks(1)) }, modifier = Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp).testTag("stats-previous-period")) { Text("‹", fontSize = 24.sp) }
            Text(periodStatisticsLabel(start, monthly), Modifier.weight(1f).testTag("stats-period"), fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            TextButton(onClick = { selectPeriod(if (monthly) start.plusMonths(1) else start.plusWeeks(1)) }, enabled = start < maxOf(currentPeriod, aggregates.lastOrNull()?.periodStart ?: currentPeriod), modifier = Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp).testTag("stats-next-period")) { Text("›", fontSize = 24.sp) }
        }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Surface)) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (monthly) "月平均" else "周平均", color = accent, fontWeight = FontWeight.Bold)
                Text(aggregate?.averageDurationNanos?.let(::formatRecordDuration) ?: "--", fontSize = 42.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${rows.size} 次有效行程", color = Muted, fontSize = 12.sp)
                    Text("最快 ${best?.let(::formatRecordDuration) ?: "--"}", fontSize = 12.sp)
                }
            }
        }
        Card(Modifier.fillMaxWidth().testTag("trip-gap-chart"), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Surface)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("每次行程 GAP", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("越低越接近最快", color = Muted, fontSize = 11.sp)
                }
                Text(if (best == null) "暂无比较基准" else "相对${if (monthly) "本月" else "本周"}最快 ${formatRecordDuration(best)} · ${axis.unitLabel}", color = Muted, fontSize = 12.sp, modifier = Modifier.testTag("stats-gap-baseline"))
                if (rows.isEmpty()) {
                    Text("这个周期还没有有效行程", Modifier.fillMaxWidth().padding(vertical = 32.dp), color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                } else {
                    GapBars(visible, best!!, axis, selectedRow?.sessionId, ordinal, accent, zone) { row ->
                        selectedId = row.sessionId; selectedIndexHint = rows.indexOfFirst { it.sessionId == row.sessionId }
                    }
                    Text("行程顺序 · 日期／当日第几次", color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.End))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { requestedWindow = (first - 5).coerceAtLeast(0); selectedId = null; selectedIndexHint = Int.MAX_VALUE }, enabled = first > 0, modifier = Modifier.testTag("stats-earlier")) { Text("较早", fontSize = 11.sp) }
                        Text("第 ${first + 1}–${first + visible.size} 次／共 ${rows.size} 次", Modifier.weight(1f).testTag("stats-window"), color = Muted, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        TextButton(onClick = { requestedWindow = (first + 5).coerceAtMost((rows.size - 5).coerceAtLeast(0)); selectedId = null; selectedIndexHint = Int.MAX_VALUE }, enabled = first + 5 < rows.size, modifier = Modifier.testTag("stats-later")) { Text("较晚", fontSize = 11.sp) }
                    }
                    selectedRow?.let { row ->
                        HorizontalDivider(color = Color(0xFF30363D))
                        Column(Modifier.fillMaxWidth().testTag("stats-selected-trip"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${Instant.ofEpochMilli(row.startedWallTimeMillis).atZone(zone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))} · 第 ${ordinal[row.sessionId]} 次", color = Muted, fontSize = 12.sp)
                                Text(if (row.totalDurationNanos == best) "GAP 0 · 本期最快" else "GAP +${formatRecordDuration(row.totalDurationNanos - best)}", fontSize = 12.sp)
                            }
                            Text(formatRecordDuration(row.totalDurationNanos), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 25.sp)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(row.sector1Nanos, row.sector2Nanos, row.sector3Nanos).forEachIndexed { index, nanos ->
                                    Column(Modifier.weight(1f)) {
                                        Text(listOf("A→S1", "S1→S2", "S2→B")[index], color = Muted, fontSize = 11.sp)
                                        Text(nanos?.let(::formatRecordDuration) ?: "--", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                                        val missing = when (index) { 0 -> row.missingCheckpointMask and 2 != 0; 1 -> row.missingCheckpointMask and 6 != 0; else -> row.missingCheckpointMask and 4 != 0 }
                                        if (missing) Text("检查点缺失", color = Muted, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Surface)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                TextButton(onClick = { historyExpanded = !historyExpanded }, modifier = Modifier.fillMaxWidth().testTag("stats-period-history")) {
                    Text("${if (historyExpanded) "▾" else "▸"} 各${if (monthly) "月" else "周"}平均", color = Muted, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
                }
                if (historyExpanded) {
                    if (aggregates.isEmpty()) Text("暂无有效周期", color = Muted, modifier = Modifier.padding(vertical = 12.dp))
                    aggregates.asReversed().forEach { item ->
                        FlowRow(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${periodStatisticsLabel(item.periodStart, monthly)} · ${item.tripCount} 次", fontSize = 12.sp)
                            Text(formatRecordDuration(item.averageDurationNanos), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GapBars(rows: List<LeaderboardRow>, best: Long, axis: GapAxis, selectedId: String?, ordinal: Map<String, Int>, accent: Color, zone: ZoneId, onSelect: (LeaderboardRow) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.width(40.dp).height(168.dp)) {
            (3 downTo 0).forEach { index ->
                ChartLabel(axis.label(index), Modifier.fillMaxWidth().offset(y = (56 * (3 - index) - 10).dp).height(20.dp).padding(end = 5.dp).testTag("gap-axis-$index"), Muted, 10f, Alignment.CenterEnd)
            }
        }
        Box(Modifier.weight(1f)) {
            Canvas(Modifier.fillMaxWidth().height(168.dp)) {
                (0..3).forEach { index ->
                    val y = size.height * index / 3
                    drawLine(Color(0xFF303942), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
            }
            Row(Modifier.fillMaxWidth()) {
                rows.forEach { row ->
                    val gap = row.totalDurationNanos - best
                    val date = Instant.ofEpochMilli(row.startedWallTimeMillis).atZone(zone)
                    Column(Modifier.weight(1f).testTag("trip-bar-${row.sessionId}")
                        .semantics { selected = row.sessionId == selectedId; contentDescription = "${date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}，第${ordinal[row.sessionId]}次，用时${formatRecordDuration(row.totalDurationNanos)}，${if (gap == 0L) "GAP 0，最快" else "GAP +${formatRecordDuration(gap)}"}" }
                        .clickable { onSelect(row) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().height(168.dp), contentAlignment = Alignment.BottomCenter) {
                            if (gap == 0L) Box(Modifier.offset(y = 4.dp).size(8.dp).background(accent, RoundedCornerShape(50)))
                            else Box(Modifier.fillMaxWidth(.58f).fillMaxHeight((gap.toDouble() / axis.topNanos).toFloat().coerceIn(0f, 1f)).background(if (row.sessionId == selectedId) TelemetryWhite else Color(0xFF75818D)))
                        }
                        ChartLabel(date.format(DateTimeFormatter.ofPattern("MM-dd")), Modifier.fillMaxWidth().padding(top = 6.dp), if (row.sessionId == selectedId) TelemetryWhite else Muted)
                        ChartLabel(if (gap == 0L) "最快" else "第${ordinal[row.sessionId]}次", Modifier.fillMaxWidth(), Muted)
                    }
                }
                repeat((5 - rows.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

internal fun periodStatisticsLabel(start: LocalDate, monthly: Boolean): String =
    if (monthly) start.format(DateTimeFormatter.ofPattern("yyyy年M月"))
    else "${start.format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))} – ${start.plusDays(6).format(DateTimeFormatter.ofPattern("MM.dd"))}"

@Composable
private fun ChartLabel(text: String, modifier: Modifier, color: Color, maximumSp: Float = 11f, alignment: Alignment = Alignment.Center) {
    BoxWithConstraints(modifier, contentAlignment = alignment) {
        val fontScale = LocalDensity.current.fontScale
        // CJK glyphs take one em; latin/digits use a conservative .65 em estimate.
        val ems = text.sumOf { if (it.code > 255) 1.0 else .65 }.toFloat()
        val size = minOf(maximumSp, maxWidth.value / (ems * fontScale)).sp
        Text(text, Modifier.fillMaxWidth(), fontSize = size,
            lineHeight = if (alignment == Alignment.CenterEnd) size * 1.2f else androidx.compose.ui.unit.TextUnit.Unspecified,
            color = color, maxLines = 1, textAlign = if (alignment == Alignment.CenterEnd) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Center)
    }
}
