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

    @Test fun gapAxisIsStableAndRoundsWithoutFloatPrecisionLoss() {
        val axis = gapAxis(151_100_000_000L)
        assertEquals(180_000_000_000L, axis.topNanos)
        assertEquals("+180", axis.label(3))
        assertEquals("秒", axis.unitLabel)
        assertEquals(15, gapWindowStart(null, 20))
        assertEquals(0, gapWindowStart(-1, 20))
        assertEquals(15, gapWindowStart(100, 20))
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
