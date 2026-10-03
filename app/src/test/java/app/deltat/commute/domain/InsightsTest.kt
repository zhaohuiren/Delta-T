package app.deltat.commute.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import app.deltat.commute.data.comparisonPeriodBounds

class InsightsTest {
    @Test fun routeRecognizerChoosesNearestStartAndStableTie() {
        val fix = fix(31.0, 121.0, 1)
        val near = candidate("near", 31.0, 121.0001, 2)
        val far = candidate("far", 31.0, 121.01, 1)
        assertEquals("near", RouteRecognizer.selectNearest(fix, listOf(far, near))?.routeId)
        val tieA = candidate("a", 31.0, 121.0001, 1)
        val tieB = candidate("b", 31.0, 121.0001, 2)
        assertEquals("a", RouteRecognizer.selectNearest(fix, listOf(tieB, tieA))?.routeId)
    }

    @Test fun speedPeakUsesThreeSampleMedianAndRejectsBadFix() {
        val tracker = SpeedPeakTracker()
        tracker.process(speedFix(10.0, 1), true)
        tracker.process(speedFix(100.0, 2), true)
        val peak = tracker.process(speedFix(11.0, 3), true)
        assertEquals(11.0, peak?.metersPerSecond ?: 0.0, 0.001)
        tracker.process(speedFix(50.0, 4, accuracy = 60.0), true)
        assertEquals(11.0, tracker.peak?.metersPerSecond ?: 0.0, 0.001)
    }

    @Test fun referenceMatcherFindsExpectedProgressAndRejectsDivergence() {
        val reference = (0..4).map { index -> fix(31.0, 121.0 + index * .001, index.toLong() * 10) }
        val passes = listOf(0L, 10L, 20L, 40L).map { it * 1_000_000_000 }
        val match = ReferenceProgressMatcher.match(
            fix(31.0, 121.0015, 15), reference, passes, currentSectorIndex = 1,
        )
        assertNotNull(match)
        assertTrue(match!!.expectedElapsedSinceStartNanos in 14_000_000_000L..16_000_000_000L)
        assertNull(ReferenceProgressMatcher.match(fix(31.01, 121.0015, 15), reference, passes, 1))
    }

    @Test fun referenceMatcherRejectsForwardJumpAndOppositeDirection() {
        val reference = (0..20).map { index -> fix(31.0, 121.0 + index * .0001, index.toLong()) }
        val passes = listOf(0L, 7L, 14L, 20L).map { it * 1_000_000_000 }
        assertNull(
            ReferenceProgressMatcher.match(
                fix(31.0, 121.0015, 3), reference, passes, currentSectorIndex = 0,
                minimumExpectedElapsedNanos = 2_000_000_000,
                maximumExpectedElapsedNanos = 5_000_000_000,
            ),
        )
        val reverse = fix(31.0, 121.0003, 3).copy(bearingDegrees = 270.0, bearingAccuracyDegrees = 5.0)
        assertNull(ReferenceProgressMatcher.match(reverse, reference, passes, currentSectorIndex = 0))
    }

    @Test fun comparisonWeekStartsOnMondayAcrossMonthBoundary() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = ZonedDateTime.of(2026, 7, 1, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        val bounds = comparisonPeriodBounds(now, zone)
        val monthStart = ZonedDateTime.of(2026, 7, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(monthStart, bounds.monthStartMillis)
        assertEquals(ZonedDateTime.of(2026, 6, 29, 0, 0, 0, 0, zone).toInstant().toEpochMilli(), bounds.weekStartMillis)
        assertEquals(now, bounds.endExclusiveMillis)
    }

    @Test fun calendarUsesMondayWeekAndDailyFastest() {
        val zone = ZoneId.of("Asia/Shanghai")
        val monday = LocalDate.of(2026, 7, 13)
        fun millis(day: Int, hour: Int) = monday.plusDays(day.toLong()).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        val rows = listOf(
            TimedResultSummary("a", millis(0, 8), 100),
            TimedResultSummary("b", millis(0, 18), 90),
            TimedResultSummary("c", millis(2, 8), 95),
        )
        val stats = CalendarAggregator.weekly(rows, monday, zone)
        assertEquals(90L, stats.bestNanos)
        assertEquals(3, stats.runs.size)
        assertEquals(2, stats.days.first().count)
        assertEquals(90L, stats.days.first().fastestNanos)
        assertEquals(monday, CalendarAggregator.weekStart(millis(5, 12), zone))
    }

    private fun candidate(id: String, lat: Double, lon: Double, created: Long): RouteCandidate = RouteCandidate(
        id, id, created,
        (0..3).map { index -> Checkpoint("$id-$index", index, listOf("A", "S1", "S2", "B")[index], lat, lon + index * .001) },
    )

    private fun fix(lat: Double, lon: Double, seconds: Long) = LocationFix(
        lat, lon, seconds * 1_000_000_000, seconds * 1_000, 5.0, 10.0, 90.0,
    )

    private fun speedFix(speed: Double, seconds: Long, accuracy: Double = 5.0) = LocationFix(
        latitude = 31.0,
        longitude = 121.0 + seconds * .0001,
        elapsedRealtimeNanos = seconds * 1_000_000_000,
        wallTimeMillis = seconds * 1_000,
        accuracyMeters = accuracy,
        speedMetersPerSecond = speed,
        speedAccuracyMetersPerSecond = 1.0,
    )
}
