package app.deltat.commute.domain

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatisticsTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun monthFilterStartsAtFirstDayAndEndsAtCurrentInstant() {
        val now = millis(2026, 9, 15, 12)
        val rows = listOf(
            result("aug", millis(2026, 8, 31, 23), 100),
            result("sep", millis(2026, 9, 1, 0), 110),
            result("future", millis(2026, 9, 15, 13), 120),
        )

        assertEquals(
            listOf("sep"),
            StatisticsAggregator.filter(rows, LeaderboardPeriodFilter.ThisMonth, now, zone).map { it.sessionId },
        )
    }

    @Test fun weekAlwaysStartsOnMondayEvenWhenItCrossesMonth() {
        val now = millis(2026, 9, 2, 12)
        val rows = listOf(
            result("sun", millis(2026, 8, 30, 12), 90),
            result("mon", millis(2026, 8, 31, 0), 100),
            result("wed", millis(2026, 9, 2, 12), 110),
        )

        assertEquals(
            listOf("mon", "wed"),
            StatisticsAggregator.filter(rows, LeaderboardPeriodFilter.ThisWeek, now, zone).map { it.sessionId },
        )
    }

    @Test fun customDateRangeIncludesBothBoundaryDates() {
        val rows = listOf(
            result("before", millis(2026, 8, 31, 23), 90),
            result("first", millis(2026, 9, 1, 0), 100),
            result("last", millis(2026, 9, 3, 23), 110),
            result("after", millis(2026, 9, 4, 0), 120),
        )
        val filter = LeaderboardPeriodFilter.Custom(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3))

        assertEquals(
            listOf("first", "last"),
            StatisticsAggregator.filter(rows, filter, zoneId = zone).map { it.sessionId },
        )
    }

    @Test fun monthlyAndWeeklyAggregatesUseArithmeticMeanAndDoNotInventEmptyPeriods() {
        val rows = listOf(
            result("a", millis(2026, 8, 31, 12), 100),
            result("b", millis(2026, 9, 1, 12), 200),
            result("c", millis(2026, 9, 2, 12), 400),
            result("d", millis(2026, 11, 1, 12), 800),
        )

        val months = StatisticsAggregator.monthly(rows, zone)
        assertEquals(listOf(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 11, 1)), months.map { it.periodStart })
        assertEquals(listOf(100L, 300L, 800L), months.map { it.averageDurationNanos })
        assertEquals(listOf(1, 2, 1), months.map { it.tripCount })
        assertFalse(months.any { it.periodStart.monthValue == 10 })

        val weeks = StatisticsAggregator.weekly(rows.take(3), zone)
        assertEquals(1, weeks.size)
        assertEquals(LocalDate.of(2026, 8, 31), weeks.single().periodStart)
        assertEquals(233L, weeks.single().averageDurationNanos)
        assertEquals(3, weeks.single().tripCount)
        assertTrue(weeks.single().periodEndInclusive == LocalDate.of(2026, 9, 6))
    }

    private fun result(id: String, started: Long, duration: Long) = TimedResultSummary(id, started, duration)

    private fun millis(year: Int, month: Int, day: Int, hour: Int): Long =
        ZonedDateTime.of(year, month, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()
}
