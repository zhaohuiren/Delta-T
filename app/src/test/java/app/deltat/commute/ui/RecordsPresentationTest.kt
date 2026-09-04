package app.deltat.commute.ui

import app.deltat.commute.data.SessionListRow
import app.deltat.commute.domain.TripGapPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RecordsPresentationTest {
    @Test fun datePickerConversionUsesUtcCalendarDatesWithoutLocalZoneDrift() {
        val dates = listOf(
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 3, 8),
            LocalDate.of(2026, 12, 31),
        )
        dates.forEach { date ->
            assertEquals(date, datePickerMillisToLocalDate(localDateToDatePickerMillis(date)))
        }
    }

    @Test fun recordDurationAddsHoursWithoutChangingSubHourFormat() {
        assertEquals("59:59.99", formatRecordDuration(3_599_990_000_000L))
        assertEquals("1:00:00.00", formatRecordDuration(3_600_000_000_000L))
        assertEquals("23:59:59.99", formatRecordDuration(86_399_990_000_000L))
    }

    @Test fun chartCoordinatesKeepEveryTripAndEndpointsInsideNormalizedPlot() {
        val rows = listOf(
            TripGapPoint("same-day-1", 100L, 5_000_000_000L),
            TripGapPoint("same-day-2", 100L, 0L),
            TripGapPoint("later", 300L, 10_000_000_000L),
        )

        val points = tripGapChartCoordinates(rows)

        assertEquals(rows.map { it.sessionId }, points.map { it.sessionId })
        assertEquals(0f, points.first().xFraction)
        assertEquals(0f, points[1].xFraction)
        assertEquals(1f, points.last().xFraction)
        assertEquals(1f, points[1].yFraction)
        assertEquals(0f, points.last().yFraction)
        assertTrue(points.all { it.xFraction in 0f..1f && it.yFraction in 0f..1f })
    }

    @Test fun oldCheckpointVersionCannotBePresentedAsCurrentRecordEligible() {
        val row = SessionListRow(
            id = "old", routeProfileId = "route", routeName = "上班", startedWallTimeMillis = 1,
            totalDurationNanos = 60_000_000_000L, status = "COMPLETED", validity = "VALID",
            qualityGrade = "A", referenceSessionIdSnapshot = null, checkpointVersionId = "v1",
            maxSpeedMps = null, maxSpeedElapsedNanos = null, maxSpeedWallTimeMillis = null,
        )

        assertEquals(false, isCurrentRecordEligible(row, "v2"))
        assertEquals(true, isCurrentRecordEligible(row, "v1"))
    }
}
