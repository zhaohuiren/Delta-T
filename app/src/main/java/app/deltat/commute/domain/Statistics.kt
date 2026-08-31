package app.deltat.commute.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToLong

sealed interface LeaderboardPeriodFilter {
    data object ThisWeek : LeaderboardPeriodFilter
    data object ThisMonth : LeaderboardPeriodFilter
    data object All : LeaderboardPeriodFilter
    data class Custom(val startDate: LocalDate, val endDateInclusive: LocalDate) : LeaderboardPeriodFilter {
        init { require(!endDateInclusive.isBefore(startDate)) }
    }
}

data class PeriodAggregate(
    val periodStart: LocalDate,
    val periodEndInclusive: LocalDate,
    val averageDurationNanos: Long,
    val tripCount: Int,
)

data class PeriodRange(val startMillisInclusive: Long?, val endMillisExclusive: Long?) {
    fun contains(wallTimeMillis: Long): Boolean =
        (startMillisInclusive == null || wallTimeMillis >= startMillisInclusive) &&
            (endMillisExclusive == null || wallTimeMillis < endMillisExclusive)
}

object StatisticsAggregator {
    fun range(
        filter: LeaderboardPeriodFilter,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): PeriodRange {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        return when (filter) {
            LeaderboardPeriodFilter.ThisWeek -> {
                val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                PeriodRange(monday.atStartOfDay(zoneId).toInstant().toEpochMilli(), nowMillis + 1)
            }
            LeaderboardPeriodFilter.ThisMonth -> {
                val first = today.withDayOfMonth(1)
                PeriodRange(first.atStartOfDay(zoneId).toInstant().toEpochMilli(), nowMillis + 1)
            }
            LeaderboardPeriodFilter.All -> PeriodRange(null, null)
            is LeaderboardPeriodFilter.Custom -> PeriodRange(
                filter.startDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                filter.endDateInclusive.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            )
        }
    }

    fun filter(
        results: List<TimedResultSummary>,
        periodFilter: LeaderboardPeriodFilter,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<TimedResultSummary> {
        val range = range(periodFilter, nowMillis, zoneId)
        return results.filter { range.contains(it.startedWallTimeMillis) }
    }

    fun monthly(results: List<TimedResultSummary>, zoneId: ZoneId = ZoneId.systemDefault()): List<PeriodAggregate> =
        results.groupBy { YearMonth.from(Instant.ofEpochMilli(it.startedWallTimeMillis).atZone(zoneId)) }
            .map { (month, rows) ->
                PeriodAggregate(
                    periodStart = month.atDay(1),
                    periodEndInclusive = month.atEndOfMonth(),
                    averageDurationNanos = rows.map { it.durationNanos }.average().roundToLong(),
                    tripCount = rows.size,
                )
            }
            .sortedBy { it.periodStart }

    fun weekly(results: List<TimedResultSummary>, zoneId: ZoneId = ZoneId.systemDefault()): List<PeriodAggregate> =
        results.groupBy {
            Instant.ofEpochMilli(it.startedWallTimeMillis).atZone(zoneId).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        }.map { (monday, rows) ->
            PeriodAggregate(
                periodStart = monday,
                periodEndInclusive = monday.plusDays(6),
                averageDurationNanos = rows.map { it.durationNanos }.average().roundToLong(),
                tripCount = rows.size,
            )
        }.sortedBy { it.periodStart }
}
