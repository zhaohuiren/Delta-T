package app.deltat.commute.ui

import app.deltat.commute.data.comparisonPeriodBounds
import app.deltat.commute.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

class RecordsBoundaryMatrixTest {
    @Test fun pickerCalendarDaySurvivesPositiveNegativeAndDstZones() {
        val original = TimeZone.getDefault()
        try {
            for (zone in listOf("UTC", "Asia/Shanghai", "America/Los_Angeles", "Pacific/Kiritimati")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                for (date in listOf("2026-01-01", "2026-03-08", "2026-11-01", "2026-12-31")) {
                    val day = LocalDate.parse(date)
                    assertEquals("$zone $date", day, datePickerMillisToLocalDate(localDateToDatePickerMillis(day)))
                }
            }
        } finally { TimeZone.setDefault(original) }
    }

    @Test fun customDayRangeUsesLocalMidnightAcrossDst23And25HourDays() {
        val zone = ZoneId.of("America/Los_Angeles")
        for ((day, hours) in listOf("2026-03-08" to 23, "2026-11-01" to 25)) {
            val date = LocalDate.parse(day)
            val range = StatisticsAggregator.range(LeaderboardPeriodFilter.Custom(date, date), 0, zone)
            assertEquals(hours * 3_600_000L, range.endMillisExclusive!! - range.startMillisInclusive!!)
            assertTrue(range.contains(range.startMillisInclusive!!))
            assertTrue(range.contains(range.endMillisExclusive!! - 1))
            assertFalse(range.contains(range.endMillisExclusive!!))
            assertFalse(range.contains(range.startMillisInclusive!! - 1))
        }
    }

    @Test fun midnightMonthAndWeekBoundariesExcludeAdjacentPeriods() {
        for (zone in listOf("UTC", "Asia/Shanghai", "America/Los_Angeles").map(ZoneId::of)) {
            for (date in listOf("2026-06-01", "2026-09-01", "2027-01-01")) {
                val now = LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()
                val month = StatisticsAggregator.range(LeaderboardPeriodFilter.ThisMonth, now, zone)
                assertEquals(now, month.startMillisInclusive)
                assertTrue(month.contains(now))
                assertFalse(month.contains(now - 1))
                val comparison = comparisonPeriodBounds(now + 1, zone)
                assertEquals(now, comparison.monthStartMillis)
                assertTrue(comparison.weekStartMillis >= comparison.monthStartMillis)
            }
        }
        val monday = Instant.parse("2026-06-01T00:00:00Z").toEpochMilli()
        val week = StatisticsAggregator.range(LeaderboardPeriodFilter.ThisWeek, monday, ZoneId.of("UTC"))
        assertEquals(monday, week.startMillisInclusive)
        assertFalse(week.contains(monday - 1))
    }

    @Test fun gapSeriesPreservesDenseSameTimeRowsAndExtremeDurations() {
        val input = (0..499).reversed().map { i -> TimedResultSummary("row-${i.toString().padStart(3, '0')}",
            1_750_000_000_000L + (i / 20) * 86_400_000L, if (i == 499) 86_399_990_000_000L else 60_000_000_000L + i * 100_000_000L) }
        val series = StatisticsAggregator.tripGapSeries(input)
        assertEquals(500, series.size)
        assertEquals(0L, series.first().gapNanos)
        assertEquals(86_339_990_000_000L, series.last().gapNanos)
        val points = tripGapChartCoordinates(series)
        assertEquals(500, points.size)
        assertTrue(points.all { it.xFraction.isFinite() && it.yFraction.isFinite() && it.xFraction in 0f..1f && it.yFraction in 0f..1f })
        assertEquals(20, points.count { it.xFraction == 0f })
    }

    @Test fun emptySingletonAndTiedTimesHaveDefinedCoordinates() {
        assertTrue(tripGapChartCoordinates(emptyList()).isEmpty())
        for (count in listOf(1, 2, 10)) {
            val series = StatisticsAggregator.tripGapSeries((0 until count).map { TimedResultSummary("$it", 1, 60_000_000_000) })
            assertEquals(count, series.size)
            assertTrue(series.all { it.gapNanos == 0L })
            assertTrue(tripGapChartCoordinates(series).all { it.xFraction.isFinite() && it.yFraction.isFinite() })
        }
    }
}
